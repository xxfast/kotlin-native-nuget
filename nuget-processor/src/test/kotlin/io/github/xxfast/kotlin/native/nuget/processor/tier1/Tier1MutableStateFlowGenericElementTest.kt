package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-071 over ADR-208: a `MutableStateFlow` whose element is a closed generic class instantiation
 * (`MutableStateFlow<Box<String>>`) is settable from C#, like any other object-handle element. The
 * write seam used to spell its element by bare qualified name on the Kotlin half
 * (`asStableRef<pkg.Box>()`, which does not compile for a generic class), so ADR-208 part D bound
 * it read-only. It now reads the element at its applied spelling (`pkg.Box<kotlin.String>`), the
 * one ADR-199 and ADR-208 already read a generic handle at, on the property, held-return and
 * awaited routes, and a generic sealed element (`MutableStateFlow<Outcome<Int>>`) rides the same
 * spelling.
 *
 * A generic VALUE class is not a handle: its member stays refused whole.
 *
 * Oreo swaps the box on the shelf. Mylo was in the old one.
 */
class Tier1MutableStateFlowGenericElementTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      mapOf(
        "Cells.kt" to """
          package tier1.cells

          import kotlin.jvm.JvmInline
          import kotlinx.coroutines.flow.MutableStateFlow

          class Box<T>(val value: T)

          sealed class Outcome<out T> {
            class Ok<T>(val v: T) : Outcome<T>()
            class Err(val e: String) : Outcome<Nothing>()
          }

          @JvmInline
          value class VCrate<T>(val item: T)

          class Shelf {
            val current: MutableStateFlow<Box<String>> = MutableStateFlow(Box("a"))
            val maybe: MutableStateFlow<Box<String>?> = MutableStateFlow(null)
            val outcome: MutableStateFlow<Outcome<Int>> = MutableStateFlow(Outcome.Ok(1))
            fun held(): MutableStateFlow<Box<Int>> = MutableStateFlow(Box(1))
            suspend fun awaited(): MutableStateFlow<Box<Int>> = MutableStateFlow(Box(1))
            val crate: MutableStateFlow<VCrate<Int>> = MutableStateFlow(VCrate(1))
          }
        """.trimIndent(),
      ),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  private val box = "global::Interop.Cells.Box"

  @Test
  fun `a generic instance element is settable on the property, held and awaited routes`() {
    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp.withoutDocComments()
    listOf(
      "public KotlinMutableStateFlow<$box<string>> Current",
      "public KotlinMutableStateFlow<$box<string>?> Maybe",
      "public KotlinMutableStateFlow<global::Interop.Cells.Outcome<int>> Outcome",
      "public KotlinMutableStateFlow<$box<int>> Held()",
      "Task<KotlinMutableStateFlow<$box<int>>> AwaitedAsync(",
    ).forEach { signature ->
      assertContains(cs, signature, message = "expected `$signature` in Interop.cs")
    }
    listOf("current", "maybe", "outcome", "held", "awaited").forEach { member ->
      assertTrue(
        result.kspWarnings.none { it.contains("[nuget:") && it.contains("$member: ") },
        "expected no diagnostic for $member; kspWarnings=${result.kspWarnings}",
      )
    }
  }

  /** The Kotlin half reads the written element, and the held flow, at the applied spelling. */
  @Test
  fun `the write seam reads the element at its applied Kotlin spelling`() {
    val kotlin: String = result.generated
    val applied = "tier1.cells.Box<kotlin.String>"
    assertContains(kotlin, "value.asStableRef<$applied>().get()")
    assertContains(kotlin, "expect.asStableRef<$applied>().get()")
    // A nullable element is the object-handle arm's nullable pointer, unwrapped null-safely.
    assertContains(kotlin, "value?.asStableRef<$applied>()?.get()")
    assertContains(kotlin, "value.asStableRef<tier1.cells.Outcome<kotlin.Int>>().get()")
    assertContains(
      kotlin,
      "flowHandle.asStableRef<kotlinx.coroutines.flow.MutableStateFlow<" +
        "tier1.cells.Box<kotlin.Int>>>().get()",
    )
    assertFalse(
      Regex("""asStableRef<tier1\.cells\.(Box|Outcome)>\(""").containsMatchIn(kotlin),
      "a generic class has no bare spelling: asStableRef<pkg.Box>() does not compile",
    )
  }

  /** A generic value class is no handle: the member stays refused whole, named. */
  @Test
  fun `a generic value class element stays refused`() {
    val cs: String = result.generatedCSharp.withoutDocComments()
    assertTrue(
      cs.lines().none { line -> line.trimStart().startsWith("public") && " Crate" in line },
      "expected no Crate member; got: ${cs.lines().filter { " Crate" in it }}",
    )
    assertFalse(result.generated.contains("_crate_"), "expected no export for crate")
    assertTrue(
      result.kspWarnings.any {
        it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name}]") &&
          it.contains("Shelf.crate")
      },
      "expected crate to be a named skip; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `the consumer sets, compares and updates through the generic element`() {
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System.Threading.Tasks;
      using Interop;
      using Interop.Cells;

      namespace Consumer
      {
          public static class Probe
          {
              public static async Task<string> Run(Shelf shelf, Box<string> mine, Box<int> seven)
              {
                  KotlinMutableStateFlow<Box<string>> current = shelf.Current;
                  using Box<string> before = current.Value;
                  current.Value = mine;
                  bool swapped = current.CompareAndSet(mine, before);
                  current.Update(_ => mine);

                  KotlinMutableStateFlow<Box<string>?> maybe = shelf.Maybe;
                  maybe.Value = null;
                  maybe.Value = mine;

                  KotlinMutableStateFlow<Outcome<int>> outcome = shelf.Outcome;
                  using var ok = new Outcome.Ok<int>(2);
                  outcome.Value = ok;

                  using KotlinMutableStateFlow<Box<int>> held = shelf.Held();
                  held.Value = seven;
                  using KotlinMutableStateFlow<Box<int>> awaited = await shelf.AwaitedAsync();
                  awaited.Value = seven;
                  using Box<int> now = awaited.Value;
                  return before.Value + swapped + now.Value;
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
