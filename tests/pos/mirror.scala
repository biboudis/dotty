import scala.annotation.mirror

class Test {
  // @reduce type +[A <: Int, B <: Int] <: Int = A + B
  // expands to
  // @reduce type +[A <: Int, B <: Int]
  // @reduce def +(A: Int, B: Int): +[A.type, B.type] = (A + B).asInstanceOf[+[A.type, B.type]]

  // @mirror type +[A <: Int, B <: Int] <: Int
  // @mirror def +(A: Int, B: Int): +[A.type, B.type] = (A + B).asInstanceOf[+[A.type, B.type]]
  @mirror def +(A: Int, B: Int): Int = A + B
}