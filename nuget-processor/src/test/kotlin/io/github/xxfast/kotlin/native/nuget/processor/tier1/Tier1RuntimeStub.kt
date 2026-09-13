package io.github.xxfast.kotlin.native.nuget.processor.tier1

/**
 * ADR-127 Tier 1's runtime stub, the sibling of [Tier1CinteropStub] and equally load-bearing.
 *
 * The generated `CNameExports.kt` no longer declares `NugetHandles`, `NugetError`, `buildError`,
 * the .NET ticks conversions or `NugetCSharpBridge`: they come from the `nuget-runtime` klib,
 * which is a Kotlin/Native artifact and cannot be on a JVM compile classpath. This is the same
 * fidelity bargain the cinterop stub makes: a stub that drifts from the real runtime turns a real
 * defect green, and the `clinic` corpus compiled against real Kotlin/Native is the backstop.
 *
 * Only the surface the generated file calls is reproduced, with the same signatures the runtime
 * publishes. Bodies are stubs: Tier 1 compiles generated code, it never runs it.
 */
internal object Tier1RuntimeStub {

  private val runtimeApiStub: String = """
    package io.github.xxfast.kotlin.native.nuget.runtime

    @RequiresOptIn(level = RequiresOptIn.Level.ERROR)
    @Retention(AnnotationRetention.BINARY)
    annotation class NugetRuntimeApi

    @NugetRuntimeApi
    object NugetRuntimeAbi1
  """.trimIndent()

  private val runtimeSurfaceStub: String = """
    @file:OptIn(ExperimentalForeignApi::class, NugetRuntimeApi::class)

    package io.github.xxfast.kotlin.native.nuget.runtime

    import kotlin.concurrent.AtomicLong
    import kotlin.time.Duration
    import kotlin.time.Instant
    import kotlinx.cinterop.COpaquePointer
    import kotlinx.cinterop.ExperimentalForeignApi

    @NugetRuntimeApi
    object NugetHandles {
      val live: AtomicLong = AtomicLong(0L)
      fun retain(value: Any): COpaquePointer = TODO()
      fun release(handle: COpaquePointer) {}
    }

    @NugetRuntimeApi
    data class NugetError(
      val type: String,
      val message: String,
      val stackTrace: String,
      val cause: NugetError? = null,
    )

    @NugetRuntimeApi
    fun buildError(e: Throwable): NugetError = TODO()

    @NugetRuntimeApi
    interface NugetCSharpBridge {
      val nugetToken: COpaquePointer
    }

    @NugetRuntimeApi
    fun Instant.toDotNetTicks(): Long = TODO()

    @NugetRuntimeApi
    fun instantFromDotNetTicks(ticks: Long): Instant = TODO()

    @NugetRuntimeApi
    fun Duration.toDotNetTicks(): Long = TODO()

    @NugetRuntimeApi
    fun durationFromDotNetTicks(ticks: Long): Duration = TODO()
  """.trimIndent()

  /**
   * ADR-128's `launchForCSharp` / `collectForCSharp`, in a **third** file because they name
   * `CoroutineScope`: `Tier1CoroutineFreeModuleTest` compiles with
   * `coroutinesOnCompileClasspath = false`, so putting these in [runtimeSurfaceStub] would make
   * that test fail on an unresolved `kotlinx.coroutines` rather than on the thing it asserts.
   */
  private val runtimeLaunchStub: String = """
    @file:OptIn(ExperimentalForeignApi::class, NugetRuntimeApi::class)

    package io.github.xxfast.kotlin.native.nuget.runtime

    import kotlinx.cinterop.COpaquePointer
    import kotlinx.cinterop.ExperimentalForeignApi
    import kotlinx.coroutines.CoroutineScope

    @NugetRuntimeApi
    fun launchForCSharp(
      scope: CoroutineScope,
      callbackPtr: COpaquePointer,
      userData: COpaquePointer,
      body: suspend () -> COpaquePointer?,
    ): COpaquePointer = TODO()

    @NugetRuntimeApi
    fun collectForCSharp(
      scope: CoroutineScope,
      onNextPtr: COpaquePointer,
      onCompletePtr: COpaquePointer,
      onErrorPtr: COpaquePointer,
      userData: COpaquePointer,
      body: suspend (emit: (COpaquePointer?) -> Unit) -> Unit,
    ): COpaquePointer = TODO()
  """.trimIndent()

  /** relative file name -> file content, ready for [Tier1Harness] to write to disk and compile. */
  val files: List<Pair<String, String>> = listOf(
    "Tier1Stub_NugetRuntimeApi.kt" to runtimeApiStub,
    "Tier1Stub_NugetRuntime.kt" to runtimeSurfaceStub,
  )

  /** Appended by [Tier1Harness] only when coroutines are on the compile classpath. */
  val coroutineFiles: List<Pair<String, String>> = listOf(
    "Tier1Stub_NugetLaunch.kt" to runtimeLaunchStub,
  )
}
