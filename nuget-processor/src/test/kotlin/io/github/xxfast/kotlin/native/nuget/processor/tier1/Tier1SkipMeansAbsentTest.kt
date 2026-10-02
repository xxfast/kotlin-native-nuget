package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ROADMAP Phase 4, measured 2026-09-20 while implementing the `ByteArray`-as-a-component item:
 * three pre-existing defects on the same path, all of the same family -- a declaration the ADR-062
 * plan **skipped** was still emitted, or crashed, by a route the skip does not gate.
 *
 * Not `ByteArray`-specific, which is why this class is not in `Tier1ByteArrayComponentTest`:
 * `hasLegacyGenericReturnRoute()` (`exports/FunctionExports.kt`) was true for ANY generic
 * declaration with type arguments, so
 *
 *  - every collection return the plan refused fell into `translateFunction`'s pre-ADR-062
 *    `isListReturnType`/`isMapReturnType`/`isSetReturnType` branches, which spell a component by
 *    its Kotlin SIMPLE NAME and drop its own type arguments (`IReadOnlyList<Instant>`,
 *    `IReadOnlyList<List>`) -- CS0246 in the consumer, next to the warning that said it was
 *    dropped; and
 *  - a skip caused by a PARAMETER left that return route open, and `mapParamType`'s fall-through
 *    degraded the parameter to a public `IntPtr` nobody can produce (issue #126's class, which
 *    ADR-122 fixed on the async routes only).
 *
 * `Instant` is the probe rather than `ByteArray` on purpose: it is still a refused component after
 * this item ships, so these cells keep testing the general hole rather than one type's arms.
 */
class Tier1SkipMeansAbsentTest {

  @Test
  fun `a skipped collection return is absent, not rendered with a simple-name component`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.skipabsent

      import kotlin.time.Instant

      fun stamps(): List<Instant> = emptyList()
      fun grids(): List<List<Instant>> = emptyList()
      fun tally(): Map<String, Instant> = emptyMap()

