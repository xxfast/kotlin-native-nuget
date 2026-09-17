package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-151 (issue #248): `kotlin.ByteArray` maps to C# `byte[]` and `ByteArray?` to `byte[]?` at
 * every ordinary position, over the **collection** wire: one `StableRef` handle per crossing,
 * `MATERIALIZED` ownership, the null pointer for `null`, and a count plus a `memcpy` instead of
 * the per-element `nuget_list_*` loop.
 *
 * The cells that matter are the two ends of that wire. Out of Kotlin the export must mint through
 * `NugetHandles.retain` (never a bare `StableRef.create`, which would not be counted by ADR-120's
 * live-handle meter) and the C# side must read it through `NugetMarshal.ReadBytes`, which disposes
 * the handle in its own `finally`. Into Kotlin the C# side mints with `NugetMarshal.CreateBytes`
 * and disposes after the call, and Kotlin reads the handle back with
 * `asStableRef<kotlin.ByteArray>`.
 */
class Tier1ByteArrayMappingTest {

  private fun run(): Tier1Result = Tier1Harness.run(
    """
    package tier1.bytes

    class Collar(val tag: ByteArray, var reading: ByteArray?) {
      fun echo(sample: ByteArray): ByteArray = sample
      fun maybe(sample: ByteArray?): ByteArray? = sample
      fun size(sample: ByteArray): Int = sample.size
    }

    object CollarRegistry {
      fun empty(): ByteArray = byteArrayOf()
    }

    fun reverse(data: ByteArray): ByteArray = data.reversedArray()

    data class Reading(val bytes: ByteArray)
    """.trimIndent()
  )

  @Test
  fun `ByteArray binds as byte array at every ordinary position`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected the generated exports to compile; got: ${result.compileErrors}",
    )
    // Kotlin side: the handle is minted through the counted helper, and read back as a ByteArray.
    assertTrue(
      "NugetHandles.retain(" in result.generated &&
          "asStableRef<kotlin.ByteArray>()" in result.generated,
      "expected the handle wire on both directions; generated=${result.generated}",
    )
    assertTrue(
      "StableRef.create(" !in result.generated,
      "expected no bare StableRef.create in a generated export (ADR-120 counts retains); " +
          "generated=${result.generated}",
    )
    // C# side: the public type is byte[] at a property, a parameter, a return, and a constructor.
    assertTrue(
      "public byte[] Tag" in result.generatedCSharp,
      "expected a byte[] property; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "public byte[] Echo(byte[] sample)" in result.generatedCSharp,
      "expected a byte[] parameter and return; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "public int Size(byte[] sample)" in result.generatedCSharp,
      "expected a byte[] parameter beside a primitive return; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "public Reading(byte[] bytes)" in result.generatedCSharp,
      "expected the data-class constructor to take a byte[]; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "public static byte[] Empty()" in result.generatedCSharp ||
          "public byte[] Empty()" in result.generatedCSharp,
      "expected the object route to return a byte[]; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "byte[] Reverse(byte[] data)" in result.generatedCSharp,
      "expected the top-level function on the byte[] row; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
  }

  @Test
  fun `the byte array wire is the collection handle wire, not a list of sbyte`() {
    val result = run()

    assertTrue(
      "List<sbyte>" !in result.generatedCSharp && "IReadOnlyList<sbyte>" !in result.generatedCSharp,
      "expected byte[] rather than a list of sbyte; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "NugetMarshal.ReadBytes(" in result.generatedCSharp,
      "expected results to be materialized through ReadBytes; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "NugetMarshal.CreateBytes(" in result.generatedCSharp,
      "expected arguments to mint a handle through CreateBytes; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "NugetBytesNative.Dispose(" in result.generatedCSharp,
      "expected an argument handle to be disposed after the call; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "nuget_bytes_create" in result.generatedCSharp &&
          "nuget_bytes_count" in result.generatedCSharp &&
          "nuget_bytes_copy" in result.generatedCSharp,
      "expected the three runtime imports; generatedCSharp=${result.generatedCSharp}",
    )
  }

  @Test
  fun `nullable ByteArray rides the null pointer sentinel rather than a has-value channel`() {
    val result = run()

    assertTrue(
      "public byte[]? Reading" in result.generatedCSharp,
      "expected byte[]? for a nullable ByteArray property; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "public byte[]? Maybe(byte[]? sample)" in result.generatedCSharp,
      "expected byte[]? in and out on one callable; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "reading_has_value" !in result.generated && "ReadingHasValue" !in result.generatedCSharp,
      "expected no has-value fan-out for a nullable ByteArray; generated=${result.generated}",
    )
    assertTrue(
      "?.asStableRef<kotlin.ByteArray>()?.get()" in result.generated,
      "expected the null wire pointer to short-circuit on the Kotlin side; " +
          "generated=${result.generated}",
    )
    assertTrue(
      "sample != null ? NugetMarshal.CreateBytes(sample) : IntPtr.Zero" in result.generatedCSharp,
      "expected IntPtr.Zero for a null argument; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "== IntPtr.Zero) return null;" in result.generatedCSharp,
      "expected the null pointer to be the null sentinel on results; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
  }
}
