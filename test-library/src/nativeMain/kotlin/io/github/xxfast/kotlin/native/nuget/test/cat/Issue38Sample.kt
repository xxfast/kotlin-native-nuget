package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * Issue #38 (ROADMAP line 63): nullable properties on a sealed subclass, a class nested inside
 * its sealed parent, lose their `?` in the generated `CNameExports.kt`, so
 * `compileKotlinMingwX64` rejects the generated file with `Return type mismatch: expected
 * 'String', actual 'String?'`. The nested path also drops the `errorOut` parameter the top-level
 * path carries.
 *
 * [Issue38State.Loaded] is the shape that blocks the idiomatic
 * `sealed class UiState { data class Success(val error: String? = null) : UiState() }` pattern,
 * since sealed subclasses are almost always nested. It carries one `String?`, one `Int?`, and one
 * non-null `Int`. The non-null scalar is the control: it must keep working while the two
 * nullable ones are what the export drops the `?` from.
 *
 * The report's other nesting shape, a plain class nesting a `data class`, is deliberately NOT here:
 * such a class is never collected at all (every root bucket in `NugetProcessor.kt` filters
 * `parentDeclaration == null`), which is a separate missing capability rather than this bug.
 *
 * ## Sealed-subclass properties on the ADR-062 property plan
 *
 * The legacy ADR-009 route spells its own property marshalling in `SealedClassExports.kt` and
 * `CirClassTranslator.translateSealedClass`, rather than going through the ADR-062 property plan
 * the ordinary-class route uses. Four mechanisms the plan handles are wrong or missing on this
 * route, and the cells below pin one each:
 *
 *  - [Issue38State.Loaded.mood] pins bug 17: a nullable enum. The export emits `.ordinal` on a
 *    `Mood?` receiver, so the generated Kotlin does not compile at all.
 *  - [Issue38State.Loaded.friend] pins bug 18: a nullable exported reference. The generated C#
 *    getter calls its native export twice (`!= IntPtr.Zero ? ... : null`), and every call mints a
 *    fresh `StableRef`, so one is leaked per read.
 *  - [Issue38State.Loaded.flag] and [Issue38State.Loaded.maybeFlag] pin bug 19: the sealed-path
 *    `bool` `DllImport`s carry no `[return: MarshalAs(UnmanagedType.I1)]`, so a one-byte Kotlin
 *    `Boolean` is read as a four-byte Win32 `BOOL` and upper garbage can flip a `false`.
 *  - [Issue38State.Issue38Boom.boom] pins bug 20: every non-List sealed-subclass getter passes
 *    `out _`, discarding the error slot, so a throwing Kotlin getter returns a default to C#
 *    instead of throwing.
 *
 * The throwing getter lives on its own subclass so that reading any other cell stays safe.
 *
 * Two consequences ADR-111 names get a cell each, because the migration hands the sealed route
 * capabilities the legacy route never had:
 *
 *  - [Issue38State.Loaded.note] is the only `var` in the hierarchy. Legacy always emitted
 *    `setter = null`, so a sealed subclass had no writable property at all; the plan gives it one.
 *  - [Issue38State.Loaded.took] and [Issue38State.Loaded.id] are planner-only types. The legacy
 *    type dispatch knew neither, so both dropped with `SKIPPED_UNSUPPORTED_TYPE` even though an
 *    ordinary class binds them: `Duration` as `System.TimeSpan` (ADR-103) and `Uuid` as
 *    `System.Guid` (ADR-106).
 */
sealed class Issue38State {
  data class Loaded(
    val error: String?,
    val retries: Int?,
    val code: Int,
    /** Bug 17: nullable enum. Null on one arm, a real [Mood] on another. */
    val mood: Mood?,
    /** Bug 18: nullable exported reference. Null on one arm, a [Cat] on another. */
    val friend: Cat?,
    /** Bug 19: non-null `Boolean`, false on one arm and true on another. */
    val flag: Boolean,
    /** Bug 19: nullable `Boolean`, with a null, a false and a true arm. */
    val maybeFlag: Boolean?,
    /**
     * ADR-111 consequence: the one `var` in this hierarchy, so the sealed route's new setter is
     * observable. The default keeps every arm of [issue38State] spelling the same starting note.
     */
    var note: String = "n",
    /**
     * ADR-111 consequence: a planner-only type the legacy dispatch skipped. Surfaces as
     * `System.TimeSpan` per ADR-103.
     */
    val took: Duration = 1500.milliseconds,
    /**
     * ADR-111 consequence: a planner-only type the legacy dispatch skipped. Surfaces as
     * `System.Guid` per ADR-106. Fixed, not random, so the C# side can assert the exact value.
     */
    val id: Uuid = Uuid.parse("feedface-0a1e-4c0a-b0b0-0ff1ceb0bade"),
  ) : Issue38State()

  data object Idle : Issue38State()

  /**
   * Bug 20: a getter that always throws, so the sealed getter's discarded `out _` error slot is
   * observable. Kept off [Loaded] so every other cell can still be read.
   */
  class Issue38Boom : Issue38State() {
    val boom: String get() = throw IllegalStateException("boom")
  }
}

/**
 * Sealed subclasses only get an `internal` C# constructor (they arrive through `FromHandle`), so
 * the C# consumer needs a factory to get hold of one, the same shape `Observation.kt` uses with
 * `openBox`/`peekBox`. One `Int` discriminator drives all cases so this fixture adds no
 * nullable top-level parameters of its own.
 */
fun issue38State(state: Int): Issue38State = when (state) {
  0 -> Issue38State.Loaded(
    error = "Oreo knocked the water bowl over",
    retries = 3,
    code = 7,
    mood = Mood.GRUMPY,
    friend = Cat("Mylo"),
    flag = true,
    maybeFlag = true,
  )

  1 -> Issue38State.Loaded(
    error = null,
    retries = null,
    code = 7,
    mood = null,
    friend = null,
    flag = false,
    maybeFlag = null,
  )

  3 -> Issue38State.Loaded(
    error = "Mylo stared at the empty bowl",
    retries = 0,
    code = 7,
    mood = Mood.SLEEPY,
    friend = Cat("Oreo"),
    flag = false,
    maybeFlag = false,
  )

  4 -> Issue38State.Issue38Boom()

  else -> Issue38State.Idle
}
