package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ROADMAP Phase 3: a *reference*-underlying value class with a secondary constructor.
 *
 * ADR-035 defers the reference-underlying primary, so this shape used to fall through the plan
 * into the pre-plan `buildConstructor`/`renderReferenceValueClass` pair, which emitted a
 * `: this(CreateChecked(...))` handing an `IntPtr` to the class-typed positional parameter (CS1503)
 * against a Kotlin export that returned the raw underlying object rather than a `StableRef`
 * pointer. No fixture ever had one, so the route shipped broken. It is deleted: the struct keeps
 * only its positional record constructor, and the skipped secondary says so by name.
 *
 * The control is the sibling that *does* work: a String-underlying value class with a secondary
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
  fun `a reference-underlying value class emits only its positional record constructor`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")

    val cs: String = result.generatedCSharp
    // The positional record header is untouched: the underlying stays the class handle itself.
    assertContains(cs, "public readonly record struct Wrapper(global::Interop.Cat Cat)")
    // ...and nothing else constructs one. Both the `CreateChecked` helper and the native import
    // that fed it are gone, on both halves of the bridge.
    assertFalse(
      "wrapper_create" in cs,
      "expected no reference-underlying constructor import; got: $cs",
    )
    val wrapperSection: String = cs
      .substringAfter("record struct Wrapper")
      .substringBefore("record struct CatId")
    assertFalse(
      "CreateChecked" in wrapperSection,
      "expected no CreateChecked helper inside Wrapper; got: $cs",
    )
    assertFalse(
      "export_wrapper_create" in result.generated,
      "expected no Kotlin constructor export; got: ${result.generated}",
    )

    // The rest of the value class is unaffected: its computed property still binds.
    assertContains(cs, "public string Label =>")
  }

  @Test
  fun `the skipped secondary constructor is named in a warning`() {
    val result = Tier1Harness.run(source)

    val warnings: List<String> = result.kspWarnings.filter { warning ->
      warning.contains(
        ForwardDiagnosticKind.SKIPPED_VALUE_CLASS_SECONDARY_CONSTRUCTOR.name,
      )
    }
    assertEquals(1, warnings.size, "expected exactly one skip warning; got: ${result.kspWarnings}")
    assertContains(warnings.single(), "tier1.vcrefsec.Wrapper.<init>_2")
    assertContains(
      warnings.single(),
      "a value class over a reference underlying carries no constructor across the bridge",
    )
    assertContains(warnings.single(), "construct the underlying and wrap it")
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