      fun control(): List<String> = listOf("oreo")
      """.trimIndent()
    )

    assertTrue(
      "Stamps" !in result.generatedCSharp && "Grids" !in result.generatedCSharp &&
          "Tally" !in result.generatedCSharp,
      "a skipped collection return must be absent from the C#; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    // Belt and braces: the two exact spellings the legacy route used to produce.
    assertTrue(
      "IReadOnlyList<Instant>" !in result.generatedCSharp &&
          "IReadOnlyList<List>" !in result.generatedCSharp,
      "a component must never be spelled by its Kotlin simple name; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    // ...and the control proves the fixture reaches the collection route at all.
    assertTrue(
      "public static IReadOnlyList<string> Control()" in result.generatedCSharp,
      "expected a bridgeable collection return to still bind; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
  }

  @Test
  fun `a skip caused by a parameter does not leave the return route open`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.skipabsentparam

      import kotlin.time.Instant

      class Cat(val name: String)

      class Box<T>(val value: T)

      // A GENERIC return, which this legacy route really does own, with a parameter it cannot
      // spell: `mapParamType` has no entry for `Cat`, so it used to render `IntPtr cat`.
      fun wrap(cat: Cat): Box<Int> = Box(cat.name.length)

      // ...and the collection-return twin, skipped for its PARAMETER rather than its return.
      fun indices(stamps: List<Instant>): List<Int> = stamps.indices.toList()
      """.trimIndent()
    )

    // Asserted on the member's own extern and signature, not on the bare word: `NugetMarshal.Wrap`
    // and `WrapString` are runtime helpers every file carries.
    assertTrue(
      "Native_Wrap" !in result.generatedCSharp && " Wrap(" !in result.generatedCSharp,
      "a generic-return member whose parameter has no spelling must be absent, not rendered " +
          "with an IntPtr slot; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "IntPtr cat" !in result.generatedCSharp,
      "a parameter must never be degraded to a public IntPtr (issue #126); " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "Indices" !in result.generatedCSharp,
      "a member skipped for its parameter must not be emitted for its return; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    // The control: the owner types themselves still bind, so the absences above are about the
    // two functions rather than about the whole file being dropped.
    assertTrue(
      "class Cat" in result.generatedCSharp,
      "expected the parameter's own type to still be declared; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
  }

  @Test
  fun `a bare ByteArray at a Flow element no longer crashes the processor`() {
    // Measured before the fix: `IllegalStateException: Kotlin builtin kotlin.ByteArray reached the
    // user-type C# speller` out of KSP, which took `packNuget` down with it -- every other
    // declaration in the module went with it. Now it BINDS (ADR-151 amendment), through the
    // ADR-123 per-member `read:` seam.
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.flowbytes

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow
      import kotlinx.coroutines.flow.flowOf

      class CollarStream {
        val pulses: Flow<ByteArray> = flowOf(byteArrayOf(1))
        suspend fun snapshot(): ByteArray = byteArrayOf(2)
        // The nullable read arm on the StateFlow shape; `Tier1ByteArrayComponentTest` pins the
        // plain-`Flow` twin, which threads a nullable element too since ADR-065's widening.
        val faint: StateFlow<ByteArray?> = MutableStateFlow(null)
        suspend fun glimpse(): ByteArray? = null
        // ADR-071's settable `.Value` seam has no arm that mints a bytes handle, so this one keeps
        // the read-only mapping rather than declaring a native setter typed `byte[]`.
        val beacon: MutableStateFlow<ByteArray> = MutableStateFlow(byteArrayOf(3))
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(
      result.kspErrors.isEmpty(),
      "the processor must not fail on a builtin at an async position; " +
          "kspErrors=${result.kspErrors}",
    )
    assertTrue(
      "public KotlinFlow<byte[]> Pulses" in result.generatedCSharp,
      "expected the Flow element to bind as byte[]; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "public Task<byte[]> SnapshotAsync" in result.generatedCSharp,
      "expected the suspend return to bind as Task<byte[]>; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    // Not `Task<ByteArray>` / `KotlinFlow<ByteArray>`: a C# type nothing declares, which is what
    // the suspend half silently rendered before the fix (ADR-123 / issue #127's shape). Asserted
    // on the two spellings rather than on the bare word, which the `ReadBytes` helper's own
    // `<summary>` legitimately contains.
    assertTrue(
      "Task<ByteArray>" !in result.generatedCSharp &&
          "KotlinFlow<ByteArray>" !in result.generatedCSharp,
      "the Kotlin type name must never be a declared C# type argument; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "NugetMarshal.ReadBytes(resultPtr)" in result.generatedCSharp,
      "expected the awaited handle to be read as bytes; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "read: static h => NugetMarshal.ReadBytes(h)" in result.generatedCSharp,
      "expected the flow element materialiser; generatedCSharp=${result.generatedCSharp}",
    )

    // The nullable twins. The Kotlin half sends `null` as a null result pointer
    // (`if (result == null) null else NugetHandles.retain(...)`), so both reads have to guard the
    // pointer first: `ReadBytes(IntPtr.Zero)` would call `nuget_bytes_count` on nothing.
    assertTrue(
      "public Task<byte[]?> GlimpseAsync" in result.generatedCSharp,
      "expected a nullable suspend return to be Task<byte[]?>; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "public KotlinStateFlow<byte[]?> Faint" in result.generatedCSharp,
      "expected a nullable state-flow element to be byte[]?; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "resultPtr == IntPtr.Zero ? null : NugetMarshal.ReadBytes(resultPtr)" in
          result.generatedCSharp,
      "expected the nullable suspend read to guard the wire pointer; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "read: static h => h == IntPtr.Zero ? null : NugetMarshal.ReadBytes(h)" in
          result.generatedCSharp,
      "expected the nullable flow element read to guard the wire pointer; " +
          "generatedCSharp=${result.generatedCSharp}",
    )

    // A declared `MutableStateFlow<ByteArray>` keeps the READ-ONLY mapping: the ADR-071 write seam
    // spells its native setter with the element's own C# type, and `byte[]` is not a value the
    // ABI can carry -- it needs a minted bytes handle nothing on that path produces.
    assertTrue(
      "public KotlinStateFlow<byte[]> Beacon" in result.generatedCSharp,
      "expected the read-only StateFlow mapping for a MutableStateFlow<ByteArray>; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "KotlinMutableStateFlow<byte[]>" !in result.generatedCSharp,
      "a bytes element must not reach the settable .Value seam; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
  }
}
