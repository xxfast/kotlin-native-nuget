package io.github.xxfast.kotlin.native.nuget.sample.overloads

import sample.overloads.OverloadLab
import sample.structs.Point
import sample.structs.Size

// ADR-057 consumer boundary: these calls compile only when the reverse projection preserves
// ordinary same-name Kotlin overloads and every class/struct constructor. Distinct markers from
// the managed fixture make a swapped registration pointer or incorrect overload dispatch visible.

fun describeOverloads(value: Int, flag: Boolean): String =
  "${OverloadLab.describe(value)}|${OverloadLab.describe(flag)}"

fun applyOverloads(text: String, value: Int): String {
  val lab = OverloadLab(7)
  try {
    return "${lab.apply(text)}|${lab.apply(value)}"
  } finally {
    lab.close()
  }
}

fun classConstructorOverloads(): String {
  val seeded = OverloadLab(9)
  val toggled = OverloadLab(false)
  try {
    return "${seeded.apply(2)}|${toggled.apply("Mylo")}"
  } finally {
    toggled.close()
    seeded.close()
  }
}

fun structConstructorOverloads(): String {
  val primary = Point(2, 3)
  val scalar = Point(4)
  val converted = Point(true)
  val structArgument = Point(Size(5, 6))
  return "${primary.x},${primary.y}|${scalar.x},${scalar.y}|" +
      "${converted.x},${converted.y}|${structArgument.x},${structArgument.y}"
}
