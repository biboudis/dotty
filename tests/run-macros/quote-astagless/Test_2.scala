import scala.quoted._

object Test {


  def main(args: Array[String]): Unit = {

    // def f(x: Int) = '{
    //   if (x > 0)
    //     1
    //   else
    //     2
    // }

    // val f$ = (x: Int) => { implicit sym: Symantics[String] => {
    //   sym.Function(sym.If((sym.Gt(x, 0)),
    //               sym.Return(1),
    //               sym.Return(2)))
    //   }
    // }

    // f(1).asTagless(sym1) // f$(1)

    // f(1).asTagless(sym2)

    val sym1 = new Symantics[[_] => Int] {
      type Repr[Y] = Y
      def Literal[E](value: E): Int = value.asInstanceOf[Int]
      def Gt[E](lhs: Int, rhs:Int): Boolean = lhs < rhs
    }

    Macros.asTagless(sym1) {
      3 > 0
    }


  }
}
