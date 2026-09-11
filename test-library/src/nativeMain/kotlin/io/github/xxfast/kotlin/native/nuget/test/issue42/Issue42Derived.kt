package io.github.xxfast.kotlin.native.nuget.test.issue42

import dev.other.core.UnexportedBase

/**
 * Issue #42, base-class variant: this class *is* in `rootPackage`, so KSP exports it, but its
 * base class is not, so the base must be dropped with `SKIPPED_UNEXPORTED_SUPERTYPE` while
 * [own] keeps exporting *and* the base's `greet`/`label` start exporting on this class.
 *
 * Deliberately implements no interface: [Issue42Api] next door is the unexported-*interface*
 * fixture, and `CirClassTranslator` only populates a class's `interfaces` list when
 * `forwardSuperClass()` is null, so mixing the two shapes into one fixture would let each mask
 * the other. Oreo owns this one -- black, with just enough white in the middle to prove he is
 * his own class.
 */
class Issue42Derived : UnexportedBase() {
  fun own(): String = "own"

  /**
   * Overrides a base method whose default parameter lives on the dropped base. The override
   * itself cannot restate `warmly = false`, so the omitting overload C# gets can only come from
   * the base's declaration.
   */
  override fun farewell(name: String, warmly: Boolean): String =
    "${super.farewell(name, warmly)} from Oreo"
}
