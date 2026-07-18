package io.github.xxfast.kotlin.native.nuget.test.clinic

// ADR-060 ("The adversarial forward fixture") sequencing step 2. This is the `Test.Household`
// mirror for the *forward* direction: a designed corpus, not the `cat` package's overflow —
// every member exists to cross one seam, and says which. Unlike `Test.Household`, it does not
// land as one shape per fix; it splits by ADR-060's observability class, because that decides
// whether a shape stops the build. Its non-quarantined half lands here, green, now — every
// quarantined member below stays commented out, carrying its cell number, mechanism, and the
// real compiler error verified against it, until the commit that fixes it lands. See ADR-060
// "What lands in `test-library` now, and what is quarantined" for the full per-cell table.

/**
 * MIGRATION.md Phase 7 (input positions). A small mood enum, only for exercising an Enum-typed
 * *parameter* (as opposed to the already-shipped Enum property setter) across a few callable
 * positions below.
 */
enum class Mood { CALM, ANXIOUS, PLAYFUL }

/**
 * The clinic facade — a Kotlin `object`, which is the natural shape for an export module and so
 * the first thing a new consumer meets (GOALS.md #2). `CatRegistry` is the only other `object`
 * fixture and returns only `Int` and `void`, which need no marshalling, so the object path —
 * which has no marshalling at all (`CirObjectRenderer.kt:24` renders the *native* return type as
 * the public one) — passed anyway. Cells 1-3.
 */
object Clinic {
  /** Cell 1 · LANDS NOW · obs C. Object × String return — the shape `CatRegistry` never had.
   *  Generated Kotlin and C# both compile; the C# just says `IntPtr greet(string)`. */
  fun greet(name: String): String = "Welcome to the clinic, $name"

  /** Cell 2 · QUARANTINED (Tier 1) until its fix · obs **K**. Object × object return. Verified with
   *  real konanc: `defaultValueFor` yields `null` for a non-`kotlin.` type, so the export is
   *  `: Patient` returning `Patient?` — *"return type mismatch: expected 'Patient', actual 'Patient?'"*.
   *  Same root cause as cells 6 and 10; the object carrier has no `StableRef` branch either. */
  fun intake(name: String): Patient = Patient(name)

  /** Cell 3, control: the exact `CatRegistry` shape. Must stay green — it is the path that works. */
  fun capacity(): Int = 12

  /** Cell 3, control: `void`. Needs no marshalling, which is why it never proved anything. */
  fun reset() {}

  /**
   * MIGRATION.md Phase 7. Object method × nullable `Int` parameter (an `Int` result keeps this
   * on the shared callable plan, unlike a `String` result — see [Patient.rename]'s cell 8 note).
   */
  fun setCapacity(value: Int?): Int = value ?: 0
}

/**
 * A patient. The class path (`translateClass` / `addClassExports`), which is where *position*
 * decides everything: the property loop re-reads `isMarkedNullable` (`CirClassTranslator.kt:110`),
 * the method loop does not (`:628`, `:636`). Same carrier, same type, three different outcomes.
 */
class Patient(val name: String) {
  /** Cell 7, control: nullable property positions already work (`Cat.owner`/`age`/`brother`). */
  var nickname: String? = null
  var weight: Int? = null
  var buddy: Patient? = null

  /**
   * Cell 14 · LANDS NOW (MIGRATION.md Phase 8). Char property on the shared property plan —
   * public C# surface is `char Grade`.
   */
  val grade: Char = 'A'

  /** Control · LANDS NOW: a non-null String method return works. */
  fun describe(): String = "$name, $weight kg"

  /** Control: a non-null String method parameter and return already work. */
  fun echoName(value: String): String = value

  /** Cell 8 · LANDS NOW · obs C. Class method × `String?` parameter. Renders non-null `string` — the
   *  API lies, and a consumer passing `null` gets `CS8625` (an error only under TWAE). */
  fun rename(to: String?): String = to ?: name

  /** Cell 12 · LANDS NOW · obs C. `Char` parameter. Verified in the real generated C header:
   *  Kotlin exports `patient_tag(void* handle, KChar initial, void* errorOut)` — `KChar` is
   *  `unsigned short`, 2 bytes — while C# renders **public** `string Tag(IntPtr initial)`. The
   *  consumer cannot pass a `char` at all, so this is a call-site defect, not a runtime one. */
  fun tag(initial: Char): String = "$initial-$name"

  /**
   * MIGRATION.md Phase 7 (input positions). Class method × nullable object-handle parameter.
   * An `Int` result (rather than `String`) so this actually exercises the shared callable plan.
   */
  fun attach(buddy: Patient?): Int {
    this.buddy = buddy
    return if (buddy == null) 0 else 1
  }

  /** MIGRATION.md Phase 7. Class method × nullable `Int` parameter. */
  fun adjustWeight(delta: Int?): Int {
    weight = (weight ?: 0) + (delta ?: 0)
    return weight!!
  }

  /** MIGRATION.md Phase 7. Class method × `Enum` parameter. */
  fun describeMood(mood: Mood): Int = mood.ordinal

  /** MIGRATION.md Phase 7. Class method × `List<String>` parameter. */
  fun addTags(tags: List<String>): Int = tags.size

  /** MIGRATION.md Phase 8. Class method × enum return (ordinal over INT32). */
  fun mood(): Mood = Mood.CALM

