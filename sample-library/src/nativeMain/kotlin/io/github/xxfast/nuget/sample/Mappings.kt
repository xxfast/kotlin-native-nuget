@file: OptIn(ExperimentalNativeApi::class)
package io.github.xxfast.nuget.sample

import kotlin.experimental.ExperimentalNativeApi

@CName("get_string")
fun string(): String = "Kotlin/Native!"

@CName("get_byte")
fun byte(): Byte = 42

@CName("get_ubyte")
fun ubyte(): UByte = 255u

@CName("get_short")
fun short(): Short = 1024

@CName("get_ushort")
fun ushort(): UShort = 65535u

@CName("get_int")
fun int(): Int = 2_147_483_647

@CName("get_uint")
fun uint(): UInt = 4_294_967_295u

@CName("get_long")
fun long(): Long = 9_223_372_036_854_775_807L

@CName("get_ulong")
fun ulong(): ULong = 18_446_744_073_709_551_615u

@CName("get_float")
fun float(): Float = 3.14f

@CName("get_double")
fun double(): Double = 2.718281828459045
