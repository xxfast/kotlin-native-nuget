package io.github.xxfast.kotlin.native.nuget.test.issue115

import dev.other.core.UnexportedFluffy
import dev.other.core.UnexportedQuilt

/**
 * The klib-rooted half of the sealed-base default cell. The same-module [Pose] cells next door are
 * green: for an override of a **source** interface member KSP already reports `hasDefault = true`,
 * so `sealedBaseEntries`' raw-bit read synthesizes the omitting overload anyway. ADR-096 measured
 * the bit as `false` on an override whose overridee is resolved out of a **klib**
 * (`Issue42Derived.farewell` over `dev.other.core.UnexportedBase`), which is why `classEntries`
 * reads defaults through `memberDefaultFlags` instead. These two sealed bases put the sealed base
 * pass over that same klib boundary.
 *
 * Two shapes, because the supertype kind is the only thing that differs and each reaches the
 * planner differently: [Biscuit] implements a klib **interface**, [Burrito] extends a klib **open
 * class** (which ADR-101 drops as an unexported supertype). Each has one arm that overrides the
 * member, so the C# base owes `Fluff()` / `Tuck()` beside `Fluff(int)` / `Tuck(int)` and the arm
 * inherits them.
 *
 * Oreo makes biscuits on the duvet; Mylo gets rolled into a burrito and stays there.
 */
sealed class Biscuit : UnexportedFluffy {
  /** The default for `pats` lives on [UnexportedFluffy.fluff], one module away. */
  override fun fluff(pats: Int): Int = pats

  /** The arm that overrides it, so the base's carrier duty is what has to supply `Fluff()`. */
  data class Shortbread(val crumbs: Int) : Biscuit() {
    override fun fluff(pats: Int): Int = pats * crumbs
  }
}

/** Same cell, klib **superclass** instead of klib interface. */
sealed class Burrito : UnexportedQuilt() {
  /** The default for `folds` lives on [UnexportedQuilt.tuck], one module away. */
  override fun tuck(folds: Int): Int = folds

  /** The arm that overrides it. */
  data class Snug(val wraps: Int) : Burrito() {
    override fun tuck(folds: Int): Int = folds + wraps
  }
}

/** The `data class` arm behind the interface-rooted sealed base. */
fun anyBiscuit(crumbs: Int): Biscuit = Biscuit.Shortbread(crumbs)

/** The `data class` arm behind the class-rooted sealed base. */
fun anyBurrito(wraps: Int): Burrito = Burrito.Snug(wraps)
