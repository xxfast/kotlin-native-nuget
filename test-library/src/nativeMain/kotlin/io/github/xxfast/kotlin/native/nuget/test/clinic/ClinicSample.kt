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
 * ADR-075 · getter facet. A minimal ObjectHandle-typed element for a nullable collection property
 * whose element itself needs conversion through `FromHandle<T>`, unlike [Visit.notes]'s
 * conversion-free `String` element (see [Roster]). Deliberately its own tiny type: nothing else
 * about this cell needs testing.
 */
class Nurse(val name: String)

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

  /**
   * ADR-077 sub-item 4 · primitive-underlying value class, param + return in one call. Mirrors
   * the ADR's own consumer-API sketch (`clinic.Prescribe(new Dosage(2.5))`). The wire carries
   * [Dosage]'s underlying `Double` directly (the primitive's own wire); Kotlin reconstructs the
   * result with `Dosage(v)`, C# unwraps the argument with `dosage.Milligrams` and rebuilds the
   * result with `new Dosage(nativeResult)`.
   */
  fun prescribe(dosage: Dosage): Dosage = Dosage(dosage.milligrams * 2)
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

  /**
   * ADR-077 sub-item 2 · mutable property position. String-underlying value class as a `var`
   * property: the getter unboxes to the underlying `String` on the wire and C# reconstructs the
   * record struct; the setter passes `value.Value` and Kotlin re-wraps with `ChartId(raw)`.
   * [chartStatus] observes it Kotlin-side. Plans via `isPlannable`'s `ValueClass` branch.
   * Non-null on purpose: `ChartId?` is sub-item 3.
   */
  var currentChart: ChartId = ChartId("CH-0")

  /**
   * ADR-077 sub-item 2 · the Kotlin-side observation for [currentChart]'s setter. Calls
   * `isValid()` on the stored value, so a C# write must have arrived as a genuinely re-wrapped
   * [ChartId] for either branch to make sense. Plain `String` return: already supported, so this
   * method binds independently of the property.
   */
  fun chartStatus(): String =
    if (currentChart.isValid()) "$name charted at ${currentChart.value}" else "$name uncharted"

  /**
   * ADR-077 sub-item 3 · nullable property (`var`) position. `ChartId?` rides the null pointer
   * already used by the nullable-String/ObjectHandle property shapes: no has-value pair, because
   * the underlying `String` is non-nullable by construction. [hasBackup] and [previousChart]
   * observe it Kotlin-side, so a C# write only reads back correctly if it arrived as a genuine
   * `null` or a genuinely re-wrapped [ChartId]. Plans once the sub-item-2 `isPlannable` guard on
   * `Nullable(ValueClass)` is removed.
   */
  var backupChart: ChartId? = null

  /** ADR-077 sub-item 3 · the Kotlin-side observation for [backupChart]'s setter. */
  fun hasBackup(): Boolean = backupChart != null

  /**
   * ADR-077 sub-item 3 · nullable return position. Returns [backupChart] itself, so this method
   * and the property getter share one piece of state: `null` before any C# write, the re-wrapped
   * [ChartId] after.
   */
  fun previousChart(): ChartId? = backupChart

  /**
   * ADR-077 sub-item 4 · primitive-underlying value class, property position. The getter/setter
   * wire is [Dosage]'s underlying `Double` directly, same shape as an ordinary `Double` property;
   * only the box/unbox at the boundary is new.
   */
  var dosage: Dosage = Dosage(1.0)

  /**
   * ADR-077 sub-item 4 · enum-underlying value class, property position. The wire is [Mood]'s
   * int ordinal; Kotlin unboxes with `.mood.ordinal` and re-wraps with
   * `Temperament(Mood.entries[value])`, C# reconstructs `new Temperament((Mood)raw)` and passes
   * `(int)value.Mood`.
   */
  var temperament: Temperament = Temperament(Mood.CALM)

  /**
   * ADR-077 sub-item 4 · enum-underlying value class, param + return in one call (the
   * [Clinic.prescribe] shape on the enum underlying). Branches on the wrapped [Mood], so the
   * parameter must arrive as a genuinely re-wrapped [Temperament].
   */
  fun soothe(current: Temperament): Temperament =
    if (current.mood == Mood.ANXIOUS) Temperament(Mood.CALM) else current

  /**
   * ADR-077 sub-item 4 · ObjectHandle-underlying value class, property position. [ChartRef]
   * wraps a `Patient`, so this patient owns a self-referential chart reference; nothing about
   * that is special to the bridge, it just keeps the fixture to one type. The wire is a
   * StableRef pointer, same as an ordinary object-handle property.
   */
  var chartRef: ChartRef = ChartRef(this)

  /**
   * ADR-079 · property getter/setter position, `Nullable(ValueClass)` over a Primitive
   * underlying. The has-value pair fans out through `ForwardPropertyPlanner`'s
   * `LegacyTwoCall`/`NullableDispatch` shapes, same machinery as [backupChart]'s String-underlying
   * nullable, but with [Dosage]'s `Double` on the value slot instead of a null pointer. [hasDosage]
   * observes it Kotlin-side, mirroring [hasBackup].
   */
  var lastDosage: Dosage? = null

  /** ADR-079 · the Kotlin-side observation for [lastDosage]'s setter. */
  fun hasDosage(): Boolean = lastDosage != null

  /**
   * ADR-079 · property getter/setter position, `Nullable(ValueClass)` over an Enum underlying.
   * Same shape as [lastDosage] but with [Mood]'s ordinal on the value slot. [temperamentStatus]
   * observes it Kotlin-side, mirroring [chartStatus].
   */
  var maybeTemperament: Temperament? = null

  /** ADR-079 · the Kotlin-side observation for [maybeTemperament]'s setter. */
  fun temperamentStatus(): String =
    maybeTemperament?.let { "$name is ${it.mood}" } ?: "$name has no recorded temperament"

  /**
   * ADR-079 · method parameter AND method return, Primitive underlying, in one call (the ADR's
   * own consumer-API sketch). `null` in means `null` out (the has-value pair reports false on
   * both the input and output slots); a real [Dosage] halves. Also the fixture cell for
   * `Nullable(Primitive) == 0.0` surviving as non-null when tapering a `1.0` down to `0.5` is not
   * itself the zero cell -- [standardDosage] below is.
   */
  fun taper(target: Dosage?): Dosage? = target?.let { Dosage(it.milligrams / 2) }

  /**
   * ADR-079 item 2 · the decisive Boolean-underlying value-class check: `Flag(false)` must
   * survive a nullable return distinguishably from `null`, proving the ADR-069
   * `[MarshalAs(UnmanagedType.I1)]` 1-byte contract still fires when the `valueOut` transfer is
   * inherited from a value class's underlying `Boolean` rather than a bare `Boolean`. `known =
   * false` means "never checked" (`null`); `known = true` returns the flag itself, which is
   * `Flag(false)` (checked, and cleared) -- never a stray 4-byte read landing on a truthy value.
   */
  fun quarantineFlag(known: Boolean): Flag? = if (known) Flag(false) else null

  /**
   * ADR-077 sub-item 4 · ObjectHandle-underlying value class, parameter position. Returns
   * `String` (already supported), so only the parameter facet is new here; [ownReferral] below
   * is the matching return-position cell.
   */
  fun reassign(referral: ChartRef): String = "$name reassigned to ${referral.patient.name}"

  /**
   * ADR-077 sub-item 4 · ObjectHandle-underlying value class, return position. Pairs with
   * [reassign] above so param and return are each exercised by a distinct method, not folded
   * into one call the way [prescribe] and [soothe] are.
   */
  fun ownReferral(): ChartRef = ChartRef(this)

  /**
   * ADR-077 sub-item 4 · `Nullable(ValueClass(ObjectHandle))`, the one deferred-until-now cell:
   * rides the null pointer exactly like sub-item 3's `ChartId?`, since an ObjectHandle underlying
   * is already pointer-shaped on the wire. Primitive/enum-underlying nullables stay deferred (the
   * wire has no in-band null for those).
   */
  var backupReferral: ChartRef? = null

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

  /** ADR-073. Class method × `Map<String, Int>` parameter: String key (boxed via
   *  nuget_wrap_string), Int value (boxed via nuget_wrap_int). */
  fun recordScores(scores: Map<String, Int>): Int = scores.values.sum()

  /** ADR-073. Class method × `MutableMap<String, Int>` parameter. The Kotlin body mutates, and the
   *  C# test asserts the caller's dictionary is UNCHANGED afterwards: this is the no-write-back
   *  regression cell. */
  fun tallyScores(scores: MutableMap<String, Int>): Int {
    scores["total"] = scores.values.sum()
    return scores.size
  }

  /** ADR-073. Class method × `Map<String, Patient>` parameter: the object-handle value cell,
   *  which exercises CreateMap's reflective `_handle` fallback on the value side. ADR-073 flagged
   *  this as the one path nobody had executed on the C# side; this cell is what verified it. */
  fun linkWard(ward: Map<String, Patient>): Int = ward.values.count { it.name.isNotBlank() }

  /** ADR-073. Class method × `Set<String>` parameter. */
  fun addLabels(labels: Set<String>): Int = labels.size

  /** ADR-073. Class method × `MutableSet<Int>` parameter: Int element (not String), and the second
   *  no-write-back assertion. */
  fun addCodes(codes: MutableSet<Int>): Int {
    codes.add(0)
    return codes.size
  }

  /** MIGRATION.md Phase 8. Class method × enum return (ordinal over INT32). */
  fun mood(): Mood = Mood.CALM

  /**
   * ADR-077 sub-item 1 · class-method position. String-underlying value class as an ordinary
   * *parameter*: the wire carries the underlying `String` and Kotlin re-wraps it with
   * `ChartId(raw)`. Calls a [ChartId] member on purpose, so a raw string smuggled through as the
   * parameter would not compile on the Kotlin side. `inputSkipReason()`'s `ValueClass` branch
   * admits the String underlying, so this method plans and both halves bind.
   */
  fun retag(id: ChartId): String = if (id.isValid()) "$name@${id.value}" else "$name@untagged"

  /**
   * ADR-077 sub-item 3 · nullable parameter position. Mirrors [retag]'s non-null parameter but
   * proves Kotlin sees `null` as `null` on the wire, not a [ChartId] wrapping an empty string:
   * only the null branch is reachable without a real re-wrapped value ever having crossed.
   */
  fun transferTo(to: ChartId?): String =
    if (to != null) "$name transferred to ${to.value}" else "$name has no transfer"

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

    /** ADR-073. Companion method × `Map<String, Int>` parameter, mirroring [batchAdmit] so the
     *  static call position is covered for maps too. */
    fun batchScore(scores: Map<String, Int>): Int = scores.size
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

  /**
   * ADR-082 amendment (2026-08-08), fix B: the first of two declared same-name overloads on a
   * value class. Exercises the secondary-constructor-style export-name numbering
   * (`${prefix}_$name` for this one, `${prefix}_${name}_2` for the next) that the C# surface
   * must resolve back to a single natural overload set on `ChartId`.
   */
  fun describe(): String = "Chart $value"

  /** Second declared overload of [describe]; must not collide with the first at the C symbol level. */
  fun describe(prefix: String): String = "$prefix $value"
}

