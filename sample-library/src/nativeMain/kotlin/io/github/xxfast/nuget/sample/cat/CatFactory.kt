package io.github.xxfast.nuget.sample.cat

fun createBrothers(firstName: String, secondName: String): Cat {
  val first = Cat(firstName)
  val second = Cat(secondName)
  first.brother = second
  second.brother = first
  return first
}
