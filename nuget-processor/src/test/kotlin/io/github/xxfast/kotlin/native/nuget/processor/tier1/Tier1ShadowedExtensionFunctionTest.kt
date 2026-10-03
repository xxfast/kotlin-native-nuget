package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An extension FUNCTION beside an applicable member of the same name. The export body used to say
 * `receiver.y()` with `y` imported by simple name, and Kotlin resolves that call to the MEMBER, so
 * the C# `FooExtensions.Y(foo)` silently returned the member's result. Every extension function is
 * now imported under a per-(package, name) alias and called through it, which reaches the extension
 * in every shadowed shape without any applicability matching.
 *
 * Tier 1 cannot observe which callee ran; it pins the spelling, and that the generated file still
 * compiles. The IntegrationTests `ShadowedExtensionFunctionTests` cell pins the value.
 */
class Tier1ShadowedExtensionFunctionTest {

  private val sources: Map<String, String> = mapOf(
    "Foo.kt" to """
      package tier1.shadowedfn.model

      open class Base {
        fun n(a: Number): String = "member"
      }

      class Foo : Base() {
        fun y(): String = "member"
        fun z(a: Int = 0): String = "member"
        fun w(a: Int): String = "member"
        fun v(vararg a: Int): String = "member"
        fun <T> g(a: T): String = "member"
        val p: () -> String = { "member" }
      }
    """.trimIndent(),
    "Ext.kt" to """
      package tier1.shadowedfn.ext

      import tier1.shadowedfn.model.Foo

      // The six shapes where the member wins a plain call: exact, member default, member vararg,
      // member generic, member property of function type, member with a supertype parameter.
      fun Foo.y(): String = "extension"
      fun Foo.z(): String = "extension"
      fun Foo.v(a: Int): String = "extension"
      fun Foo.g(a: String): String = "extension"
      fun Foo.p(): String = "extension"
      fun Foo.n(a: Int): String = "extension"

      // The false-positive control: the member `w(a: Int)` is not applicable to a Long.
      fun Foo.w(a: Long): String = "extension"

      // An overload pair in one package: one alias must cover both.
      fun Foo.pair(): String = "short"
      fun Foo.pair(a: Int): String = "long"

      // A defaulted extension parameter: every ADR-164 dispatch arm must call through the alias.
      fun Foo.labelled(label: String = "x"): String = label

      // A plain top-level property sharing a name with an extension function in the same package:
      // its simple-name import must survive beside the aliased one.
      val q: Int = 1
      fun Foo.q(): Int = 2

      fun String.shout(): String = "ext"
    """.trimIndent(),
    "Other.kt" to """
      package tier1.shadowedfn.other

      // The same simple name as `tier1.shadowedfn.ext.shout`, from a second package. A `String`
      // receiver homes each C# class in its declaring package, so the two do not collide in C#.
      fun String.shout(): String = "other"
    """.trimIndent(),
  )

  // Each package its own C# namespace, so the two `String.shout` classes do not collide in C#.
  private val options: Map<String, String> = mapOf("nuget.rootPackage" to "tier1.shadowedfn")

  private val extAlias: String = "nuget_ext_tier1__shadowedfn__ext__"

  @Test
  fun `every shadowed extension function exports and calls the extension through its alias`() {
    val result = Tier1Harness.run(sources, options)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    // The member keeps its own export beside the extension.
    assertContains(kotlin, "@CName(\"library_model__foo_y\")")
    listOf("y", "z", "v", "g", "p", "n", "w").forEach { name ->
      assertContains(kotlin, "@CName(\"library_ext__foo_$name\")")
      assertContains(kotlin, "import tier1.shadowedfn.ext.`$name` as $extAlias$name")
      assertContains(kotlin, ".get().$extAlias$name(")
    }
    // No extension is imported by its bare simple name any more, so none can be called by it. The
    // members' own exports still say `.get().y(`, which is why the check is on the import.
    listOf("y", "z", "v", "g", "p", "n", "w", "pair", "labelled").forEach { name ->
      assertFalse(
        kotlin.contains("import tier1.shadowedfn.ext.$name\n"),
        "extension $name must not be imported by its simple name; generated=$kotlin",
      )
    }
  }

  @Test
  fun `one alias covers an overload pair and every defaulted dispatch arm`() {
    val result = Tier1Harness.run(sources, options)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"library_ext__foo_pair\")")
    assertContains(kotlin, "@CName(\"library_ext__foo_pair_2\")")
    assertContains(kotlin, "${extAlias}pair()")
    assertContains(kotlin, "${extAlias}pair(a)")
    assertContains(kotlin, "${extAlias}labelled()")
    assertContains(kotlin, "${extAlias}labelled(label = ")
  }

  @Test
  fun `two same-named extensions from two packages each call their own`() {
    val result = Tier1Harness.run(sources, options)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"library_other__string_shout\")")
    assertContains(kotlin, "@CName(\"library_ext__string_shout\")")
    assertContains(kotlin, ".nuget_ext_tier1__shadowedfn__other__shout(")
    assertContains(kotlin, ".${extAlias}shout(")
  }

  @Test
  fun `a default-package extension is imported under its alias`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "Root.kt" to """
          fun String.wag(): String = this
        """.trimIndent(),
      ),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    assertContains(kotlin, "import `wag` as nuget_ext_wag")
    assertContains(kotlin, "receiver.nuget_ext_wag()")
  }

  @Test
  fun `a plain import of the same qualified name survives beside the alias`() {
    val result = Tier1Harness.run(sources, options)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    assertContains(kotlin, "import tier1.shadowedfn.ext.q\n")
    assertContains(kotlin, "import tier1.shadowedfn.ext.`q` as ${extAlias}q")
    assertContains(kotlin, ".get().${extAlias}q(")
  }
}
