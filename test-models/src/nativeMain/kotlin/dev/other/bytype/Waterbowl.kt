package dev.other.bytype

/**
 * ADR-154 fixture package: a dependency package that is **not** in `:test-library`'s
 * `include(...)` and never will be. Everything here is reachable only through the new additive
 * `admit(...)` verb, by qualified type name.
 *
 * Three of the five declarations are admitted by name in `test-library/build.gradle.kts`
 * ([Bedding], [PurrLevel], [Waterbowl]); [Rimguard] and [Eartag] are deliberately left out, so
 * this one package holds both halves of the decision:
 *
 * - **admitted by name, no package walk** (ADR-154 §3): admitting `dev.other.bytype.Waterbowl`
 *   admits that declaration only. `Rimguard`, one segment away in the same package, stays refused
 *   `NOT_INCLUDED` and every member mentioning it skips named, with the class kept.
 * - **un-admitted top-level value class** (ROADMAP line 38, folded into ADR-154 §4): [Eartag] must
 *   become a *named skip*, not a `global::...Eartag` spelling of a type that was never declared.
 *   Today that spelling is emitted with no diagnostic and the C# fails CS0246.
 *
 * Deliberately a new package rather than a member of `dev.other.admitted`: that package is
 * admitted through `include(...)`, which is the mechanism this feature has to be distinguishable
 * from. The two sit one segment apart, exactly like `dev.other.core` (the negative case for
 * ADR-066) does.
 *
 * Oreo drinks only from the tap. Mylo uses the bowl, then knocks it over.
 */

/**
 * The plain enum, closed and edge-free: the `kermit.Severity` shape. Admitted by name, so it is
 * declared as a C# `enum` and converted by value at every position.
 */
enum class Bedding {
  FLEECE,
  WICKER,
}

/**
 * The ktor `LogLevel` shape: an enum with constructor properties **and** a companion, so the
 * admitted-enum route has to carry the extension-property projection (`PurrLevelExtensions`) and
 * the companion, not just the ordinals. Admitted by name.
 */
enum class PurrLevel(val audible: Boolean, val rumbling: Boolean) {
  SILENT(audible = false, rumbling = false),
  RUMBLE(audible = true, rumbling = true),
  CHIRRUP(audible = true, rumbling = false),
  ;

  companion object {

    /** The companion member, exactly as ktor's `LogLevel` carries one. */
    fun contented(): PurrLevel = RUMBLE
  }
}

/**
 * The ktor-`Url` shape: admitted by name, kept whole, and short of the two members it loses to a
 * sibling nobody admitted. Single-paragraph on purpose — ADR-150 folds any later paragraph into
 * the same `<remarks>` the #276 skip paragraphs use, and this type's remark is asserted. Its
 * constructor takes the `String` alone so the type stays constructible from C# and the cell is
 * about member admission rather than `WARNING_NO_PUBLIC_CONSTRUCTOR`.
 */
class Waterbowl(val label: String) {

  /** The surviving non-String member, so "member kept" is not just the constructor property. */
  fun millilitres(): Int = 250

  /**
   * Dropped at a **property** position. Per ADR-154 (verified by spike 1b), a property-position
   * dependency refusal is reported under `SKIPPED_UNSUPPORTED_PROPERTY` carrying the dependency
   * hint, *not* under `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`.
   */
  val rim: Rimguard get() = Rimguard("silicone")

  /**
   * Dropped at a **method/parameter** position: this is the
   * `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` arm, and its hint must read
   * `add admit("dev.other.bytype.Rimguard")`.
   */
  fun fitGuard(guard: Rimguard): String = "$label wears ${guard.material}"
}

/**
 * NOT admitted, on purpose: the sibling type that [Waterbowl]'s dropped members mention. It must
 * never be declared in C# (`admit` does not walk the package, ADR-154 §3).
 */
class Rimguard(val material: String)

/**
 * NOT admitted, on purpose: ROADMAP line 38's top-level dependency **value class**. A klib value
 * class reports `Modifier.INLINE` rather than `VALUE` (ADR-066), which the Tier 1 jar cannot
 * reproduce, so the gate has to be proven here against a real klib.
 *
 * Used by `admission.Storeroom.eartag()`, which must be absent from C# with a named skip. Today
 * it is spelled `global::TestLibrary.Dev.Other.Bytype.Eartag` with no declaration and no warning.
 */
value class Eartag(val code: String)
