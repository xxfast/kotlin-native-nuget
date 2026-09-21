package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-161, the guard finding 6 of the research memo asks for: **nothing in the build compares a
 * forward callback's two halves**.
 *
 * A callback pointer crosses as a bare `IntPtr` in every `DllImport`, and the Kotlin side spells the
 * `CFunction<...>` type it `reinterpret`s to itself. So neither compiler sees the other half: a
 * Kotlin export that calls an N-ary function pointer through an (N+1)-ary `CFunction` type, or the
 * reverse, links and runs, and corrupts the stack or writes through a slot the caller never
 * supplied. That is the exact bug class ADR-053's `SIGBUS` came from, and it is the one thing that
 * can go silently wrong when ADR-161 part B adds a trailing `errOut` slot to the user-code thunks:
 * the slot has to appear on BOTH halves in one commit.
 *
 * The cell compares parameter counts, which is all the two spellings have in common (`IntPtr`
 * against `COpaquePointer?`, `int` against `Int`). It passes at today's arity N. When part B lands,
 * both halves move together and it still passes; if only one half moves, it fails here rather than
 * in a consumer's process.
 *
 * Deliberately excluded: `NugetFlowOn*` and `NugetAsyncCallback`. Those two thunk families are
 * invoked by the published `nuget-runtime` klib (`collectForCSharp`, `launchForCSharp`), not by
 * KSP-generated Kotlin, so there is no generated `CFunction` in this module to compare them with,
 * and part B leaves their arity alone.
 *
 * Oreo is called back four different ways. Every one of them counts to the same number.
 */
class Tier1CallbackArityAgreementTest {

