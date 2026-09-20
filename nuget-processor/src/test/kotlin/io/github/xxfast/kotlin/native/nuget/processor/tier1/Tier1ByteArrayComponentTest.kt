package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ROADMAP Phase 4, the ADR-151 amendment: `ByteArray` as a **collection component**.
 *
 * Every cell here asserts the rendered `ReadBytes` / `CreateBytes` **text**, not merely that the
 * member binds. That is load-bearing: a missing component arm does not fail the build, it renders
 * `NugetMarshal.FromHandle<byte[]>(h)` and `CreateList<byte[]>(...)`, which compile against the
 * generated helpers and throw at the first element at runtime (`NotSupportedException: Cannot pass
 * Byte[] to a Kotlin collection`). A binding assertion alone would pass over both.
 *
 * `Tier1ByteArrayMappingTest` covers the ordinary (non-component) positions ADR-151 shipped.
 */
class Tier1ByteArrayComponentTest {

  private fun run(): Tier1Result = Tier1Harness.run(
    """
    package tier1.bytecomponents

    class CollarLog(var bursts: List<ByteArray>, var chips: Map<String, ByteArray>) {
      fun rewound(): List<ByteArray> = bursts.map { it.reversedArray() }
    }

    fun burstChunks(data: ByteArray): List<ByteArray> = listOf(data)
    fun spliceBursts(parts: List<ByteArray>): Int = parts.sumOf { it.size }
    fun weighBursts(parts: MutableList<ByteArray>): Int = parts.sumOf { it.size }
    fun rewindCollars(bursts: Map<String, ByteArray>): Map<String, ByteArray> = bursts
    fun countCollarBytes(bursts: MutableMap<String, ByteArray>): Int = bursts.size
    fun litterGrid(): List<List<ByteArray>> = emptyList()
    fun weighLitterGrid(rows: List<List<ByteArray>>): Int = rows.size
    fun patchySignals(): List<ByteArray?> = emptyList()
    fun missingSignals(signals: List<ByteArray?>): Int = signals.count { it == null }

    fun uniquePatches(patches: Set<ByteArray>): Int = patches.size
    fun byFingerprint(index: Map<ByteArray, String>): Int = index.size
    """.trimIndent()
  )

