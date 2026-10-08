package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * ADR-065: StateFlow<T> mapping fixture.
 *
 * Tracks a cat's mood, energy level, and current playmate as hot, always-current-value
 * streams -- exercising every branch the `.Value` unwrap / stream element marshalling cascade
 * must cross:
 *  - [energyLevel]: `StateFlow<Int>`    -- primitive, no conversion at the seam
 *  - [mood]:        `StateFlow<String>` -- needs conversion (box unwrap)
 *  - [playmate]:    `StateFlow<Cat>`    -- object element, handle-backed IDisposable wrapper
 *
 * [moodReport] additionally covers `StateFlow<T>` as a **non-suspend function return** (it
 * shares the same underlying [_mood] MutableStateFlow as the [mood] property, so mutating one
 * is observable through both surface positions).
 *
 * All mutations are driven by explicit methods ([bumpEnergy], [setMood], [setPlaymate]) --
 * never a timer -- so tests can assert deterministic conflated updates without racing a
 * background emitter.
 *
 * ADR-067 extends this fixture with the two nullable StateFlow shapes:
 *  - [nickname]:    `StateFlow<String?>` -- nullable REFERENCE element (`.Value` is `string?`)
 *  - [streak]:      `StateFlow<Int?>`    -- nullable VALUE element (`.Value` is `int?`, needs the
 *                    `Nullable<T>`-aware unwrap; a plain `int` would pass trivially and hide the seam)
 *  - [maybeMood]:   `StateFlow<String>?` -- nullable MEMBER, absent until [startTracking]
 *  - [maybeStreak]: `StateFlow<Int?>?`   -- nullable MEMBER *and* nullable VALUE element, together
 *
 * ADR-071 extends this fixture with genuinely-declared (not `.asStateFlow()`-narrowed)
 * `MutableStateFlow<T>` members, so C# gets a settable `.Value`:
 *  - [treatCount]:    `MutableStateFlow<Int>`    -- primitive element, no conversion at the write seam
 *  - [collarColour]:  `MutableStateFlow<String>` -- needs conversion at the write seam
 *  - [favouriteToy]:  `MutableStateFlow<Cat>`    -- object element, crosses as a handle
 *  - [treatJar]:      `MutableStateFlow<Int>` as a non-suspend function return, sharing storage
 *                      with [treatCount]
 *  - [grudge]:        `MutableStateFlow<Grudge>` whose element's `equals` throws, forcing the
 *                      setter's ADR-030 `errorOut` path
 */
class CatMoodTracker(private val catName: String) {
  private val _energyLevel: MutableStateFlow<Int> = MutableStateFlow(100)

  /** StateFlow<Int> as a class property -- primitive element, no conversion at the seam. */
  val energyLevel: StateFlow<Int> = _energyLevel.asStateFlow()

  private val _mood: MutableStateFlow<String> = MutableStateFlow("sleepy")

  /** StateFlow<String> as a class property -- needs conversion (box unwrap) at the seam. */
  val mood: StateFlow<String> = _mood.asStateFlow()

  private val _playmate: MutableStateFlow<Cat> = MutableStateFlow(Cat(catName))

  /** StateFlow<Cat> as a class property -- object element, handle-backed IDisposable wrapper. */
  val playmate: StateFlow<Cat> = _playmate.asStateFlow()

  /**
   * StateFlow<T> as a non-suspend function return, mirroring [mood] verbatim (same underlying
   * MutableStateFlow) so mutation-visibility can be asserted through both surface positions.
   */
  fun moodReport(): StateFlow<String> = mood

  /** Deterministic mutation -- bumps [energyLevel] by [amount]. No timers involved. */
  fun bumpEnergy(amount: Int) {
    _energyLevel.value += amount
  }

  /** Deterministic mutation -- sets [mood] (and therefore [moodReport]) to [newMood]. */
  fun setMood(newMood: String) {
    _mood.value = newMood
  }

  /** Deterministic mutation -- replaces [playmate] with a freshly named [Cat]. */
  fun setPlaymate(name: String) {
    _playmate.value = Cat(name)
  }

