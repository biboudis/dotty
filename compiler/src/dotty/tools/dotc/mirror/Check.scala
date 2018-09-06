package dotty.tools
package dotc
package mirror

import core._
import ast._
import util.Positions._, Types._, Contexts._, Constants._, Names._, NameOps._, Flags._
import SymDenotations._, Symbols._, StdNames._, Annotations._, Trees._
import Decorators._, transform.SymUtils._
import NameKinds.{UniqueName, EvidenceParamName, DefaultGetterName}

object Check {
  import untpd._

  /**     @mirror def +(A: Int, B: Int): Int = A + B
   *  ==>
   *      @mirror type +[A <: Int, B <: Int] <: Int
   *      @mirror def +(A: Int, B: Int): +[A.type, B.type] = (A + B).asInstanceOf[+[A.type, B.type]]
   */
  def defDef(meth: DefDef)(implicit ctx: Context): Tree = {
    if (!isMirrorDef(meth)) return meth

    val DefDef(name, tparams, vparamss, tpt, rhs) = meth
    if (tparams.nonEmpty) ctx.error("mirror methods should not take type parameters", meth.pos)
    if (vparamss.length > 1) ctx.error("mirror methods should only have one parameter block", meth.pos)

    // @mirror type +[A <: Int, B <: Int] <: Int
    val paramsT = vparamss.head.map { vdef =>
      TypeDef(vdef.name.toTypeName, TypeBoundsTree(EmptyTree, vdef.tpt))
    }
    val rhsT = LambdaTypeTree(paramsT, TypeBoundsTree(EmptyTree, tpt))
    val tdef = TypeDef(name.toTypeName, rhsT).withMods(Modifiers(annotations = meth.mods.annotations))

    // @mirror def +(A: Int, B: Int): +[A.type, B.type] = (A + B).asInstanceOf[+[A.type, B.type]]
    val tpt2 = AppliedTypeTree(Ident(name.toTypeName), vparamss.head.map { vdef => SingletonTypeTree(Ident(vdef.name)) })
    val rhs2 = TypeApply(Select(meth.rhs, nme.asInstanceOf_), tpt2 :: Nil)
    val meth2 = cpy.DefDef(meth)(tpt = tpt2, rhs = rhs2)

    Thicket(tdef, meth2)
  }

  /** TODO: Keyword needed to be more semantic
   */
  def isMirrorDef(meth: DefDef)(implicit ctx: Context): Boolean = {
    meth.mods.annotations.exists {
      case Apply(Select(New(Ident(name)), nme.CONSTRUCTOR), Nil) => name.toString == "mirror"
      case _ => false
    }
  }

  def typeDefDef(sym: Symbol, rhs: tpd.Tree)(implicit ctx: Context) = {
    sym.unforcedAnnotation(defn.BodyAnnot) match {
      case Some(ann: ConcreteBodyAnnotation) =>
      case Some(ann: LazyBodyAnnotation) if ann.isEvaluated =>
      case _ =>
        if (!ctx.isAfterTyper) {
          val TypeApply(Select(expr, _), _) = rhs
          sym.addAnnotation(ConcreteBodyAnnotation(expr))
          val t = new tpd.TreeTraverser {
            def traverse(tree: tpd.Tree)(implicit ctx: Context): Unit = tree match {
              case ident: tpd.Ident =>
                if (ident.symbol.owner != sym)
                  ctx.error(s"Cannot access ${ident.symbol}. Only parameters are accessible in mirror methods.", ident.pos)
              case tree: tpd.DefTree =>
                ctx.error(s"Definitions are not allowed in mirror methods.", tree.pos)
              case _ => traverseChildren(tree)
            }
          }
          t.traverse(expr)
        }
    }
  }
}
