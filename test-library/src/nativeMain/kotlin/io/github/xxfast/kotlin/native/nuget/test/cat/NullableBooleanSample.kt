package io.github.xxfast.kotlin.native.nuget.test.cat

/**
 * ADR-069: `Boolean?` forward mapping at every position the ADR's table names. Kotlin `Boolean` is
 * 1 byte on the native side (`kotlinx.cinterop.BooleanVar`, verified `Type(1)` / 1-byte `putByte`
 * write) against C#'s 4-byte default `bool` marshalling, so every cell below carries a mandatory
 * `false` case alongside `true`/`null`: a `true`/`null`-only fixture would pass under a 4-byte read
 * of a 1-byte write whenever the marshaller's stack temp happened to have zeroed upper bytes.
 * `tribool` drives all three states from one C#-supplied `Int` so no Kotlin-side state is needed.
 */
private fun tribool(state: Int): Boolean? = when (state) {
  0 -> true
  1 -> false
  else -> null
}

/** ADR-069 cell 1: data-class constructor parameter + read-only property getter (two-call). */
data class CatChip(val name: String, val implanted: Boolean? = null)

/**
 * ADR-069 cells 2-4: a mutable `Boolean?` property (two-call getter + `NullableDispatch` setter,
 * cell 2), an instance-method return (single-call `valueOut`, cell 3), and a nullable `Boolean`
 * input parameter on a non-null-returning method (cell 4 - proves the fan-out works both as a
 * constructor parameter and as an ordinary parameter).
 */
class CatChecklist(var vaccinated: Boolean? = null) {
  /** ADR-069 cell 3: instance-method return. */
  fun isGroomed(state: Int): Boolean? = tribool(state)

  /** ADR-069 cell 4: nullable `Boolean` input, non-null `String` return. */
  fun record(flag: Boolean?): String = flag?.toString() ?: "unknown"
}

/** ADR-069 cell 5: `object` method return. */
object ChipRegistry {
  fun isKnown(state: Int): Boolean? = tribool(state)
}

/** ADR-069 cell 6: companion method return. */
class CatRecord {
  companion object {
    fun isArchived(state: Int): Boolean? = tribool(state)
  }
}

/**
 * ADR-069 cell 7: top-level function return. `ForwardKotlinPlanEmitter.kt:173` hard-crashes
 * `packNuget` for this cell today (`require(value.result != BOOLEAN && value.result != VOID)`).
 * This fixture is what turns that from a theoretical hole into a reachable, reproducible one.
 */
fun chipImplanted(state: Int): Boolean? = tribool(state)

/** ADR-069 cell 8: top-level function, nullable `Boolean` input. */
fun describeChip(flag: Boolean?): String = flag?.toString() ?: "unknown"

/** ADR-069 cell 9: top-level mutable `Boolean?` property. */
var chipRegistryOnline: Boolean? = null

/** ADR-069 cell 10: extension-function return. */
fun Cat.isPurringNow(state: Int): Boolean? = tribool(state)

/**
 * ADR-069 cell 11: extension-property getter. Reuses `Cat.lives` (existing, immutable constructor
 * parameter) rather than adding stored state to `Cat`: the `Cat.kt` default (`9`) reads `true`,
 * `0` reads `false`, anything else reads `null`.
 */
val Cat.hasChip: Boolean?
  get() = when (lives) {
    9 -> true
    0 -> false
    else -> null
  }
