package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-188: a C# 14 extension property and a classic extension method of one C# name on one
 * receiver both declare, but every `cat.NameOrStray` access is then ambiguous (CS9339). The
 * property is skipped on both halves with `SHADOWED_BY_EXTENSION_FUNCTION`, the function keeps the
 * name, and the hint names `@CSharpName` (ADR-179), which this test also proves keeps both.
 */
class Tier1ExtensionPropertyFunctionClashTest {

  private val sources: Map<String, String> = mapOf(
    "Cat.kt" to """
      package tier1.extclash

      import io.github.xxfast.kotlin.native.nuget.annotations.CSharpName

      class Cat(val name: String)

      fun Cat?.nameOrStray(): String = this?.name ?: "stray"
      val Cat?.nameOrStray: String get() = this?.name ?: "stray"

      fun Cat.homeLabel(): String = name
      @CSharpName("HomeTag")
      val Cat.homeLabel: String get() = name
    """.trimIndent(),
  )

  @Test
  fun `an extension property sharing a C# name with an extension function is a named skip`() {
    val result = Tier1Harness.run(sources, libraries = listOf(csharpNameLibrary))

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp

    // The function keeps the name on both halves.
    assertContains(csharp, "public static string NameOrStray(this global::Interop.Cat? receiver)")
    // The property is gone from both halves: no export, no import, no extension member.
    assertFalse(
      kotlin.contains("cat_get_nameOrStray"),
      "property must not export; generated=$kotlin",
    )
    assertFalse(csharp.contains("cat_get_nameOrStray"), "property must not import; csharp=$csharp")
    assertFalse(csharp.contains("public string NameOrStray"), "no extension member; csharp=$csharp")

    val warning: List<String> =
      result.kspWarnings.filter { it.contains("SHADOWED_BY_EXTENSION_FUNCTION") }
    assertEquals(
      1,
      warning.size,
      "expected exactly one clash warning; kspWarnings=${result.kspWarnings}",
    )
    assertContains(warning.single(), "SKIPPED_UNSUPPORTED_PROPERTY")
    assertContains(warning.single(), "the extension function `nameOrStray`")
    assertContains(warning.single(), "CS9339")
    assertContains(warning.single(), "@CSharpName")
  }

