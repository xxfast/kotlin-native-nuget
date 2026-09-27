# A sealed arm's own interfaces are dropped from its C# base list

A sealed arm (`class`/`object` under a `sealed class`, or under an eligible `sealed interface`)
never lists its own interfaces in its generated C# base list: `CirSealedSubclass` has no
`interfaces` slot at all, and `CirSealedRenderer` renders every arm as `class Arm : Base`, never
`class Arm : Base, IFoo`. So `arm is IFoo` is always false in C#, and a C# caller cannot cast an arm
to an interface it genuinely implements in Kotlin, even when that interface has a real, reachable C#
declaration.

Verified by reading (`Tier1InterfaceVarPropertyTest.kt`'s `tier1.ivarseal` cell, `sealed class Perch
{ open val count: Int = 0; class Arm : Perch(), Tally { override var count: Int = 0 } }`): the
generated `Arm` renders `public sealed class Arm : Perch`, with no `ITally` anywhere and no
diagnostic naming the drop. This is the same underlying gap the [interfaces topic
page](../topics/interfaces-abstract-sealed.md#limitations) already named narrowly, for an eligible
sealed interface arm's own *extra* interfaces (`class Odd : Kind, CharSequence`); the `ivarseal` cell
proves it is not limited to that shape, since `Perch` is an ordinary `sealed class`, not a sealed
interface, and `Tally` is not an extra interface beside a sealed-interface parent, it is the arm's
only interface.

One consequence this surfaced directly: [ADR-168](../adr/168-interface-var-explicit-setter.md)'s
explicit-interface-setter shape (a get-only public `override` beside an explicit `IFoo.X { get; set;
}` member) has no sealed-arm equivalent, because there is no `IFoo` in the arm's base list to attach
an explicit member to (one would be `CS0540`, "cannot implement an interface member because it is
not implemented on an interface in the base list"). A sealed arm's `override var` widening a
read-only sealed base and an interface `var` at once therefore stays a plain get-only override with
the ordinary named skip, unlike the same shape on a non-sealed class.

Discovered alongside ADR-168 and [ADR-125](../adr/125-sealed-interface-sibling-arms.md) (which
already named the narrower extra-interfaces case). Fix would give `CirSealedSubclass` its own
`interfaces: List<String>` slot, spelled the same way `translateClass`'s and `translateInterface`'s
base lists already are (`forwardSuperInterfaceSpelling`), and would need to decide whether an
unspellable or unexported interface re-homes its members onto the arm the way ADR-101/ADR-167 already
do for an ordinary class and interface.
