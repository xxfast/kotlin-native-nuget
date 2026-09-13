package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #126 / ADR-122. A handle-typed parameter (a sealed arm, a sealed base, an ordinary class,
 * an `object`) on a `Flow`/`StateFlow`-returning or `suspend` member renders as raw `IntPtr` in
 * C#, because the legacy routes never ask the classifier about a *non-generic* parameter and fall
 * through to `mapParamType`'s 13-entry primitive table:
 *
 * ```csharp
 * public KotlinStateFlow<string> Watch(IntPtr observation) { ... }
 * public KotlinStateFlow<string> Watch(IntPtr observation) { ... }
 * ```
 * ```
 * Interop.cs(25614,40): error CS0111: Type 'ObservationRadio' already defines a member called
 * 'Watch' with the same parameter types
 * ```
 *
 * The collision is the symptom; `IntPtr` is the defect. A single non-overloaded `Watch(IntPtr)`
 * is equally broken, because the only source of that pointer is an `internal` handle constructor.
 * So the cells below assert the *mapped type*, not merely that the signatures differ.
 *
 * The Kotlin half is separately wrong, and Tier 1 is the cheapest place to see both halves at
 * once: the export declares the parameter with its real Kotlin type (`observation:
 * Observation.Alive`) while C# declares `IntPtr`, in the same generated pair.
 *
 * **Four routes, asserted separately, because they are hand-written copies of the same mistake
 * and they have drifted before:** `_collect` and `_value` (`ClassExports.addFlowParameters`),
 * `_async` on a class method, and `_async` at top level (`addLegacySuspendParameters`).
 *
 * **Spelling variety**, because a fix that hardcodes one of them passes half the cells: a
 * **nested** arm renders `global::Ns.Observation.Alive`, a **sibling** arm renders
 * `global::Ns.Lamp` at namespace level, and the sealed **base** renders `global::Ns.Observation`.
 *
 * **The refusal arm** (ADR-122 alternative 1's second half): every remaining non-scalar
 * non-generic parameter, an enum here, skips the member *named* rather than silently rendering a
 * public `IntPtr` nobody can call.
 *
 * Oreo (black with the white bib) reports in alive. Mylo (brown and creamy) is only ever a rumour.
 */
class Tier1LegacyRouteHandleParameterTest {

  private val fixture: String = """
    package tier1.watchtower

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.flowOf

    // Nested arms: C# spells these `Observation.Alive` inside the namespace.
    sealed class Observation {
      data class Alive(val name: String) : Observation()
      data class Dead(val cause: String) : Observation()
    }

    // Sibling arm: declared beside its base, so C# spells it at namespace level, not nested.
    sealed class Beacon
    data class Lamp(val text: String) : Beacon()

    class Cat(val name: String)

    enum class Mood { CALM, CROSS }

    class Radio {
      // _collect and _value, the issue's exact shape, with a NESTED sealed arm.
      fun watch(observation: Observation.Alive): StateFlow<String> =
        MutableStateFlow("alive:" + observation.name)

      // The overload that collapses onto the one above today (CS0111).
      fun watch(observation: Observation.Dead): StateFlow<String> =
        MutableStateFlow("dead:" + observation.cause)

      // A SIBLING arm, which is spelled at namespace level rather than nested.
      fun watch(lamp: Lamp): StateFlow<String> = MutableStateFlow("lamp:" + lamp.text)

      // The sealed BASE itself: `sealedAsHandle()` on the legacy route.
      fun watch(observation: Observation): StateFlow<String> = MutableStateFlow("base")

      // An ordinary exported class, so nothing here is sealed-specific.
      fun watch(cat: Cat): StateFlow<String> = MutableStateFlow("cat:" + cat.name)

      // _collect alone, plain Flow return.
      fun stream(cat: Cat): Flow<String> = flowOf(cat.name)

      // _async on a class method: the second hand-written copy of the same mistake.
      suspend fun log(observation: Observation.Alive): String = "logged:" + observation.name

      // A collection parameter and a handle parameter on one member: prelude ordering, and a
      // native argument spelled per parameter (one built and disposed, one borrowed).
      fun tally(kinds: List<String>, observation: Observation.Alive): StateFlow<Int> =
        MutableStateFlow(kinds.size)

      // Refusal arm: an enum parameter is the same defect and has no wire shape here yet.
      fun moods(mood: Mood): Flow<String> = flowOf(mood.name)

      // Refusal arm: a NULLABLE handle. The type binds, the nullability does not (ADR-114's
      // deferral), so it is refused rather than silently crossing a null as IntPtr.Zero.
      fun ghost(observation: Observation?): Flow<String> = flowOf("ghost")

      // Control: a scalar parameter must render exactly as it does today.
      fun label(text: String): StateFlow<String> = MutableStateFlow(text)

      // Control: a NULLABLE scalar keeps the non-null spelling both halves already ship for it,
      // rather than being swept up by the new refusal arm (ADR-114's nullable deferral, kept).
      fun maybe(text: String?): StateFlow<String> = MutableStateFlow(text ?: "")
    }

    // The top-level suspend route, a third parameter builder with the same gap.
    suspend fun broadcast(cat: Cat): String = "broadcast:" + cat.name
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    fixture,
    fileName = "Watchtower.kt",
    processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    // Without coroutines on the KSP libraries path `Flow` resolves to `<ERROR TYPE: Flow>` and
    // none of these members would take the legacy routes at all.
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  /**
   * The headline cell, and the whole of issue #126: five overloads that differ only in a handle
   * parameter must be five distinct C# signatures, each spelled as the type the *return* position
   * on the same class already spells. Pre-fix every one of them is `Watch(IntPtr observation)`.
   */
  @Test
  fun `a handle parameter is spelled as its mapped type in the public C# signature`() {
    val result = run()

    val missing: List<String> = listOf(
      // Nested arms, two overloads, the CS0111 pair.
      "public KotlinStateFlow<string> Watch(global::Interop.Watchtower.Observation.Alive " +
          "observation)",
      "public KotlinStateFlow<string> Watch(global::Interop.Watchtower.Observation.Dead " +
          "observation)",
      // Sibling arm: namespace level, not nested under its base.
      "public KotlinStateFlow<string> Watch(global::Interop.Watchtower.Lamp lamp)",
      // The sealed base.
      "public KotlinStateFlow<string> Watch(global::Interop.Watchtower.Observation observation)",
      // An ordinary class, on both the StateFlow and the plain Flow route.
      "public KotlinStateFlow<string> Watch(global::Interop.Watchtower.Cat cat)",
      "public KotlinFlow<string> Stream(global::Interop.Watchtower.Cat cat)",
      // _async, class method and top level.
      "public Task<string> LogAsync(global::Interop.Watchtower.Observation.Alive observation",
      "public static Task<string> BroadcastAsync(global::Interop.Watchtower.Cat cat",
      // A collection and a handle on one member.
      "public KotlinStateFlow<int> Tally(IReadOnlyList<string> kinds, " +
          "global::Interop.Watchtower.Observation.Alive observation)",
    ).filterNot(result.generatedCSharp::contains)

    assertTrue(
      missing.isEmpty(),
      "expected every handle parameter to take its mapped C# type rather than IntPtr; missing: " +
          "$missing; got: ${csharpSignatures(result)}",
    )
  }

  /**
   * The defect stated directly, rather than through the collision it causes. A fix that only made
   * the signatures *distinct* (numbering the overloads) would pass the cell above's arity checks
   * and still ship four uncallable methods; this one fails it.
   */
  @Test
  fun `no member takes a raw IntPtr on the legacy routes`() {
    val result = run()

    // This fixture's own members only. The emitted runtime support surface legitimately takes
    // IntPtr (`NugetMarshal.FromHandle<T>`, `Dispose`, `ReadList`), and the general invariant over
    // the whole file, allow-list and all, is `Tier1StructuralInteropCsTest`.
    val members: List<String> =
      listOf("Watch(", "Stream(", "LogAsync(", "Tally(", "BroadcastAsync(", "Label(", "Moods(")
    val offenders: List<String> = result.generatedCSharp.lines()
      .map(String::trim)
      .filter { line -> line.startsWith("public ") && line.contains("(IntPtr ") }
      .filter { line -> members.any(line::contains) }

    assertFalse(
      result.generatedCSharp.contains("Watch(IntPtr"),
      "expected no Watch(IntPtr ...): the sealed arms only have internal handle constructors, so " +
          "a caller has no way to produce that pointer; got: ${csharpLinesFor(result, "Watch")}",
    )
    assertTrue(
      offenders.isEmpty(),
      "expected no public member on this fixture to expose IntPtr at all; got: $offenders",
    )
  }

  /**
   * The C# marshalling half. The `DllImport` stays `IntPtr`, and the argument is the wrapper's own
   * `internal IntPtr _handle`, which is exactly what the ordinary ADR-062 plan route passes
   * (`ForwardCirPlanProjection`) and what the sealed base and every wrapper class already declare.
   */
  @Test
  fun `a handle parameter is passed as the wrappers own handle field`() {
    val result = run()

    val missing: List<String> = listOf(
      // _collect: inside the collect delegate.
      "Native_WatchCollect(_handle, GetOrCreateScope(), observation._handle,",
      // _value: the re-read lambda.
      "Native_WatchValue(_handle, observation._handle)",
      // The sibling arm, under its own overload number.
      "Native_Watch_3Value(_handle, lamp._handle)",
      // _async.
      "Native_LogAsync(_handle, GetOrCreateScope(), observation._handle,",
      // The mixed member: the collection keeps its call-scoped handle, the object is borrowed.
      "Native_TallyValue(_handle, kindsHandle, observation._handle)",
    ).filterNot(result.generatedCSharp::contains)

    assertTrue(
      missing.isEmpty(),
      "expected the native call to pass `x._handle` for a handle parameter; missing: $missing",
    )
  }

  /**
   * A borrowed handle must NOT get the collection route's create-then-`finally`-dispose block: the
   * C# wrapper owns that handle and the call site only reads it. Disposing it here would free the
   * caller's own object out from under it.
   */
  @Test
  fun `a handle parameter is never wrapped in a create and dispose block`() {
    val result = run()

    val watch: String = csharpMember(result, "Native_WatchValue(_handle")
    assertFalse(
      watch.contains("NugetMarshal.Dispose("),
      "expected no dispose around a borrowed handle argument: the C# wrapper owns it; got: $watch",
    )
    assertFalse(
      result.generatedCSharp.contains("NugetMarshal.Dispose(observationHandle)"),
      "expected no call-scoped handle to be minted for a handle parameter at all",
    )
  }

  /**
   * The Kotlin half, and the reason the ABI slot has to move. Today the export declares the real
   * Kotlin type, which `@CName` compiles into a `kref_<T>` struct wrapping a pinned pointer while
   * C# declares `IntPtr` (ADR-114 "The trap"). The slot must be a plain `COpaquePointer`.
   */
  @Test
  fun `a handle parameter crosses as an opaque pointer on every legacy route`() {
    val result = run()

    val wrong: List<String> = listOf(
      "radio_watch_collect" to "observation",
      "radio_watch_value" to "observation",
      "radio_watch_3_collect" to "lamp",
      "radio_watch_4_value" to "observation",
      "radio_watch_5_value" to "cat",
      "radio_stream_collect" to "cat",
      "radio_log_async" to "observation",
      "radio_tally_value" to "observation",
      "broadcast_async" to "cat",
    ).filterNot { (export, param) ->
      exportSignature(result, export).contains("$param: COpaquePointer")
    }.map { (export, param) -> "$export($param)" }

    assertTrue(
      wrong.isEmpty(),
      "expected every handle parameter to be declared COpaquePointer; these were not: $wrong",
    )
  }

  /**
   * The ownership claim, from the generator's side. The dereference is a prelude local emitted
   * **before** the launch -- `collectForCSharp(` since ADR-128 moved the launch shape into the
   * runtime helper -- so the coroutine captures a strong Kotlin reference and a consumer
   * disposing its C# wrapper mid-flow cannot invalidate what the coroutine is still reading.
   * Inlining `observation.asStableRef<Q>().get()` at the call site would evaluate it inside
   * `launch`, i.e. after the export returned: ADR-114's own lifetime hazard, from the other side.
   */
  @Test
  fun `a handle parameter is dereferenced before the coroutine launches`() {
    val result = run()

    val body: String = exportBody(result, "radio_watch_collect")
    val deref: Int = body.indexOf(
      "val observationArg = observation.asStableRef<tier1.watchtower.Observation.Alive>().get()"
    )
    val launch: Int = body.indexOf("collectForCSharp(")

    assertTrue(
      deref >= 0,
      "expected a prelude local dereferencing the handle parameter; got: $body",
    )
    assertTrue(
      launch >= 0 && deref < launch,
      "expected the dereference BEFORE collectForCSharp( (ADR-122: the coroutine must capture a " +
          "strong Kotlin reference, not the raw handle); got: $body",
    )
    assertTrue(
      body.contains("obj.watch(observationArg)"),
      "expected the member to be called with the dereferenced local; got: $body",
    )
  }

  /**
   * The mixed member. A collection parameter is copied eagerly (ADR-114) and a handle parameter is
   * dereferenced eagerly (ADR-122), both before `launch`, in declaration order, and the call site
   * reads both locals. A fix that emitted one prelude and forgot the other still compiles.
   */
  @Test
  fun `a collection and a handle parameter on one member both get preludes`() {
    val result = run()

    val body: String = exportBody(result, "radio_tally_collect")

    assertTrue(
      body.contains("obj.tally(kindsArg, observationArg)"),
      "expected both parameters to be read from their eagerly-bound locals; got: $body",
    )
  }

  /**
   * The refusal arm ADR-122 adds: `Plain` narrows from "no type arguments" to "a scalar", so every
   * other non-generic shape skips the member *named* instead of silently rendering a public
   * `IntPtr`. An enum is the cheapest instance of the family (`Instant`, `Duration`, `Uuid`, a
   * value class, an interface and an unexported class are the rest).
   */
  @Test
  fun `a non-scalar non-handle parameter on a legacy route skips the member named`() {
    val result = run()

    assertFalse(
      result.generatedCSharp.contains("Moods("),
      "expected no Moods member: an enum parameter has no wire shape on this route yet; got: " +
          "${csharpLinesFor(result, "Moods")}",
    )
    assertFalse(
      result.generated.contains("radio_moods_collect"),
      "expected no _moods_collect export either; got: " +
          "${result.generated.lines().filter { it.contains("moods") }.map(String::trim)}",
    )

    val diagnostic: String? = result.kspWarnings.firstOrNull {
      it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name}]") &&
          it.contains("moods")
    }
    assertTrue(
      diagnostic != null,
      "expected a SKIPPED_UNSUPPORTED_INPUT naming Radio.moods rather than a silent vanish or a " +
          "public IntPtr; kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      diagnostic.contains("mood: Mood"),
      "expected the diagnostic to name the offending parameter and its type, so the author knows " +
          "which one to change; got: $diagnostic",
    )
  }

  /**
   * The other half of the refusal arm: a type that *would* bind, refused for its nullability
   * alone. The diagnostic has to keep the `?`, or the author reads it as "no objects here" and
   * goes looking for the wrong defect.
   */
  @Test
  fun `a nullable handle parameter is refused and the diagnostic keeps the question mark`() {
    val result = run()

    assertFalse(
      result.generatedCSharp.contains("Ghost("),
      "expected no Ghost member: nullability is not threaded on these routes; got: " +
          "${csharpLinesFor(result, "Ghost")}",
    )

    val diagnostic: String? = result.kspWarnings.firstOrNull {
      it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name}]") &&
          it.contains("ghost")
    }
    assertTrue(
      diagnostic != null && diagnostic.contains("observation: Observation?"),
      "expected the refusal to name the parameter WITH its `?`, since the type itself binds; " +
          "got: $diagnostic",
    )
  }

  /**
   * Control. Narrowing `Plain` to scalars must cost the scalar routes nothing: a `String`
   * parameter on the same class keeps its exact shipped spelling on both halves.
   */
  @Test
  fun `a scalar parameter on the same class is untouched`() {
    val result = run()

    assertTrue(
      result.generatedCSharp.contains("public KotlinStateFlow<string> Label(string text)"),
      "control: a String parameter must render exactly as today; got: " +
          "${csharpLinesFor(result, "Label")}",
    )
    assertTrue(
      exportSignature(result, "radio_label_value").contains("text: String"),
      "control: the Kotlin slot for a String parameter is unchanged; got: " +
          exportSignature(result, "radio_label_value"),
    )
  }

  /**
   * Control, and ADR-122's one deliberate hole. Narrowing `Plain` must not sweep up a nullable
   * *scalar*: both halves already ship the non-null spelling for it, so refusing it would drop a
   * member that binds today for the sake of a nullability rule the legacy routes do not thread
   * anywhere yet (ADR-114's deferral). A nullable *object* is refused, which is the same rule.
   */
  @Test
  fun `a nullable scalar parameter keeps its shipped spelling`() {
    val result = run()

    assertTrue(
      result.generatedCSharp.contains("public KotlinStateFlow<string> Maybe(string text)"),
      "control: a nullable scalar keeps the shipped non-null spelling; got: " +
          "${csharpLinesFor(result, "Maybe")}",
    )
  }

  /** The generated Kotlin still has to compile: the ABI slot moved on five export builders. */
  @Test
  fun `a handle parameter on a legacy route still generates compiling Kotlin`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected clean generated Kotlin for handle parameters on the flow and suspend routes; " +
          "got: ${result.compileErrors}",
    )
  }

  /** The declared parameter list of the `@CName`-named export, up to its return type. */
  private fun exportSignature(result: Tier1Result, export: String): String =
    exportFrom(result, export).substringBefore("): ")

  /** The whole `@CName`-named export, up to the next one. */
  private fun exportBody(result: Tier1Result, export: String): String =
    exportFrom(result, export)

  private fun exportFrom(result: Tier1Result, export: String): String {
    val generated: String = result.generated
    val start: Int = generated.indexOf("@CName(\"$export\")")
    require(start >= 0) {
      "no @CName(\"$export\") in the generated Kotlin; exports present: " +
          generated.lines().filter { it.contains("@CName(") }.map(String::trim)
    }
    val rest: String = generated.substring(start + 1)
    val next: Int = rest.indexOf("@CName(\"")
    return if (next >= 0) rest.substring(0, next) else rest
  }

  /** The generated C# from the line containing [marker] back to its enclosing member header. */
  private fun csharpMember(result: Tier1Result, marker: String): String {
    val lines: List<String> = result.generatedCSharp.lines()
    val index: Int = lines.indexOfFirst { it.contains(marker) }
    require(index >= 0) { "no generated C# line containing \"$marker\"" }
    val start: Int = lines.take(index).indexOfLast { it.trim().startsWith("public ") }
    return lines.subList(if (start >= 0) start else index, index + 1).joinToString("\n")
  }

  private fun csharpLinesFor(result: Tier1Result, member: String): List<String> =
    result.generatedCSharp.lines().filter { it.contains(member) }.map(String::trim)

  private fun csharpSignatures(result: Tier1Result): List<String> =
    result.generatedCSharp.lines()
      .map(String::trim)
      .filter { it.startsWith("public ") && it.contains("(") }
}
