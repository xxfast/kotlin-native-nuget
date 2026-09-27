package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ADR-068 (2026-09-27 amendment): TOP-LEVEL `suspend fun` returning `StateFlow<T>`. No parent class
// owns a scope here, so the awaited holder's collections launch on the runtime's ad-hoc scope, the
// same one the top-level suspend call itself launches on. Each function genuinely suspends before
// handing back the one shared holder, so the outer `Task` is not vestigial.

private val purrCount: MutableStateFlow<Int> = MutableStateFlow(0)

private val napBuddy: MutableStateFlow<Cat> = MutableStateFlow(Cat("Oreo"))

private val nickname: MutableStateFlow<String?> = MutableStateFlow(null)

/** Primitive element: awaits to `KotlinStateFlow<int>`. */
suspend fun watchPurrCount(): StateFlow<Int> {
  delay(1)
  return purrCount.asStateFlow()
}

/** Deterministic mutation for [watchPurrCount]. */
fun purrMore(times: Int) {
  purrCount.value += times
}

/** Object element: awaits to `KotlinStateFlow<Cat>`; each `.Value` is a fresh wrapper. */
suspend fun watchNapBuddy(): StateFlow<Cat> {
  delay(1)
  return napBuddy.asStateFlow()
}

/** Deterministic mutation for [watchNapBuddy]. */
fun swapNapBuddy(name: String) {
  napBuddy.value = Cat(name)
}

/**
 * Nullable element: refused by name (`SKIPPED_UNSUPPORTED_RETURN`), not bound. The shared
 * `nuget_stateflow_value` this route reads through has no null arm yet.
 */
suspend fun watchNickname(): StateFlow<String?> {
  delay(1)
  return nickname.asStateFlow()
}
