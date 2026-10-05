package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * A value class's own method and getter keep the ADR-014 no-errorOut ABI. Their nullable results
 * used to fail the whole KSP run (the value-class emitter's `Nullable` arm required an error slot),
 * then skipped by name; they now bind on the ordinary member route's own wire per family, with no
 * error slot: a reference type rides the null pointer, a has-value type the `bool` result plus a
 * `valueOut` out slot on the value-class `DllImport`.
 *
 * The same renderer, `valueClassMemberExpression`, spelled every NON-null handle-shaped result as
 * the bare native call (`IReadOnlyList<string> Names() => Native_Names(Name)`, an `IntPtr` where a
 * list was declared), and the Kotlin half had no arm at all for an interface, an `Instant`/
 * `Duration`, a `Uuid` or a value-class result, so those non-null cells are pinned here too.
 *
 * `@JvmInline` is only for the JVM harness (ADR-060's Tier 1 constraint).
 *
 * Mylo's collar tag has his name on it; his nickname is a matter of opinion.
 */
class Tier1ValueClassNullableResultTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.vcnullable

      import kotlin.time.Duration
      import kotlin.time.Duration.Companion.minutes
      import kotlin.uuid.ExperimentalUuidApi
      import kotlin.uuid.Uuid

      class Pompom(val colour: String)
      enum class Mood { GRUMPY, HAPPY }
      interface Pet { val name: String }
      class Cat(override val name: String) : Pet

      @JvmInline
      value class Dose(val grams: Int)

      @JvmInline
      value class Nick(val text: String)

      @OptIn(ExperimentalUuidApi::class)
      @JvmInline
      value class Tag(val name: String) {
        fun label(): String? = name.takeIf { it.isNotEmpty() }
        fun count(): Int? = name.length.takeIf { it > 0 }
        fun grumpy(): Boolean? = null
        fun mood(): Mood? = Mood.HAPPY.takeIf { name.isNotEmpty() }
        fun initial(): Char? = name.firstOrNull()
        fun nap(): Duration? = 5.minutes.takeIf { name.isNotEmpty() }
        fun chip(): Uuid? = null
        fun pompom(): Pompom? = Pompom(name).takeIf { name.isNotEmpty() }
        fun pet(): Pet? = Cat(name).takeIf { name.isNotEmpty() }
        fun names(): List<String>? = listOf(name).takeIf { name.isNotEmpty() }
        fun bytes(): ByteArray? = name.encodeToByteArray().takeIf { name.isNotEmpty() }
        fun mishap(): Throwable? = IllegalStateException(name).takeIf { name.isNotEmpty() }
        fun dose(): Dose? = Dose(name.length).takeIf { name.isNotEmpty() }
        fun nick(): Nick? = Nick(name).takeIf { name.isNotEmpty() }
        val nickname: String? get() = name.takeIf { it.isNotEmpty() }
        val age: Int? get() = name.length.takeIf { it > 0 }

        fun ownPompom(): Pompom = Pompom(name)
        fun ownPet(): Pet = Cat(name)
        fun allNames(): List<String> = listOf(name)
        fun allBytes(): ByteArray = name.encodeToByteArray()
        fun longNap(): Duration = 9.minutes
        fun ownChip(): Uuid = Uuid.NIL
        fun ownDose(): Dose = Dose(name.length)
        fun ownNick(): Nick = Nick(name)
        fun upper(): String = name.uppercase()
      }

      fun tag(): Tag = Tag("Mylo")
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    )
  }

  private val cs: String get() = result.generatedCSharp

  @Test
  fun `the fixture plans, compiles and its generated C# builds`() {
    assertTrue(result.kspSucceeded, "expected KSP to succeed; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(
      result.kspWarnings.none { it.contains("tier1.vcnullable.Tag.") },
      "expected no Tag member to skip; kspWarnings=${result.kspWarnings}",
    )
    Tier1CSharpCompile.assertCompiles(
      result, "public static class Consumer { }", allowUnsafe = true,
    )
  }

  @Test
  fun `a nullable reference result rides the null pointer with no error slot`() {
    assertContains(cs, "public string? Label() => Marshal.PtrToStringUTF8(Native_Label(Name));")
    assertContains(
      cs, "public string? Nickname => Marshal.PtrToStringUTF8(Native_GetNickname(Name));",
    )
    assertContains(
      result.generated,
      "public fun export_library_vcnullable__tag_label(`value`: String): String? = run {",
    )
  }

  @Test
  fun `a nullable has-value result reads the bool and the valueOut out slot`() {
    assertContains(
      cs,
      "public int? Count() => Native_Count(Name, out int valueOut) ? valueOut : (int?)null;",
    )
    assertContains(
      cs,
      "public int? Age => Native_GetAge(Name, out " +
          "int valueOut) ? valueOut : (int?)null;",
    )
    assertContains(cs, "out int valueOut);")
    assertContains(cs, "[MarshalAs(UnmanagedType.I1)] out bool valueOut")
    assertContains(
      cs,
      "? (global::Interop.Vcnullable.Mood)valueOut " +
          ": (global::Interop.Vcnullable.Mood?)null;",
    )
    assertContains(cs, "? (char)valueOut : (char?)null;")
    assertContains(cs, "? new global::System.TimeSpan(valueOut) : (global::System.TimeSpan?)null;")
    assertContains(
      cs,
      "? new global::Interop.Vcnullable.Dose(valueOut) " +
          ": (global::Interop.Vcnullable.Dose?)null;",
    )
  }

  @Test
  fun `a nullable handle result is guarded against the null handle`() {
    val pompom: String = cs.substringAfter("public global::Interop.Vcnullable.Pompom? Pompom()")
      .substringBefore("\n        }")
    assertContains(pompom, "IntPtr nativeResult = Native_Pompom(Name);")
    assertContains(
      pompom,
      "return nativeResult == IntPtr.Zero ? null : " +
          "new global::Interop.Vcnullable.Pompom(nativeResult, out _);",
    )
    assertContains(cs, "public global::Interop.Vcnullable.IPet? Pet()")
    assertContains(cs, "public IReadOnlyList<string>? Names()")
    assertContains(cs, "public byte[]? Bytes()")
    assertContains(cs, "public global::System.Guid? Chip()")
    assertContains(cs, "public global::Interop.Vcnullable.Nick? Nick()")
    assertContains(
      cs,
      "return nativeResult == IntPtr.Zero ? null : NugetErrorNative.BuildException(nativeResult);",
    )
  }

  @Test
  fun `a non-null handle result reconstructs instead of returning the raw handle`() {
    assertContains(
      cs,
      "public global::Interop.Vcnullable.Pompom OwnPompom() => " +
          "new global::Interop.Vcnullable.Pompom(Native_OwnPompom(Name), out _);",
    )
    assertContains(cs, "public IReadOnlyList<string> AllNames() => NugetMarshal.ReadList<string>(")
    assertContains(cs, "public byte[] AllBytes() => NugetMarshal.ReadBytes(Native_AllBytes(Name));")
    assertContains(
      cs,
      "public global::System.TimeSpan LongNap() " +
          "=> new global::System.TimeSpan(Native_LongNap(Name));",
    )
    assertContains(cs, "public global::System.Guid OwnChip() => global::System.Guid.Parse(")
    assertContains(
      cs,
      "public global::Interop.Vcnullable.Dose OwnDose() " +
          "=> new global::Interop.Vcnullable.Dose(Native_OwnDose(Name));",
    )
    assertContains(cs, "public global::Interop.Vcnullable.IPet OwnPet()")
  }

  /**
   * A GENERIC value class's own member with a `T?` result. The open point the nullable-result arms
   * left: whether such a member is planned at all, and if so whether it binds or skips by name.
   * It must never reach the C# renderer's no-arm `error(...)`.
   */
  @Test
  fun `a generic value class member with a nullable type-parameter result never crashes`() {
    val generic: Tier1Result = Tier1Harness.run(
      """
      package tier1.vcgeneric

      @JvmInline
      value class Box<T>(val item: T) {
        fun maybe(): T? = item
        fun label(): String? = null
      }

      fun box(): Box<String> = Box("Mylo")
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    )

    assertTrue(generic.kspSucceeded, "expected KSP to succeed; got: ${generic.kspErrors}")
    assertTrue(generic.compiledClean, "expected a clean compile; got: ${generic.compileErrors}")
    val cs: String = generic.generatedCSharp
    // Answered: not planned at all. A value class over a bare `T` has no value-class wire, so the
    // whole class skips by name before any member is planned, and nothing reaches the renderer.
    assertTrue(
      !Regex("""public [^\n]* Maybe\(""").containsMatchIn(cs),
      "expected no Box.Maybe member; generated=$cs",
    )
    assertTrue(
      generic.kspWarnings.any {
        it.contains("Skipping tier1.vcgeneric.Box:") && it.contains("no value-class wire")
      },
      "expected the generic value class to skip by name; kspWarnings=${generic.kspWarnings}",
    )
    Tier1CSharpCompile.assertCompiles(
      generic, "public static class Consumer { }", allowUnsafe = true,
    )
  }
}
