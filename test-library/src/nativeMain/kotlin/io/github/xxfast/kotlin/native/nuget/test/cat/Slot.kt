package io.github.xxfast.kotlin.native.nuget.test.cat

// Nullable properties on a generic class. Generic classes export no setters and no
// methods, so an initializer is the only null source: `previous` is the crash cell
// (the lifted getter forces `!!`), `current` proves a nullable-typed getter still
// passes a real value through. Rides ADR-083's null-pointer decision.
class Slot<T>(val value: T) {
  val previous: T? = null
  val current: T? = value
}
