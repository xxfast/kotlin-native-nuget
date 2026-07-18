package io.github.xxfast.kotlin.native.nuget.processor.tier1

/**
 * ADR-060 Tier 1's cinterop stub — **load-bearing code, not test scaffolding.**
 *
 * `CNameExports.kt` imports Kotlin/Native-only API (`kotlin.native.CName`,
 * `kotlin.experimental.ExperimentalNativeApi`, `kotlinx.cinterop.*`) that has no JVM
 * implementation, so compiling it for the JVM (see [Tier1Harness]) needs stand-ins for those
 * declarations. Every K-cell's compile error lives in the *user-code* half of a generated
 * export (a nullable return, a dropped parameter, a raw `List`) — never in this plumbing — but
 * a signature that drifted from Kotlin/Native's real one could still turn a real defect green
 * by accident (ADR-060 Risk / inferred claim #2). Treat any change here as a review-worthy
 * change to the harness's fidelity, the same as a change to [Tier1Harness] itself. The `clinic`
 * corpus (Tier 3, compiled against the *real* Kotlin/Native target) is the independent
 * backstop if this file is ever wrong.
 *
 * Declaring into the `kotlin` package requires `-Xallow-kotlin-package`
 * (`allowKotlinPackage = true` on the [org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments]
 * built by [Tier1Harness]) — verified: without it, kotlinc refuses with "only the Kotlin
 * standard library is allowed to use the 'kotlin' package".
 *
 * Kotlin requires one `package` declaration per file, so this is three files' worth of source,
 * each written out separately by [Tier1Harness] before compiling.
 */
internal object Tier1CinteropStub {

  private val cNameStub: String = """
    package kotlin.native

    @Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
    @Retention(AnnotationRetention.BINARY)
    annotation class CName(val externName: String = "", val shortName: String = "")
  """.trimIndent()

  private val experimentalNativeApiStub: String = """
    package kotlin.experimental

    @RequiresOptIn
    @Retention(AnnotationRetention.BINARY)
    annotation class ExperimentalNativeApi
  """.trimIndent()

  // The exact surface `NugetProcessor`'s generated file unconditionally imports/uses today
  // (`NugetProcessor.kt`'s `generateCNameWrappers`, `Helpers.kt`, `GenericClassExports.kt`'s
  // `addNugetErrorHelperExports`): a StableRef-backed handle, the two-call nullable/error-out
  // pattern's `reinterpret<COpaquePointerVar>().pointed.value = ...`, and `asStableRef()`.
  // Real Kotlin/Native's `COpaquePointer` is `CPointer<out CPointed>` and `reinterpret`/
  // `pointed` operate over that generic `CPointer<T : CVariable>` hierarchy; none of that
  // hierarchy is reproduced here because no generated K-cell export needs it to typecheck —
  // this stub only has to satisfy the exact call shapes `NugetProcessor` emits, not model
  // cinterop's real typed-memory-access machinery. Bodies are `TODO()`/unchecked casts
  // throughout: Tier 1 only *compiles* generated code, it never runs it, so behavioural
  // correctness here would be effort spent on a claim nothing asserts.
  private val cinteropStub: String = """
    package kotlinx.cinterop

    @RequiresOptIn
    @Retention(AnnotationRetention.BINARY)
    annotation class ExperimentalForeignApi

    open class COpaquePointer

    open class COpaquePointerVar : COpaquePointer()

    // Real Kotlin/Native exposes a memory-typed variable's `.value` accessor as a top-level
    // extension property (several overloads, one per `*Var` type), not a class member — which
    // is exactly why `NugetProcessor.kt` unconditionally emits `import kotlinx.cinterop.value`
    // (a member import, not a class-qualified one) for every generated file. A class member
    // here would compile the call site fine but leave that import unresolved.
    var COpaquePointerVar.value: COpaquePointer?
      get() = null
      set(_) {}

    // ADR-061's nullable-primitive method/extension-function return out-parameter
    // (`valueOut.reinterpret<IntVar>().pointed.value = result`) needs a stand-in for the specific
    // `*Var` type it reinterprets to — real Kotlin/Native's `IntVar` is not a `COpaquePointerVar`
    // subtype, but (per this file's own stated scope) this stub only has to satisfy the exact
    // call shape the generator emits, not the real typed-memory-access hierarchy. Only `IntVar`
    // is stubbed: the one blittable numeric width ADR-061's Tier 1 cell (5) actually exercises.
    class IntVar : COpaquePointerVar()

    var IntVar.value: Int
      get() = 0
      set(_) {}

    @Suppress("UNCHECKED_CAST")
    fun <T : COpaquePointer> COpaquePointer.reinterpret(): T = this as T

    // Generic (mirroring real Kotlin/Native's `val <T : CVariable> CPointer<T>.pointed: T`) so
    // `.pointed` preserves the reinterpreted `*Var` type instead of widening it back to
    // `COpaquePointerVar` — needed for `IntVar.value` (below) to resolve on the result.
    val <T : COpaquePointerVar> T.pointed: T
      get() = this

    class StableRef<T : Any> private constructor(private val referent: T) {
      companion object {
        fun <T : Any> create(value: T): StableRef<T> = StableRef(value)
      }

      fun get(): T = referent
      fun dispose() {}
      fun asCPointer(): COpaquePointer = TODO("Tier 1 compiles generated code, it never runs it")
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> COpaquePointer.asStableRef(): StableRef<T> =
      StableRef.create(this as T)
  """.trimIndent()

  /** relative file name -> file content, ready for [Tier1Harness] to write to disk and compile. */
  val files: List<Pair<String, String>> = listOf(
    "Tier1Stub_KotlinNative.kt" to cNameStub,
    "Tier1Stub_ExperimentalNativeApi.kt" to experimentalNativeApiStub,
    "Tier1Stub_Cinterop.kt" to cinteropStub,
  )
}
