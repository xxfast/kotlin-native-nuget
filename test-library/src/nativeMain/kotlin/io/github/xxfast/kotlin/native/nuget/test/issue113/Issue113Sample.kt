package io.github.xxfast.kotlin.native.nuget.test.issue113

import io.github.xxfast.kotlin.native.nuget.test.models.CatteryInternalApi

/**
 * Fixture for [#113](https://github.com/xxfast/kotlin-native-nuget/issues/113) / ADR-115: a
 * declaration carrying a `@RequiresOptIn`-meta-annotated marker is not part of the forward-exported
 * C# surface, at any `RequiresOptIn.Level`.
 *
 * Two failures, one fixture:
 * 1. the generated `CNameExports.kt` *reads* the marked declaration, so the generated Kotlin does
 *    not compile (`e: ... Litter bookkeeping, not a public API`);
 * 2. once opted in, every marked member lands in the C# public surface, where a consumer has no way
 *    to honour the marker.
 *
 * **This fixture is deliberately red before the implementation lands.** Every `ERROR`-level cell
 * below is failure (1), so `nugetGen`'s output fails to compile, `packNuget` fails, and
 * `IntegrationTests` cannot build. That is the strongest available failing test: weakening the
 * markers to `WARNING` for a green build would delete the reproducer for the reported bug.
 *
 * ## The absence trap
 *
 * Every assertion for this feature is that a member is **absent**, and this pipeline already drops
 * members for a dozen unrelated reasons (`SKIPPED_UNSUPPORTED_*`, `SKIPPED_NULLABLE_*`,
 * `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`, ...). A cell whose type would have been dropped anyway goes
 * green before the feature exists. So **every marked declaration here carries `String`**, the least
 * ambiguous exported type in the repo, and every marked member has an unmarked sibling of the
 * *same* type in the *same* class ([Litter.name], [Litter.plain], [Litter.mutablePlain],
 * [Shelter.address], [Cattery.plainName]) that must stay exported. Those controls are the only
 * thing that can catch a skip that is too wide, which absence assertions structurally cannot.
 *
 * ## What the compiler actually allows (verified against Kotlin 2.4.10, this repo's toolchain)
 *
 * ADR-115's Findings 2 and 3 come from a KSP-only spike, which does not run the frontend's
 * `OPT_IN_MARKER_ON_WRONG_TARGET` checker. Compiling the shapes they describe shows three of them
 * are not writable Kotlin at all:
 *
 * | Position | Kotlin 2.4.10 |
 * | -------- | ------------- |
 * | `@Marker` on a class / function / body property / top-level declaration | legal |
 * | `@property:Marker` on a constructor `val` | legal |
 * | default-target `@Marker` on a constructor `val`, marker with **no** `@Target` | **rejected**: "Opt-in requirement marker annotation cannot be used on parameter." The default use-site target for a constructor `val` is `param`, and a marker may not sit on a parameter |
 * | default-target `@Marker` on a constructor `val`, marker whose `@Target` excludes `VALUE_PARAMETER` | legal, and the target then resolves to `property` |
 * | `@get:Marker`, or the same annotation written on an explicit `get()` accessor | **rejected**: "...cannot be used on getter." Both spellings |
 * | `@set:Marker` on a `var` | legal |
 * | `@field:Marker` | **rejected**: "...cannot be used on field." Already out of ADR-115's scope, and not expressible either |
 *
 * So [LedgerApi] exists purely to make cell 2 writable, and cell 3 is a `@set:` marker rather than
 * the `@get:` one ADR-115 describes. The mechanism under test is unchanged and is the reason the
 * cell exists: an accessor-targeted marker is invisible on `KSPropertyDeclaration.annotations`, so
 * an implementation that reads only the declaration misses it and silently leaks the property.
 *
 * ## Cells
 *
 * | # | Shape | Mechanism |
 * | - | ----- | --------- |
 * | 1 | [Litter.extra] | `@property:` target: on `KSPropertyDeclaration.annotations`, NOT on the `KSValueParameter` (Finding 1). The issue's literal reported shape |
 * | 2 | [Litter.other] | default target on a constructor `val`, via a marker that excludes `VALUE_PARAMETER` |
 * | 3 | [Litter.viaSetter] | accessor-only marker: NOT on the property, only on `setter.annotations` |
 * | 4 | [Cattery.markedName] | member **function** skip, not just properties |
 * | 5 | [HouseRules] | marked **class**: refused at declaration/reachability, so no C# type at all |
 * | 6 | [Shelter.rules] | member whose **return type** is a marked class: its own `OPT_IN_MARKER_TYPE` reason, because `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`'s `include(...)` hint is actively wrong here |
 * | 7 | [Cattery.experimentalName] | `RequiresOptIn.Level.WARNING`: the decision is level-independent, and a level-keyed implementation would pass every other cell |
 * | 8 | [Cattery.crossModuleName] | marker declared in `:test-models`, applied here: retires ADR-115's one `Inferred` claim (klib, not jar) |
 * | C1 | [Litter.name], [Litter.plain], [Litter.mutablePlain], [Shelter.address], [Cattery.plainName] | control: **still exported** |
 * | C2 | [Cattery.consumesMarked] | control: `@OptIn(...)` is a marker *consumer*, not a marker *member*. `kotlin.OptIn` is not itself `@RequiresOptIn`-meta-annotated (Finding 7), so this stays **exported** |
 *
 * Deliberately absent, per ADR-115's Scope: function-`@param:`-only markers and
 * `@SubclassOptInRequired` (Finding 6: not a marker, and explicitly out of scope). `@field:`-only
 * markers are absent for a stronger reason than scope: the compiler rejects them.
 *
 * Oreo (black, white in the middle) and Mylo (brown and creamy) run the cattery. The paperwork is
 * internal; the cats are public.
 */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "Litter bookkeeping, not a public API")
