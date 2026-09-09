package io.github.xxfast.kotlin.native.nuget.test.issue131

import kotlinx.coroutines.flow.Flow

/**
 * Fixture for issue [#131](https://github.com/xxfast/kotlin-native-nuget/issues/131): the
 * "give me the defaults, or override these two" factory shape, whose parameters are optional.
 *
 * Two halves, and only the first was a defect.
 *
 * The **diagnostic** half: [hubWithEvents] is dropped because `events` has no input wire, and the
 * message used to say `SKIPPED_UNSUPPORTED_RETURN` about a return type that was perfectly
 * exportable. It now names the position and the parameter. Mapping `Flow<T>` at a parameter
 * position is a separate ROADMAP item, so this cell stays a refusal.
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
 * - [hubWithEvents]: the refusal. Absent from C#, named `SKIPPED_UNSUPPORTED_INPUT` and quoting
 *   `events`. Deliberately second in parameter order, so the skip has a non-offending parameter
 *   ahead of it to walk past.
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

/** Refused: `Flow<T>` has no input wire, so the whole function is absent from C#. */
fun hubWithEvents(settings: Settings = Settings(), events: Flow<Int>? = null): Hub =
  Hub(settings, null, events?.toString())
