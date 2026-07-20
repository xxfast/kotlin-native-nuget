package dev.other.core

/**
 * ADR-066 admission-predicate guard. Deliberately outside `:test-library`'s `rootPackage`
 * (`io.github.xxfast.kotlin.native.nuget.test`), so a reference to this type from an in-scope
 * declaration is reachable but must never be admitted: it should skip with
 * `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`, whose hint must name the exact fix
 * (`include("dev.other.core")`), and it must not turn into a build break or an opaque handle.
 */
class Advertisement(val sponsor: String)
