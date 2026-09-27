package io.github.xxfast.kotlin.native.nuget.test.windowsill

/**
 * An interface default inherited by an **open** owner and overridden one level down. Kotlin lets
 * a subclass override [Scratcher.scratch] through [ScratchingPost] without either class declaring
 * it, so C# has to render the inherited default `virtual` on the open owner and `override` on the
 * subclass. Before the fix the owner rendered it non-virtual, and the subclass's `override` was
 * CS0506, which failed the whole generated file.
 *
 * - [ScratchingPost] / [CarpetPost]: the ordinary open-class shape.
 * - [Lounger.Hammock] / [SunHammock]: the same shape on an `open` sealed arm, which lists
 *   [Scratcher] in its own base list.
 *
 * Mylo shreds the carpet post; Oreo kneads the hammock in the sun.
 */
interface Scratcher {
  fun scratch(): String = "a quick scratch"

  val claws: Int get() = 10
}

/** The open owner: inherits both defaults, overrides neither. */
open class ScratchingPost : Scratcher

/** Overrides the defaults its base only inherited. */
class CarpetPost : ScratchingPost() {
  override fun scratch(): String = "Mylo shreds the carpet post"

  override val claws: Int get() = 18
}

/** A sealed class whose one arm is `open`. */
sealed class Lounger {
  /** The open arm: inherits both defaults, overrides neither. */
  open class Hammock : Lounger(), Scratcher
}

/** A Kotlin subclass of the open arm, overriding the defaults the arm only inherited. */
class SunHammock : Lounger.Hammock() {
  override fun scratch(): String = "Oreo kneads the sun hammock"

  override val claws: Int get() = 16
}

/** Takes any scratcher back through the interface, dispatching in Kotlin. */
fun scratchOf(scratcher: Scratcher): String = scratcher.scratch()
