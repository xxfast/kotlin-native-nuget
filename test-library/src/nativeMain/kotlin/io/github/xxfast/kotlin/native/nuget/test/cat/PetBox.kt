package io.github.xxfast.kotlin.native.nuget.test.cat

// Constrained generic class with a validating init — exercises ADR-032 (the
// constrained create_object path that routes through PetBoxNative.Create_object).
class PetBox<T : Pet>(val value: T) {
  init {
    require(value.name.isNotBlank()) { "PetBox needs a named pet" }
  }
}
