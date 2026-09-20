package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * A PLAIN `Flow<T?>` (not a `StateFlow`), one member per element kind.
 *
 * Research found by reading that only `StateFlow` computed `isNullableElement`, so a plain
 * `Flow<T?>` bound as a non-null `KotlinFlow<T>` and the Kotlin half boxed each emission as
 * `value as Any`. Measured, then fixed (2026-09-20): the null emission faulted the stream instead
 * of arriving as null. The gap was per ROUTE, not per element type, so all three element kinds are
 * here: an interface (the arm this item is about), a nullable reference scalar and a nullable
 * value scalar. Each emits non-null, then null, then non-null, so a consumer can tell "the null
 * arrived" from "the stream died at item 2" from "the stream ended early".
 *
 * Its own class, and its own file, so a generator oddity on this route lands in its own generated
 * type instead of inside [PetSitter], which the ADR-136 identity facts depend on.
 *
 * Oreo naps by the window and counts whoever goes past; sometimes it is nobody at all.
 */
class PassersBy {
  /** `Flow<Pet?>`: the interface element, the case the nullable element arm is about. */
  fun petsPassingBy(): Flow<Pet?> = flow {
    emit(strayPet())
    emit(null)
    emit(strayPet())
  }

  /** `Flow<String?>`: nullable REFERENCE element on the same route, no interface involved. */
  fun remarksPassingBy(): Flow<String?> = flowOf("a tail", null, "a shadow")

  /** `Flow<Int?>`: nullable VALUE element, where a plain `int` would need no conversion at all. */
  fun napsPassingBy(): Flow<Int?> = flowOf(1, null, 3)

  /**
   * A flow that FAILS mid-stream, with a type and a message the consumer can read.
   *
   * The nullable-element defect surfaced in C# as a bare `KotlinException : Kotlin error`, which
   * reads like the Flow error path throwing away detail the ordinary call path keeps. It does not:
   * both go through `NugetErrorNative.BuildException`, and that text was the honest rendering of a
   * Kotlin `NullPointerException` whose own `message` is null (`buildError` falls back to the
   * literal "Kotlin error") and whose type is absent from the mapping table. This member is the
   * control that says so: a named exception with a message keeps both across the same callback.
   *
   * Oreo watches a squirrel, and then the window bangs shut.
   */
  fun boomsPassingBy(): Flow<String> = flow {
    emit("a tail")
    throw IllegalStateException("boom")
  }
}
