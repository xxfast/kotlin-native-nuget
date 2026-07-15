package io.github.xxfast.kotlin.native.nuget.test.cat

// --- Regular class: IllegalArgumentException from require() ---
// Oreo and Mylo were both found as kittens; a negative age is a data-entry error.
class Kitten(val name: String, val age: Int) {
  init {
    require(age >= 0) { "Kitten age cannot be negative" }
  }
}

// --- Regular class: IllegalStateException from check() ---
// A rescue shelter must be open (capacity > 0) before accepting cats.
class CatRescue(val name: String, val capacity: Int) {
  init {
    check(capacity > 0) { "Rescue shelter must have positive capacity" }
  }
}

// --- Custom exception: user-defined type → falls back to base KotlinException ---
// Overfeeding Oreo results in a custom domain exception, not a stdlib one.
internal class OverfedCatException(message: String) : Exception(message)

class CatWeightRecord(val name: String, val weightKg: Double) {
  init {
    if (weightKg > 20.0) throw OverfedCatException("$name is dangerously overweight at ${weightKg}kg")
  }
}

// --- Data class: init validation exercises copy() propagation ---
// Mylo's treat budget is tracked per month; a negative budget makes no sense.
data class CatProfile(val name: String, val treatBudget: Int) {
  init {
    require(treatBudget >= 0) { "Treat budget cannot be negative" }
  }
}

// --- Secondary constructor: ADR-034 ---
// A litter order can be given directly by total weight, or as bags × weight-per-bag.
// The secondary delegates to the primary, which re-runs the init validation.
class CatLitter(val brand: String, val weightKg: Int) {
  init {
    require(weightKg > 0) { "Litter weight must be positive" }
  }

  constructor(brand: String, bags: Int, perBag: Int) : this(brand, bags * perBag)
}
