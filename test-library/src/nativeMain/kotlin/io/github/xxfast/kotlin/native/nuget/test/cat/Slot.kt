package io.github.xxfast.kotlin.native.nuget.test.cat

// Properties on a generic class, both `T`-typed and concretely typed.
//
// Nullable `T?`: `previous` is always null (the lifted getter used to force `!!`), `current`
// proves a nullable-typed getter still passes a real value through, and since `T` is
// unconstrained (upper bound `Any?`) the constructor argument itself may be null:
// `Slot<string?>(null)` reads null back through every getter. Rides ADR-083's null-pointer
// decision.
//
// Concrete: `label`, `count`, `note` and `keeper` do not mention `T`, so each must surface with
// its own declared type (`string`, `int`, `string { get; set; }`, `Cat`) at every instantiation,
// never as `T`. One per wire: a converted scalar, a pass-through scalar, a mutable converted
// property, and a handle-returning exported class. Pins the ADR-147 fix.
class Slot<T>(val value: T) {
  val previous: T? = null
  val current: T? = value

  val label: String = "window sill"
  val count: Int = 2
  var note: String = "Oreo napped here"
  val keeper: Cat = Cat("Mylo", 7)
}
