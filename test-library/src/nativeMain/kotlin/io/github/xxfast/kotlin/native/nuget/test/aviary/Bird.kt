package io.github.xxfast.kotlin.native.nuget.test.aviary

/**
 * ADR-075: an exported interface property that an exported abstract class inherits but does not
 * implement. [Feathered] declares both a `val` and a `var`; [Bird] implements neither, so C# must
 * see `public abstract string Plumage { get; }` and `public abstract string Perch { get; set; }`
 * on `Bird` itself. Without them the generated `Bird : IFeathered` is CS0535 before any consumer
 * subclass gets a chance to override anything.
 *
 * Distinct from [io.github.xxfast.kotlin.native.nuget.test.orchestra.Instrument], whose abstract
 * properties are declared on the abstract class itself. Here the declaration lives on the
 * interface and the abstract class only inherits it.
 *
 * [describe] reads both members through Kotlin's own dispatch, so a C# write to [Feathered.perch]
 * is observable rather than echoed back by the C# getter. Mylo is the brown one.
 */
interface Feathered {
  /** Read-only on the interface: must reach C# as `public abstract string Plumage { get; }`. */
  val plumage: String

  /** Mutable on the interface: must reach C# as `public abstract string Perch { get; set; }`. */
  var perch: String
}

/** Inherits both [Feathered] members without implementing either. */
abstract class Bird(val name: String) : Feathered {
  fun describe(): String = "$name: $plumage on $perch"
}

/** Implements both inherited members. Compiles in C# only once [Bird] declares them abstract. */
class Finch : Bird("finch") {
  override val plumage: String = "brown"
  override var perch: String = "twig"
}
