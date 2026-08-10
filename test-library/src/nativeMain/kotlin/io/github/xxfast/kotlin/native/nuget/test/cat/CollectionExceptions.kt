package io.github.xxfast.kotlin.native.nuget.test.cat

// Fixtures for temporary collection handle cleanup on the throwing path.
// Every call below fails *after* the C# side has built native handles for its
// collection arguments, so the handles must still be released.

/** Audits the treat ledger Oreo and Mylo keep. It never balances. */
class Auditor {
  fun audit(entries: List<String>): Int =
    throw IllegalStateException("audit failed: ${entries.size} entries do not balance")

  fun crossCheck(entries: List<String>, labels: Set<String>): Int =
    throw IllegalStateException(
      "cross-check failed: ${entries.size} entries, ${labels.size} labels",
    )
}

/** A ledger of treats handed out. An empty one is a mistake, not a fresh start. */
class Ledger(entries: List<String>) {
  val count: Int = entries.size

  init {
    require(entries.isNotEmpty()) { "ledger needs entries" }
  }
}