  private val source: String = """
    package tier1.arity

    class Toy(val name: String)

    interface Greeter {
      fun greet(name: String): String
      fun tick(count: Int)
    }

    interface CatEventListener {
      fun onMeow(message: String)
      fun onPurr()
    }

    class Cat(val name: String) {
      private val watchers: MutableList<(String) -> Unit> = mutableListOf()
      private val ticks: MutableList<(Int) -> Unit> = mutableListOf()

      // Per-call lambda, the ADR-062 plan route (ADR-160) and its payload kinds.
      fun describeWith(format: (String) -> String): String = format(name)
      fun forEachToy(action: (Toy) -> Unit) = action(Toy("Mouse"))
      fun weigh(measure: (Int) -> Int): Int = measure(7)

      // Stored callback, ADR-037, both payload kinds.
      fun addWatcher(watcher: (String) -> Unit) { watchers.add(watcher) }
      fun removeWatcher(watcher: (String) -> Unit) { watchers.remove(watcher) }
      fun addTicker(ticker: (Int) -> Unit) { ticks.add(ticker) }
      fun removeTicker(ticker: (Int) -> Unit) { ticks.remove(ticker) }

      // ADR-084 interface bridge slots: a C# class implements Greeter and Kotlin calls it.
      fun greetVia(greeter: Greeter): String = greeter.greet(name)
    }

    // ADR-039 listener bridge: an add/remove pair taking an interface.
    class CatEventSource(val name: String) {
      private val listeners: MutableList<CatEventListener> = mutableListOf()
      fun addListener(listener: CatEventListener) { listeners.add(listener) }
      fun removeListener(listener: CatEventListener) { listeners.remove(listener) }
      fun trigger() = listeners.forEach { it.onMeow("meow") }
    }
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(source)

  /**
   * The headline cell. Every arity the generated Kotlin invokes a callback at is an arity some
   * generated C# thunk offers, and every arity a user-code thunk offers is one the Kotlin half
   * invokes.
   */
  @Test
  fun `the generated Kotlin and C# callback halves agree on arity`() {
    val result = run()

    val kotlinArities: Set<Int> = kotlinCallbackArities(result.generated)
    val csharpArities: Set<Int> = csharpThunkArities(result.generatedCSharp)

    assertTrue(
      kotlinArities.isNotEmpty(),
      "expected the fixture to emit generated CFunction call sites at all; a fixture that stopped " +
          "reaching the callback emitters would make this guard vacuous; lines: " +
          "${result.generated.lines().filter { it.contains("CFunction") }.map(String::trim)}",
    )
    assertEquals(
      csharpArities,
      kotlinArities,
      "expected the Kotlin CFunction arities and the C# user-code thunk arities to agree; the " +
          "pointer crosses as a bare IntPtr, so a disagreement links and runs and writes through a " +
          "slot the caller never supplied",
    )
  }

  /**
   * ADR-161 part B: the trailing error slot. Counting alone cannot catch the one change part B
   * makes, because both halves grow by one and the set equality above stays green either way. The
   * slot has to be the LAST parameter on both halves, and it has to be a pointer-to-pointer on the
   * C# side and a nullable opaque pointer on the Kotlin side, so this cell names the types.
   *
   * Written red before the emitters moved: today every Kotlin `CFunction` ends at the echoed ctx
   * (`COpaquePointer`) and every C# thunk ends at `IntPtr`.
   */
  @Test
  fun `every user-code callback half ends with the error slot`() {
    val result = run()

    val kotlinTails: Set<String> = kotlinCallbackTails(result.generated)
    val csharpTails: Set<String> = csharpThunkTails(result.generatedCSharp)

    assertTrue(kotlinTails.isNotEmpty(), "expected generated CFunction call sites at all")
    assertEquals(
      setOf("COpaquePointer?"),
      kotlinTails,
      "expected every generated Kotlin CFunction type to end with the ADR-161 error slot",
    )
    assertEquals(
      setOf("IntPtr*"),
      csharpTails,
      "expected every generated user-code thunk to end with the ADR-161 `IntPtr* errOut` slot; a " +
          "thunk at arity N+1 called with N writes a managed handle through garbage",
    )
  }

  /** Both halves still have to be individually well formed, not merely equal in count. */
  @Test
  fun `the generated Kotlin compiles and every thunk pointer is a cdecl function pointer`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected the callback exports to compile; got: ${result.compileErrors}",
    )

    val pointers: List<String> = result.generatedCSharp.lines()
      .map(String::trim)
      .filter { it.startsWith("(IntPtr)(delegate* unmanaged[Cdecl]<") }
    assertTrue(
      pointers.size >= 4,
      "expected a thunk pointer per delegate shape reached by the fixture; got: $pointers",
    )
  }

  /**
   * The arities the generated Kotlin invokes: one per `reinterpret<CFunction<(A, B) -> R>>`. Nested
   * generic commas cannot occur in these signatures (every parameter is a wire type), so a comma
   * split is exact.
   */
  private fun kotlinCallbackArities(generated: String): Set<Int> =
    generated.split("reinterpret<CFunction<").drop(1)
      .map { chunk -> parameterCount(chunk.substringAfter("(").substringBefore(")")) }
      .toSet()

  /**
   * The arities the generated C# user-code thunks offer: the `delegate* unmanaged[Cdecl]<...>` type
   * argument list is parameters plus the return type, so the count is one less. The two
   * runtime-invoked families are excluded (see the class comment).
   */
  private fun csharpThunkArities(generated: String): Set<Int> =
    generated.split("(delegate* unmanaged[Cdecl]<").drop(1)
      .filterNot { chunk ->
        val name: String = chunk.substringAfter(">)&").substringBefore("Thunk")
        name.startsWith("NugetFlowOn") || name == "NugetAsyncCallback"
      }
      .map { chunk -> parameterCount(chunk.substringBefore(">)&")) - 1 }
      .toSet()

  /** The last parameter type of each generated Kotlin `CFunction` type. */
  private fun kotlinCallbackTails(generated: String): Set<String> =
    generated.split("reinterpret<CFunction<").drop(1)
      .map { chunk ->
        chunk.substringAfter("(").substringBefore(")").split(",").last().trim()
      }
      .toSet()

  /**
   * The last PARAMETER type of each generated C# user-code thunk: the `delegate*` type argument
   * list is parameters plus the return type, so the tail is the second-to-last entry.
   */
  private fun csharpThunkTails(generated: String): Set<String> =
    generated.split("(delegate* unmanaged[Cdecl]<").drop(1)
      .filterNot { chunk ->
        val name: String = chunk.substringAfter(">)&").substringBefore("Thunk")
        name.startsWith("NugetFlowOn") || name == "NugetAsyncCallback"
      }
      .map { chunk ->
        chunk.substringBefore(">)&").split(",").map(String::trim).dropLast(1).last()
      }
      .toSet()

  private fun parameterCount(list: String): Int =
    list.split(",").map(String::trim).count(String::isNotEmpty)
}
