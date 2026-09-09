package io.github.xxfast.kotlin.native.nuget.test.models

/**
 * Issue #128: an opt-in-marked enum, declared **one Gradle module away** so the fixture matches the
 * reported shape (marker and marked type both behind a klib boundary, reached as a constructor
 * parameter type in `test-library`).
 *
 * A same-module marker reproduces the bug just as well (verified against Kotlin 2.4.10), so the
 * module hop is about matching the report, not about reachability.
 *
 * The type itself is skipped for the reason [CatteryInternalApi] already covers. What this cell
 * exists for is the *consumer* of it: `test-library/.../issue128/Issue128Sample.kt`, whose
 * constructor takes one of these with a default. Kotlin propagates the opt-in requirement from the
 * declared parameter TYPE at every arity, so no shorter call repairs it.
 *
 * Oreo is brushed daily. Mylo is brushed weekly, under protest.
 */
@CatteryInternalApi
enum class Grooming { DAILY, WEEKLY }
