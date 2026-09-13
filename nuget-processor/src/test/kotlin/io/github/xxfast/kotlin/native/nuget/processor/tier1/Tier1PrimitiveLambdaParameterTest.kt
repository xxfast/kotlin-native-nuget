package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-036 · the **primitive payload** row of the marshalling table, on the per-call lambda
 * parameter route (`fun onTick(listener: (Int) -> Unit)`).
 *
 * The table has said since ADR-036 that a primitive crosses by value in both directions, and the
 * interface-bridge route implements exactly that. The per-call route never did. It boxed the
 * payload into a `StableRef` on the Kotlin side and read it back through
 * `NugetMarshal.FromHandle<Int>(arg0Ptr)` on the C# side, against a delegate whose parameter it
 * declared `IntPtr` for every argument regardless of type. Both halves are wrong, in different
 * ways, which is why one cell cannot cover it:
 *
 *  - **Kotlin does not compile.** `cfuncArgTypes` spells `COpaquePointer?` for every argument
 *    while the wrapper body binds a `Byte` for a `Boolean` payload, so `(Boolean) -> Unit` fails
 *    the generated file's own compile with `Argument type mismatch: actual type is 'Byte'`. That
 *    makes [compiledClean] the load-bearing assertion here, not a formality.
 *  - **C# does not compile.** `Int arg0 = NugetMarshal.FromHandle<Int>(arg0Ptr);` names a type
 *    that does not exist in C# (CS0246), and the lambda cannot convert to the delegate anyway
 *    (CS1661/CS1678, `nint` against `int`), which is the error a consumer actually sees first
 *    because a lambda's shape is checked before its body.
 *
 * One cell per primitive family, because a fix trimmed to `Int` would go green while the other
 * two stayed broken:
 *
 *  - `onTick` is the **integer** cell, the payload `NugetIntVoidCallback` is already named after,
 *  - `onBeat` is the **`Boolean`** cell, the one primitive that is not its own wire type: it
 *    crosses as a `byte` and has to be widened on the C# side, so it catches a fix that forwards
 *    the argument verbatim,
 *  - `onVelocity` is the **signed 8-bit** cell (`Byte`), declared straight after `onBeat`. It
 *    shares `Boolean`'s one-byte wire but not its C# type (`sbyte` against `byte`), and the
 *    delegate-name suffix spelled `Byte` for both, so under first-wins registration the pair got
 *    one declaration and the loser rendered a lambda that cannot convert to it (ADR-036
 *    amendment, 2026-09-13). Two names, two shapes, one declaration each,
 *  - `onTempo` is the **floating point** cell, a different register class from the integers, so
 *    it catches a by-value fix that only ever passes integer-shaped payloads.
 *
 * [MoodRing] is not decoration. Its `addMoodListener`/`removeMoodListener` pair is the ADR-037
 * **stored**-callback route, which registers `NugetIntVoidCallback(int arg0Ord, IntPtr _)` for the
 * enum ordinal. Delegate registration is first-wins (`tracker.callbackDelegates.none { ... }`), so
 * the two routes share one delegate declaration and one thunk. If the per-call route's `Int`
 * payload did not land on the *same* `(int, IntPtr)` shape, whichever route lost the race would
 * emit a lambda that cannot convert to the delegate the other registered. The collision is the
 * point: the shapes have to agree, not merely each be internally consistent.
 *
 * The real fixture is `test-library/.../metronome/Metronome.kt`, which `scripts/verify.sh`
 * compiles on both sides and calls at runtime. This cell asserts the Kotlin half by compiling it
 * and the C# half structurally (ADR-060 rejected compiling C# in Tier 1).
 *
 * Oreo keeps the count. Mylo just follows the tempo.
 */
class Tier1PrimitiveLambdaParameterTest {

