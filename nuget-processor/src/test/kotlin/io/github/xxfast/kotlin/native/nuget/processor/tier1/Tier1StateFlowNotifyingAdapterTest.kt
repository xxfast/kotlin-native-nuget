package io.github.xxfast.kotlin.native.nuget.processor.tier1

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-206: a module that declares a `StateFlow<T>` member gets a generated
 * `KotlinStateFlowObservable<T>` (`INotifyPropertyChanged` + `IDisposable`) and an
 * `AsNotifying()` extension on `KotlinStateFlow<T>`, beside the `KotlinStateFlow<T>` it adapts. A
 * module with only cold flows gets neither.
 */
class Tier1StateFlowNotifyingAdapterTest {

  private val coroutines: List<File> = listOf(Tier1Classpath.kotlinxCoroutinesCore)

  /** Declaration lines only: a `///` remark may name the adapter without declaring it. */
  private fun Tier1Result.codeLines(): List<String> =
    generatedCSharp.lines().filterNot { it.trimStart().startsWith("///") }

  @Test
  fun `a StateFlow member emits the notifying adapter and it compiles for a consumer`() {
    val result = Tier1Harness.run(
      """
      package tier1.notifying

      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow

      class Litter {
        val level: StateFlow<Int> = MutableStateFlow(3)
        val mood: StateFlow<String> = MutableStateFlow("sleepy")
      }
      """.trimIndent(),
      libraries = coroutines,
    )
    val code: List<String> = result.codeLines()
    assertTrue(
      code.any { it.contains("public sealed class KotlinStateFlowObservable<T>") },
      "no KotlinStateFlowObservable<T> declaration:\n${result.generatedCSharp}",
    )
    assertTrue(
      code.any { it.contains("KotlinStateFlowObservable<T> AsNotifying<T>(") } &&
        code.any { it.contains("this KotlinStateFlow<T> flow, SynchronizationContext? context") },
      "no AsNotifying extension on KotlinStateFlow<T>:\n${result.generatedCSharp}",
    )
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop;

      namespace Consumer
      {
          public static class Probe
          {
              public static int Run(
                  global::Interop.Litter litter,
                  global::System.Threading.SynchronizationContext context)
              {
                  using global::Interop.KotlinStateFlowObservable<int> level =
                      litter.Level.AsNotifying();
                  using global::Interop.KotlinStateFlowObservable<string> mood =
                      litter.Mood.AsNotifying(context);
                  global::System.ComponentModel.INotifyPropertyChanged notifying = mood;
                  notifying.PropertyChanged += (sender, args) => { };
                  global::System.Threading.Tasks.Task completion = level.Completion;
                  return level.Value + mood.Value.Length;
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  @Test
  fun `a module with only a cold Flow emits no notifying adapter`() {
    val result = Tier1Harness.run(
      """
      package tier1.coldonly

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.emptyFlow

      class Litter {
        fun purrs(): Flow<Int> = emptyFlow()
      }
      """.trimIndent(),
      libraries = coroutines,
    )
    val code: List<String> = result.codeLines()
    assertTrue(
      code.any { it.contains("public class KotlinFlow<T>") },
      "expected the cold flow helper to be emitted:\n${result.generatedCSharp}",
    )
    assertTrue(
      code.none { it.contains("class KotlinStateFlowObservable<T>") },
      "a module without StateFlow must not declare the adapter:\n${result.generatedCSharp}",
    )
    assertTrue(
      code.none { it.contains("AsNotifying<T>(") },
      "a module without StateFlow must not declare AsNotifying:\n${result.generatedCSharp}",
    )
  }
}
