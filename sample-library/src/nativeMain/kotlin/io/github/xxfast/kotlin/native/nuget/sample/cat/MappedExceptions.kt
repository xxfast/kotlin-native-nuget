package io.github.xxfast.kotlin.native.nuget.sample.cat

// --- Mapped: kotlin.IllegalArgumentException → KotlinArgumentException : ArgumentException ---
// Oreo is on a strict diet. Any treat attempt is an illegal argument.
fun checkOreoWeight(grams: Int): String {
  if (grams > 0) throw IllegalArgumentException("Oreo is on a diet, $grams g treat is too much")
  return "Mylo accepted ${-grams} g of kibble gracefully"
}

// --- Mapped: kotlin.IllegalStateException → KotlinInvalidOperationException : InvalidOperationException ---
// Oreo is asleep. Operating the laser pointer while Oreo sleeps is an illegal state.
fun activateLaserPointer(catName: String): String {
  if (catName == "Oreo") error("Cannot play: Oreo is asleep")
  return "$catName chased the red dot enthusiastically"
}

// --- Mapped: kotlin.NoSuchElementException → KotlinInvalidOperationException : InvalidOperationException ---
// Oreo ate all the treats from the treat bag; asking for the first remaining treat fails.
fun grabFirstTreatFromBag(catName: String): String {
  if (catName == "Oreo") throw NoSuchElementException("Treat bag is empty — Oreo ate them all")
  return "$catName found a treat"
}

// --- Mapped: kotlin.ConcurrentModificationException → KotlinInvalidOperationException : InvalidOperationException ---
// Oreo keeps jumping into the treat basket while we're counting.
fun countTreatsInBasket(catName: String): String {
  if (catName == "Oreo") throw ConcurrentModificationException("Oreo modified the basket while counting")
  return "$catName waited patiently; basket has 5 treats"
}

// --- Mapped: kotlin.UnsupportedOperationException → KotlinNotSupportedException : NotSupportedException ---
// Oreo refuses baths. This operation is simply not supported.
fun giveCatABath(catName: String): String {
  if (catName == "Oreo") throw UnsupportedOperationException("Oreo does not support baths")
  return "$catName enjoyed a splashy bath"
}

// --- Mapped: kotlin.ClassCastException → KotlinInvalidCastException : InvalidCastException ---
// Oreo cannot be mistaken for a dog. A bad cast.
fun treatCatAsADog(catName: String): String {
  if (catName == "Oreo") throw ClassCastException("Cannot cast Oreo to Dog — he is very much a cat")
  return "$catName trotted off happily (still a cat)"
}

// --- Mapped: kotlin.ArithmeticException → KotlinArithmeticException : ArithmeticException ---
// Oreo stole all the treats, leaving zero. Division by zero ensues.
fun shareRemainingTreats(catName: String): String {
  if (catName == "Oreo") {
    val remainingTreats = 0
    // Force an ArithmeticException: integer division by zero
    @Suppress("DIVISION_BY_ZERO", "UNUSED_VARIABLE")
    val unused = 1 / remainingTreats
  }
  return "$catName shared treats evenly with the household"
}

// --- Mapped: kotlin.NumberFormatException → KotlinFormatException : FormatException ---
// Oreo chewed the weight label off the food bag. Parsing the number now fails.
fun parseCatWeight(catName: String): String {
  if (catName == "Oreo") "Oreo_chewed_label".toInt() // triggers NumberFormatException
  return "$catName weighs a healthy 4.2 kg"
}

// --- Unmapped: kotlin.NullPointerException → stays KotlinException (fallback) ---
// Oreo swatted the toy off the table. There is nothing left to retrieve.
fun retrieveCatToy(catName: String): String {
  if (catName == "Oreo") throw NullPointerException("Oreo's toy is gone — he knocked it behind the sofa")
  return "$catName retrieved his favourite toy"
}

// --- Unmapped: kotlin.IndexOutOfBoundsException → stays KotlinException (fallback) ---
// Oreo pushed everything off the shelf. Accessing index 0 of the now-empty shelf fails.
fun getItemFromShelf(catName: String): String {
  if (catName == "Oreo") throw IndexOutOfBoundsException("Shelf is empty — Oreo cleared it off")
  return "$catName fetched item 0 from the tidy shelf"
}