  @Test
  fun `a CSharpName on the extension property keeps both`() {
    val result = Tier1Harness.run(sources, libraries = listOf(csharpNameLibrary))

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val csharp: String = result.generatedCSharp
    assertContains(csharp, "public static string HomeLabel(this global::Interop.Cat receiver)")
    assertContains(csharp, "extension(global::Interop.Cat receiver)")
    assertContains(csharp, "public string HomeTag")
    assertFalse(
      result.kspWarnings.any { it.contains("homeLabel") },
      "a renamed property must not warn; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * Two extension properties rendering one C# name into one class were fatal as two `GetTag`
   * methods; as two `public string Tag` extension members they must stay fatal, not reach the
   * consumer's compiler.
   */
  @Test
  fun `two extension properties of one C# name on one receiver stay a fatal collision`() {
    val result = Tier1Harness.run(
      mapOf(
        "Cat.kt" to """
          package tier1.extpropnames

          import io.github.xxfast.kotlin.native.nuget.annotations.CSharpName

          class Cat(val name: String)

          val Cat.tag: String get() = name
          @CSharpName("Tag")
          val Cat.label: String get() = name
        """.trimIndent(),
      ),
      libraries = listOf(csharpNameLibrary),
    )

    assertTrue(
      result.kspErrors.any {
        it.contains("ERROR_CSHARP_SIGNATURE_COLLISION") && it.contains("Tag")
      },
      "expected a fatal name collision; kspErrors=${result.kspErrors}",
    )
  }

  private fun runSplit(sources: Map<String, String>): Tier1Result = Tier1Harness.run(
    sources,
    processorOptions = mapOf(
      "nuget.rootPackage" to "tier1.extsplit",
      "nuget.namespace" to "Split",
    ),
  )

  /** The body of one `namespace X { ... }` block, up to the next top-level `namespace` header. */
  private fun namespaceBlock(cs: String, namespace: String): String {
    val header: String = "namespace $namespace\n"
    assertContains(cs, header)
    return cs.substringAfter(header).substringBefore("\nnamespace ")
  }

  private fun shadowWarnings(result: Tier1Result): List<String> =
    result.kspWarnings.filter { it.contains("SHADOWED_BY_EXTENSION_FUNCTION") }

  /**
   * An unexported receiver homes `{Receiver}Extensions` on the DECLARING package (ADR-126), so a
   * property in one package and a function in another render into two classes in two namespaces.
   * A consumer importing one namespace sees no ambiguity, so both bind.
   */
  @Test
  fun `an unexported-receiver pair in two packages binds both`() {
    val result: Tier1Result = runSplit(
      mapOf(
        "A.kt" to """
          package tier1.extsplit.a

          val String.tag: Int get() = 1
        """.trimIndent(),
        "B.kt" to """
          package tier1.extsplit.b

          fun String.tag(): Int = 2
        """.trimIndent(),
      ),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertEquals(
      emptyList(),
      shadowWarnings(result),
      "two namespaces must not clash; kspWarnings=${result.kspWarnings}",
    )
    val csharp: String = result.generatedCSharp
    val aBlock: String = namespaceBlock(csharp, "Split.A")
    val bBlock: String = namespaceBlock(csharp, "Split.B")
    assertContains(aBlock, "extension(string receiver)")
    assertContains(aBlock, "public int Tag\n")
    assertContains(bBlock, "public static int Tag(this string receiver)")
    assertFalse(aBlock.contains("public static int Tag("), "function leaked into A: $aBlock")
    assertFalse(bBlock.contains("public int Tag\n"), "property leaked into B: $bBlock")
  }

  /** The same pair in ONE package shares one class, so the ADR-188 skip stays. */
  @Test
  fun `an unexported-receiver pair in one package is still a named skip`() {
    val result: Tier1Result = runSplit(
      mapOf(
        "A.kt" to """
          package tier1.extsplit.a

          val String.tag: Int get() = 1
          fun String.tag(): Int = 2
        """.trimIndent(),
      ),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertEquals(1, shadowWarnings(result).size, "kspWarnings=${result.kspWarnings}")
    val aBlock: String = namespaceBlock(result.generatedCSharp, "Split.A")
    assertContains(aBlock, "public static int Tag(this string receiver)")
    assertFalse(aBlock.contains("public int Tag\n"), "property must be skipped: $aBlock")
  }

  /**
   * An exported receiver homes `{Receiver}Extensions` on the RECEIVER's package, so a property and
   * a function declared in two other packages still merge into one `CatExtensions`, where every
   * `cat.Tag` is CS9339. The skip stays across packages.
   */
  @Test
  fun `an exported-receiver pair in two packages is still a named skip`() {
    val result: Tier1Result = runSplit(
      mapOf(
        "Cat.kt" to """
          package tier1.extsplit.model

          class Cat(val name: String)
        """.trimIndent(),
        "A.kt" to """
          package tier1.extsplit.a

          import tier1.extsplit.model.Cat

          val Cat.tag: Int get() = 1
        """.trimIndent(),
        "B.kt" to """
          package tier1.extsplit.b

          import tier1.extsplit.model.Cat

          fun Cat.tag(): Int = 2
        """.trimIndent(),
      ),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val warning: List<String> = shadowWarnings(result)
    assertEquals(1, warning.size, "kspWarnings=${result.kspWarnings}")
    assertContains(warning.single(), "the extension function `tag`")
    val modelBlock: String = namespaceBlock(result.generatedCSharp, "Split.Model")
    assertContains(modelBlock, "public static int Tag(this global::Split.Model.Cat receiver)")
    assertFalse(
      result.generatedCSharp.contains("public int Tag\n"),
      "property must be skipped: ${result.generatedCSharp}",
    )
  }

  /**
   * ADR-188 amendment: a class INSTANCE method `X(int)` beside a C# 14 extension property `X` on
   * that class makes `cat.X` a method group (CS0428), so the extension property is unreachable by
   * member syntax. Kotlin itself resolves the pair (properties and functions are two namespaces),
   * so the property is skipped with the ADR-188 warning naming the member function, which keeps
   * the name.
   */
  @Test
  fun `a class member function keeps the name over an extension property`() {
    val result = Tier1Harness.run(
      """
      package tier1.extmemberfun

      class Cat(val lives: Int) {
        fun grooming(times: Int): Int = lives * times
      }

      val Cat.grooming: Int get() = lives
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val warning: List<String> = shadowWarnings(result)
    assertEquals(1, warning.size, "kspWarnings=${result.kspWarnings}")
    assertContains(warning.single(), "SKIPPED_UNSUPPORTED_PROPERTY")
    assertContains(warning.single(), "the member function `Cat.grooming`")
    assertContains(warning.single(), "CS0428")
    assertFalse(warning.single().contains("extension function"), warning.single())

    val getter = "library_tier1_extmemberfun__cat_get_grooming"
    assertFalse(result.generated.contains(getter), "no property export; ${result.generated}")
    assertFalse(result.generatedCSharp.contains(getter), "no property import")
    assertFalse(
      Regex("""public\s+int\s+Grooming\s*\{""").containsMatchIn(result.generatedCSharp),
      "no extension property member; csharp=${result.generatedCSharp}",
    )
    assertContains(result.generatedCSharp, "public int Grooming(int times)")
  }

  /** The same rule for a value-class receiver, whose member functions are C# instance methods. */
  @Test
  fun `a value class member function keeps the name over an extension property`() {
    val result = Tier1Harness.run(
      """
      package tier1.extvaluefun

      @JvmInline
      value class Lives(val count: Int) {
        fun grooming(times: Int): Int = count * times
      }

      val Lives.grooming: Int get() = count
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val warning: List<String> = shadowWarnings(result)
    assertEquals(1, warning.size, "kspWarnings=${result.kspWarnings}")
    assertContains(warning.single(), "the member function `Lives.grooming`")

    val getter = "library_tier1_extvaluefun__lives_get_grooming"
    assertFalse(result.generated.contains(getter), "no property export; ${result.generated}")
    assertFalse(result.generatedCSharp.contains(getter), "no property import")
    assertFalse(
      Regex("""public\s+int\s+Grooming\s*\{""").containsMatchIn(result.generatedCSharp),
      "no extension property member; csharp=${result.generatedCSharp}",
    )
    assertTrue(
      Regex("""public int Grooming\(int times\)""").containsMatchIn(result.generatedCSharp),
      "the member function keeps the name; csharp=${result.generatedCSharp}",
    )
  }

  /**
   * Kotlin compiles `val Cat.x` beside `val Cat?.x` in one package and resolves them by static
   * type. C# cannot hold both: `extension(Cat c) { int X }` beside `extension(Cat? c) { int X }`
   * is CS0102 at the declaration, and neither is a safe survivor (dropping either changes the
   * value one receiver type reads). Fatal, naming both declarations, rather than an unlocated
   * internal failure blaming expect/actual.
   */
  @Test
  fun `an extension property on a receiver and on its nullable twin is a fatal collision`() {
    val result = Tier1Harness.run(
      """
      package tier1.extnullabletwin

      class Cat(val lives: Int)

      val Cat.x: Int get() = 1
      val Cat?.x: Int get() = 2
      """.trimIndent(),
    )

    val collisions: List<String> = result.kspErrors.filter {
      it.contains("ERROR_CSHARP_SIGNATURE_COLLISION")
    }
    assertEquals(1, collisions.size, "expected one collision; kspErrors=${result.kspErrors}")
    val message: String = collisions.single()
    assertContains(message, "`val Cat.x`")
    assertContains(message, "`val Cat?.x`")
    assertFalse(
      result.kspErrors.any { it.contains("ERROR_INTERNAL_GENERATOR_FAILURE") },
      "no internal failure; kspErrors=${result.kspErrors}",
    )
  }
}
