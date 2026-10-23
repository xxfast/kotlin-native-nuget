package io.github.xxfast.kotlin.native.nuget.test.cheersquad

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/*
 * ADR-239: a function that RETURNS a lambda binds as a BCL delegate on every owner, the shapes
 * ADR-236 gave a lambda property: `(A) -> R` is `Func<A, R>`, `(A) -> Unit` is `Action<A>`,
 * `suspend (A) -> R` is `Func<A, CancellationToken, Task<R>>`, `suspend (A) -> Unit` is
 * `Func<A, CancellationToken, Task>`, and a nullable lambda result is the same type with `?`.
 *
 * This file is the top-level owner (static on `CheersquadSample`), the extension owner (on
 * `TasselExtensions` and `StringExtensions`) and the Kotlin callers that make a C# implementation
 * of [Chanter] observable. Oreo shakes the pompoms; Mylo leads the chant.
 */

private var claps: Int = 0

/** How many claps the returned [clap] and [stomp] lambdas have counted. */
fun clapCount(): Int = claps

fun cheer(): (String) -> String = { who -> "go $who" }

fun clap(): (Int) -> Unit = { times -> claps += times }

fun chant(): suspend (String) -> String = { who ->
  delay(1.milliseconds)
  "$who, $who, $who"
}

fun stomp(): suspend (Int) -> Unit = { times ->
  delay(1.milliseconds)
  claps += times
}

fun maybeCheer(loud: Boolean): ((String) -> String)? = if (loud) { who -> "GO $who" } else null

fun maybeChant(loud: Boolean): (suspend (String) -> String)? =
  if (loud) {
    { who ->
      delay(1.milliseconds)
      "$who! $who!"
    }
  } else {
    null
  }

/** A class-receiver extension returning a lambda. */
fun Tassel.encore(): (String) -> String = { who -> "$colour encore for $who" }

/** A class-receiver extension returning a `suspend` lambda. */
fun Tassel.encoreLater(): suspend (String) -> String = { who ->
  delay(1.milliseconds)
  "$colour encore later for $who"
}

/** A `String`-receiver extension returning a lambda. */
fun String.refrain(): (Int) -> String = { times -> List(times) { this }.joinToString(" ") }

/** A Kotlin-backed [Chanter], so C# calls its lambda-returning members through the wrapper. */
fun megaphone(): Chanter = Bullhorn("Mylo")

/** Calls the lambda a [Chanter] returns, so a C# implementation's delegate runs from Kotlin. */
fun rally(chanter: Chanter, who: String): String = chanter.cheer()(who)

/**
 * Awaits the `suspend` lambda a [Chanter] returns. A class holds the [Chanter] so the `suspend`
 * member itself takes only a `String`.
 */
class Cheerleader(private val chanter: Chanter) {
  suspend fun rallyLater(who: String): String = chanter.chant()(who)
}

/** Calls the nullable lambda a [Chanter] returns, or names its absence. */
fun rallyMaybe(chanter: Chanter, loud: Boolean, who: String): String =
  chanter.maybeCheer(loud)?.invoke(who) ?: "silence for $who"

/** A sealed [Routine] seen as its base. */
fun tumble(flips: Int): Routine = Routine.Tumble(flips)

private class Bullhorn(val leader: String) : Chanter {
  override fun cheer(): (String) -> String = { who -> "$leader cheers $who" }

  override fun chant(): suspend (String) -> String = { who ->
    delay(1.milliseconds)
    "$leader chants $who"
  }

  override fun maybeCheer(loud: Boolean): ((String) -> String)? =
    if (loud) { who -> "$leader roars $who" } else null
}
