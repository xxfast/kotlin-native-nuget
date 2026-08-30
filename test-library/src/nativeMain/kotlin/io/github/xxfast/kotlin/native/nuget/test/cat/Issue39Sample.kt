package io.github.xxfast.kotlin.native.nuget.test.cat

/**
 * Fixture for [#39](https://github.com/xxfast/kotlin-native-nuget/issues/39): a `List<T>` property
 * on a *sealed subclass*.
 *
 * `CirSealedRenderer.kt:28` emits every sealed-subclass property as
 * `public ${prop.type} ${prop.name} => ${prop.getter};` unconditionally. That is correct for the
 * scalar [Issue39State.Loaded.refreshing] getter, whose body is a single expression, and wrong for
 * [Issue39State.Loaded.items], whose getter body is the multi-statement `listHandle`/`count`/`for`/
 * `Dispose`/`return` block the top-level-class path (`Cat.toys`) puts inside a `get { ... }` block.
 *
 * Fixture disjointness with the sibling issues in the same ROADMAP cluster:
 * - no nullable property anywhere on the subclass (that is #38's nested-nullable export bug),
 * - no `StateFlow`/`Flow` of the sealed base (#40),
 * - a single package/namespace (#41),
 * - no supertype outside this file beyond the sealed base (#42).
 *
 * Instances are produced by top-level functions, mirroring `Observation.kt`'s `openBox`/`peekBox`.
 */
data class Issue39Item(val name: String, val count: Int)

sealed class Issue39State {
  data class Loaded(val items: List<Issue39Item>, val refreshing: Boolean) : Issue39State()

  data object Loading : Issue39State()
}

/** The cell under test: a sealed subclass carrying both a `List<T>` and a scalar property. */
fun loadedCats(): Issue39State =
  Issue39State.Loaded(listOf(Issue39Item("Oreo", 1), Issue39Item("Mylo", 2)), refreshing = true)

/** Keeps the `data object` arm of the sealed hierarchy reachable from C#. */
fun loadingCats(): Issue39State = Issue39State.Loading