  // --- ADR-067: nullable element -- the tracker always exists, its current value can be null ---

  private val _nickname: MutableStateFlow<String?> = MutableStateFlow(null)

  /**
   * StateFlow<String?> -- nullable REFERENCE element. `.Value` is `string?`; a null current
   * value crosses as IntPtr.Zero and reuses `FromHandle<T>` unchanged (already null-safe).
   */
  val nickname: StateFlow<String?> = _nickname.asStateFlow()

  private val _streak: MutableStateFlow<Int?> = MutableStateFlow(null)

  /**
   * StateFlow<Int?> -- nullable VALUE element. `.Value` is `int?`; needs the new
   * `Nullable<T>`-aware unwrap (a plain `int` would need no conversion and pass trivially).
   */
  val streak: StateFlow<Int?> = _streak.asStateFlow()

  /** Deterministic mutation -- sets [nickname], which may be null. */
  fun setNickname(name: String?) {
    _nickname.value = name
  }

  /** Deterministic mutation -- sets [streak], which may be null. */
  fun setStreak(n: Int?) {
    _streak.value = n
  }

  // --- ADR-067: nullable member -- the whole StateFlow can be absent until tracking starts ---

  private var _maybeMood: MutableStateFlow<String>? = null

  /**
   * StateFlow<String>? -- nullable MEMBER. Null until [startTracking] is called; the `_has_value`
   * presence-probe backs the C# getter, which returns `null` before subscription.
   */
  val maybeMood: StateFlow<String>? get() = _maybeMood?.asStateFlow()

  /** Deterministic mutation -- brings [maybeMood] into existence with [initial]. */
  fun startTracking(initial: String) {
    _maybeMood = MutableStateFlow(initial)
  }

  /** Deterministic mutation -- sets [maybeMood]'s current value, once tracking has started. */
  fun setMaybeMood(m: String) {
    _maybeMood?.value = m
  }

  // --- ADR-067: both together -- nullable member AND nullable value element ---

  private var _maybeStreak: MutableStateFlow<Int?>? = null

  /** StateFlow<Int?>? -- nullable member AND nullable value element, exercised together. */
  val maybeStreak: StateFlow<Int?>? get() = _maybeStreak?.asStateFlow()

  /** Deterministic mutation -- brings [maybeStreak] into existence with [initial] (may be null). */
  fun startStreakTracking(initial: Int?) {
    _maybeStreak = MutableStateFlow(initial)
  }

  /** Deterministic mutation -- sets [maybeStreak]'s current value, once tracking has started. */
  fun setMaybeStreak(n: Int?) {
    _maybeStreak?.value = n
  }

  // --- ADR-068: suspend fun returning StateFlow<T> -- outer suspend kept as Task, inner is ADR-065's
  // KotlinStateFlow<T> unchanged. Both genuinely suspend (a real delay) before handing back the SAME
  // underlying MutableStateFlow already exposed elsewhere, so mutation is observable across every
  // surface position and the outer suspend is not vestigial. ---

  /**
   * ADR-068: `suspend fun` returning `StateFlow<String>` -- primitive/value-element variant.
   * Genuinely suspends (a real await) before handing back the SAME underlying [_mood]
   * MutableStateFlow as [mood]/[moodReport].
   */
  suspend fun awaitMoodReport(): StateFlow<String> {
    kotlinx.coroutines.delay(1)
    return mood
  }

  /** ADR-068: object-element variant -- suspend fun returning StateFlow<Cat>. */
  suspend fun awaitPlaymateReport(): StateFlow<Cat> {
    kotlinx.coroutines.delay(1)
    return playmate
  }

  /**
   * ADR-118 / ROADMAP line 54, the StateFlow half: an overload of [awaitMoodReport] whose return is
   * a `StateFlow<T>`.
   *
   * `suspendStateFlowMembers` is a **separate** `flatMap` from `asyncMembers` in
   * `CirClassTranslator`, with its own `${prefix}_${cname}_async` / `Native_${Name}Async`
   * composition, while the Kotlin half is shared (ADR-068). So numbering the plain-suspend
   * projection alone would fix the Kotlin symbol and leave this pair colliding on the C# extern
   * name; both projections have to read the same `overloadSuffix`.
   *
   * It hands back a *fresh* StateFlow carrying the prefixed current mood rather than the shared
   * [_mood] the no-argument overload returns, so the two overloads' awaited values can never be
   * confused with one another.
   */
  suspend fun awaitMoodReport(prefix: String): StateFlow<String> {
    kotlinx.coroutines.delay(1)
    return MutableStateFlow("$prefix${_mood.value}").asStateFlow()
  }

