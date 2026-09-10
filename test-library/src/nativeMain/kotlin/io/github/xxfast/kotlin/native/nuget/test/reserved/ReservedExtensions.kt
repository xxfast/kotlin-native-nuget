package io.github.xxfast.kotlin.native.nuget.test.reserved

/**
 * Extension-receiver collision. The generated extension method already names its `this` parameter
 * `receiver` on the C# side and its first export parameter `receiver` on the Kotlin side, so a user
 * parameter of the same name is a duplicate at the same position on both. Joins the two so the
 * order they arrive in is observable.
 *
 * Lives beside the rest of its family in `reserved/`, in its own file because the merged
 * `{Receiver}Extensions` class it renders into is named after the receiver, not the source file.
 */
fun String.tag(receiver: String): String = "$this:$receiver"
