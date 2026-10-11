package io.github.xxfast.kotlin.native.nuget.processor

/**
 * The C# unwrap of a record struct over a reference underlying (a String or an exported class)
 * that refuses `default(V)`:
 * `(tag.Id ?? throw new ArgumentException("default(Tag) carries no Id; ...", nameof(tag)))`.
 *
 * A C# `record struct` always has a `default`, whose reference underlying is null. Kotlin could
 * never have built that value, and a null string pointer in a non-null Kotlin `String` slot is an
 * access violation in the export (measured: `0xC0000005` out of a constructor import), so the
 * unwrap itself throws before anything crosses. It is an expression, not a statement, so the one
 * spelling serves an argument list, a `Select` lambda, an expression-bodied member and a write
 * lambda alike; every route that unwraps a value class for a non-null Kotlin slot calls this.
 *
 * [struct] is a non-nullable record-struct expression (`tag`, `spare.Value`, `this`), [property]
 * its capitalized underlying property, [structName] the struct's simple name for the message, and
 * [parameter] the `nameof` target when the struct came in through a named parameter.
 */
internal fun valueClassUnderlyingOrThrow(
  struct: String,
  property: String,
  structName: String,
  parameter: String? = null,
): String {
  val named: String = parameter?.let { name -> ", nameof($name)" }.orEmpty()
  return "($struct.$property ?? throw new ArgumentException(\"default($structName) carries no " +
      "$property; construct a $structName instead\"$named))"
}
