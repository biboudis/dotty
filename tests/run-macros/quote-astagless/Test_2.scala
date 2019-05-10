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


    // @finalAlg @autoFunctorK
    // trait Increment[F[_]] {
    //   def plusOne(i: Int): F[Int]
    // }

    // implicit object incTry extends Increment[Try] {
    //   def plusOne(i: Int) = Try(i + 1)
    // }

    // def program[F[_]: Monad: Increment](i: Int): F[Int] = for {
    //   j <- Increment[F].plusOne(i)
    //   z <- if (j < 10000) program[F](j) else Monad[F].pure(j)
    // } yield z

    // Macros.asTagless(sym1) { args =>

    def program(i: Int): Symantics[Int] = virt(sym1) {
      val j = i + 1
      if (j < 10000)
        program(j)
      else
        j
    }

    implicit def pure[T](i: Int): Symantics[Int] = ???

    inline def virt[T, F[_] <: Symantics[_]](sym: F[_])(body: F[T]): F[T] = ${ impl('sym, 'body) }

    def impl[T, F[_] <: Symantics[_]](sym: Expr[F[_]], body: Expr[F[T]]): Expr[F[T]] = {
      ???
    }

    // def programImpl(sym: Symantics[T])(i: Int) = {
    //   val j = i + 1
    //   if (j < 10000)
    //     programImpl(j)
    //   else
    //     j
    // }

    // program(args.head)
  }

}
