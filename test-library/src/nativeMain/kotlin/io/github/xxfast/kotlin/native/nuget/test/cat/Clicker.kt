package io.github.xxfast.kotlin.native.nuget.test.cat

/**
 * Case A of the override-val-with-var matrix: the read-only declaration lives on an *interface*.
 * The C# projection of a class member that only implements an interface member renders `virtual`,
 * not `override`, so an implementation is free to add a setter.
 */
interface Counter {
  /** Read-only here. [Clicker] widens it to `var`. */
  val count: Int
}

/**
 * Oreo's treat tally. Implements [Counter]'s `val count` with an `override var`, so C# gets
 * `public virtual int Count { get; set; }` against `ICounter`'s get-only `int Count { get; }`.
 */
class Clicker : Counter {
  override var count: Int = 0
}
