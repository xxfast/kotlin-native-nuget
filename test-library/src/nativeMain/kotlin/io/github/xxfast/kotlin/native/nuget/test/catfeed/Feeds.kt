package io.github.xxfast.kotlin.native.nuget.test.catfeed

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-174: an interface whose `suspend`, `Flow` and `StateFlow` members are declared on `IFeed`
 * and reached through an `IFeed`-typed reference, whatever object is behind it.
 *
 * Every async shape the class route carries is here once: a string-returning suspend with a real
 * suspension point ([fetch]), an int-returning suspend with none ([count], the tight-loop leak
 * row), a Flow-returning method ([ticks]), a StateFlow property ([level]) and a DEFAULT Flow
 * member ([doubled]) whose body lives on the interface. [name] is the sync member `IFeed` already
 * declares.
 *
 * The package is `catfeed`, not `feed`: package `feed` would be namespace `TestLibrary.Feed`,
 * which collides with the `Feed` backing wrapper inside it. The file is `Feeds.kt` for the same
 * reason, so the top-level factories land on a static class `Feeds` rather than a second `Feed`.
 *
 * Oreo gets fed from the RSS feed, Mylo from the crate. Both of them want the doubled portion.
 */
interface Feed {
  suspend fun fetch(id: Int): String
  suspend fun count(): Int
  fun ticks(): Flow<Int>
  val level: StateFlow<Int>
  fun doubled(): Flow<Int> = ticks().map { it * 2 }
  fun name(): String
}

/** The ordinary exported implementer: projects every async member through its own class routes. */
class RssFeed : Feed {
  override suspend fun fetch(id: Int): String {
    delay(10.milliseconds)
    return "rss-$id"
  }

  override suspend fun count(): Int = 3

  override fun ticks(): Flow<Int> = flowOf(1, 2, 3)

  override val level: StateFlow<Int> = MutableStateFlow(1)

  override fun name(): String = "rss"
}

/**
 * The generic implementer: ADR-147 refuses the legacy async routes on a generic owner, so
 * `Crate<T>` projects none of the async members itself. ADR-174 Rule 7 gives it explicit
 * interface implementations over the interface's own dispatch exports.
 */
class Crate<T>(val item: T) : Feed {
  override suspend fun fetch(id: Int): String {
    delay(10.milliseconds)
    return "crate-$id"
  }

  override suspend fun count(): Int = 7

  override fun ticks(): Flow<Int> = flowOf(4, 5)

  override val level: StateFlow<Int> = MutableStateFlow(2)

  override fun name(): String = "crate"
}

/** Returns the interface type: C# receives the `Feed` backing wrapper around an `RssFeed`. */
fun makeFeed(): Feed = RssFeed()

/** Returns the interface type around a GENERIC implementer: dispatch must reach `Crate<Int>`. */
fun makeCrate(): Feed = Crate(1)

/**
 * ADR-159 side check, no test asserts on it: a generic class implementing a SYNC-only interface.
 * Neither `Named` nor `Tag<T>` has an async member, so neither should render `IAsyncDisposable`.
 */
interface Named {
  fun name(): String
}

class Tag<T>(val payload: T) : Named {
  override fun name(): String = "tag"
}
