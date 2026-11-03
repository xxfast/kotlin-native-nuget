package io.github.xxfast.kotlin.native.nuget.test.cattery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList

/*
 * ADR-230 amendment: a flow `var` on every owner with no C# instance scope, set from C#: the top
 * level (on the `Feedings` file facade), an `object`, a default and a named companion, an enum
 * body and an enum companion. A sealed `object` arm beside them sets through its singleton's
 * handle, as any arm does. Each owner reads its flows back in Kotlin, so a setter that kept the
 * old flow shows up.
 *
 * Oreo waits by the bowl at every feeding time. Mylo checks the pantry.
 */

/** A top-level cold flow `var`. */
var feedingTimes: Flow<Int> = flowOf(7)

/** A top-level nullable cold flow `var`. */
var lateSnack: Flow<String>? = null

/** Every feeding time Kotlin sees now. */
suspend fun feedings(): String = feedingTimes.toList().joinToString()

/** The late snack Kotlin sees now, or its absence. */
suspend fun snack(): String = lateSnack?.toList()?.joinToString() ?: "no snack"

/** An `object` with a `var` of every flow kind. */
object Pantry {
  var stock: Flow<String> = flowOf("kibble")

  var level: StateFlow<Int> = MutableStateFlow(1)

  var dial: MutableStateFlow<Int> = MutableStateFlow(2)

  var news: SharedFlow<String> = MutableSharedFlow<String>(replay = 1).apply { tryEmit("Mylo") }

  var bell: MutableSharedFlow<String> = MutableSharedFlow(replay = 1)

  suspend fun read(): String =
    "stock=${stock.toList().joinToString()} level=${level.value} dial=${dial.value} " +
      "news=${news.first()} bell=${bell.replayCache.joinToString()}"
}

/** A default-named companion's flow `var`. */
class Bowlful {
  companion object {
    var refills: Flow<Int> = flowOf(1)

    suspend fun total(): Int = refills.toList().sum()
  }
}

/** A named companion's flow `var`, so the Kotlin assignment has to be owner-qualified. */
class Larder {
  companion object Shelf {
    var tins: StateFlow<Int> = MutableStateFlow(3)

    fun count(): Int = tins.value
  }
}

/** An enum body's flow `var`, one per entry, and an enum companion's. */
enum class Meal {
  BREAKFAST,
  DINNER;

  var menu: Flow<String> = flowOf(name.lowercase())

  suspend fun dish(): String = menu.toList().joinToString()

  companion object {
    var special: Flow<String> = flowOf("tuna")

    suspend fun todays(): String = special.toList().joinToString()
  }
}

/** A sealed `object` arm's flow `var`, set through the singleton's handle like any arm's. */
sealed class Tray {
  object Empty : Tray() {
    var crumbs: Flow<String> = flowOf("none")
  }
}

/** The empty tray, for C#. */
fun emptyTray(): Tray.Empty = Tray.Empty

/** The crumbs Kotlin sees on the empty tray now. */
suspend fun crumbs(): String = Tray.Empty.crumbs.toList().joinToString()
