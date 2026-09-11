package io.github.xxfast.kotlin.native.nuget.test.roost

/**
 * ADR-009 amendment (2026-09-11): an `open` arm of a sealed class renders `public class`, its
 * `open` members render `virtual`, and a Kotlin subclass of the arm takes the ordinary class route
 * with the arm spelled by its nested C# name (`Roost.Perch`).
 */
sealed class Roost {
  open class Perch(open val height: Int) : Roost() {
    open fun describe(): String = "perch $height"
  }

  data object Ground : Roost()
}

class HighPerch : Roost.Perch(1) {
  override val height: Int get() = 99

  override fun describe(): String = "high"
}

/**
 * A direct arm at a sealed **return** position, so `Roost.FromHandle` and the arm's own
 * `Height`/`Describe` bodies are reachable from C#: without a callable that hands one out, nothing
 * ever constructs a `Perch` wrapper.
 */
fun roost(): Roost = Roost.Perch(3)

/**
 * The same return position holding a *further* subclass of the open arm. ADR-009's discriminator is
 * over direct arms only, so this reconstructs as a `Roost.Perch` wrapper in C#; the handle is the
 * `HighPerch` instance and Kotlin dispatch is still virtual, so `Describe()` through it says
 * "high".
 */
fun highRoost(): Roost = HighPerch()
