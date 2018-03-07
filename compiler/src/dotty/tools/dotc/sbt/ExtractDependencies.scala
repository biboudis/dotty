package dotty.tools.dotc
package sbt

import ast.{Trees, tpd}
import core._, core.Decorators._
import util.NoSource.{file => NoSourceFile}
import Contexts._, Flags._, Phases._, Trees._, Types._, Symbols._
import Names._, NameOps._, StdNames._

import scala.collection.{Set, mutable}

import dotty.tools.io
import dotty.tools.io.{AbstractFile, ZipArchive, PlainFile}

import java.io.File

import java.util.{Arrays, Comparator, EnumSet}

import xsbti.api.DependencyContext
import xsbti.api.DependencyContext._
import xsbti.UseScope


/** This phase sends information on classes' dependencies to sbt via callbacks.
 *
 *  This is used by sbt for incremental recompilation. Briefly, when a file
 *  changes sbt will recompile it, if its API has changed (determined by what
 *  `ExtractAPI` sent) then sbt will determine which reverse-dependencies
 *  (determined by what `ExtractDependencies` sent) of the API have to be
 *  recompiled depending on what changed.
 *
 *  See the documentation of `ExtractDependenciesCollector`, `ExtractAPI`,
 *  `ExtractAPICollector` and
 *  http://www.scala-sbt.org/0.13/docs/Understanding-Recompilation.html for more
 *  information on how sbt incremental compilation works.
 *
 *  The following flags affect this phase:
 *   -Yforce-sbt-phases
 *   -Ydump-sbt-inc
 *
 *  @see ExtractAPI
 */
class ExtractDependencies extends Phase {
  import ExtractDependencies._

  override def phaseName: String = "sbt-deps"

  // This phase should be run directly after `Frontend`, if it is run after
  // `PostTyper`, some dependencies will be lost because trees get simplified.
  // See the scripted test `constants` for an example where this matters.
  // TODO: Add a `Phase#runsBefore` method ?

  override def run(implicit ctx: Context): Unit = {
    val unit = ctx.compilationUnit
    val dumpInc = ctx.settings.YdumpSbtInc.value
    val forceRun = dumpInc || ctx.settings.YforceSbtPhases.value
    if ((ctx.sbtCallback != null || forceRun) && !unit.isJava) {
      val extractDeps = new ExtractDependenciesCollector
      extractDeps.traverse(unit.tpdTree)

      if (dumpInc) {
        val names = extractDeps.usedNames.map { case (clazz, names) => s"$clazz: $names" }.toArray[Object]
        val deps = extractDeps.dependencies.map(_.toString).toArray[Object]
        Arrays.sort(names)
        Arrays.sort(deps)

        val sourceFile = unit.source.file
        val pw = io.File(sourceFile.jpath).changeExtension("inc").toFile.printWriter()
        try {
          pw.println(s"// usedNames: ${names.mkString(",")}")
          pw.println(s"// Dependencies: ${deps.mkString(",")}")
        } finally pw.close()
      }

      if (ctx.sbtCallback != null) {
        extractDeps.usedNames.foreach {
          case (clazz, usedNames) =>
            val className = clazz
            usedNames.names.foreach {
              case (usedName, scopes) =>
                ctx.sbtCallback.usedName(className, usedName.toString, scopes)
            }
        }

        extractDeps.dependencies.foreach(recordDependency)
      }
    }
  }

