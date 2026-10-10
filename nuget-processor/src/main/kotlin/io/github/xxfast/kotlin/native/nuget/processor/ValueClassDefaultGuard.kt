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

/**
 * The C# read of a `string` headed for a non-null Kotlin `String` slot, refusing null:
 * `(text ?? throw new ArgumentNullException(nameof(text)))`. The string twin of
 * [valueClassUnderlyingOrThrow], and an expression for the same reason: one spelling serves an
 * argument list, a `Select` lambda, an expression-bodied member and a write lambda.
 *
 * C# can always pass `null!` where a `string` is declared. The marshaller turns it into a null
 * pointer, and Kotlin then holds a null in a `String` it believes is non-null: measured, that is
 * an access violation as soon as the export touches it (`0xC0000005` out of a companion, object or
 * top-level import), a silent null stored in the object when it does not, a
 * `kotlin.NullPointerException` out of a collection read, and an uncaught one that takes the
 * process down when it is a callback's result. One null check per string argument, no allocation.
 *
 * [value] is the expression read, [parameter] the name the caller wrote, which is what
 * `ArgumentNullException.ParamName` is for (a collection's own name for one of its elements).
 */
internal fun nonNullStringOrThrow(value: String, parameter: String = value): String =
  "($value ?? throw new ArgumentNullException(nameof($parameter)))"
