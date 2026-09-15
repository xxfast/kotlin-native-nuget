package io.github.xxfast.kotlin.native.nuget.test.issue131

import kotlinx.coroutines.flow.Flow

/**
 * Fixture for issue [#131](https://github.com/xxfast/kotlin-native-nuget/issues/131) and ADR-149.
 *
 * Two halves, and only the first was a defect.
 *
 * The **diagnostic** half: the declared arity of [hubWithEvents] is dropped because `events` has
 * no input wire, and the message used to say `SKIPPED_UNSUPPORTED_RETURN` about a return type that
 * was perfectly exportable. It now names the position and the parameter. Mapping `Flow<T>` at a
 * parameter position is a separate ROADMAP item, so that arity stays a refusal.
 *
 * ADR-149: the shorter arities never mention `events`, so they bind. C# gets `HubWithEvents()` and
 * `HubWithEvents(Settings)`; the events arity stays absent. Same rule on every function route that
 * already synthesizes omitting overloads, plus constructors (already this shape, pinned here so
 * they cannot regress).
 *
 * The **mapping** half: a nullable exported class handle at a parameter position already works,
 * with `null` riding `IntPtr.Zero`. It was only pinned on the class-method route
 * (`Patient.attach` / `MethodParameterMarshallingTests`), so [Hub] and [hub] pin the constructor
 * and top-level-function routes against the same rule.
 *
 * ### Cells
 * - [Hub]: constructor route. [Hub.logger] needs a conversion at the seam
 *   (`?._handle ?? IntPtr.Zero` out, `?.asStableRef<Logger>()?.get()` in); [Hub.note] needs none
 *   (a `String?` rides its own null pointer), so both lowerings are crossed.
 * - [hub]: the same two, on the top-level-function route, with trailing defaults so ADR-096's
 *   omitting overloads are minted over a nullable handle parameter too.
 * - [hubWithEvents]: shorter arities bind; the events arity is `SKIPPED_UNSUPPORTED_INPUT` quoting
 *   `events`. Deliberately second in parameter order, so the skip has a non-offending parameter
 *   ahead of it to walk past.
 * - [hubWithLoggerAndEvents]: the 3-arg sibling. `hub(settings, logger)` must bind without ever
 *   requiring `events`. [Logger] needs handle conversion; [Settings] does too. Distinct
 *   [Hub.describe] so a wrong overload cannot hide behind [hub]'s `note`.
 * - [Sill]: constructor control. No Planned gate on constructors, so `new Sill()` and
 *   `new Sill(settings)` already bind today; the events arity does not.
 * - [Desk.open], [Switchboard.patch], [Window.of], [Logger.call], [Shift.Night.watch]: one cell
 *   per remaining function route (class, object, companion, extension, sealed arm). [Settings] is
 *   the conversion type; `Int` needs none. Not both on every cell.
 *
 * Oreo logs everything. Mylo prefers to run without a logger.
 */
class Settings(val level: Int = 0)

class Logger(val tag: String) {
  fun log(message: String): String = "[$tag] $message"
}

class Hub(val settings: Settings, val logger: Logger?, val note: String? = null) {
  fun describe(): String = "${settings.level}/${logger?.tag ?: "none"}/${note ?: "-"}"
}

fun hub(settings: Settings = Settings(), logger: Logger? = null, note: String? = null): Hub =
  Hub(settings, logger, note)

/**
 * Shorter arities bind (`HubWithEvents()`, `HubWithEvents(Settings)`); the events arity does not.
 * Kotlin fills `events = null` at the omitting call site.
 */
fun hubWithEvents(settings: Settings = Settings(), events: Flow<Int>? = null): Hub =
  Hub(settings, null, events?.toString())

/**
 * Q1: a 3-arg sibling. `HubWithLoggerAndEvents(settings, logger)` never touches `events`.
 * Note is `"feed"` when events are omitted, so this cannot be mistaken for [hub].
 */
fun hubWithLoggerAndEvents(
  settings: Settings = Settings(),
  logger: Logger? = null,
  events: Flow<Int>? = null,
): Hub = Hub(settings, logger, events?.toString() ?: "feed")

/**
 * Constructor control. Trailing defaulted `Flow` does not kill the shorter arities: constructors
 * have no Planned gate. `new Sill()` and `new Sill(settings)` must already exist.
 */
class Sill(val settings: Settings = Settings(), events: Flow<Int>? = null) {
  private val feed: String = events?.toString() ?: "-"

  fun describe(): String = "${settings.level}/$feed"
}

/** Class-method route. [Settings] needs handle conversion. */
class Desk(val name: String) {
  fun open(settings: Settings = Settings(), events: Flow<Int>? = null): String =
    "$name desk ${settings.level}/${events?.toString() ?: "-"}"
}

/** `object` member route. `level` needs no conversion. */
object Switchboard {
  fun patch(level: Int = 0, events: Flow<Int>? = null): String =
    "patch $level/${events?.toString() ?: "-"}"
}

/** Companion-member route. [Settings] needs handle conversion. */
class Window {
  companion object {
    fun of(settings: Settings = Settings(), events: Flow<Int>? = null): String =
      "window ${settings.level}/${events?.toString() ?: "-"}"
  }
}

/** Extension-function route. `level` needs no conversion; the [Logger] receiver does. */
fun Logger.call(level: Int = 0, events: Flow<Int>? = null): String =
  "$tag calls $level/${events?.toString() ?: "-"}"

/** Sealed-arm declared-method route. [Settings] needs handle conversion. */
sealed class Shift {
  class Night(val cat: String) : Shift() {
    fun watch(settings: Settings = Settings(), events: Flow<Int>? = null): String =
      "$cat watches ${settings.level}/${events?.toString() ?: "-"}"
  }
}
