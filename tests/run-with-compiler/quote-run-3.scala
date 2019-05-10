import scala.quoted._

object Test {
  def main(args: Array[String]): Unit = {
    implicit val toolbox: scala.quoted.Toolbox = scala.quoted.Toolbox.make(getClass.getClassLoader)

    def foo(c: Expr[Boolean]): Unit = {
      var x = 1
      '{x}
      // if (${c})
      //   x = 2
      // else
      //   x = 3

      println(x)
    }
  }
}