  /*
   * Handles dependency on given symbol by trying to figure out if represents a term
   * that is coming from either source code (not necessarily compiled in this compilation
   * run) or from class file and calls respective callback method.
   */
  def recordDependency(dep: ClassDependency)(implicit ctx: Context): Unit = {
    val fromClassName = classNameAsString(dep.from)
    val sourceFile = ctx.compilationUnit.source.file.file

    def binaryDependency(file: File, binaryClassName: String) =
      ctx.sbtCallback.binaryDependency(file, binaryClassName, fromClassName, sourceFile, dep.context)

    def processExternalDependency(depFile: AbstractFile) = {
      def binaryClassName(classSegments: List[String]) =
        classSegments.mkString(".").stripSuffix(".class")

      depFile match {
        case ze: ZipArchive#Entry => // The dependency comes from a JAR
          for (zip <- ze.underlyingSource; zipFile <- Option(zip.file)) {
            val classSegments = io.File(ze.path).segments
            binaryDependency(zipFile, binaryClassName(classSegments))
          }

        case pf: PlainFile => // The dependency comes from a class file
          val packages = dep.to.ownersIterator
            .count(x => x.is(PackageClass) && !x.isEffectiveRoot)
          // We can recover the fully qualified name of a classfile from
          // its path
          val classSegments = pf.givenPath.segments.takeRight(packages + 1)
          binaryDependency(pf.file, binaryClassName(classSegments))

        case _ =>
          ctx.warning(s"sbt-deps: Ignoring dependency $depFile of class ${depFile.getClass}}")
      }
    }

    val depFile = dep.to.associatedFile
    if (depFile != null) {
      // Cannot ignore inheritance relationship coming from the same source (see sbt/zinc#417)
      def allowLocal = dep.context == DependencyByInheritance || dep.context == LocalDependencyByInheritance
      if (depFile.extension == "class") {
        // Dependency is external -- source is undefined
        processExternalDependency(depFile)
      } else if (allowLocal || depFile.file != sourceFile) {
        // We cannot ignore dependencies coming from the same source file because
        // the dependency info needs to propagate. See source-dependencies/trait-trait-211.
        val toClassName = classNameAsString(dep.to)
        ctx.sbtCallback.classDependency(toClassName, fromClassName, dep.context)
      }
    }
  }
}

object ExtractDependencies {
  def classNameAsString(sym: Symbol)(implicit ctx: Context): String =
    sym.fullName.stripModuleClassSuffix.toString

  def isLocal(sym: Symbol)(implicit ctx: Context): Boolean =
    sym.ownersIterator.exists(_.isTerm)
}

private case class ClassDependency(from: Symbol, to: Symbol, context: DependencyContext)

private final class UsedNamesInClass {
  private val _names = new mutable.HashMap[Name, EnumSet[UseScope]]
  def names: collection.Map[Name, EnumSet[UseScope]] = _names

  def update(name: Name, scope: UseScope): Unit = {
    val scopes = _names.getOrElseUpdate(name, EnumSet.noneOf(classOf[UseScope]))
    scopes.add(scope)
  }

  override def toString(): String = {
    val builder = new StringBuilder
    names.foreach { case (name, scopes) =>
      builder.append(name.mangledString)
      builder.append(" in [")
      scopes.forEach(new java.util.function.Consumer[UseScope]() { // TODO: Adapt to SAM type when #2732 is fixed
        override def accept(scope: UseScope): Unit =
          builder.append(scope.toString)
      })
      builder.append("]")
      builder.append(", ")
    }
    builder.toString()
  }
}

/** Extract the dependency information of a compilation unit.
 *
 *  To understand why we track the used names see the section "Name hashing
 *  algorithm" in http://www.scala-sbt.org/0.13/docs/Understanding-Recompilation.html
 *  To understand why we need to track dependencies introduced by inheritance
 *  specially, see the subsection "Dependencies introduced by member reference and
 *  inheritance" in the "Name hashing algorithm" section.
 */
private class ExtractDependenciesCollector extends tpd.TreeTraverser { thisTreeTraverser =>
  import tpd._
  import ExtractDependencies._

  private[this] val _usedNames = new mutable.HashMap[String, UsedNamesInClass]
  private[this] val _dependencies = new mutable.HashSet[ClassDependency]

  /** The names used in this class, this does not include names which are only
   *  defined and not referenced.
   */
  def usedNames: collection.Map[String, UsedNamesInClass] = _usedNames

