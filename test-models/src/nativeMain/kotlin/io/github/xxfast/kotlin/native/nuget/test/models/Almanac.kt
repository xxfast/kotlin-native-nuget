package io.github.xxfast.kotlin.native.nuget.test.models

/**
 * Fixture for the ADR-066 amendment, **edge (A): nested type to its owner chain**.
 *
 * [Almanac] is a cross-module class that **nothing returns**. That is the entire point, and it is
 * what makes this fixture different from [Broadcast]: `Newsroom.broadcast()` already admits
 * `Broadcast`, so any nested type under it is declared by the owner walk regardless of whether the
 * closure can climb from a nested reference to its owner. Here the only reference anywhere is
 * `Newsroom.page(): Almanac.Page`, so the closure has to walk *up* from [Almanac.Page] to
 * [Almanac], admit the owner, and let ADR-133's owner walk declare the nested type.
 *
 * Before the amendment the closure records `NESTED_DECLARATION` for `Almanac.Page` and returns
 * without ever visiting `Almanac`: no `Almanac` is declared at all, and `page()` skips as
 * `UNDECLARED_CLASS` folded into `SKIPPED_UNSUPPORTED_TYPE`, carrying the post-ADR-133 stale
 * remedy "move it to the top level of its file", which cannot help.
 *
 * Deliberately memberless apart from the nested type: a ctor parameter here would smuggle a
 * marshalling question into a fixture about reachability. `typeof(Almanac)` existing at all is the
 * observation edge (A) needs.
 *
 * Oreo (black with a white middle) keeps the page count; Mylo naps on the almanac itself.
 */
class Almanac {

  /** The only reachable spelling of [Almanac] anywhere in the fixture. */
  class Page(val number: Int)
}
