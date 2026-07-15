package io.github.xxfast.kotlin.native.nuget.test.nullability

import test.nullability.LegacyNicknameBook
import test.nullability.Nickname
import test.nullability.NicknameBook

/**
 * ADR-053 round trip: nullable string return, non-null string parameter
 * (`fun find(name: String): String?`). Only Oreo has a nickname on record ("Biscuit");
 * everyone else looks up as null.
 */
fun findNickname(name: String): String? = NicknameBook().find(name)

/**
 * ADR-053 round trip: non-null string return, nullable string parameter
 * (`fun greet(name: String?): String`). A missing name falls back to "stranger".
 */
fun greetNickname(name: String?): String = NicknameBook().greet(name)

/**
 * ADR-053 round trip: nullable handle return (`fun lookup(name: String): Nickname?`).
 * Only Mylo has a looked-up nickname ("Cream"); everyone else looks up as null.
 */
fun lookupNickname(name: String): String? = NicknameBook().lookup(name)?.value

/**
 * ADR-053 round trip: non-null handle return (`fun defaultNickname(): Nickname`). No more
 * `requireNotNull` is needed here once the metadata reader lands nullability — this is
 * ADR-051's flagged judgment call, corrected.
 */
fun defaultNickname(): String = NicknameBook().defaultNickname().value

/**
 * ADR-053 round trip: nullable handle parameter (`fun describe(nickname: Nickname?): String`).
 * A null nickname renders "none".
 */
fun describeNickname(value: String?): String {
  val nickname: Nickname? = value?.let(::Nickname)
  return NicknameBook().describe(nickname)
}

/**
 * ADR-053 / ROADMAP line 157 round trip: the settable, nullable handle property
 * (`var favourite: Nickname?`). A nickname is assigned, read back, then cleared to null —
 * this is the "rule 4" deletion: a handle-typed settable property is no longer collapsed to
 * a read-only `val`.
 */
fun favouriteNicknameRoundTrip(name: String): String? {
  val book = NicknameBook()
  book.favourite = Nickname(name)
  val current: String? = book.favourite?.value
  book.favourite = null
  check(book.favourite == null) { "NicknameBook.favourite did not clear back to null" }
  return current
}

/**
 * ADR-053 round trip: the non-null settable handle property (`var primary: Nickname`).
 */
fun primaryNicknameRoundTrip(name: String): String {
  val book = NicknameBook()
  book.primary = Nickname(name)
  return book.primary.value
}

/**
 * ADR-053 round trip: the settable, nullable string property (`var note: String?`).
 */
fun noteRoundTrip(value: String?): String? {
  val book = NicknameBook()
  book.note = value
  return book.note
}

/**
 * ADR-053 oblivious-island round trip: `LegacyNicknameBook` is compiled under
 * `#nullable disable`, so every reference type in it binds non-null (ADR-053 decision 1a),
 * with one `info_oblivious_nullability` diagnostic emitted per member.
 */
fun legacyFindNickname(name: String): String = LegacyNicknameBook().find(name)