  /** The set of class dependencies from this compilation unit.
   */
  def dependencies: Set[ClassDependency] = _dependencies

  /** Top level import dependencies are registered as coming from a first top level
   *  class/trait/object declared in the compilation unit. If none exists, issue warning.
   */
  private[this] var _responsibleForImports: Symbol = _
  private def responsibleForImports(implicit ctx: Context) = {
    def firstClassOrModule(tree: Tree) = {
      val acc = new TreeAccumulator[Symbol] {
        def apply(x: Symbol, t: Tree)(implicit ctx: Context) =
          t match {
            case typeDef: TypeDef =>
              typeDef.symbol
            case other =>
              foldOver(x, other)
          }
      }
      acc(NoSymbol, tree)
    }

    if (_responsibleForImports == null) {
      val tree = ctx.compilationUnit.tpdTree
      _responsibleForImports = firstClassOrModule(tree)
      if (_responsibleForImports == NoSymbol)
          ctx.warning("""|No class, trait or object is defined in the compilation unit.
                         |The incremental compiler cannot record the dependency information in such case.
                         |Some errors like unused import referring to a non-existent class might not be reported.
                         |""".stripMargin, tree.pos)
    }
    _responsibleForImports
  }

  /**
   * Resolves dependency source (that is, the closest non-local enclosing
   * class from a given `currentOwner` set by the `Traverser`).
   *
   * TODO: cache and/or optimise?
   */
  private def resolveDependencySource(implicit ctx: Context): Symbol = {
    def isNonLocalClass(sym: Symbol) = sym.isClass && !isLocal(sym)
    //val source = ctx.owner.ownersIterator.find(isNonLocalClass).get // Zinc
    val source = currentClass
    if (source.isEffectiveRoot) responsibleForImports else source
  }

  private def addUsedName(enclosingSym: Symbol, name: Name, scope: UseScope)(implicit ctx: Context) = {
    val enclosingName =
      if (enclosingSym == defn.RootClass) classNameAsString(responsibleForImports)
      else classNameAsString(enclosingSym)
    val nameUsed = _usedNames.getOrElseUpdate(enclosingName, new UsedNamesInClass)
    nameUsed.update(name, scope)
  }

  private def addDependency(sym: Symbol)(implicit ctx: Context): Unit =
    if (!ignoreDependency(sym)) {
      val tlClass = sym.topLevelClass
      val from = resolveDependencySource
      if (tlClass.ne(NoSymbol)) {
        _dependencies += ClassDependency(from, tlClass, DependencyByMemberRef)
      }
      addUsedName(from, sym.name.stripModuleClassSuffix, UseScope.Default)
    }

  private def ignoreDependency(sym: Symbol)(implicit ctx: Context) =
    sym.eq(NoSymbol) ||
    sym.isEffectiveRoot ||
    sym.isAnonymousFunction ||
    sym.isAnonymousClass

  private def addInheritanceDependency(parent: Symbol)(implicit ctx: Context): Unit =
    _dependencies += ClassDependency(resolveDependencySource, parent.topLevelClass, DependencyByInheritance)

  /** Traverse the tree of a source file and record the dependencies which
   *  can be retrieved using `topLevelDependencies`, `topLevelInheritanceDependencies`,
   *  and `usedNames`
   */
  override def traverse(tree: Tree)(implicit ctx: Context): Unit = {
    tree match {
      case Match(selector, _) =>
        addPatMatDependency(selector.tpe)
      case Import(expr, selectors) =>
        def lookupImported(name: Name) = expr.tpe.member(name).symbol
        def addImported(name: Name) = {
          // importing a name means importing both a term and a type (if they exist)
          addDependency(lookupImported(name.toTermName))
          addDependency(lookupImported(name.toTypeName))
        }
        selectors foreach {
          case Ident(name) =>
            addImported(name)
          case Thicket(Ident(name) :: Ident(rename) :: Nil) =>
            addImported(name)
            if (rename ne nme.WILDCARD) {
              addUsedName(resolveDependencySource, rename, UseScope.Default)
            }
          case _ =>
        }
      case Inlined(call, _, _) =>
        // The inlined call is normally ignored by TreeTraverser but we need to
        // record it as a dependency
        traverse(call)
      case t: TypeTree =>
        addTypeDependency(t.tpe)
      case ref: RefTree =>
        addDependency(ref.symbol)
        addTypeDependency(ref.tpe)
      case t @ Template(_, parents, _, _) =>
        t.parents.foreach(p => addInheritanceDependency(p.tpe.classSymbol))
      case _ =>
    }
    traverseChildren(tree)
  }