  // --- ADR-068, collection element: a `suspend fun` returning a read-only `StateFlow` of a
  // collection. Its `.Value` and enumeration read through a per-member pair keyed on the awaited
  // flow, because the runtime's shared pair boxes each list unprojected. One component needs no
  // conversion (`Int`), one does (`CatId`, a value class that crosses as its underlying string).

  private val _litterSizes: MutableStateFlow<List<Int>> = MutableStateFlow(listOf(3, 5))

  /** `StateFlow<List<Int>>`: Oreo's and Mylo's litter sizes, no conversion at the seam. */
  suspend fun awaitLitterSizes(): StateFlow<List<Int>> {
    kotlinx.coroutines.delay(1)
    return _litterSizes.asStateFlow()
  }

  /** Deterministic mutation -- records one more litter, observable through the awaited holder. */
  fun recordLitter(size: Int) {
    _litterSizes.value = _litterSizes.value + size
  }

  private val _housemates: MutableStateFlow<List<CatId>> =
    MutableStateFlow(listOf(CatId(catName), CatId("mylo")))

  /** `StateFlow<List<CatId>>`: a value-class component, projected to its underlying per element. */
  suspend fun awaitHousemates(): StateFlow<List<CatId>> {
    kotlinx.coroutines.delay(1)
    return _housemates.asStateFlow()
  }

  /** Deterministic mutation -- a new housemate moves in. */
  fun welcomeHousemate(id: String) {
    _housemates.value = _housemates.value + CatId(id)
  }

  // --- ADR-071: MutableStateFlow<T> declared PUBLICLY -- settable .Value from C#. Contrast with
  // [mood]/[energyLevel] above, which are MutableStateFlow-backed but declared as read-only
  // StateFlow views and must keep their get-only .Value. ---

  /** MutableStateFlow<Int> -- primitive element, no conversion at the write seam. */
  val treatCount: MutableStateFlow<Int> = MutableStateFlow(0)

  /** MutableStateFlow<String> -- needs conversion (string marshalling) at the write seam. */
  val collarColour: MutableStateFlow<String> = MutableStateFlow("red")

  /** MutableStateFlow<Cat> -- object element; crosses as a handle in both directions. */
  val favouriteToy: MutableStateFlow<Cat> = MutableStateFlow(Cat("Mittens"))

  /**
   * MutableStateFlow<T> as a non-suspend function return, sharing [treatCount]'s storage so a
   * write through one surface position is observable through the other.
   */
  fun treatJar(): MutableStateFlow<Int> = treatCount

  /** Kotlin-side read-back: proves a C# write really landed in Kotlin, not just in a C# cache. */
  fun treatsGivenSoFar(): Int = treatCount.value

  val grudge: MutableStateFlow<Grudge> = MutableStateFlow(Grudge("the vet"))

  // --- ADR-071 amendment (nullable element write): a `MutableStateFlow<T?>` whose `.Value` C# can
  // set to null. One reference element, one value element (the has-value pair, so a null is never
  // a zero) and one object element (a null handle). Each starts null. ---

  /** MutableStateFlow<String?> -- nullable reference element; Oreo's collar tag can be removed. */
  val collarTag: MutableStateFlow<String?> = MutableStateFlow(null)

  /** MutableStateFlow<Int?> -- nullable value element; null means "nap not tracked", not 0. */
  val napMinutes: MutableStateFlow<Int?> = MutableStateFlow(null)

  /** MutableStateFlow<Cat?> -- nullable object element; Mylo may have no best friend. */
  val bestFriend: MutableStateFlow<Cat?> = MutableStateFlow(null)

