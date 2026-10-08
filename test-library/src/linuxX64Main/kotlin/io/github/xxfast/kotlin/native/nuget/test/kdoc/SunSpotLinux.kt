package io.github.xxfast.kotlin.native.nuget.test.kdoc

// ADR-150 fixture: the linuxX64 actuals for `SunSpot.kt`, deliberately bare of KDoc. A KDoc here
// would MASK the expect's text (`forwardKdoc` is `docString ?: expect's doc`), which is exactly
// what `IntegrationTests/XmlDocTests.cs` asserts against. The public surface must stay identical to
// `SunSpotMacos.kt`; only the returned values differ.

actual class SunSpot {
  actual val warmth: String = "toasty on linux"
  actual fun sunbeam(): String = "warm on linux"
}

actual fun basking(cat: String): String = "$cat basks on linux"

actual fun basking(minutes: Int): String = "someone basked $minutes min on linux"

actual val baskingTag: String = "perch-linux"

actual fun SunSpot.stretch(): String = "stretched out on linux"

actual fun SunSpot.stretchFor(minutes: Int): String = "stretched " + minutes + " min on linux"

actual object SunLounge {
  actual fun loungers(): Int = 2
}
