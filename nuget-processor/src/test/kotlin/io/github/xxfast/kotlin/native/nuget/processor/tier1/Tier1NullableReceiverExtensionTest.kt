package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-105 amendment (2026-09-11): an extension function whose RECEIVER is a *nullable* exported
 * handle (`fun Cat?.nameOrStray()`) binds. The planner already lowers the receiver to one pointer
 * slot; both emitters now spell the nullable form the ADR-062 parameter slots already spell:
 * Kotlin reads the handle back with `receiver?.asStableRef<Cat>()?.get()` (typing the chain `Cat?`,
 * which resolves the `Cat?` extension), and C# renders `this Cat? receiver`, passing
 * `receiver?._handle ?? IntPtr.Zero` so a null receiver crosses as a null pointer.
 *
 * The control is a non-null receiver on the same type: it keeps the plain `asStableRef<Cat>().get()`
 * / `receiver._handle` shape, so the nullable arms are provably additive.
 */
class Tier1NullableReceiverExtensionTest {

  private val source: String = """
    package tier1.nullablereceiver

    class Cat(val name: String)

    fun Cat?.nameOrStray(): String = this?.name ?: "stray"

    fun Cat.loud(): String = name.uppercase()
  """.trimIndent()

  @Test
  fun `a nullable handle receiver dereferences through a safe call`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.compiledClean,
      "expected the fixture to compile; got: ${result.compileErrors}",
    )
    assertTrue(
      result.generated.contains(
        "receiver?.asStableRef<tier1.nullablereceiver.Cat>()?.get().nameOrStray()",
      ),
      "expected the export to read the nullable receiver back through a safe StableRef chain; " +
          "generated=${result.generated}",
    )
  }

  @Test
  fun `a nullable handle receiver renders as a C# extension on the nullable type`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.generatedCSharp.contains(
        "public static string NameOrStray(this global::Interop.Cat? receiver)",
      ),
      "expected a C# extension method on the nullable handle; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("NameOrStray") }}",
    )
    assertTrue(
      result.generatedCSharp.contains(
        "Native_NameOrStray(receiver?._handle ?? IntPtr.Zero, out IntPtr error)",
      ),
      "expected a null receiver to cross as IntPtr.Zero; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Native_NameOrStray") }}",
    )
  }

  /** The control: a non-null receiver on the same type keeps the plain handle shape. */
  @Test
  fun `a non-null receiver on the same type is unchanged`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.generated.contains(
        "receiver.asStableRef<tier1.nullablereceiver.Cat>().get().loud()",
      ),
      "expected the non-null receiver to keep its direct StableRef read; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.generatedCSharp.contains(
        "public static string Loud(this global::Interop.Cat receiver)",
      ),
      "expected the non-null C# extension to stay on the non-nullable type; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Loud") }}",
    )
  }
}
