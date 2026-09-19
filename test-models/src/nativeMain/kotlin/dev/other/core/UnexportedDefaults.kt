package dev.other.core

/**
 * The interface half of the klib-rooted default cell, and the supertype twin of [UnexportedBase]
 * for a **sealed** subclass.
 *
 * Declared in `dev.other.core`, outside `:test-library`'s `rootPackage`, so the overridee is
 * resolved out of klib metadata rather than out of source. That is the distinction the same-module
 * `Squishy`/`Pose` pins in `issue115/PoseSample.kt` cannot draw: there, KSP already reports
 * `hasDefault = true` on the override's own parameter, so the sealed base pass synthesizes its
 * ADR-096 omitting overload from the raw bit. This shape pins that klib metadata carries that bit
 * onto the override the same way source does (verified by execution, 2026-09-19).
 *
 * One member only, and the sealed base overrides it, so nothing here needs ADR-101's dropped-base
 * re-homing to reach C#.
 */
interface UnexportedFluffy {
  /** [pats] defaults here, one module away, and nowhere else. */
  fun fluff(pats: Int = 2): Int
}

/**
 * The open-class half of the same cell: a Kotlin `sealed class` may extend an open class, so this
 * is the superclass shape of [UnexportedFluffy]. [UnexportedBase] already covers an ordinary
 * exported class over a klib base (`Issue42Derived.farewell`); this one is here so a **sealed**
 * base can sit over a klib class too.
 *
 * Deliberately carries nothing but the overridable member, so the ADR-101 unexported-supertype
 * drop has no non-overridden member to re-home and cannot smear this cell into that one.
 */
open class UnexportedQuilt {
  /** [folds] defaults here; a subclass override cannot restate it. */
  open fun tuck(folds: Int = 3): Int = folds
}