  @Test
  fun `a List of ByteArray binds as a list of byte arrays at a return and a parameter`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected the generated exports to compile; got: ${result.compileErrors}",
    )
    assertTrue(
      "public static IReadOnlyList<byte[]> BurstChunks(byte[] data)" in result.generatedCSharp,
      "expected IReadOnlyList<byte[]> at a return; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "public static int SpliceBursts(IReadOnlyList<byte[]> parts)" in result.generatedCSharp,
      "expected IReadOnlyList<byte[]> at a parameter; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "public static int WeighBursts(IList<byte[]> parts)" in result.generatedCSharp,
      "expected the MutableList spelling; generatedCSharp=${result.generatedCSharp}",
    )
  }

  @Test
  fun `the element read is ReadBytes and the element write is CreateBytes, not FromHandle`() {
    val result = run()

    // The whole point of the item: a missing arm renders these instead, and they COMPILE.
    assertTrue(
      "FromHandle<byte[]>" !in result.generatedCSharp,
      "a bytes element must never read through FromHandle (it throws at the first element); " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "NugetMarshal.ReadList<byte[]>(listHandle, static h1 => NugetMarshal.ReadBytes(h1))" in
          result.generatedCSharp,
      "expected the per-element ReadBytes lambda; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "NugetMarshal.CreateList(global::System.Linq.Enumerable.Select(parts, x => " +
          "NugetMarshal.CreateBytes(x)))" in result.generatedCSharp,
      "expected the per-element CreateBytes projection; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    // The Kotlin half casts the dereferenced box; a missing arm here CRASHES packNuget outright.
    assertTrue(
      "it as kotlin.ByteArray" in result.generated,
      "expected the Kotlin component lowering; generated=${result.generated}",
    )
    assertTrue(
      "StableRef.create(" !in result.generated,
      "expected no bare StableRef.create (ADR-120 counts retains); generated=${result.generated}",
    )
  }

  @Test
  fun `a Map value slot carries bytes while its String key keeps the ordinary read`() {
    val result = run()

    assertTrue(
      ("public static IReadOnlyDictionary<string, byte[]> RewindCollars(" +
          "IReadOnlyDictionary<string, byte[]> bursts)") in result.generatedCSharp,
      "expected the map VALUE slot to bind; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "public static int CountCollarBytes(IDictionary<string, byte[]> bursts)" in
          result.generatedCSharp,
      "expected the MutableMap spelling; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      ("static k1 => NugetMarshal.FromHandle<string>(k1), " +
          "static v1 => NugetMarshal.ReadBytes(v1)") in result.generatedCSharp,
      "expected the key to keep FromHandle and only the value to read bytes; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "new KeyValuePair<string, IntPtr>(x.Key, NugetMarshal.CreateBytes(x.Value))" in
          result.generatedCSharp,
      "expected the value slot to project to an IntPtr handle; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
  }

  @Test
  fun `nesting recurses without a new arm`() {
    val result = run()

    assertTrue(
      "public static IReadOnlyList<IReadOnlyList<byte[]>> LitterGrid()" in result.generatedCSharp,
      "expected List<List<ByteArray>> at a return; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "static h1 => NugetMarshal.ReadList<byte[]>(h1, static h2 => NugetMarshal.ReadBytes(h2))" in
          result.generatedCSharp,
      "expected the inner level to read bytes at depth 2; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "x1 => NugetMarshal.CreateBytes(x1)" in result.generatedCSharp,
      "expected the depth-1 lambda parameter to mint bytes; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
  }

  @Test
  fun `a nullable element writes as a nullable IntPtr so Wrap never owns a zero handle`() {
    val result = run()

    assertTrue(
      "public static IReadOnlyList<byte[]?> PatchySignals()" in result.generatedCSharp,
      "expected the nullable element on the read side; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "elementHandle1 == IntPtr.Zero ? (byte[]?)null : NugetMarshal.ReadBytes(elementHandle1)" in
          result.generatedCSharp,
      "expected the null pointer to short-circuit the read; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    // The load-bearing half. `(IntPtr?)null` makes `Wrap<IntPtr?>` return at its `value == null`
    // guard with `owned = false`; the plain `IntPtr.Zero` spelling would report `owned = true` and
    // the fill loop would then call `Dispose(IntPtr.Zero)`, which takes the host process down.
    assertTrue(
      "Select(signals, x => x == null ? (IntPtr?)null : NugetMarshal.CreateBytes(x))" in
          result.generatedCSharp,
      "expected the nullable element to project as IntPtr?; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "it as kotlin.ByteArray?" in result.generated,
      "expected the nullable Kotlin cast; generated=${result.generated}",
    )
  }

  @Test
  fun `a class carries bytes components at a constructor, both accessors and a method`() {
    val result = run()

    assertTrue(
      ("public CollarLog(IReadOnlyList<byte[]> bursts, " +
          "IReadOnlyDictionary<string, byte[]> chips)") in result.generatedCSharp,
      "expected the constructor parameters; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "public IReadOnlyList<byte[]> Bursts" in result.generatedCSharp,
      "expected the property getter; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "public IReadOnlyDictionary<string, byte[]> Chips" in result.generatedCSharp,
      "expected the map property; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "public IReadOnlyList<byte[]> Rewound()" in result.generatedCSharp,
      "expected the method return; generatedCSharp=${result.generatedCSharp}",
    )
    // A dropped setter leaves the getter behind silently, so the setter is asserted apart.
    assertTrue(
      "set_bursts" in result.generated && "set_chips" in result.generated,
      "expected both setters to survive the write-side gate; generated=${result.generated}",
    )
  }

  @Test
  fun `NugetBytesNative is emitted when the only ByteArray in the file is nested`() {
    val nested: Tier1Result = Tier1Harness.run(
      """
      package tier1.nestedbytesonly

      fun grid(): List<List<ByteArray>> = emptyList()
      """.trimIndent()
    )

    assertTrue(
      "class NugetBytesNative" in nested.generatedCSharp,
      "a nested-only ByteArray must still emit its P/Invoke class, or ReadBytes is CS0103; " +
          "generatedCSharp=${nested.generatedCSharp}",
    )
    assertTrue(
      "NugetMarshal.ReadBytes(" in nested.generatedCSharp,
      "expected the nested read; generatedCSharp=${nested.generatedCSharp}",
    )
  }

  /**
   * The crossing of two changes that were written apart: the ADR-151 bytes element and the ADR-065
   * widening of nullable-element threading from `StateFlow` to plain `Flow`.
   *
   * Until the widening landed, a plain `Flow<ByteArray?>` dropped its `?` on the C# half: it
   * declared `KotlinFlow<byte[]>` and read every item with an UNGUARDED
   * `NugetMarshal.ReadBytes(h)`, so a null emission asked `nuget_bytes_count` to dereference
   * `IntPtr.Zero`. The bytes author could only probe the nullable arm through
   * `StateFlow<ByteArray?>`, so this cell pins the pair on BOTH plain-`Flow` positions -- the
   * property and the method return -- which compute their nullability in separate copies of the
   * same code on each of the two halves (four sites, one assertion set).
   */
  @Test
  fun `a plain Flow of a nullable ByteArray guards the null handle at both positions`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.patchybytes

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      class CollarStream {
        val pulses: Flow<ByteArray?> = flowOf(byteArrayOf(1), null)

        fun pulsesNow(): Flow<ByteArray?> = pulses
      }
      """.trimIndent(),
      fileName = "CollarStream.kt",
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(
      result.compiledClean,
      "expected the generated exports to compile; got: ${result.compileErrors}",
    )
    assertTrue(
      "public KotlinFlow<byte[]?> Pulses" in result.generatedCSharp,
      "expected the nullable bytes element at the property position; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "public KotlinFlow<byte[]?> PulsesNow()" in result.generatedCSharp,
      "expected the nullable bytes element at the method position; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    // Exactly the text the `StateFlow<ByteArray?>` arm already renders, once per position.
    assertEquals(
      2,
      Regex(Regex.escape("read: static h => h == IntPtr.Zero ? null : NugetMarshal.ReadBytes(h)"))
        .findAll(result.generatedCSharp).count(),
      "expected both positions to guard the wire pointer before ReadBytes; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    // ...and the unguarded read, which dereferences a null handle, nowhere in the file: every
    // bytes element here is nullable, so a single occurrence is a dropped `?`.
    assertTrue(
      "read: static h => NugetMarshal.ReadBytes(h)" !in result.generatedCSharp,
      "a nullable bytes element must never read through the unguarded arm; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    // The Kotlin half, which no C# assertion can see: null leaves as a null item pointer rather
    // than throwing out of `retain(value as Any)`.
    assertEquals(
      2,
      Regex(Regex.escape("if (value != null) NugetHandles.retain(value) else null"))
        .findAll(result.generated).count(),
      "expected both emissions to be boxed null-guarded; generated=${result.generated}",
    )
    assertTrue(
      "NugetHandles.retain(value as Any)" !in result.generated,
      "expected no unguarded box left on a nullable bytes element; generated=${result.generated}",
    )
  }

  @Test
  fun `a Set element and a Map key stay declined, with a hint that says why`() {
    val result = run()

    assertTrue(
      "UniquePatches" !in result.generatedCSharp && "ByFingerprint" !in result.generatedCSharp,
      "a declined shape must have no C# member at all; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    val declined: List<String> = result.kspWarnings.filter { warning ->
      "uniquePatches" in warning || "byFingerprint" in warning
    }
    assertTrue(
      declined.size == 2,
      "expected both declined shapes to warn once each; kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      declined.all { "BYTE_ARRAY" in it },
      "expected the skip to be attributed to BYTE_ARRAY rather than to STRING or COLLECTION; " +
          "declined=$declined",
    )
    assertTrue(
      declined.all { "compare by identity" in it && "every crossing of this bridge copies" in it },
      "expected the identity-versus-copy reason in the hint; declined=$declined",
    )
  }
}
