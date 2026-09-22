package io.github.xxfast.kotlin.native.nuget.processor.tier1

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ADR-160: a per-call lambda-parameter member on the ADR-062 forward callable plan. The two returns
 * are kept apart, as they are everywhere else in this item: the METHOD (outer) return is the
 * member's own result, the LAMBDA (inner) return is what the C# lambda hands back.
 *
 * These are text pins on both halves of ONE plan, which is the point of the migration: the outer
 * return reuses the plan's existing return matrix, so the cells below assert the extern's scalar
 * return type rather than an `IntPtr` and a `new Int(...)` reconstruction.
 */
class Tier1CallbackParameterPlanTest {

  private val source: String = """
    package io.pkg

    class Metronome(private val beats: Int) {
      fun countTicks(listener: (Int) -> Unit): Int {
        repeat(beats) { listener(it + 1) }
        return beats
      }

      fun sumWeights(weigh: (Int) -> Int): Int = (1..beats).sumOf { weigh(it) }

      fun countAbove(min: Int, listener: (Int) -> Unit): Int {
        var fired = 0
        for (beat in 1..beats) if (beat > min) { listener(beat); fired++ }
        return fired
      }

      fun joinTicks(nameOf: (Int) -> String): String = (1..beats).joinToString("-") { nameOf(it) }
    }
  """.trimIndent()

  @Test
  fun `a scalar method return survives a per-call lambda parameter on both halves`() {
    val result: Tier1Result = Tier1Harness.run(source)
    assertTrue(result.compileErrors.isEmpty(), "compile errors: ${result.compileErrors}")

    val interop: String = result.generatedCSharp
    // The outer axis: the extern returns the member's own scalar, not a handle.
    assertTrue(
      interop.contains("private static extern int Native_CountTicks("),
      "expected a scalar extern return in:\n$interop",
    )
    assertFalse(
      interop.contains("return new Int("),
      "the Kotlin simple name must not be spelled as a C# type",
    )
    // The inner axis: a scalar lambda return crosses by value, in both halves.
    assertTrue(
      interop.contains("internal delegate int NugetIntIntCallback(int a0, IntPtr ctx);"),
      "expected a by-value Func<int,int> delegate in:\n$interop",
    )
    assertTrue(
      result.generated.contains("CFunction<(Int, COpaquePointer, COpaquePointer?) -> Int>"),
      "expected a by-value CFunction signature in:\n${result.generated}",
    )
    assertFalse(
      result.generated.contains("asStableRef<String>().get()") &&
          !result.generated.contains("joinTicks"),
      "a scalar lambda return must not read a String box",
    )
    // The public surface: an Action for a Unit lambda, a Func otherwise.
    assertTrue(
      interop.contains("public int CountTicks(Action<int> listener)"),
      "expected an Action<int> public parameter in:\n$interop",
    )
    assertTrue(
      interop.contains("public int SumWeights(Func<int, int> weigh)"),
      "expected a Func<int, int> public parameter in:\n$interop",
    )
    // Mixed parameters come free off the plan: today's route dropped `min` from both halves.
    assertTrue(
      interop.contains("public int CountAbove(int min, Action<int> listener)"),
      "expected the non-lambda parameter to survive in:\n$interop",
    )
    // ADR-102 is not regressed: the address is a link-time thunk pointer, never
    // Marshal.GetFunctionPointerForDelegate.
    assertTrue(
      interop.contains("NugetThunks.NugetIntVoidCallbackPtr"),
      "expected the AOT-safe thunk address in:\n$interop",
    )
    assertFalse(
      interop.contains("GetFunctionPointerForDelegate"),
      "ADR-102: no runtime function-pointer minting",
    )
    // Ownership: the plan mints and releases only through NugetHandles.
    assertFalse(
      result.generated.contains("StableRef.create("),
      "handles must be minted through NugetHandles.retain",
    )
  }

  /**
   * ADR-160 step 4, the original symptom's own cell. A member the plan declines AND the
   * hand-written route cannot marshal used to be a **build failure**: the C# half declared the
   * extern as `IntPtr` while the Kotlin half returned the scalar, and `ForwardAbiContract` threw
   * `Forward ABI mismatch for <export>; ... -> pointer, actual ... -> int` out of `process`, so
   * `CNameExports.kt` was never written and the whole package died. Each row below is one refused
   * combination: it must be a NAMED skip, absent from both halves, with the rest of the file still
   * generated (the harness throwing is itself the red here -- `Tier1Harness.run` propagates the
   * `require`, which is why this cell reads `generatedCSharp` at all).
   */
  @Test
  fun `a member neither route can carry is a named skip rather than an ABI mismatch`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package io.pkg

      class Toy(val name: String)

      enum class Mood { CALM, BRISK }

      class Refusals(private val beats: Int) {
        // Char payload, which no callback route has a crossing convention for, plus a scalar
        // METHOD return: the ROADMAP line 75 shape.
        fun countInitials(listener: (Char) -> Unit): Int {
          listener('o')
          return beats
        }

        // An object LAMBDA return: Kotlin would have to release a box the C# wrapper still owns.
        fun pickToy(make: (Int) -> Toy): Int = make(beats).name.length

        // An enum LAMBDA return, refused for the same reason.
        fun pickMood(make: (Int) -> Mood): Int = make(beats).ordinal

