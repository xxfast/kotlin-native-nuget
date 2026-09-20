package io.github.xxfast.kotlin.native.nuget.test.kdoc

// ADR-150 fixture: the macosArm64 actuals for `SunSpot.kt`, deliberately bare of KDoc. A KDoc here
// would MASK the expect's text (`forwardKdoc` is `docString ?: expect's doc`), which is exactly
// what `IntegrationTests/XmlDocTests.cs` asserts against. The public surface must stay identical to
// `SunSpotMingw.kt`; only the returned values differ.

actual class SunSpot {
  actual val warmth: String = "toasty on macos"
  actual fun sunbeam(): String = "warm on macos"
}

actual fun basking(cat: String): String = "$cat basks on macos"

actual fun basking(minutes: Int): String = "someone basked $minutes min on macos"

actual val baskingTag: String = "perch-macos"

actual fun SunSpot.stretch(): String = "stretched out on macos"

actual fun SunSpot.stretchFor(minutes: Int): String = "stretched " + minutes + " min on macos"

actual object SunLounge {
  actual fun loungers(): Int = 2
}