  /** MIGRATION.md Phase 8. Class method × Map return. */
  fun scores(): Map<String, Int> = mapOf("weight" to (weight ?: 0))

  /** MIGRATION.md Phase 8. Class method × Set return. */
  fun labels(): Set<String> = setOf(name)

  /**
   * Cell 13 · LANDS NOW (MIGRATION.md Phase 8). Char return on the shared callable plan —
   * catch default is `'\u0000'`, not the legacy `"0"` that broke konanc.
   */
  fun initial(): Char = name.first()

  companion object {
    /** MIGRATION.md Phase 7. Companion method × `List<String>` parameter. */
    fun batchAdmit(names: List<String>): Int = names.size
  }

  // ---- QUARANTINED in the Tier 1 harness until each fix lands (all obs K, all verified) ----

  /** Cell 4 · obs K. Class method × `String?` return. The export declares non-null `String` and the
   *  body returns `String?`. The C# half compounds it with a null-forgiving `!` (`:658`). */
  // fun alias(): String? = nickname

  /** Cell 5 · obs K. Class method × `Int?` return. Same mechanism, primitive facet. */
  // fun ageInMonths(): Int? = weight?.times(2)

  /** Cell 6 · obs K. Class method × object return. The method loop has no `StableRef` branch, unlike
   *  the property loop (`:361`/`:404`), so ADR-005 holds for properties and not for methods. */
  // fun companion(): Patient = buddy ?: this
}

/**
 * Cell 21: a secondary constructor taking an object-typed parameter. `mapParamType`'s `?: "IntPtr"`
 * fallthrough renders it `(IntPtr)`, colliding with the `internal Referral(IntPtr handle)` the renderer
 * always emits (`CirClassRenderer.kt:62`). ADR-034's fail-fast cannot see it: it builds its signature
 * set from the Kotlin constructors alone (`CirClassTranslator.kt:89-92`). `CatId(name, number)` is the
 * only secondary-ctor fixture and its `(String, Int)` cannot collide.
 */
// NEVER LANDS in test-library. Its Kotlin compiles (verified with real konanc: `@CName` accepts a
// Kotlin-class parameter and links a real .dll), so the CS0111 genuinely reaches Interop.cs and would
// brick every consumer including IntegrationTests. And its correct end state is a *fail-fast
// diagnostic* (ADR-034), so a version of this that "passes" would mean the check stopped firing.
// Permanent Tier 1 diagnostic cell.
// class Referral(val note: String) {
//   constructor(from: Patient) : this("referred by ${from.name}")
// }

/**
 * Cells 15/16 (Phase 9): value-class methods with parameters for both underlying-type branches.
 */
value class ChartId(val value: String) {
  /** Cell 15: primitive-underlying value-class method with a parameter. */
  fun matches(other: String): Boolean = value == other

  fun isValid(): Boolean = value.isNotBlank()
}

value class ChartRef(val patient: Patient) {
  /** Cell 16: reference-underlying value-class method with a parameter. */
  fun label(suffix: String): String = "${patient.name}$suffix"
}

/**
 * Cells 17/18/20: generic type arguments. `parameterizedBy` appears zero times in the processor, so
 * every `bestGuess` site renders a raw `List`. The three failing sites are the data-class constructor,
 * the generated `copy()` (`ClassExports.kt:687`) and the nullable list property getter; the non-null
 * list property (`Cat.nicknames`) escapes only by routing through the handle path, which is why
 * collections look complete from the C# side.
 */
// QUARANTINED · obs K (cells 17/18/20) until the type-argument fix lands.
// data class Visit(
//   val patient: String,
//   val symptoms: List<String>,
//   val notes: List<String>? = null,
// )

/** Cell 10 · QUARANTINED · obs K. A top-level factory returning a plain class. `FunctionExports.kt`
 *  handles nullable (`:53`), sealed-or-generic (`:105`, StableRef), enum (`:132`) and `Unit` (`:156`)
 *  returns — a plain class falls to `:178`, which returns the class itself and defaults to `null` on
 *  the catch path. Verified: *"return type mismatch: expected 'Patient', actual 'Patient?'"*. */
fun admit(name: String): Patient = Patient(name)

/** MIGRATION.md Phase 7 (input positions). Top-level function × non-nullable object-handle
 *  parameter (an `Int` result, not `String`, so this is on the shared callable plan). */
fun patientNameLength(patient: Patient): Int = patient.name.length

/**
 * MIGRATION.md Phase 7. A secondary carrier whose *primary constructor* takes a non-nullable
 * object-handle parameter alongside a `String` — constructors returning the class handle are
 * already on the shared plan (Phase 6), so this exercises the newly-admitted parameter shape.
 */
class Escort(val patient: Patient, val note: String)

/**
 * MIGRATION.md Phase 7. Extension function × object-handle receiver and nullable object-handle
 * parameter, in one shape: `pairWith(null)` clears [Patient.buddy]; `pairWith(other)` sets it.
 */
fun Patient.pairWith(other: Patient?): Patient? {
  buddy = other
  return buddy
}

// Deliberately not in this corpus: the cell-23 shape (`suspend inline fun <reified T>
// Patient.chartEntry(...): Result<T>`). Per ROADMAP.md's forward-diagnostics item (MVP.md P1) its
// correct end state is a named skip, not working code, so it lives only in Tier 1
// (`Tier1CompileCellsTest`), asserting today's real invalid-Kotlin failure until that item lands.
// Putting it here before then would stop the build with no fix available.
