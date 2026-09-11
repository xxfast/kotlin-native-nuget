package io.github.xxfast.kotlin.native.nuget.runtime

/**
 * ADR-127: the opt-in marker every public declaration in this module carries.
 *
 * The stability commitment is on the **C names**: the 66 `nuget_*` exports are the versioned ABI
 * a generated `Interop.cs` P/Invokes. The Kotlin names are for the code generator of the same
 * version, so calling them from hand-written Kotlin is opting into a surface that can change with
 * the generator. `@PublishedApi internal` cannot express this: it admits calls only from public
 * inline functions in the same module, and the generated code lives in another module.
 */
@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message = "This is the nuget-runtime's generator-facing surface. It is stable for the code " +
    "generator of the same version, not for hand-written code.",
)
@Retention(AnnotationRetention.BINARY)
public annotation class NugetRuntimeApi

/**
 * ADR-127's skew guard. The generated `CNameExports.kt` names this object in one line, so a
 * runtime whose ABI major differs (it would rename this to `NugetRuntimeAbi2`) fails the
 * consumer's compile with an unresolved reference instead of crossing the bridge and failing
 * later, at a P/Invoke, with `EntryPointNotFoundException`.
 *
 * The supported path cannot skew at all: the Gradle plugin pins the runtime and the processor
 * from one generated `PLUGIN_VERSION`. This anchor is for the author who overrides the version
 * anyway, through a dependency constraint or a `resolutionStrategy`.
 */
@NugetRuntimeApi
public object NugetRuntimeAbi1
