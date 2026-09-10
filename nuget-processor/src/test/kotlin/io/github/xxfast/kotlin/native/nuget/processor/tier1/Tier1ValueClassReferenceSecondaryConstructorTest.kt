package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ROADMAP Phase 3: a *reference*-underlying value class with a secondary constructor.
 *
 * ADR-035's 2026-09-11 amendment lifts the secondary off the skip list. It is a plan-routed
 * constructor like any other: the export returns the underlying `Cat` as a fresh handle, and C#
 * delegates to its own positional record constructor by reconstructing that handle,
 * `: this(new Cat(CreateChecked_2(name)))`. The *primary* stays deferred, because a positional
 * `Wrapper(Cat Cat)` cannot coexist with a hand-written `Wrapper(Cat cat)` (CS0111), so no
 * `wrapper_create` crosses.
 *
 * The control is the sibling that always worked: a String-underlying value class with a secondary
 * constructor is plan-routed and must still render both its `Native_Create` imports.
 */
class Tier1ValueClassReferenceSecondaryConstructorTest {

  private val source: String = """
    package tier1.vcrefsec

    class Cat(val name: String)

    @JvmInline
    value class Wrapper(val cat: Cat) {
      constructor(name: String) : this(Cat(name))

      val label: String get() = cat.name
    }

    @JvmInline
    value class CatId(val id: String) {
      constructor(name: String, number: Int) : this("${'$'}name-${'$'}number")

      val length: Int get() = id.length
    }
  """.trimIndent()

  @Test
  fun `a reference-underlying value class binds its secondary constructor`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")

    val cs: String = result.generatedCSharp
    // The positional record header is untouched: the underlying stays the class handle itself.
    assertContains(cs, "public readonly record struct Wrapper(global::Interop.Cat Cat)")
    // The secondary imports the handle-returning export and checks the error slot like any other
    // plan-routed constructor.
    assertContains(
      cs,
      "private static extern IntPtr Native_Create_2(" +
          "[MarshalAs(UnmanagedType.LPUTF8Str)] string name, out IntPtr error);",
    )
    assertContains(cs, "private static IntPtr CreateChecked_2(string name)")
    // ...then delegates to the positional constructor by rebuilding the returned handle.
    assertContains(
      cs,
      "public Wrapper(string name) : this(new global::Interop.Cat(CreateChecked_2(name)))",
    )
    // The rest of the value class is unaffected: its computed property still binds.
    assertContains(cs, "public string Label =>")
  }

  @Test
  fun `the Kotlin half exports the secondary and not the deferred primary`() {
    val result = Tier1Harness.run(source)

    assertContains(result.generated, "export_wrapper_create_2")
    assertFalse(
      "export_wrapper_create(" in result.generated,
      "expected no export for the deferred primary; got: ${result.generated}",
    )
  }

  @Test
  fun `no secondary constructor is skipped`() {
    val result = Tier1Harness.run(source)

    val warnings: List<String> = result.kspWarnings.filter { warning ->
      "SKIPPED_VALUE_CLASS_SECONDARY_CONSTRUCTOR" in warning ||
          "REFERENCE_UNDERLYING_VALUE_CLASS_CONSTRUCTOR" in warning
    }
    assertTrue(warnings.isEmpty(), "expected no constructor skip warning; got: $warnings")
  }

  @Test
  fun `a String-underlying value class still renders both its constructors`() {
    val result = Tier1Harness.run(source)

    val cs: String = result.generatedCSharp
    assertContains(cs, "private static extern IntPtr Native_Create(")
    assertContains(cs, "private static extern IntPtr Native_Create_2(")
    assertContains(result.generated, "export_catid_create")
    assertContains(result.generated, "export_catid_create_2")
  }
}