  /** The held function-return twin of [napMinutes], sharing its storage. */
  fun napLog(): MutableStateFlow<Int?> = napMinutes

  /** Kotlin-side read-back: proves a C# write of [bestFriend] (null included) landed in Kotlin. */
  fun bestFriendName(): String? = bestFriend.value?.name

  // --- ADR-071 amendment (nullable member write): a `MutableStateFlow<T>?` absent until
  // [openDiary], settable once present. [closeDiary] makes it absent again, so a C# holder obtained
  // before the close finds the member gone on its next write. ---

  private var _diary: MutableStateFlow<String>? = null

  /** MutableStateFlow<String>? -- nullable MEMBER; null until [openDiary]. */
  val diary: MutableStateFlow<String>? get() = _diary

  /** Deterministic mutation -- brings [diary] into existence with [first]. */
  fun openDiary(first: String) {
    _diary = MutableStateFlow(first)
  }

  /** Deterministic mutation -- makes [diary] absent again. */
  fun closeDiary() {
    _diary = null
  }

  // --- ADR-071 held-route amendment (ADR-068 cross-note): `suspend fun` returning the declared
  // MutableStateFlow, so the awaited holder is settable. Both share storage with a property above
  // and genuinely suspend first. ---

  /** suspend fun returning MutableStateFlow<Int>, sharing [treatCount]'s storage. */
  suspend fun awaitTreatJar(): MutableStateFlow<Int> {
    kotlinx.coroutines.delay(1)
    return treatCount
  }

  /** suspend fun returning MutableStateFlow<Cat>, sharing [favouriteToy]'s storage. */
  suspend fun awaitFavouriteToy(): MutableStateFlow<Cat> {
    kotlinx.coroutines.delay(1)
    return favouriteToy
  }

  // --- ROADMAP line 74 (fromhandle-enum): an ENUM element on the StateFlow and Flow routes. The
  // Kotlin shim retains the enum object itself, and the C# side reads it through
  // `NugetMarshal.FromHandle<Mood>`, which needs a `Factories` entry for the enum. Every value a
  // test asserts has a NON-ZERO ordinal (SLEEPY = 1, GRUMPY = 2), so a read that always answers
  // ordinal 0 cannot pass. Mylo starts sleepy; Oreo's sulk turns everything grumpy. ---

  private val _temper: MutableStateFlow<Mood> = MutableStateFlow(Mood.SLEEPY)

  /** StateFlow<Mood> -- non-nullable enum element. Starts SLEEPY (Mylo napping), ordinal 1. */
  val temper: StateFlow<Mood> = _temper.asStateFlow()

  private val _maybeTemper: MutableStateFlow<Mood?> = MutableStateFlow(null)

  /** StateFlow<Mood?> -- nullable enum element. Null until [sulk]; `T` is `Mood?` in C#. */
  val maybeTemper: StateFlow<Mood?> = _maybeTemper.asStateFlow()

  /** Deterministic mutation -- Oreo sulks: [temper] and [maybeTemper] both become GRUMPY. */
  fun sulk() {
    _temper.value = Mood.GRUMPY
    _maybeTemper.value = Mood.GRUMPY
  }

  /** Flow<Mood?> -- nullable enum element on the cold Flow route, a null between two moods. */
  fun moodSwings(): Flow<Mood?> = flow {
    emit(Mood.SLEEPY)
    emit(null)
    emit(Mood.GRUMPY)
  }

  // --- Regression pins (memo what-question 1): a VALUE CLASS element on the same two routes.
  // ADR-171 registers `CatId` in `Factories` via `NugetUnbox`, so these are expected to pass
  // already; no fixture reached them at runtime before. ---

  private val _tag: MutableStateFlow<CatId> = MutableStateFlow(CatId("oreo-1"))

  /** StateFlow<CatId> -- value-class element. */
  val tag: StateFlow<CatId> = _tag.asStateFlow()

  /** Deterministic mutation -- re-tags the cat. */
  fun retag(id: String) {
    _tag.value = CatId(id)
  }

  /** Flow<CatId?> -- nullable value-class element on the Flow route, a null in the middle. */
  fun tags(): Flow<CatId?> = flow {
    emit(CatId("oreo-1"))
    emit(null)
    emit(CatId("mylo-2"))
  }