        // A `suspend` lambda, which is not bridged at any position.
        fun awaitAll(step: suspend (Int) -> Unit): Int = if (step === step) beats else 0

        // A non-lambda parameter beside a lambda the plan declines: the legacy route drops `min`
        // from both halves and the generated Kotlin then fails to compile.
        fun countInitialsAbove(min: Int, listener: (Char) -> Unit): Int {
          listener('o')
          return min
        }
      }
      """.trimIndent()
    )
    // The processor completed at all, which is the regression this cell exists for.
    assertTrue(result.compileErrors.isEmpty(), "compile errors: ${result.compileErrors}")
    assertTrue(
      result.generatedCSharp.contains("class Refusals"),
      "the rest of the owner must still be generated",
    )
    listOf(
      "CountInitials(", "PickToy(", "PickMood(", "AwaitAll(", "CountInitialsAbove(",
    ).forEach { member ->
      assertFalse(
        result.generatedCSharp.contains(member),
        "$member is refused, so no half of it may be rendered; got:\n${result.generatedCSharp}",
      )
    }
    listOf("countInitials", "pickToy", "pickMood", "awaitAll", "countInitialsAbove")
      .forEach { member ->
        assertTrue(
          result.kspWarnings.any { warning -> warning.contains("Refusals.$member") },
          "expected a named skip for $member; kspWarnings=${result.kspWarnings}",
        )
      }
  }

  /**
   * ADR-160: a callback is **per-call**, so every position that would STORE the lambda past the
   * crossing keeps the `CALLBACK_PROTOCOL` skip: a constructor, a data-class `copy`, and a
   * data-class `copy`. The C# call site allocates the delegate's `GCHandle` before the call and
   * frees it in the `finally`, so binding one of those would dispatch the ADR-102 thunk through a
   * freed handle on the first later invocation -- a use-after-free, not a compile error. Storing a
   * callback handed IN is ADR-037's route, not this one.
   *
   * A function-typed PROPERTY is the control: it is untouched by ADR-160 and keeps its own
   * pre-existing route, which hands the Kotlin lambda OUT as a `KotlinAction`/`KotlinFunc` handle
   * (getter only, so nothing on the C# side is ever stored into Kotlin). Pinned here because the
   * classifier now answers `Callback` for that property's type too, and the property planner has to
   * keep declining it for the legacy route to see it at all.
   */
  @Test
  fun `a stored callback position is refused while a lambda property keeps its own route`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package io.pkg

      class Bell(val label: String) {
        var onRing: (Int) -> Unit = {}
        val hook: (Int) -> Unit = {}
      }

      data class Chord(val cb: (Int) -> Unit)

      class Rack(val make: (Int) -> Bell)
      """.trimIndent()
    )
    assertTrue(result.compileErrors.isEmpty(), "compile errors: ${result.compileErrors}")
    val interop: String = result.generatedCSharp
    // No constructor binds a callback parameter, on any of the three owners.
    listOf("public Chord(", "public Rack(", "public Bell(Action", "public Bell(Func")
      .forEach { member ->
        assertFalse(
          interop.contains(member),
          "$member would store the lambda past the crossing and must not bind; got:\n$interop",
        )
      }
    // ...and no `GCHandle` is allocated anywhere in this module, which is the shape that would go
    // wrong: the plan's callback prelude frees its ctx at the end of the call.
    assertFalse(interop.contains("Ctx = default;"), "no callback ctx may be minted here")
    // The control: the lambda-valued properties still bind, on their own route, getter-only.
    assertTrue(
      interop.contains("public KotlinAction<int> OnRing =>"),
      "a function-typed property keeps its own handle-out route; got:\n$interop",
    )
    assertTrue(
      interop.contains("public KotlinFunc<int, global::Interop.Bell> Make =>"),
      "...including one whose lambda returns an exported object; got:\n$interop",
    )
  }

  /**
   * The other half of the gate: a payload shape the plan's callback lowering does not implement
   * (`Char`, which has no by-value crossing convention on any callback route and is not a legal
   * `[UnmanagedCallersOnly]` signature type) is NOT planned, and the hand-written route it stays on
   * keeps emitting it exactly as before. This is the cell that fails if the classifier is ever
   * widened past what both plan halves can lower, which would leave the legacy route retired for a
   * member the plan then skips -- a silently dropped member.
   */
  @Test
  fun `a Char lambda payload stays on the legacy route rather than being planned`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package io.pkg

      class Chimes(private val beats: Int) {
        fun eachInitial(listener: (Char) -> Unit) = repeat(beats) { listener('o') }
      }
      """.trimIndent()
    )
    assertTrue(result.compileErrors.isEmpty(), "compile errors: ${result.compileErrors}")
    val interop: String = result.generatedCSharp
    assertTrue(
      interop.contains("EachInitial"),
      "the legacy route must keep emitting a Char payload member in:\n$interop",
    )
    // The plan's own two-slot shape is what must be absent: no GCHandle ctx prelude of the ADR-160
    // spelling, since the legacy route allocates its own under a different name.
    assertFalse(
      interop.contains("listenerCtx"),
      "an unplanned member must not carry the plan's callback prelude in:\n$interop",
    )
  }
}
