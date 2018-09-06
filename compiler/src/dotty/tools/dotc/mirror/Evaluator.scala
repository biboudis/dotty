package dotty.tools
package dotc
package mirror

import core._
import typer._
import ast._, Trees._
import util.Positions._, Types._, Contexts._, Constants._, Names._, NameOps._, Flags._
import SymDenotations._, Symbols._, StdNames._, Annotations._
import Decorators._, transform.SymUtils._
import NameKinds.{UniqueName, EvidenceParamName, DefaultGetterName}

object Evaluator {
  import tpd._

  /** partially evaluate mirror types */
  def reduce(tp: AppliedType)(cont: Type => Boolean)(implicit ctx: Context): Boolean = {
    val reduced: Type = reduce(tp)
    reduced != tp && cont(reduced)
  }

  def reduce(tp: Type)(implicit ctx: Context): Type = tp match {
    case AppliedType(typcon: TypeRef, args) =>
      val args2 = args.map(arg => reduce(arg))

      val reducible = args2.forall(_.isInstanceOf[ConstantType])

      if (reducible) {
        val sym = typcon.typeSymbol
        val expr = getTree(sym)
        val names = getNames(sym)

        val values = args.map { case ConstantType(const) => tpd.Literal(const) }
        val env = names.zip(values).toMap

        val treeMap = new TreeMap {
          override def transform(t: Tree)(implicit ctx: Context) = t match {
            case Ident(name) => env(name.asTermName)
            case _ => super.transform(t)
          }
        }
        val exprClosed = treeMap.transform(expr)
        ConstFold(exprClosed).tpe
      } else AppliedType(typcon, args2)
    case _ =>
      tp
  }

  def getNames(sym: Symbol)(implicit ctx: Context): List[TermName] = {
    val methSym = sym.owner.asClass.typeRef.member(sym.name.toTermName).symbol
    methSym.info.paramNamess.flatten
  }

  def getTree(sym: Symbol)(implicit ctx: Context): Tree = {
    val methSym = sym.owner.asClass.typeRef.member(sym.name.toTermName).symbol
    methSym.unforcedAnnotation(defn.BodyAnnot).get.tree
  }
}