value class ChartRef(val patient: Patient) {
  /** Cell 16: reference-underlying value-class method with a parameter. */
  fun label(suffix: String): String = "${patient.name}$suffix"
}

/**
 * ADR-077 sub-item 4 · primitive-underlying value class at ordinary positions ([Clinic.prescribe]
 * param + return, [Patient.dosage] property). Milligrams rather than a raw `Double` name so a
 * consumer reading `dosage.Milligrams` in C# has a reason to be reading a [Dosage], not just a
 * number.
 */
value class Dosage(val milligrams: Double)

/**
 * ADR-077 sub-item 4 · enum-underlying value class. The bare declaration used to crash
 * `packNuget` outright on both KSP targets (`java.lang.IllegalArgumentException: Forward ABI
 * missing C# projection for temperament_create; expected temperament_create(in int, out pointer)
 * -> int`); it now packs as a `readonly record struct Temperament` wrapping [Mood]. Its use at
 * ordinary positions ([Patient.temperament] property, [Patient.soothe] param + return) is the
 * next slice and stays quarantined above.
 */
value class Temperament(val mood: Mood)

/**
 * ADR-079 item 2 · Boolean-underlying value class. Exists only so a nullable return crossing
 * `false` is provably distinct from crossing `null` -- see [Patient.quarantineFlag].
 */
