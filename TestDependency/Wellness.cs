namespace Test.Wellness;

/// <summary>
/// ADR-085 follow-up fixture (cross-package enum slot import): declared in a namespace
/// independent of <see cref="Test.Menagerie"/>, then consumed as an INBOUND (setter/parameter)
/// type on a bound interface member declared in that other namespace
/// (<see cref="Test.Menagerie.IPerformer.Energy"/>). The generated `{Iface}Bindings.kt` slot body
/// must import this type by its fully bound Kotlin name; a slot body that references it only by
/// simple name compiles solely by accident when the enum happens to live in the same bound
/// package as the interface it is used from. Nibbles the goat's daily energy level.
/// </summary>
public enum EnergyLevel
{
    Low,
    Medium,
    High,
}