annotation class InternalApi

/**
 * Cell 2's marker. Identical to [InternalApi] except for the `@Target`, which is what makes a
 * default-target marker on a constructor `val` legal: with `VALUE_PARAMETER` off the list, the
 * default use-site target resolves to `property` instead of `param`, and the frontend's
 * `OPT_IN_MARKER_ON_WRONG_TARGET` check no longer fires.
 *
 * This is not fixture contortion, it is the shape of most real markers. `kotlin.time.ExperimentalTime`
 * and friends all declare a `@Target` list.
 */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "Ledger bookkeeping, not a public API")
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
annotation class LedgerApi

/**
 * Cell 7's marker, at `WARNING` level. `@RequiresOptIn` covers two intents, *internal* and
 * *experimental*, and ADR-115 deliberately treats them the same: level is never consulted. If every
 * cell in this fixture were `ERROR`, a level-keyed implementation would pass the whole suite while
 * the experimental-marker case kept leaking to C# with no signal at all.
 */
@RequiresOptIn(level = RequiresOptIn.Level.WARNING, message = "Purr telemetry is still settling")
annotation class ExperimentalPurr

/**
 * Cells 1, 2, 3 and control C1, on the issue's exact shape: a `data class` with markers on
 * constructor `val`s.
 *
 * Every property here is a `String`, so nothing in this class can be dropped by any existing bridge
 * limitation. Pre-fix, all five are exported and `CNameExports.kt` reads the three marked ones.
 * Post-fix, only [name], [plain] and [mutablePlain] survive.
 */
data class Litter(
  /** Control C1: unmarked, same type, same class, constructor `val`. Must stay exported. */
  val name: String,
  /**
   * Cell 1, the issue's literal reported shape. The `@property:` target puts this annotation on the
   * `KSPropertyDeclaration` and **not** on the `KSValueParameter` (Finding 1), which is the single
   * claim the whole feature rests on.
   */
  @property:InternalApi val extra: String,
  /**
   * Cell 2: the same position without a use-site target. See [LedgerApi] for why the marker needs a
   * `@Target` list for this to compile at all.
   */
  @LedgerApi val other: String,
) {
  /**
   * Cell 3. An accessor-targeted marker is **not** on the property, only on
   * `KSPropertyDeclaration.setter.annotations`. An implementation that reads only
   * `declaration.annotations` misses this one and silently leaks it, which is the whole reason the
   * cell exists.
   *
   * ADR-115's table skips the *property* when its accessor carries a marker, which is what the C#
   * assertion pins. The defensible alternative, exporting it get-only because only writes require
   * the opt-in, is a semantic ADR-115 never considered separately from `@get:`, and `@get:` turns
   * out not to exist. Flagged rather than assumed.
   */
  @set:InternalApi
  var viaSetter: String = "unset"

  /** Control C1: the unmarked sibling of [viaSetter], same type, same mutability. Must stay exported. */
  var mutablePlain: String = "loaf"

  /** Control C1: an unmarked read-only body property. Must stay exported. */
  val plain: String get() = "plain:$name"
}

