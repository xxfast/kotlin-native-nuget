package io.github.xxfast.kotlin.native.nuget.sample.cat

// --- Class member property: throwing SETTER ---
// Oreo's treat jar enforces a non-negative count.
class TreatJar(initial: Int) {
  var treatCount: Int = initial
    set(value) {
      require(value >= 0) { "Treat count cannot be negative" }
      field = value
    }
}

// --- Class member property: throwing GETTER + nullable throwing GETTER ---
// Mylo's snack bowl tracks what's next; asking when it's empty throws.
class SnackBowl {
  private val snacks: MutableList<String> = mutableListOf()

  val isEmpty: Boolean get() = snacks.isEmpty()

  val nextSnack: String
    get() {
      check(!isEmpty) { "Snack bowl is empty — Mylo ate everything" }
      return snacks.first()
    }

  fun addSnack(snack: String) { snacks.add(snack) }

  // Nullable Int? property with a throwing setter (negative portions are illegal)
  var portion: Int? = null
    set(value) {
      if (value != null) require(value > 0) { "Portion must be positive" }
      field = value
    }
}

// --- Top-level property: throwing getter ---
// The treat budget is only readable when it has been set; reading before setting is illegal.
private var treatBudgetInternal: Int? = null

var treatBudget: Int
  get() {
    check(treatBudgetInternal != null) { "Treat budget has not been set yet" }
    return treatBudgetInternal!!
  }
  set(value) {
    require(value >= 0) { "Treat budget cannot be negative" }
    treatBudgetInternal = value
  }

// --- Extension property: throwing getter ---
// A Cat's favourite treat name can only be retrieved if the cat has at least one toy;
// otherwise it's an illegal state (Oreo chewed through them all).
val Cat.favouriteTreatName: String
  get() {
    check(toys.isNotEmpty()) { "No toys left — cannot determine favourite treat for $name" }
    return "${toys.first().name} flavour"
  }