value class Flag(val value: Boolean)

/**
 * ADR-075 · extension-property + setter regression cell. Same eligibility predicate as an ordinary
 * property setter ([Chart.tags] in `ChartSample.kt`), but the receiver crosses the bridge by value
 * ([ChartId]'s underlying `String`, not an ObjectHandle) — nobody had combined that with a
 * collection setter before this change. Backed by a package-level map keyed by the value class
 * itself: value classes derive structural equality from their underlying value, so two [ChartId]s
 * built from the same string collide on the same entry, which is the correct behaviour for a
 * by-value receiver.
 */
private val chartIdSymptomTags: MutableMap<ChartId, List<String>> = mutableMapOf()

var ChartId.symptomTags: List<String>
  get() = chartIdSymptomTags[this] ?: emptyList()
  set(value) {
    chartIdSymptomTags[this] = value
  }

/**
 * Extension-property-receiver widening, half 1 · no ADR, a clean mirror of ADR-077's parameter
 * lowering at the receiver slot. `ForwardPropertyPlanner.extensionProperty`'s `supportedReceiver`
 * check has admitted a value-class receiver only over a `Primitive`/`String` underlying
 * ([ChartId.symptomTags] above is the `String` precedent); an enum-underlying or
 * ObjectHandle-underlying receiver still hits the named `SKIPPED_UNSUPPORTED_PROPERTY` receiver
 * diagnostic and the whole property vanishes. [Dosage.label] below closes the primitive-underlying
 * cell ROADMAP.md names as untested even though `Primitive` is already in the admit list ("A
 * primitive-underlying value-class extension-property receiver is also untested, only the
 * `String`-underlying case (`ChartId`) is covered"); [Temperament.note] and [ChartRef.annotation]
 * are the two cells that do not admit at all yet. Backed by a package-level map per receiver, same
 * shape as [chartIdSymptomTags]: a value class's structural equality (delegating to its underlying
 * value) makes it a correct map key, and for [ChartRef] specifically that equality delegates to the
 * identical [Patient] instance the StableRef returns, so keying by [ChartRef] only works because a
 * repeated crossing of the same handle resolves to the same Kotlin object.
 */