/**
 * Cell 5: a marked **class** with ordinary, fully bridgeable members. Structurally different from a
 * marked member: it is refused at declaration/reachability rather than per-callable, so no C# type
 * is declared for it at all and nothing in `CNameExports.kt` names it.
 *
 * Both members are `String`-typed and would export without complaint if the class itself were not
 * marked, so an empty or missing C# `HouseRules` can only be this feature's doing.
 */
@InternalApi
class HouseRules(val motto: String) {
  fun describe(): String = "rules:$motto"
}

/**
 * Cell 6: a member whose **return type** is cell 5's marked class.
 *
 * ADR-115 gives this its own `OPT_IN_MARKER_TYPE` reason instead of letting it fall through to the
 * existing machinery, because `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`'s hint names `include(...)` and
 * no `include` can ever bring a marked type into scope. The warning must blame the *type*, not the
 * member.
 *
 * The `@OptIn` on [rules] is load-bearing and is **not** control C2: the member genuinely uses a
 * marked type, so `test-library` itself does not compile without acknowledging it. C2 lives on
 * [Cattery.consumesMarked], whose return type is a plain `String`.
 */
class Shelter {
  /** Control C1: unmarked, plain `String` return, same class. Must stay exported. */
  fun address(): String = "12 Sunbeam Lane"

  @OptIn(InternalApi::class)
  fun rules(): HouseRules = HouseRules("no zoomies after midnight")
}

/**
 * Cells 4, 7, 8 and control C2: the member-**function** skip path, across all three marker
 * varieties, plus the `@OptIn` consumer that must survive it.
 *
 * Every function returns `String`, so no existing skip reason can reach any of them.
 */
class Cattery {
  /** Control C1: unmarked, `String`, same class. Must stay exported. */
  fun plainName(): String = "Oreo & Mylo"

  /** Cell 4: the member-function skip, with an `ERROR`-level module-local marker. */
  @InternalApi
  fun markedName(): String = "ledger:oreo"

  /**
   * Cell 7: the same shape at `WARNING` level. ADR-115 does not consult the level, so this must be
   * skipped exactly like [markedName]. Pre-fix it is the one marked cell that does *not* break the
   * generated compile (a `WARNING` marker only warns), which is precisely why failure (2), the
   * silent leak, is the half of the bug that matters for experimental markers.
   */
  @ExperimentalPurr
  fun experimentalName(): String = "telemetry:mylo"

  /**
   * Cell 8: marked with [CatteryInternalApi], declared one klib boundary away in `:test-models`.
   * If two-hop marker resolution does not survive the native klib read, this is the only cell in
   * the fixture that notices.
   */
  @CatteryInternalApi
  fun crossModuleName(): String = "cattery:oreo"

  /**
   * Control C2: a marker *consumer*, not a marker *member*. `kotlin.OptIn` carries no
   * `RequiresOptIn` meta-annotation (Finding 7), so the two-hop test answers `false` for it and
   * this function must stay exported. It really does call a marked member, so the `@OptIn` is not
   * decorative.
   */
  @OptIn(InternalApi::class)
  fun consumesMarked(): String = "opted-in:${markedName()}"
}

/**
 * Constructs a [Litter] from Kotlin, so the C# assertions about which *properties* survive do not
 * also depend on what happens to the primary constructor's parameters.
 *
 * ADR-115 decides the property skip; it does not decide whether a constructor parameter whose
 * property is marked keeps its slot in the generated C# constructor. Reading [Litter] through this
 * factory keeps the two questions separate. No `@OptIn` is needed here: both markers sit on the
 * *properties*, never on the parameters, so constructing one is not an opt-in usage.
 */
fun adopt(): Litter = Litter(name = "Oreo", extra = "chip-0001", other = "vet-2024-11")
