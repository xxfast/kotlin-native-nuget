package io.github.xxfast.kotlin.native.nuget.test.cat

/**
 * ADR-211: top-level generic functions on the ADR-062 plan. Each binds as a static generic
 * method on the file facade, its bare `T` crossing as a boxed handle, so a builtin argument and a
 * generated wrapper both reach Kotlin as themselves.
 *
 * Oreo gets described by his colours; Mylo just gets a label on his bowl.
 */
fun <T : Any> describe(value: T): String = "$value: ${value::class.simpleName}"

/** The unbounded sibling of [describe]: C# gets no `where` clause. */
fun <T> label(value: T): String = "bowl of $value"
