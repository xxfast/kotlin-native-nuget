package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-106: `kotlin.uuid.Uuid` maps to `System.Guid` over the RFC 9562 hex-dash **String** wire, at
 * every ordinary position. The cell that matters most is `Uuid?`: it rides the null-pointer
 * sentinel `String?` uses, *not* `Instant?`'s out-of-band has-value channel, so the generated C#
 * must show a single slot per nullable Uuid and no `HasValue` fan-out.
 */
class Tier1UuidMappingTest {

  private fun run(): Tier1Result = Tier1Harness.run(
    """
    package tier1.uuid

    import kotlin.uuid.Uuid

    class Microchip(val chipId: Uuid, var previousChipId: Uuid?) {
      fun matches(candidate: Uuid): Boolean = candidate == chipId
      fun lastRetired(): Uuid? = previousChipId
      fun describe(tag: Uuid?): String = if (tag == null) "none" else "scanned ${'$'}tag"
      fun echo(tag: Uuid): Uuid = tag
    }

    object MicrochipRegistry {
      fun nil(): Uuid = Uuid.NIL
      fun isNil(tag: Uuid): Boolean = tag == Uuid.NIL
    }

    fun wellKnownChip(): Uuid = Uuid.parse("00112233-4455-6677-8899-aabbccddeeff")

    data class ChipRecord(val id: Uuid)
    """.trimIndent()
  )

  @Test
  fun `Uuid binds as System Guid at every ordinary position`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected the generated exports to compile; got: ${result.compileErrors}",
    )
    // Kotlin side: text out, parse in.
    assertTrue(
      ".chipId.toString()" in result.generated,
      "expected the property getter to ship the hex-dash text; generated=${result.generated}",
    )
    assertTrue(
      "kotlin.uuid.Uuid.parse(" in result.generated,
      "expected inputs to parse the text back into a Uuid; generated=${result.generated}",
    )
    // C# side: Guid public type, Guid.Parse on the way in.
    assertTrue(
      "public global::System.Guid ChipId" in result.generatedCSharp,
      "expected a Guid property; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "global::System.Guid.Parse(Marshal.PtrToStringUTF8(nativeResult)!)" in result.generatedCSharp,
      "expected results to be parsed into a Guid; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "public bool Matches(global::System.Guid candidate)" in result.generatedCSharp,
      "expected a Guid parameter; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "candidate.ToString()" in result.generatedCSharp,
      "expected the argument to cross as its canonical text; generatedCSharp=${result.generatedCSharp}",
    )
  }

  @Test
  fun `nullable Uuid rides the null pointer sentinel rather than a has-value channel`() {
    val result = run()

    assertTrue(
      "public global::System.Guid? PreviousChipId" in result.generatedCSharp,
      "expected Guid? for a nullable Uuid property; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "previousChipId_has_value" !in result.generated &&
          "PreviousChipIdHasValue" !in result.generatedCSharp,
      "expected no has-value fan-out for a nullable Uuid; generated=${result.generated}",
    )
    assertTrue(
      "?.toString()" in result.generated,
      "expected the nullable getter to safe-call toString(); generated=${result.generated}",
    )
    assertTrue(
      "nativeResult == IntPtr.Zero ? null : global::System.Guid.Parse(" in result.generatedCSharp,
      "expected the null pointer to be the null sentinel; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "tag?.ToString()" in result.generatedCSharp,
      "expected a nullable Uuid argument to cross as one nullable string slot; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
  }

  @Test
  fun `Uuid binds on the static object route and in a data class constructor`() {
    val result = run()

    assertTrue(
      "public static global::System.Guid Nil()" in result.generatedCSharp,
      "expected the object route to return a Guid; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "public ChipRecord(global::System.Guid id)" in result.generatedCSharp,
      "expected the data-class constructor to take a Guid; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "public global::System.Guid wellKnownChip()" in result.generatedCSharp ||
          "public static global::System.Guid wellKnownChip()" in result.generatedCSharp,
      "expected the top-level function to return a Guid; generatedCSharp=${result.generatedCSharp}",
    )
  }
}
