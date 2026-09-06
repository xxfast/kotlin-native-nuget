package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Issues #65/#66 on the **legacy** forward routes (ADR-078). A Kotlin parameter named after a C#
 * reserved word (`ref`, `params`), or named literally `error` next to the ADR-024 exception slot,
 * must render escaped on every route that predates the ordinary callable plan, not just on the
 * plan.
 *
 * The plan's own half is already green (`ForwardCirPlanProjectionTest`); the escape helper lives in
 * `Reserved.kt` and every legacy translator applies it where it copies a KSP identifier into a
 * [io.github.xxfast.kotlin.native.nuget.processor.cir.CirParameter] or into hand-built body text.
 *
 * One cell per legacy render site, because a fixture trimmed to the easiest route would go green
 * against a fix covering only that route. Each cell asserts the escaped spelling at **both** the
 * declaration and the native-call argument list: the two are printed from different code, so a fix
 * that moved one without the other would still not compile.
 *
 * Mirrors `test-library/.../test/routes/KeywordRoutesSample.kt`, whose `IntegrationTests
 * .KeywordRoutesTests` facts prove the same C# actually builds and runs.
 */
class Tier1LegacyRouteParameterNamesTest {

  private val fixture: String = """
    package tier1.keyword

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.flow

    suspend fun fetch(ref: Int): Int = ref + 1

    fun <T> put(ref: T): T = ref

    fun make(ref: Int): KeywordShape = Round(ref)

    sealed class KeywordShape

    data class Round(val r: Int) : KeywordShape()

    class KeywordTick(val n: Int)

    class KeywordRoutes {
      suspend fun load(params: String): String = params

      suspend fun poll(params: String): StateFlow<Int> = MutableStateFlow(params.length)

      fun watch(params: String): Flow<Int> = flow {
        (1..3).forEach { emit(it + params.length) }
      }

      fun state(error: Int): MutableStateFlow<Int> = MutableStateFlow(error)

      fun onEvent(ref: (KeywordTick) -> Unit) {
        ref(KeywordTick(7))
      }

      fun onFail(error: (KeywordTick) -> Unit) {
        error(KeywordTick(9))
      }
    }

    interface KeywordHandler {
      fun handle(params: String): String
    }

    abstract class KeywordBase : KeywordHandler

    class KeywordHandlerImpl : KeywordHandler {
      override fun handle(params: String): String = params.uppercase()
    }
  """.trimIndent()

