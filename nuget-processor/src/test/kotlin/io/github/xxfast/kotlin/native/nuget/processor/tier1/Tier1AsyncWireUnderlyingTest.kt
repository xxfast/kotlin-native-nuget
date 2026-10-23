package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-240: the legacy `suspend` and `Flow` routes carry a bare `Instant`, `ByteArray` and plain
 * interface, and a value class over a `ByteArray`, on the ordinary route's wire, driven through the
 * real `NugetProcessor` so both halves come off the one legacy `Wire` shape. A bytes struct also
 * gains its content `Equals` and `GetHashCode`.
 */
class Tier1AsyncWireUnderlyingTest {

  @Test
  fun `suspend and Flow members take and return wire underlyings on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.asyncwire

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf
      import kotlin.time.Instant

      interface Bell {
        fun ring(): String
      }

      @JvmInline
      value class Print(val pads: ByteArray)

      class Slot(val owner: String) {
        suspend fun stamp(at: Instant?): Instant? = at

        suspend fun press(print: Print): Print = print

        suspend fun hear(bell: Bell): String = bell.ring()

        fun stamps(at: Instant): Flow<Instant> = flowOf(at)
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "wire exports should compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    // A nullable `Instant` fans out to its has-value pair, read before the launch.
    assertContains(kotlin, "atHasValue: Boolean")
    assertContains(kotlin, "if (atHasValue) instantFromDotNetTicks(at) else null")
    // A narrow Flow element leaves as itself, the erased box ADR-239 reads.
    assertContains(kotlin, "NugetHandles.retain(value as Any)")

    val cs: String = result.generatedCSharp
    assertContains(cs, "read: static h => NugetMarshal.FromHandle<global::System.DateTimeOffset>(h)")
    assertContains(
      cs,
      "public Task<global::System.DateTimeOffset?> StampAsync(global::System.DateTimeOffset? at",
    )
    assertContains(cs, "(at).HasValue, (at).GetValueOrDefault().UtcTicks")
    assertContains(cs, "NugetMarshal.CreateBytes(")
    // The bytes struct crosses the async route through its own box pair.
    assertContains(cs, "global::Interop.Print.NugetUnbox(resultPtr)")
    assertContains(cs, "internal static IntPtr NugetBox(Print unboxed)")
    // A bare interface parameter rides ADR-232's interface shape, minted through `Wrap<I>`.
    assertContains(cs, Regex("""NugetMarshal\.Wrap<[\w.:]*IBell>\(bell!, out bool bellOwned\)"""))
    assertContains(
      cs,
      "public KotlinFlow<global::System.DateTimeOffset> Stamps(global::System.DateTimeOffset at)",
    )
    // Content equality on the bytes struct.
    assertContains(cs, "public bool Equals(Print other)")
    assertContains(cs, "global::System.MemoryExtensions.SequenceEqual<byte>(self, that)")
  }

  @Test
  fun `a MutableStateFlow of a narrow element writes on its ticks wire`() {
    val result = Tier1Harness.run(
      """
      package tier1.asyncwirestate

      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlin.time.Instant

      class Clock(val owner: String) {
        fun last(): MutableStateFlow<Instant> = MutableStateFlow(Instant.fromEpochSeconds(0))
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected the module to compile; got: ${result.compileErrors}")
    assertContains(
      result.generatedCSharp,
      "KotlinMutableStateFlow<global::System.DateTimeOffset> Last(",
    )
    assertContains(result.generated, "instantFromDotNetTicks(value)")
  }
}
