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
 * Nullable reference element: awaits to `KotlinStateFlow<string?>`. `.Value` reads through the
 * runtime's null-aware `nuget_stateflow_value_or_null`, and `await foreach` yields the null.
 */
suspend fun watchNickname(): StateFlow<String?> {
  delay(1)
  return nickname.asStateFlow()
}

/** Deterministic mutation for [watchNickname]; null clears the nickname. */
fun callNickname(name: String?) {
  nickname.value = name
}

private val napStreak: MutableStateFlow<Int?> = MutableStateFlow(null)

/** Nullable value element: awaits to `KotlinStateFlow<int?>`, which needs the boxed read. */
suspend fun watchNapStreak(): StateFlow<Int?> {
  delay(1)
  return napStreak.asStateFlow()
}

/** Deterministic mutation for [watchNapStreak]; null breaks the streak. */
fun countNapStreak(days: Int?) {
  napStreak.value = days
}

private val lapCat: MutableStateFlow<Cat?> = MutableStateFlow(null)

/** Nullable object element: awaits to `KotlinStateFlow<Cat?>`; each present value is new. */
suspend fun watchLapCat(): StateFlow<Cat?> {
  delay(1)
  return lapCat.asStateFlow()
}

/** Deterministic mutation for [watchLapCat]; null empties the lap. */
fun adoptLapCat(name: String?) {
  lapCat.value = name?.let { Cat(it) }
}

private var den: MutableStateFlow<Cat>? = null

/**
 * Nullable member: awaits to `KotlinStateFlow<Cat>?`, null while no den is open. The `Task`
 * completion tests the wire pointer before it wraps anything.
 */
suspend fun watchDen(): StateFlow<Cat>? {
  delay(1)
  return den?.asStateFlow()
}

/** Opens a den with [name] curled up inside, so [watchDen] hands back a holder. */
fun openDen(name: String) {
  den = MutableStateFlow(Cat(name))
}

/** Closes the den, so [watchDen] hands back null again. */
fun closeDen() {
  den = null
}