  private val source: String = """
    package tier1.metronome

    enum class Mood { CALM, EAGER }

    class Metronome(private val beats: Int) {
      fun onTick(listener: (Int) -> Unit) = repeat(beats) { listener(it + 1) }
      fun onBeat(listener: (Boolean) -> Unit) = repeat(beats) { listener(it % 2 == 0) }
      fun onVelocity(listener: (Byte) -> Unit) = repeat(beats) { listener((it * 40 - 100).toByte()) }
      fun onTempo(listener: (Double) -> Unit) = repeat(beats) { listener(60.0 + it * 0.5) }
    }

    class MoodRing {
      private val listeners = mutableListOf<(Mood) -> Unit>()
      fun addMoodListener(listener: (Mood) -> Unit) { listeners += listener }
      fun removeMoodListener(listener: (Mood) -> Unit) { listeners -= listener }
    }
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(source)

  /**
   * The Kotlin half, proved by compiling it. Red today on the `Boolean` cell: the `CFunction`
   * type says `COpaquePointer?` and the wrapper hands it a `Byte`.
   */
  @Test
  fun `the generated Kotlin exports compile`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected the generated primitive-payload lambda exports to compile; " +
          "got: ${result.compileErrors}",
    )
  }

  /** Each payload's `CFunction` signature is the primitive itself, not a boxed handle. */
  @Test
  fun `the Kotlin function pointer takes the payload by value`() {
    val result = run()

    listOf("Int", "Byte", "Double").forEach { wire ->
      assertTrue(
        result.generated.contains("CFunction<($wire, COpaquePointer) -> Unit>"),
        "expected a by-value `CFunction<($wire, COpaquePointer) -> Unit>` reinterpret; " +
            "got: ${lambdaLines(result.generated)}",
      )
    }
    assertFalse(
      result.generated.contains("CFunction<(COpaquePointer?, COpaquePointer) -> Unit>"),
      "no primitive payload may still cross as a boxed handle; " +
          "got: ${lambdaLines(result.generated)}",
    )
  }

  /**
   * A by-value primitive mints no `StableRef`, so the retain/release pair around the invocation
   * has to be gone. Left in place it would leak a handle per invocation (and the release would be
   * called on a value that was never retained).
   */
  @Test
  fun `a by-value payload is neither retained nor released`() {
    val result = run()

    assertFalse(
      result.generated.contains("NugetHandles.retain(it0)"),
      "a primitive payload must be passed by value, not boxed into a StableRef",
    )
    assertFalse(
      result.generated.contains("NugetHandles.release(arg0Ref"),
      "nothing was retained, so nothing may be released",
    )
  }

  /** The C# delegate declarations: one per shape, each taking the primitive itself. */
  @Test
  fun `the C sharp delegates declare typed primitive parameters`() {
    val result = run()

    val expected: Map<String, String> = mapOf(
      "NugetIntVoidCallback" to "int",
      "NugetBoolVoidCallback" to "byte",
      "NugetByteVoidCallback" to "sbyte",
      "NugetDoubleVoidCallback" to "double",
    )
    expected.forEach { (name, csType) ->
      val declaration: String = delegateDeclaration(result, name)
      assertTrue(
        Regex("""$name\($csType \w+, IntPtr \w+\);""").containsMatchIn(declaration),
        "expected `$name` to take a `$csType` by value; got: $declaration",
      )
    }
  }

  /**
   * The collision. Both routes name `NugetIntVoidCallback`, registration is first-wins, so there
   * must be exactly one declaration of it and its shape must serve both.
   */
  @Test
  fun `the per-call and stored routes share one NugetIntVoidCallback shape`() {
    val result = run()

    val declarations: List<String> = result.generatedCSharp.lines()
      .filter { it.contains("internal delegate") && it.contains("NugetIntVoidCallback(") }
    assertTrue(
      declarations.size == 1,
      "expected exactly one NugetIntVoidCallback declaration; got: $declarations",
    )
    assertTrue(
      declarations.single().contains("(int "),
      "the stored enum-ordinal route registers `(int, IntPtr)`; the per-call Int payload must " +
          "land on the same shape; got: ${declarations.single()}",
    )
  }

  /**
   * The reported collision. `Boolean` and Kotlin `Byte` both spelled the suffix `Byte`, so the
   * two shapes landed on one `NugetByteVoidCallback` declaration and the second registration
   * reused the first's parameter list. They are separate wires (`byte` widened to `bool`, against
   * `sbyte` passed through) and so must be separate delegates, each declared exactly once.
   */
  @Test
  fun `the Boolean and Byte payloads get two distinct delegates`() {
    val result = run()

    val declarations: List<String> = result.generatedCSharp.lines()
      .filter { it.contains("internal delegate") }

    listOf("NugetBoolVoidCallback" to "byte", "NugetByteVoidCallback" to "sbyte")
      .forEach { (name, csType) ->
        val matching: List<String> = declarations.filter { it.contains("$name(") }
        assertTrue(
          matching.size == 1,
          "expected exactly one declaration of `$name`; got: $matching",
        )
        assertTrue(
          Regex("""$name\($csType \w+, IntPtr \w+\);""").containsMatchIn(matching.single()),
          "expected `$name` to take a `$csType` by value; got: ${matching.single()}",
        )
      }
  }

  /** The C# thunk body reads the payload straight off the typed parameter. */
  @Test
  fun `the C sharp callback body reads the payload from the delegate parameter`() {
    val result = run()

    val onTick: String = callbackLine(result, "OnTick")
    assertTrue(
      onTick.contains("(int arg0, IntPtr userData) =>"),
      "expected the Int payload lambda to take `int arg0`; got: $onTick",
    )
    assertTrue(
      onTick.contains("listener(arg0);"),
      "expected the Int payload to be handed to the caller's lambda unchanged; got: $onTick",
    )

    val onBeat: String = callbackLine(result, "OnBeat")
    assertTrue(
      onBeat.contains("(byte arg0Byte, IntPtr userData) =>"),
      "expected the Boolean payload lambda to take `byte arg0Byte`; got: $onBeat",
    )
    assertTrue(
      onBeat.contains("bool arg0 = arg0Byte != 0;"),
      "expected the Boolean payload to be widened from its byte wire type; got: $onBeat",
    )

    val onTempo: String = callbackLine(result, "OnTempo")
    assertTrue(
      onTempo.contains("(double arg0, IntPtr userData) =>"),
      "expected the Double payload lambda to take `double arg0`; got: $onTempo",
    )
  }

  /**
   * The defect's own line. `FromHandle<Int>` / `<Boolean>` / `<Double>` name Kotlin types that do
   * not exist in C# (CS0246), and no by-value payload has a handle to read in the first place.
   */
  @Test
  fun `no primitive payload is unmarshalled through FromHandle`() {
    val result = run()

    val handleTypes: List<String> = listOf("Int", "Boolean", "Double", "int", "double", "bool")
    val offending: List<String> = result.generatedCSharp.lines()
      .filter { line -> handleTypes.any { line.contains("FromHandle<$it>") } }
    assertTrue(
      offending.isEmpty(),
      "a by-value primitive payload has no handle to unmarshal; got: $offending",
    )
  }

  /** The public C# surface was already right and must stay right. */
  @Test
  fun `the public method still binds Action of the C sharp primitive`() {
    val result = run()

    listOf(
      "Action<int> listener",
      "Action<bool> listener",
      "Action<sbyte> listener",
      "Action<double> listener",
    )
      .forEach { signature ->
        assertTrue(
          result.generatedCSharp.contains(signature),
          "control: the public signature is unchanged by this fix; expected `$signature`",
        )
      }
  }

  private fun lambdaLines(generated: String): List<String> =
    generated.lines().filter { it.contains("reinterpret<CFunction<") }

  private fun delegateDeclaration(result: Tier1Result, name: String): String =
    result.generatedCSharp.lines()
      .firstOrNull { it.contains("internal delegate") && it.contains("$name(") }
      ?: "<no declaration of $name>"

  /** The delegate declaration and its body, which [renderCallbackMethod] spreads over lines. */
  private fun callbackLine(result: Tier1Result, method: String): String {
    val lines: List<String> = result.generatedCSharp.lines()
    val start: Int = lines.indexOfFirst { it.contains("public void $method(") }
    if (start < 0) return "<no $method method>"
    return lines.drop(start).take(8).joinToString("\n") { it.trim() }
  }
}
