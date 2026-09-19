package io.github.xxfast.kotlin.native.nuget.test.garage

import io.github.xxfast.kotlin.native.nuget.hidden.Nesting
import io.github.xxfast.kotlin.native.nuget.test.cat.Toy

/**
 * The abstract method walk hand-spells a class-typed return or parameter by its bare simple name,
 * so it only ever compiled because [Vehicle.honk] is typed `String`. Three cells, one per way the
 * bare name is wrong, and all three sit on the same abstract class so one generated file proves
 * them together:
 *
 *  - [Hauler.cargo]: returns [Toy], exported but declared in `TestLibrary.Cat`. The generated
 *    `TestLibrary.Garage` file carries only the `System` usings, so a bare `Toy` names nothing
 *    here. Must render `global::TestLibrary.Cat.Toy`.
 *  - [Hauler.latch]: takes and returns [Hitch.Pin], an ADR-133 nested class. `Pin` exists only as
 *    `Hitch.Pin`, so the bare name is undeclared even inside its own namespace. Must render
 *    `global::TestLibrary.Garage.Hitch.Pin` at BOTH positions; the parameter arm is a separate
 *    hand-spelling from the return arm and a return-only fix leaves this half red.
 *  - [Hauler.padding]: returns `Nesting.Lining`, nested under an interface in the unexported
 *    `io.github.xxfast.kotlin.native.nuget.hidden` package. Nothing declares it in C#, so it must
 *    be dropped with one named skip rather than spelled at all.
 *
 * [CatHauler] overrides all three, so the concrete leaf is real and the (a) and (b) cells round
 * trip rather than merely compiling.
 *
 * Mylo rides up front with the catnip banana. Oreo refuses to be latched to anything.
 */
class Hitch(val teeth: Int) {
  /** ADR-133 nested class: declared in C# as `Hitch.Pin`, never as a bare `Pin`. */
  class Pin(val label: String)
}

abstract class Hauler(val plate: String) {
  /** Cell (a): exported class, another package. */
  abstract fun cargo(): Toy

  /** Cell (b): nested declared class, at a parameter AND at the return. */
  abstract fun latch(pin: Hitch.Pin): Hitch.Pin

  /** Cell (c): unexported class, no C# surface at all. */
  abstract fun padding(): Nesting.Lining
}

class CatHauler(plate: String) : Hauler(plate) {
  override fun cargo(): Toy = Toy("Catnip Banana", "Green")

  override fun latch(pin: Hitch.Pin): Hitch.Pin = Hitch.Pin("${pin.label}-latched-to-$plate")

  override fun padding(): Nesting.Lining = Nesting.Lining("fleece")
}