private val dosageLabels: MutableMap<Dosage, String> = mutableMapOf()

/** Primitive-underlying (`Double`) receiver — the ROADMAP-noted untested cell. */
var Dosage.label: String
  get() = dosageLabels[this] ?: ""
  set(value) {
    dosageLabels[this] = value
  }

private val temperamentNotes: MutableMap<Temperament, String> = mutableMapOf()

/** Enum-underlying (`Mood`) receiver — skips today via `SKIPPED_UNSUPPORTED_PROPERTY`. */
var Temperament.note: String
  get() = temperamentNotes[this] ?: ""
  set(value) {
    temperamentNotes[this] = value
  }

private val chartRefAnnotations: MutableMap<ChartRef, String> = mutableMapOf()

/** ObjectHandle-underlying (`Patient`) receiver — skips today via `SKIPPED_UNSUPPORTED_PROPERTY`. */
var ChartRef.annotation: String
  get() = chartRefAnnotations[this] ?: ""
  set(value) {
    chartRefAnnotations[this] = value
  }

/**
 * Extension-property-receiver widening, half 2 · no ADR, same mirror as the properties above but
 * for the callable plan: `ForwardCallablePlanner.extensionEntry` has *admitted* a value-class
 * receiver of any underlying since ADR-077 (`inputSkipReason()` does not special-case the receiver
 * position), but nothing downstream lowers it correctly. `ChartId.abbreviate` (`String` underlying)
 * and `Temperament.escalate` (`Mood` enum underlying, and a [Temperament] *return* in the same
 * call) are the first fixtures to declare one; zero existed before this, so this shape has never
 * actually run. Expected failure, captured verbatim rather than fixed here: on the Kotlin side,
 * `ForwardKotlinPlanEmitter.receiverExpression` only special-cases an `ObjectHandle` receiver, so
 * the generated wrapper declares its `receiver` parameter typed as the *wire* (`String` for
 * [ChartId], `Int` for [Temperament]'s ordinal) and then calls `receiver.abbreviate(...)` /
 * `receiver.escalate()` directly on that wire value, which does not compile: neither `String` nor
 * `Int` has an `abbreviate`/`escalate` member. On the C# side, the generated `DllImport` expects
 * the underlying wire type but the generated call site still passes the raw value-class struct.
 */
