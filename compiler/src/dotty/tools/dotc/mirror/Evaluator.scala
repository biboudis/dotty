package dotty.tools
package dotc
package mirror

import core._
import ast._
import util.Positions._, Types._, Contexts._, Constants._, Names._, NameOps._, Flags._
import SymDenotations._, Symbols._, StdNames._, Annotations._, Trees._
import Decorators._, transform.SymUtils._
import NameKinds.{UniqueName, EvidenceParamName, DefaultGetterName}

object Evaluator {
  /** partially evaluate mirror types
   */
  def reduce(tp: Type)(cont: Type => Boolean)(implicit ctx: Context): Boolean = {
    println("reducing " + tp.show)
    println("tree = " + getTree(tp.typeSymbol))
    false
  }

  def getTree(sym: Symbol)(implicit ctx: Context) = {
    val methSym = sym.owner.asClass.typeRef.member(sym.name.toTermName).symbol
    methSym.unforcedAnnotation(defn.BodyAnnot).get.tree
  }
}
