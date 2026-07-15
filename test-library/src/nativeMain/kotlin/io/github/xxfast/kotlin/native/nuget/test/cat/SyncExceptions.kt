package io.github.xxfast.kotlin.native.nuget.test.cat

fun feedCatTreat(catName: String): String {
  if (catName == "Oreo") throw IllegalArgumentException("Oreo is on a diet!")
  return "$catName got a treat"
}