fun ChartId.abbreviate(length: Int): String = value.take(length)

/**
 * Enum-underlying receiver AND a value-class return in the same call, per the class doc above.
 */
fun Temperament.escalate(): Temperament = when (mood) {
  Mood.CALM -> Temperament(Mood.ANXIOUS)
  Mood.ANXIOUS -> Temperament(Mood.PLAYFUL)
  Mood.PLAYFUL -> Temperament(Mood.PLAYFUL)
}

/**
 * ADR-077 sub-item 1 · constructor position (regular class). [chart] is a plain constructor
 * parameter, not a `val` (the property facet is [Patient.currentChart]'s and [ChartEntry.id]'s
 * job), so this cell measures the constructor seam alone. [label] is an ordinary `String`
 * property, which is what the C# side reads back to prove Kotlin saw a re-wrapped [ChartId].
 */
class Admission(chart: ChartId, val ward: String) {
  val label: String = "${chart.value}/$ward"
}

/**
 * ADR-079 · constructor parameter position, `Nullable(ValueClass)` over a Primitive underlying.
 * Mirrors [Admission]'s non-null ctor-parameter cell, but with a nullable [Dosage]: the has-value
 * pair fans out at the constructor the same way it does at [Patient.taper]'s parameter, and
 * [label] reads back whichever branch actually crossed so a smuggled default dosage would not
 * compile-and-pass by coincidence.
 */
class Prescription(dosage: Dosage?, val patient: String) {
  val label: String =
    if (dosage != null) "$patient: ${dosage.milligrams}mg" else "$patient: no dosage prescribed"
}

/**
 * ADR-077 sub-item 1 · data-class primary-constructor position, and the generated `copy()` that
 * mirrors it. A data class primary constructor parameter *must* be `val`/`var`, so [id] is also a
 * property: the sub-item 2 read-only getter cell (`entry.Id` in C#), asserted by
 * `ValueClassPropertyTests`. [label] exposes the same information through an already-supported
 * `String` return.
 */
data class ChartEntry(val id: ChartId, val note: String) {
  fun label(): String = "${id.value}: $note"
}

/**
 * ADR-077 sub-item 1 · extension-function position. Object-handle receiver ([Patient], already
 * supported) × value-class parameter, so the only new seam is the parameter.
 */
fun Patient.chartLabel(id: ChartId): String = "$name reads ${id.value}"

/**
 * ADR-079 · extension-function parameter position, `Nullable(ValueClass)` over an Enum
 * underlying. `null` means "no expected mood filter"; a wrapped [Temperament] must arrive as a
 * genuine re-wrapped value for the ordinal comparison against [Patient.temperament] to be
 * meaningful rather than accidental.
 */
fun Patient.matchesTemperament(expected: Temperament?): Boolean =
  expected == null || temperament.mood == expected.mood

/**
 * ADR-077 sub-item 1 · top-level-function position. Mirrors [Patient.retag] on the top-level
 * carrier, and likewise calls a [ChartId] member so the parameter has to arrive as a real value
 * class rather than its underlying `String`.
 */
fun chartSummary(id: ChartId): String =
  if (id.isValid()) "Chart ${id.value} filed" else "Chart missing"

