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

    // ADR-069's method/extension/object/companion `Boolean?` return uses this same reinterpret
    // shape with `BooleanVar` in place of `IntVar` (kept as a distinct stub so a signature drift
    // in either is caught independently).
    class BooleanVar : COpaquePointerVar()

    var BooleanVar.value: Boolean
      get() = false
      set(_) {}

    // ADR-079's `Dosage?` (a Double-underlying value class) method return writes its unboxed
    // underlying through the same reinterpret shape, so the Double width needs a stand-in too.
    class DoubleVar : COpaquePointerVar()

    var DoubleVar.value: Double
      get() = 0.0
      set(_) {}

    // ADR-098 amendment (boundary nullability part C): a `Char?` method return writes its `.code`
    // as a `UShort` through the same reinterpret shape. `UShortVar` rather than a `CharVar` because
    // kotlinx.cinterop has no `CharVar` at all (`unresolved reference` on Kotlin/Native 2.4.10),
    // which is why the wire is a blittable `ushort` the C# side casts.
    class UShortVar : COpaquePointerVar()

    var UShortVar.value: UShort
      get() = 0.toUShort()
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

    // ADR-084's bridge factory reinterprets each incoming slot pointer as a C function pointer and
    // invokes it. Real Kotlin/Native spells that `CPointer<CFunction<(A) -> R>>` with one `invoke`
    // extension per arity; per this file's stated scope the stand-in only has to satisfy the
    // emitted call shapes (a slot is arity 0-2 plus its trailing context pointer), so `CFunction`
    // is itself the pointer here and the arities are spelled out.
    class CFunction<F : Function<*>> : COpaquePointer()

    // Generic in EVERY parameter, which is how real Kotlin/Native spells these (one extension per
    // arity, no position pinned to a concrete type). They used to pin the trailing parameter to
    // `COpaquePointer` because every callback ended at its echoed context pointer; ADR-161 appended
    // a NULLABLE error slot after it, and a pinned trailing `COpaquePointer` would not match a
    // `CFunction<(..., COpaquePointer, COpaquePointer?) -> R>`. Adding a second, nullable-tailed
    // overload per arity is not an option: the receiver's type argument erases away, so the two
    // would be a platform declaration clash. Fully generic is both closer to the real signature and
    // the one spelling that admits both.
    //
    // A suspend export's completion callback is the widest shape reached without an error slot
    // (`(COpaquePointer?, COpaquePointer?, Byte, COpaquePointer) -> Unit`), and a three-payload
    // user-code callback plus ctx plus errOut is the widest shape reached with one.
    fun <P0, R> CFunction<(P0) -> R>.invoke(arg0: P0): R =
      TODO("Tier 1 compiles generated code, it never runs it")

    fun <P0, P1, R> CFunction<(P0, P1) -> R>.invoke(arg0: P0, arg1: P1): R =
      TODO("Tier 1 compiles generated code, it never runs it")

    fun <P0, P1, P2, R> CFunction<(P0, P1, P2) -> R>.invoke(
      arg0: P0,
      arg1: P1,
      arg2: P2,
    ): R = TODO("Tier 1 compiles generated code, it never runs it")

    fun <P0, P1, P2, P3, R> CFunction<(P0, P1, P2, P3) -> R>.invoke(
      arg0: P0,
      arg1: P1,
      arg2: P2,
      arg3: P3,
    ): R = TODO("Tier 1 compiles generated code, it never runs it")

    fun <P0, P1, P2, P3, P4, R> CFunction<(P0, P1, P2, P3, P4) -> R>.invoke(
      arg0: P0,
      arg1: P1,
      arg2: P2,
      arg3: P3,
      arg4: P4,
    ): R = TODO("Tier 1 compiles generated code, it never runs it")
  """.trimIndent()

  // ADR-084 stage 2: the bridge object's cleaner and the forced-collection support export. Real
  // Kotlin/Native's `createCleaner` is `@ExperimentalNativeApi` and returns an opaque `Cleaner`;
  // `GC.collect()` is `@NativeRuntimeApi`. Only the shapes the generator emits are reproduced.
  private val cleanerStub: String = """
    package kotlin.native.ref

    class Cleaner internal constructor()

    fun <T> createCleaner(argument: T, block: (T) -> Unit): Cleaner = Cleaner()
  """.trimIndent()

  private val runtimeStub: String = """
    package kotlin.native.runtime

    @RequiresOptIn
    @Retention(AnnotationRetention.BINARY)
    annotation class NativeRuntimeApi

    object GC {
      fun collect() {}
    }
  """.trimIndent()

  // ADR-120: `kotlin.concurrent.AtomicLong` is Kotlin/Native-only (the JVM's `kotlin.concurrent`
  // has no such class), and the generated `NugetHandles` counter object uses it. Signatures match
  // the Native stdlib's: a `Long` constructor argument, a mutable `value`, and the two
  // increment/decrement operations the counter calls.
  private val concurrentStub: String = """
    package kotlin.concurrent

    class AtomicLong(var value: Long) {
      fun incrementAndGet(): Long = ++value
      fun decrementAndGet(): Long = --value
    }
  """.trimIndent()

  /** relative file name -> file content, ready for [Tier1Harness] to write to disk and compile. */
  val files: List<Pair<String, String>> = listOf(
    "Tier1Stub_KotlinNative.kt" to cNameStub,
    "Tier1Stub_Concurrent.kt" to concurrentStub,
    "Tier1Stub_ExperimentalNativeApi.kt" to experimentalNativeApiStub,
    "Tier1Stub_Cinterop.kt" to cinteropStub,
    "Tier1Stub_Cleaner.kt" to cleanerStub,
    "Tier1Stub_Runtime.kt" to runtimeStub,
  )
}