  // --- ADR-071 amendment (enum element write): a `MutableStateFlow<Mood>` whose `.Value` C# can
  // set. The write crosses as the enum's ordinal, as the synchronous enum setter does. Starts
  // SLEEPY (ordinal 1) and every asserted write lands on a non-zero ordinal, so a write that
  // always sends 0 cannot pass. ---

  /** MutableStateFlow<Mood> -- enum element, crosses the write seam as its ordinal. */
  val outlook: MutableStateFlow<Mood> = MutableStateFlow(Mood.SLEEPY)

  /** The held function-return twin of [outlook], sharing its storage. */
  fun outlookDial(): MutableStateFlow<Mood> = outlook

  /** Kotlin-side read-back: proves a C# write of [outlook] landed in Kotlin as the real entry. */
  fun currentOutlook(): Mood = outlook.value

  /**
   * MutableStateFlow<CatId> -- a value-class element has no write arm, so this binds the
   * read-only `KotlinStateFlow<CatId>` and the processor names the refused setter.
   */
  val chipId: MutableStateFlow<CatId> = MutableStateFlow(CatId("oreo-chip"))
}

/**
 * ROADMAP line 74 (fromhandle-enum): an ADR-147 exported generic class instantiated at an ENUM.
 * `Box<T>.Value` is generic in C#, so it reads through `NugetMarshal.FromHandle<T>` with
 * `T = Mood`; no per-member read delegate can reach it, only a `Factories` entry for the enum.
 */
fun sulkBox(): Box<Mood> = Box(Mood.GRUMPY)

/**
 * An enum parameter on the generic-return route: the C# enum crosses as its ordinal. A happy cat
 * gets one treat per extra mood step, so each mood boxes a distinct count.
 */
fun treatsFor(mood: Mood): Box<Int> = Box(mood.ordinal + 1)

/** Two enum parameters on the same route: Oreo's mood counts tens, Mylo's counts units. */
fun standoff(oreo: Mood, mylo: Mood): Box<Int> = Box(oreo.ordinal * 10 + mylo.ordinal + 1)

/**
 * The overload of [treatsFor] on the same route: a cat named by a string gets a treat per letter.
 * It takes the planner's overload number, so the two do not collide on one C entry point.
 */
fun treatsFor(name: String): Box<Int> = Box(name.length)

/**
 * Nullable enum and primitive parameters on the same route. An unknown mood counts zero hundreds
 * and unknown naps count -1, so every null is distinguishable from every value, including 0 naps.
 */
fun snackPlan(mood: Mood?, naps: Int?): Box<Int> =
  Box(((mood?.ordinal ?: -1) + 1) * 100 + (naps ?: -1))

/**
 * A nullable type argument on the generic-return route keeps its `?` (`Box<int?>`,
 * `Box<string?>`), so a null item reads back as null rather than `0` or an empty string.
 */
fun unknownNaps(): Box<Int?> = Box(null)

/** The value-carrying twin of [unknownNaps]. */
fun countedNaps(): Box<Int?> = Box(5)

/** A stray with no name yet: the reference-type twin of [unknownNaps]. */
fun unnamedStray(): Box<String?> = Box(null)

/** The value-carrying twin of [unnamedStray]. */
fun namedStray(): Box<String?> = Box("Mylo")

/**
 * ADR-071: an element type whose `equals` throws, so the Kotlin `value` setter itself throws
 * (MutableStateFlow conflates by Any.equals -- StateFlow.kt:332). Forces the ADR-030 errorOut
 * path on the setter export to be genuinely reachable rather than defensive-only.
 *
 * Deliberately a top-level class (not nested inside [CatMoodTracker]) -- nested-class bridging
 * is not an established, tested feature of this generator, and this fixture must not risk an
 * unrelated build failure that masks the real ADR-071 failure signal.
 */
class Grudge(val reason: String) {
  override fun equals(other: Any?): Boolean = error("Cats never forgive: $reason")
  override fun hashCode(): Int = reason.hashCode()
}