/**
 * ADR-079 · top-level-function parameter position, `Nullable(ValueClass)` over an Enum
 * underlying. Mirrors [Patient.matchesTemperament] on the top-level carrier.
 */
fun describeTemperament(temperament: Temperament?): String =
  if (temperament != null) "Mood: ${temperament.mood}" else "Mood: unknown"

/**
 * ADR-079 · top-level-function return position, `Nullable(ValueClass)` over a Primitive
 * underlying (the ADR's own consumer-API sketch: `fun standardDosage(kind: Int): Dosage?`).
 * `kind < 0` is the `null` cell (ADR-002 two-call reports `_has_value = false`); `kind == 0` is
 * the mandatory `0.0`-survives-as-non-null cell (`Dosage(0.0)`, not the in-band sentinel a
 * two-call shape is specifically here to avoid confusing with `null`); a positive `kind` returns
 * a real half-milligram-per-unit dose.
 */
fun standardDosage(kind: Int): Dosage? =
  if (kind < 0) null else Dosage(kind * 0.5)

/**
 * Cells 17/18/20: generic type arguments. `parameterizedBy` appeared zero times in the processor,
 * so every `bestGuess` site rendered a raw `List`. The three failing sites were the data-class
 * constructor, the generated `copy()` (`ClassExports.kt:687`) and the nullable list property
 * getter; the non-null list property (`Cat.nicknames`) escaped only by routing through the handle
 * path, which is why collections looked complete from the C# side.
 *
 * ADR-075 · getter facet. `notes` is the conversion-free control for the nullable-collection
 * getter: a raw `String` element, `null` on an unset visit rather than a stray `IntPtr`. See
 * [Roster.staff] for the ObjectHandle-element counterpart.
 */
data class Visit(
  val patient: String,
  val symptoms: List<String>,
  val notes: List<String>? = null,
)

/**
 * ADR-075 · getter facet. Hosts `staff: List<Nurse>?`, a nullable collection getter whose element
 * is an ObjectHandle ([Nurse]), unlike [Visit.notes]'s raw `String` element. `nurse` is a plain
 * nullable ObjectHandle constructor parameter (an already-supported shape), so this cell exercises
 * only the getter side: a populated roster proves the ObjectHandle element materializes through
 * `FromHandle<T>`; an empty one proves the null-handle guard returns `null`, not a stray `IntPtr`.
 */
class Roster(nurse: Nurse?) {
  val staff: List<Nurse>? = nurse?.let { listOf(it) }
}

/** Cell 10 · QUARANTINED · obs K. A top-level factory returning a plain class. `FunctionExports.kt`
 *  handles nullable (`:53`), sealed-or-generic (`:105`, StableRef), enum (`:132`) and `Unit` (`:156`)
 *  returns — a plain class falls to `:178`, which returns the class itself and defaults to `null` on
 *  the catch path. Verified: *"return type mismatch: expected 'Patient', actual 'Patient?'"*. */
fun admit(name: String): Patient = Patient(name)

/** MIGRATION.md Phase 7 (input positions). Top-level function × non-nullable object-handle
 *  parameter (an `Int` result, not `String`, so this is on the shared callable plan). */
fun patientNameLength(patient: Patient): Int = patient.name.length

/** ADR-073. Top-level function × `Set<String>` parameter. */
fun countLabels(labels: Set<String>): Int = labels.size

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

/** ADR-073. Extension function × `Map<String, Int>` parameter, over an object-handle receiver. */
fun Patient.mergeScores(extra: Map<String, Int>): Int = extra.size

/** ADR-073. Constructor × `Set<String>` parameter. */
class Ward(val name: String, val tags: Set<String>)

// Deliberately not in this corpus: the cell-23 shape (`suspend inline fun <reified T>
// Patient.chartEntry(...): Result<T>`). Per ROADMAP.md's forward-diagnostics item (MVP.md P1) its
// correct end state is a named skip, not working code, so it lives only in Tier 1
// (`Tier1CompileCellsTest`), asserting today's real invalid-Kotlin failure until that item lands.
// Putting it here before then would stop the build with no fix available.
