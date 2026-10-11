package io.github.xxfast.kotlin.native.nuget.test.stamps

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Every forward route on which a non-null Kotlin `String` is filled from C#. C# can always pass
 * `null!` where a `string` is declared, and a null string pointer in a non-null Kotlin `String`
 * slot is an access violation in the export, so the generated C# refuses it with an
 * `ArgumentNullException` before anything crosses. Each member reads back what it was given, so a
 * test can tell a refused call from a lost one.
 *
 * Mylo runs the stamp desk; Oreo's parcels get stamped.
 */
class StampDesk(val owner: String) {
  /** Property setter. */
  var label: String = "unlabelled"

  /** A nullable slot beside it: null stays a legitimate value here. */
  var note: String? = null

  /** Method parameter. */
  fun stamp(text: String): String = "$owner stamped $text"

  /** A nullable parameter: null is passed through, not refused. */
  fun stampOrBlank(text: String?): String = "$owner stamped ${text ?: "nothing"}"

  /** `List` component. */
  fun stampAll(texts: List<String>): Int = texts.sumOf { it.length }

  /** `Set` component. */
  fun stampUnique(texts: Set<String>): Int = texts.sumOf { it.length }

  /** `Map` key and value components. */
  fun stampKeyed(byKey: Map<String, String>): Int =
    byKey.entries.sumOf { it.key.length + it.value.length }

  /**
   * A collection ahead of the string: the C# side has already built [names]' native handle when
   * the null [text] is refused, so that handle has to be released on the way out.
   */
  fun stampNamed(names: List<String>, text: String): Int = names.size + text.length

  /** `suspend` member parameter. */
  suspend fun stampLater(text: String): String = "$owner stamped $text later"

  /** `Flow`-returning member parameter. */
  fun stampStream(text: String): Flow<String> = flowOf("$owner stamped $text")

  /** A callback that RETURNS a non-null string to Kotlin. */
  fun stampWith(make: () -> String): String = "$owner stamped ${make()}"

  /** A C#-implemented interface that returns a non-null string to Kotlin. */
  fun stampThrough(stamper: Stamper, text: String): String = stamper.press(text)

  /** `MutableStateFlow<String>` element write. */
  val title: MutableStateFlow<String> = MutableStateFlow("untitled")

  /** `MutableSharedFlow<String>` element emit. */
  val notices: MutableSharedFlow<String> = MutableSharedFlow(replay = 1)

  /** Kotlin-side read-back of [label]. */
  fun currentLabel(): String = label

  companion object {
    /** Companion member parameter. */
    fun measure(text: String): Int = text.length
  }
}

/** An interface whose member takes and returns a non-null string. */
interface Stamper {
  fun press(text: String): String
}

/** A Kotlin-backed [Stamper], so the interface member is also called from C#. */
class InkStamper(val ink: String) : Stamper {
  override fun press(text: String): String = "$ink $text"
}

/** An `object` member parameter. */
object StampLedger {
  fun entry(text: String): Int = text.length
}

/** A sealed arm's constructor and member parameters. */
sealed class Seal {
  class Wax(val colour: String) : Seal() {
    fun press(text: String): String = "$colour wax on $text"
  }
}

/** Top-level function parameter. */
fun stampLength(text: String): Int = text.length

/** Extension-function receiver. */
fun String.stamped(): String = "[$this]"

/** Extension-property receiver. */
val String.stampWidth: Int get() = length + 2
