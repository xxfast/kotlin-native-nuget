package io.github.xxfast.kotlin.native.nuget.test.kdoc

// ADR-150 fixture: the mingwX64 actuals for `SunSpot.kt`, deliberately bare of KDoc. A KDoc here
// would MASK the expect's text (`forwardKdoc` is `docString ?: expect's doc`), which is exactly
// what `IntegrationTests/XmlDocTests.cs` asserts against. The public surface must stay identical to
// `SunSpotMacos.kt`; only the returned values differ.

actual class SunSpot {
  actual val warmth: String = "toasty on mingw"
  actual fun sunbeam(): String = "warm on mingw"
}

actual fun basking(cat: String): String = "$cat basks on mingw"

actual fun basking(minutes: Int): String = "someone basked $minutes min on mingw"

actual val baskingTag: String = "perch-mingw"

actual fun SunSpot.stretch(): String = "stretched out on mingw"

actual fun SunSpot.stretchFor(minutes: Int): String = "stretched " + minutes + " min on mingw"

actual object SunLounge {
  actual fun loungers(): Int = 2
}
