import scala.annotation.mirror

class Test {
  // @reduce type +(A: Int, B: Int): Int = A + B
  // expands to
  // @reduce type +[A <: Int, B <: Int]
  // @reduce def +(A: Int, B: Int): +[A.type, B.type] = (A + B).asInstanceOf[+[A.type, B.type]]

  // @mirror type +[A <: Int, B <: Int] <: Int
  // @mirror def +(A: Int, B: Int): +[A.type, B.type] = (A + B).asInstanceOf[+[A.type, B.type]]
  @mirror def +(A: Int, B: Int): Int = A + B
  @mirror def *(A: Int, B: Int): Int = A * B

  val a: 5 + 4 = 9
  val b: 5 * 4 = 20

  def f(x: 9) = x * x
  def g(x: 20) = x + x

  f(a) + g(b)
}
