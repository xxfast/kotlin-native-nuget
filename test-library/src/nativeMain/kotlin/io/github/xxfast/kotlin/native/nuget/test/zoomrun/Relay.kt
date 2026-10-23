package io.github.xxfast.kotlin.native.nuget.test.zoomrun

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-236: lambda-typed properties on a generic class, their payloads typed by its `T`, so the
 * C# delegate on `Relay<string>` is `Func<string, string>` and on `Relay<Skein>` is
 * `Func<Skein, Skein>`.
 */
class Relay<T>(var baton: T) {
  val onPass: (T) -> T = { next ->
    val previous: T = baton
    baton = next
    previous
  }

  var inspect: (T) -> String = { held -> "holding $held" }

  var onDrop: ((T) -> Unit)? = null

  val onCarry: suspend (T) -> T = { held ->
    delay(1.milliseconds)
    held
  }

  /** Calls whatever [inspect] holds now, with the current baton. */
  fun describe(): String = inspect(baton)

  /** Calls [onDrop] with the current baton when set. */
  fun drop(): String {
    val handler: (T) -> Unit = onDrop ?: return "nobody watching"
    handler(baton)
    return "dropped"
  }
}

/** A ball of yarn, the payload a generic class carries. */
class Skein(val colour: String)

/** A generic class at a lambda property's payload position. */
class Tote<T>(val item: T)

/**
 * ADR-236: a lambda property carrying a generic class, nested, on an ordinary class. The class
 * route refuses generic type arguments on the lambda-property route today.
 */
class YarnBin(val owner: String) {
  var unwind: () -> Tote<Tote<Skein>> = { Tote(Tote(Skein("$owner's red"))) }

  val onSort: (Tote<Skein>) -> String = { tote -> tote.item.colour }

  var onTangle: ((Tote<Skein>) -> Tote<Skein>)? = null

  /** Calls whatever [unwind] holds now and reads the inner skein's colour. */
  fun unravel(): String = unwind().item.item.colour

  /** Calls [onTangle] when set. */
  fun tangle(colour: String): String =
    onTangle?.invoke(Tote(Skein(colour)))?.item?.colour ?: "untangled"
}

/** ADR-236: the same generic payload on an object. */
object YarnShop {
  var unwind: () -> Tote<Tote<Skein>> = { Tote(Tote(Skein("Mylo's brown"))) }

  /** Calls whatever [unwind] holds now and reads the inner skein's colour. */
  fun unravel(): String = unwind().item.item.colour
}
