package io.github.xxfast.kotlin.native.nuget.test.lounge

import io.github.xxfast.kotlin.native.nuget.hidden.Cushion
import io.github.xxfast.kotlin.native.nuget.hidden.Nesting

/**
 * ADR-075 amendment: an exported abstract class inheriting abstract `val`/`var` members from an
 * UNEXPORTED abstract BASE CLASS it does not implement.
 *
 * The interface twin is `Nester : Nesting`, which stays untouched. Here ADR-101 drops `: Cushion`
 * from the C# base list (`SKIPPED_UNEXPORTED_SUPERTYPE`) and re-homes the base's members onto
 * `Lounger`, but the declaration catalog is planned over interfaces only, so today [weave] and
 * [loft] are dropped with no diagnostic while [Beanbag] still renders `public override`: the
 * generated `Interop.cs` fails CS0115 on its own text. `Lounger` must declare
 * `public abstract string Weave { get; }` and `public abstract int Loft { get; set; }` itself;
 * `Cushion.stuffing` must be skipped named (`SKIPPED_UNSUPPORTED_PROPERTY` at `Lounger.stuffing`),
 * not crash. `Cushion.squish` is the control and already renders abstract here.
 *
 * [describe] reads both bridgeable members through Kotlin's own dispatch, so a C# write to [loft]
 * is observable rather than echoed back by the C# getter. Oreo naps on the corduroy one.
 */
abstract class Lounger : Cushion() {
  fun describe(): String = "$weave@$loft"
}

/** Overrides all four inherited members. Compiles in C# only once [Lounger] declares them. */
class Beanbag : Lounger() {
  override val weave: String = "corduroy"
  override var loft: Int = 4
  override val stuffing: Nesting.Lining = Nesting.Lining()

  override fun squish(): String = "$weave squished from $loft"
}
