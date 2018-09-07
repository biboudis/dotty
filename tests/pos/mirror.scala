import scala.annotation.mirror

object mirrors {
  // @reduce type +(A: Int, B: Int): Int = A + B
  // expands to
  // @reduce type +[A <: Int, B <: Int]
  // @reduce def +(A: Int, B: Int): +[A.type, B.type] = (A + B).asInstanceOf[+[A.type, B.type]]

  // @mirror type +[A <: Int, B <: Int] <: Int
  // @mirror def +(A: Int, B: Int): +[A.type, B.type] = (A + B).asInstanceOf[+[A.type, B.type]]
  @mirror def +(A: Int, B: Int): Int = A + B
  @mirror def *(A: Int, B: Int): Int = A * B
  @mirror def Max(A: Int, B: Int): Int = if (A > B) A else B

  type Singleton[T] = T
  def singlify[T](implicit x: T): T = x
}

import mirrors._

// TODO: implicit synthesis for singleton types and mirror types (if it's constant)
class Vector[+N <: Int : Singleton] {
  def size: Int = singlify[N]

  def padding(x: Int): Vector[N + x.type] = ???

  def concat[M <: Int](vec: Vector[M]): Vector[M + N] = ???

  def maxPad(a: Int, b: Int): Vector[N + Max[a.type, b.type]] =
    padding(Max(a, b))
}

class Test {
  val a: 5 + 4 = 9
  val b: 5 * 4 = 20

  def f(x: 9) = x * x
  def g(x: 20) = x + x

  f(a) + g(b)

  val x: Max[6, 9] = 9
  val y: 9 = Max(6, 9)

  val vec: Vector[500] = ???
  val vec2 : Vector[1000] = vec.padding(500)
}
