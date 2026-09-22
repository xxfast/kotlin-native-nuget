package io.github.xxfast.kotlin.native.nuget.test.namesake.b

/**
 * Namesake fixture, half B: the twin sealed class. Same simple name, same arm names, same
 * discriminator, different package; the answers differ so a cell cannot pass by reaching the wrong
 * declaration.
 *
 * Mylo's food bowl: he insists it is empty even when it is full, so house B disagrees with house A
 * on the same name.
 */
sealed class LoadState {
  /** The loaded arm, carrying the name it loaded. */
  class Ready(val name: String) : LoadState()

  /** The failed arm, carrying a distinguishable cause. */
  class Failed(val cause: String) : LoadState()
}

/** Mirror image of half A: Mylo is the resident here, so the SAME input takes the OTHER arm. */
fun loadFor(name: String): LoadState =
  if (name == "Mylo") LoadState.Ready("b:$name") else LoadState.Failed("b: $name is not in house B")
