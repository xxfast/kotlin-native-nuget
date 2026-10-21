package io.github.xxfast.kotlin.native.nuget.test.playroom

import io.github.xxfast.kotlin.native.nuget.test.membergeneric.Furry

/**
 * ADR-222: Kotlin reads, writes and calls a C#-implemented generic interface. [Keepsake] is the
 * ADR-218 interface in `Playpens.kt`; this file adds the input positions that take one, so a C#
 * `class Diary : IKeepsake<string>` crosses through one erased bridge factory per interface.
 *
 * Oreo keeps a diary of every sunbeam; Mylo keeps a tally of every treat.
 */
interface Album<T> {
  val cover: T
  var bookmark: T?
  fun turn(next: T): T
  fun title(): String
}

/** ADR-222: a self-referencing bound, bridged without a trampoline. */
interface Podium<T : Comparable<T>> {
  val best: T
}

/** A closed instantiation: the getter slot only. */
fun inspect(keepsake: Keepsake<String>): String = "curator sees ${keepsake.item}"

/** A closed `Int` instantiation: the getter and the setter slot, both boxed. */
fun polish(keepsake: Keepsake<Int>): Int {
  keepsake.item += 1
  return keepsake.item
}

/** An open `T`: Kotlin reads the erased owner, swaps the item and hands back the old one. */
fun <T> trade(keepsake: Keepsake<T>, value: T): T {
  val old: T = keepsake.item
  keepsake.item = value
  return old
}

/** A bounded `T`: the item is read through [Furry] on the Kotlin side. */
fun <T : Furry> petName(keepsake: Keepsake<T>): String = keepsake.item.name

/** A self-bounded `T`, so a trampolined owner reads the bridge. */
fun <T : Comparable<T>> crown(podium: Podium<T>): String = "crowned ${podium.best}"

/** The extension-receiver position over a closed instantiation. */
fun Keepsake<String>.shine(): String = "shining $item"

/** A class member taking a generic interface, with `T` in, out and a nullable `T` setter. */
class Curator {
  fun leaf(album: Album<String>): String {
    val first: String = album.turn("page 2")
    album.bookmark = null
    return "${album.title()}: ${album.cover} then $first, bookmark ${album.bookmark}"
  }
}
