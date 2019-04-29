
import scala.quoted._
import scala.quoted.matching._

import scala.tasty.Reflection


object Macros {

  inline def asTagless[T, Repr[_]](sym: Symantics[Repr])(a: => T): Repr[T] = ${impl[T, Repr]('sym, 'a)}

  private def impl[T: Type, Repr[_]: Type](sym: Expr[Symantics[Repr]], a: Expr[T])(implicit reflect: Reflection): Expr[Repr[T]] = {

    def lift[X](e: Expr[Any]): Expr[Repr[X]] = (e match {

      case Literal(c: Int) =>
        '{ $sym.Literal($e) }

      case '{ ($x: Int) > ($y: Int) } =>
        '{ $sym.Gt(${lift(x)}, ${lift(y)}) }

      case _ =>
        import reflect._
        error("Expected explicit DSL", e.unseal.pos)
        '{ ??? }

    }).asInstanceOf[Expr[Repr[X]]]

    lift(a)
  }

}

trait Symantics[Repr[_]] {
  // def Method[E](body: Repr[E]): Repr[E]
  // def Return[E](e: Repr[E]): Repr[E]
  // def If[E](cond: Repr[Boolean], thenp: Repr[E], elsep: Repr[E]): Repr[E]

  def Literal[E](value: E): Repr[E]
  def Gt[E](lhs: Repr[E], rhs: Repr[E]): Repr[Boolean]
}
