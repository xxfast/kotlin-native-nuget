package dev.other.admitted

/**
 * ADR-066 §5 amendment fixture: the **positive** out-of-root case.
 *
 * `dev.other.admitted` sits outside `:test-library`'s `rootPackage`
 * (`io.github.xxfast.kotlin.native.nuget.test`) and is admitted by an explicit `include(...)`, so
 * it renders at `TestLibrary.Dev.Other.Admitted` — the full Kotlin package, every segment
 * PascalCased, under the assembly's root namespace.
 *
 * Deliberately a **new** package rather than a member of `dev.other.core`: that package is the
 * load-bearing *negative* case for four fixtures (`Newsroom.sponsor`/`airwave`, `CatCam.onSponsor`,
 * `Issue42Api`, `Issue42Derived`) and stays unadmitted. The two cases now sit side by side, one
 * segment apart, which is exactly the distinction the admission predicate has to make.
 *
 * Oreo endorses the sunbeam. Mylo endorses the food bowl.
 */
class Billboard(val slogan: String)