  // `Flow` and `MutableStateFlow` must resolve for the flow routes to be classified at all; with
  // only kotlin-stdlib on the KSP libraries path both `watch` and `state` are skipped as
  // SKIPPED_UNSUPPORTED_TYPE and the two flow cells below would pass vacuously.
  private val generated: String by lazy {
    Tier1Harness.run(fixture, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))
      .generatedCSharp
  }

  /**
   * ADR-105 (issue #54): a sealed return is planned at every origin now, so this cell no longer
   * covers a legacy route -- it stays as the parity guard that moving `make` off the specialized
   * adapter kept the keyword escape (the plan projection's own escape, on the plan's `Native_Make`
   * import spelling).
   */
  @Test
  fun `the sealed return keeps its keyword parameter escaped on the plan`() {
    assertContains(
      generated,
      "private static extern IntPtr Native_Make(int @ref, out IntPtr error);",
    )
    assertContains(generated, "public static global::Interop.KeywordShape Make(int @ref)")
    assertContains(generated, "Native_Make(@ref, out IntPtr error);")
  }

  @Test
  fun `the generic function route escapes a keyword parameter in every instantiation`() {
    assertContains(generated, "public static T Put<T>(T @ref)")
    assertContains(
      generated,
      "private static extern int Put_int_native(int @ref, out IntPtr error);",
    )
    assertContains(
      generated,
      "private static extern IntPtr Put_object_native(IntPtr @ref, out IntPtr error);",
    )
    assertContains(generated, "Put_string_native((string)(object)@ref!, out error)")
    assertContains(generated, "Put_int_native((int)(object)@ref!, out error)")
    assertContains(generated, "IntPtr handle = ((INugetHandle)@ref!).Handle;")
  }

  @Test
  fun `the top level suspend route escapes a keyword parameter`() {
    assertContains(
      generated,
      "private static extern IntPtr FetchAsync_native(int @ref, IntPtr callback, IntPtr userData);",
    )
    assertContains(
      generated,
      "public static Task<int> FetchAsync(int @ref, CancellationToken cancellationToken = default)",
    )
    assertContains(generated, "FetchAsync_native(@ref, NugetThunks.NugetAsyncCallbackPtr")
  }

  @Test
  fun `the class suspend route escapes a keyword parameter`() {
    assertContains(
      generated,
      "string @params, IntPtr callback, IntPtr userData);",
    )
    assertContains(
      generated,
      "public Task<string> LoadAsync(string @params, " +
          "CancellationToken cancellationToken = default)",
    )
    assertContains(generated, "Native_LoadAsync(_handle, GetOrCreateScope(), @params, NugetThunks")
  }

  @Test
  fun `the flow route escapes a keyword parameter`() {
    assertContains(
      generated,
      "string @params, IntPtr onNext, IntPtr onComplete, IntPtr onError, IntPtr userData);",
    )
    assertContains(generated, "public KotlinFlow<int> Watch(string @params)")
    assertContains(
      generated,
      "Native_WatchCollect(_handle, GetOrCreateScope(), @params, onNext, onComplete, onError, " +
          "userData));",
    )
  }

  /**
   * The one route that needs the #66 rename rather than the #65 escape: the generated
   * MutableStateFlow write lambda declares its own `out IntPtr error`, so a user parameter named
   * `error` is CS0100 on the setter import and CS0841 inside the lambda.
   */
  @Test
  fun `the mutable state flow route renames a parameter that shadows the error slot`() {
    assertContains(
      generated,
      "private static extern IntPtr Native_StateCollect(IntPtr handle, IntPtr scopeHandle, " +
          "int error_,",
    )
    assertContains(
      generated,
      "private static extern IntPtr Native_StateValue(IntPtr handle, int error_);",
    )
    assertContains(
      generated,
      "private static extern void Native_StateSetValue(IntPtr handle, int error_, int value, " +
          "out IntPtr error);",
    )
    assertContains(generated, "public KotlinMutableStateFlow<int> State(int error_)")
    assertContains(generated, "Native_StateCollect(_handle, GetOrCreateScope(), error_, onNext")
    assertContains(generated, "() => Native_StateValue(_handle, error_),")
    assertContains(generated, "Native_StateSetValue(_handle, error_, v, out IntPtr error);")
  }

  @Test
  fun `the lambda parameter route escapes a keyword parameter and its pointer local`() {
    assertContains(
      generated,
      "private static extern void Native_OnEvent(IntPtr handle, IntPtr @refPtr, IntPtr userData, " +
          "out IntPtr error);",
    )
    assertContains(generated, "public void OnEvent(Action<KeywordTick> @ref)")
    assertContains(generated, "@ref(arg0);")
  }

  @Test
  fun `the lambda parameter route renames a callback that shadows the error slot`() {
    assertContains(
      generated,
      "private static extern void Native_OnFail(IntPtr handle, IntPtr error_Ptr, IntPtr " +
          "userData, out IntPtr error);",
    )
    assertContains(generated, "public void OnFail(Action<KeywordTick> error_)")
    assertContains(generated, "error_(arg0);")
  }

  @Test
  fun `the suspend state flow route escapes a keyword parameter`() {
    assertContains(
      generated,
      "string @params, IntPtr callback, IntPtr userData);",
    )
    assertContains(
      generated,
      "public Task<KotlinStateFlow<int>> PollAsync(string @params, " +
          "CancellationToken cancellationToken = default)",
    )
    assertContains(generated, "Native_PollAsync(_handle, GetOrCreateScope(), @params, NugetThunks")
  }

  /**
   * The abstract-member route. `KeywordBase` inherits `handle` from `KeywordHandler` without
   * declaring it, which is the only shape that reaches the abstract branch: an `abstract fun`
   * declared on the abstract class itself is dropped entirely (see the class doc of the fixture in
   * `test-library`).
   */
  @Test
  fun `the abstract member route escapes a keyword parameter`() {
    assertContains(generated, "public abstract string Handle(string @params);")
  }

  @Test
  fun `the interface declaration route escapes a keyword parameter`() {
    // Leading indentation, so the abstract-member cell's own `string Handle(string @params);`
    // cannot satisfy this one.
    assertContains(generated, "\n        string Handle(string @params);")
  }

  /** Control: the class method beside the interface is on the ordinary plan and stays escaped. */
  @Test
  fun `the class method control stays escaped`() {
    assertContains(generated, "public virtual string Handle(string @params)")
  }

  /**
   * The complement of the positive cells: no bare spelling survives anywhere. Each entry is
   * written so the escaped form is not a substring of it (`int @ref` does not contain `int ref`),
   * and so a legitimate C# `ref` argument keyword (`Interlocked.Exchange(ref _handle, ...)`) or the
   * shared runtime's own `IntPtr errorPtr` delegate slot is not a false positive.
   */
  @Test
  fun `no legacy render site leaves a keyword or error slot name bare`() {
    val offenders: List<String> = generated.lines()
      .map { line -> line.trim() }
      .filter { line -> BARE.any { bare -> line.contains(bare) } || line in BARE_CALLS }

    assertEquals(emptyList(), offenders, "bare parameter names: ${offenders.joinToString("\n")}")
  }

  private companion object {
    val BARE: List<String> = listOf(
      "int ref", "long ref", "float ref", "double ref", "bool ref", "string ref", "T ref",
      "IntPtr ref,", "IntPtr refPtr",
      "string params", "GetOrCreateScope(), params,",
      "int error,", "int error)", "GetOrCreateScope(), error,",
      "Native_StateValue(_handle, error)", "Native_StateSetValue(_handle, error,",
      "IntPtr errorPtr, IntPtr userData, out IntPtr error",
      "Make_native(ref,", "FetchAsync_native(ref,", "(object)ref!", "(INugetHandle)ref!",
    )

    /** Whole trimmed lines, because the escaped form here is a strict superset of the bare one. */
    val BARE_CALLS: List<String> = listOf("ref(arg0);", "error(arg0);")
  }
}
