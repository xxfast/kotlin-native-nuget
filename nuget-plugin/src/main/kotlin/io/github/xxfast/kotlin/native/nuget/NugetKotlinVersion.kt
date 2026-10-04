package io.github.xxfast.kotlin.native.nuget

// ADR-195: one plugin release supports Kotlin from KOTLIN_FLOOR up to KOTLIN_TESTED. Below the
// floor the compiler cannot read the published klibs; above the last tested version it is
// expected to work but is unproven.
internal sealed interface KotlinSupport {
  data object Supported : KotlinSupport
  data class BelowFloor(val message: String) : KotlinSupport
  data class AboveTested(val message: String) : KotlinSupport
  data class Unrecognised(val message: String) : KotlinSupport
}

private val NUMERIC_VERSION = Regex("""^(\d+)\.(\d+)\.(\d+)""")

// Only the leading numeric major.minor.patch counts, so `2.4.0-RC2` compares as `2.4.0`.
private fun parse(version: String): List<Int>? =
  NUMERIC_VERSION.find(version)?.groupValues?.drop(1)?.map(String::toInt)

private fun compare(left: List<Int>, right: List<Int>): Int =
  left.zip(right).map { (l, r) -> l.compareTo(r) }.firstOrNull { it != 0 } ?: 0

internal fun kotlinSupport(current: String, floor: String, tested: String): KotlinSupport {
  val floorParts: List<Int> = requireNotNull(parse(floor)) { "[nuget] Kotlin floor '$floor' is not major.minor.patch" }
  val testedParts: List<Int> = requireNotNull(parse(tested)) { "[nuget] Kotlin tested '$tested' is not major.minor.patch" }
  val currentParts: List<Int> = parse(current) ?: return KotlinSupport.Unrecognised(
    "[nuget] Could not read the Kotlin version '$current'; kotlin-native-nuget $PLUGIN_VERSION supports " +
      "Kotlin $floor to $tested and did not check this build.",
  )

  val abi = "${floorParts[0]}.${floorParts[1]}"
  if (compare(currentParts, floorParts) < 0) return KotlinSupport.BelowFloor(
    "[nuget] Kotlin $current is not supported. kotlin-native-nuget $PLUGIN_VERSION needs Kotlin $floor or newer: " +
      "its nuget-runtime and nuget-annotations klibs are built at klib ABI $abi, which an older Kotlin/Native " +
      "compiler cannot read. Update the Kotlin Gradle plugin to $floor or newer.",
  )

  if (compare(currentParts, testedParts) > 0) return KotlinSupport.AboveTested(
    "[nuget] Kotlin $current is newer than the last version kotlin-native-nuget $PLUGIN_VERSION was tested " +
      "with ($tested). It is expected to work. If it does not, update the plugin.",
  )

  return KotlinSupport.Supported
}