  /** Traverse a used type and record all the dependencies we need to keep track
   *  of for incremental recompilation.
   *
   *  As a motivating example, given a type `T` defined as:
   *
   *    type T >: L <: H
   *    type L <: A1
   *    type H <: B1
   *    class A1 extends A0
   *    class B1 extends B0
   *
   *  We need to record a dependency on `T`, `L`, `H`, `A1`, `B1`. This is
   *  necessary because the API representation that `ExtractAPI` produces for
   *  `T` just refers to the strings "L" and "H", it does not contain their API
   *  representation. Therefore, the name hash of `T` does not change if for
   *  example the definition of `L` changes.
   *
   *  We do not need to keep track of superclasses like `A0` and `B0` because
   *  the API representation of a class (and therefore its name hash) already
   *  contains all necessary information on superclasses.
   *
   *  A natural question to ask is: Since traversing all referenced types to
   *  find all these names is costly, why not change the API representation
   *  produced by `ExtractAPI` to contain that information? This way the name
   *  hash of `T` would change if any of the types it depends on change, and we
   *  would only need to record a dependency on `T`. Unfortunately there is no
   *  simple answer to the question "what does T depend on?" because it depends
   *  on the prefix and `ExtractAPI` does not compute types as seen from every
   *  possible prefix, the documentation of `ExtractAPI` explains why.
   *
   *  The tests in sbt `types-in-used-names-a`, `types-in-used-names-b`,
   *  `as-seen-from-a` and `as-seen-from-b` rely on this.
   */
  private abstract class TypeDependencyTraverser(implicit ctx: Context) extends TypeTraverser()(ctx) {
    protected def addDependency(symbol: Symbol): Unit

    val seen = new mutable.HashSet[Type]
    def traverse(tp: Type): Unit = if (!seen.contains(tp)) {
      seen += tp
      tp match {
        case tp: NamedType =>
          val sym = tp.symbol
          if (!sym.is(Package)) {
            addDependency(sym)
            if (!sym.isClass)
              traverse(tp.info)
            traverse(tp.prefix)
          }
        case tp: ThisType =>
          traverse(tp.underlying)
        case tp: ConstantType =>
          traverse(tp.underlying)
        case tp: ParamRef =>
          traverse(tp.underlying)
        case _ =>
          traverseChildren(tp)
      }
    }
  }

  def addTypeDependency(tpe: Type)(implicit ctx: Context) = {
    val traverser = new TypeDependencyTraverser {
      def addDependency(symbol: Symbol) = thisTreeTraverser.addDependency(symbol)
    }
    traverser.traverse(tpe)
  }

  def addPatMatDependency(tpe: Type)(implicit ctx: Context) = {
    val traverser = new TypeDependencyTraverser {
      def addDependency(symbol: Symbol) =
        if (!ignoreDependency(symbol) && symbol.is(Sealed)) {
          val enclosingSym = resolveDependencySource
          val usedName = symbol.name.stripModuleClassSuffix
          addUsedName(enclosingSym, usedName, UseScope.Default)
          addUsedName(enclosingSym, usedName, UseScope.PatMatTarget)
        }
    }
    traverser.traverse(tpe)
  }
}
