package io.github.xxfast.kotlin.native.nuget.test

fun string(): String = "Kotlin/Native!"

fun byte(): Byte = 42

fun ubyte(): UByte = 255u

fun short(): Short = 1024

fun ushort(): UShort = 65535u

fun int(): Int = 2_147_483_647

fun uint(): UInt = 4_294_967_295u

fun long(): Long = 9_223_372_036_854_775_807L

fun ulong(): ULong = 18_446_744_073_709_551_615u

fun float(): Float = 3.14f

fun double(): Double = 2.718281828459045

fun nullableInt(hasValue: Boolean): Int? = if (hasValue) 42 else null

fun nullableString(hasValue: Boolean): String? = if (hasValue) "hello" else null

// Regression coverage for the nullable-returning ADR-002 two-call pattern combined with ADR-024
// synchronous exception propagation: a negative input throws, zero returns null, and a positive
// input returns a value. See ADR-002 and ADR-024.
fun nullableIntOrThrow(input: Int): Int? = when {
  input < 0 -> throw IllegalArgumentException("input must not be negative")
  input == 0 -> null
  else -> input
}

fun nullableStringOrThrow(input: Int): String? = when {
  input < 0 -> throw IllegalArgumentException("input must not be negative")
  input == 0 -> null
  else -> "value-$input"
}

fun greeter(greeting: String): (String) -> String = { name -> "$greeting, $name!" }
