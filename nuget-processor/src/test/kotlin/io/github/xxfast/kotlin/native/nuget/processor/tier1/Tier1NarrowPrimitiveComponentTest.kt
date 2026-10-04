package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-097 / ADR-098 coverage · the narrow-primitive arms of the Kotlin plan emitter's
 * `PrimitiveKind.simpleKotlinName()`, which has exactly two call sites: the collection-component
 * cast target (`elementKotlinTypeName`) and the per-call callback payload wire
 * (`loweredCallbackExpression`).
 *
 * `Short` and `Byte` already had processor cells; `UByte`, `UInt` and `ULong` were only reached by
 * the test-library fixture, and `UShort` reached neither call site anywhere. This cell takes all
 * six narrow kinds as collection components and a `(UShort) -> Unit` callback, so every arm is
 * proven by compiling the generated Kotlin, not just by reading the `when`.
 */
class Tier1NarrowPrimitiveComponentTest {

  private val source: String = """
    package tier1.narrowkinds

    class Gauge {
      fun bytes(xs: List<Byte>): Int = xs.size
      fun ubytes(xs: List<UByte>): Int = xs.size
      fun shorts(xs: List<Short>): Int = xs.size
      fun ushorts(xs: List<UShort>): Int = xs.size
      fun uints(xs: Set<UInt>): Int = xs.size
      fun ulongs(xs: Map<ULong, UShort>): Int = xs.size
      fun onPulse(listener: (UShort) -> Unit) = listener(65535u)
    }
  """.trimIndent()

  private val result: Tier1Result by lazy { Tier1Harness.run(source) }

  @Test
  fun `every member binds without a diagnostic and the generated Kotlin compiles`() {
    assertTrue(result.kspSucceeded, "got: ${result.kspExitCode} ${result.kspErrors}")
    assertTrue(
      result.kspWarnings.none { "SKIPPED" in it },
      "no narrow component may skip; got: ${result.kspWarnings}",
    )
    assertTrue(result.compiledClean, "got: ${result.compileErrors}")
  }

  /** Call site one: each narrow component casts to its own Kotlin type, `UShort` included. */
  @Test
  fun `every narrow primitive kind casts to its own Kotlin type as a collection component`() {
    listOf("Byte", "UByte", "Short", "UShort", "UInt", "ULong").forEach { kind ->
      assertTrue(
        Regex("""as kotlin\.$kind\b""").containsMatchIn(result.generated),
        "expected an `as kotlin.$kind` component cast; got: ${castLines()}",
      )
    }
  }

  /** Call site two: the `UShort` callback payload crosses by value as `UShort`. */
  @Test
  fun `a UShort callback payload crosses by value`() {
    assertTrue(
      result.generated.contains("CFunction<(UShort, COpaquePointer, COpaquePointer?) -> Unit>"),
      "expected a by-value UShort payload; got: ${
        result.generated.lines().filter { it.contains("reinterpret<CFunction<") }
      }",
    )
  }

  /** The C# half: the unsigned 16-bit kind surfaces as `ushort` at both positions. */
  @Test
  fun `the C sharp members take ushort at the collection and callback positions`() {
    val expected: List<String> = listOf(
      "public int Ushorts(IReadOnlyList<ushort> xs)",
      "public int Ulongs(IReadOnlyDictionary<ulong, ushort> xs)",
      "public void OnPulse(Action<ushort> listener)",
    )
    val declarations: List<String> = result.generatedCSharp.lines().map { it.trim() }
    expected.forEach { signature ->
      assertTrue(
        signature in declarations,
        "expected the declaration `$signature`; got: ${
          declarations.filter { it.startsWith("public ") }
        }",
      )
    }
    assertTrue(
      Regex("""internal delegate void NugetUShortVoidCallback\(ushort \w+, IntPtr \w+\);""")
        .containsMatchIn(result.generatedCSharp),
      "expected a by-value ushort callback delegate",
    )
  }

  private fun castLines(): List<String> =
    result.generated.lines().filter { it.contains(" as kotlin.") }
}
