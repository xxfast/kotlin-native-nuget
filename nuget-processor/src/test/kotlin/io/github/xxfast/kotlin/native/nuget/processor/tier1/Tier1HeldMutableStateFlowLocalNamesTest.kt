package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * A HELD (non-suspend) `MutableStateFlow<T>` method (`CirFlowRenderer.renderHeldStateFlowMethod`)
 * spells its setter lambda parameter `v`, the setter's `out IntPtr error` and the collect lambda's
 * `onNext`..`userData` literally. All of them are declared inside a lambda, so a method parameter
 * of the same name is shadowed there, not redeclared, and LangVersion 14 (the version every
 * generated `Interop.cs` builds at) accepts that; no user parameter is read inside those lambdas,
 * so the shadowing is also the intended binding. The method-scope locals (`flow`, `owned`,
 * `collectScope`) and the compare-and-set lambda's `expect`/`update` already go through
 * `freshName`. The inferred non-compiling C# does not occur; this pins it.
 *
 * One cell per write-arm element kind: scalar, `String`, enum by ordinal, object handle, nullable
 * reference and nullable value. A top-level function returning `MutableStateFlow` is refused by
 * design (`SKIPPED_UNSUPPORTED_RETURN`), so there is no static held cell. The proof is a real
 * `dotnet build` of the generated C#.
 */
class Tier1HeldMutableStateFlowLocalNamesTest {

  private val fixture: String = """
    package tier1.heldlocals

    import kotlinx.coroutines.flow.MutableStateFlow

    enum class Mood { CALM, LOUD }

    class Bell(val tone: Int)

    class Dial {
      fun counts(
        v: Int,
        error: Int,
        expect: Int,
        update: Int,
        onNext: Int,
        onComplete: Int,
        onError: Int,
        userData: Int,
      ): MutableStateFlow<Int> = MutableStateFlow(v)

      fun labels(v: String, error: String, prev: String, next: String): MutableStateFlow<String> =
        MutableStateFlow(v)

      fun moods(v: Int, error: Int, transform: Int): MutableStateFlow<Mood> =
        MutableStateFlow(Mood.CALM)

      fun bells(v: Int, error: Int, flow: Int, owned: Int): MutableStateFlow<Bell> =
        MutableStateFlow(Bell(v))

      fun blanks(v: String?, error: Int, collectScope: Int): MutableStateFlow<String?> =
        MutableStateFlow(v)

      fun maybeCounts(v: Int?, error: Int): MutableStateFlow<Int?> = MutableStateFlow(v)
    }
  """.trimIndent()

  @Test
  fun `a held MutableStateFlow method with parameters named after its lambdas compiles in C#`() {
    val result: Tier1Result = Tier1Harness.run(
      fixture,
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    assertTrue(result.kspSucceeded, "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin must compile; got: ${result.compileErrors}")
    // Each cell must reach the held route, or the build below proves nothing. `error` arrives as
    // `error_` (the issue #66 rename), so it never meets the setter's `out IntPtr error`.
    val csharp: String = result.generatedCSharp
    listOf(
      "KotlinMutableStateFlow<int> Counts(int v, int error_, int expect, int update, " +
        "int onNext, int onComplete, int onError, int userData)",
      "KotlinMutableStateFlow<string> Labels(string v, string error_, string prev, string next)",
      "KotlinMutableStateFlow<global::Interop.Mood> Moods(int v, int error_, int transform)",
      "KotlinMutableStateFlow<global::Interop.Bell> Bells(int v, int error_, int flow, int owned)",
      "KotlinMutableStateFlow<string?> Blanks(string? v, int error_, int collectScope)",
      "KotlinMutableStateFlow<int?> MaybeCounts(int? v, int error_)",
    ).forEach { signature -> assertContains(csharp, signature) }
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      namespace Consumer
      {
          public static class Probe
          {
              public static object Run(global::Interop.Dial dial) => dial;
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
