using System.Linq;
using TestLibrary.Issue54;

namespace IntegrationTests;

/// <summary>
/// The nested-interface gate: a Kotlin <c>interface</c> nested inside an exported class is never
/// declared in C#, because <c>rootInterfaces</c> only collects top-level interfaces and the
/// reachability closure never admits a module-local declaration. Every member typed with it must
/// therefore skip named rather than be spelled.
///
/// A leak would not surface as a nested type. <c>interfaceType</c> renders an interface as a
/// namespace-root <c>I{SimpleName}</c>, so the dangling reference would be a bare
/// <c>global::TestLibrary.Issue54.IListener</c> that nothing declares, and <c>Interop.cs</c> would
/// fail the consumer compile with CS0246. Hence both halves below: no member may mention it, and
/// no type named <c>IListener</c> (or <c>Listener</c>, had the generator declared the backing type
/// instead) may exist anywhere in the assembly.
///
/// The diagnostics themselves (<c>SKIPPED_UNSUPPORTED_TYPE</c>, <c>SKIPPED_UNSUPPORTED_PROPERTY</c>)
/// are asserted at Tier 1; from compiled C# only the absence of the members, and the survival of
/// everything around them, is observable.
///
/// Mylo hears the treat cupboard from three rooms away. Nobody can name the interface he does it
/// through.
/// </summary>
public class NestedInterfaceGateTests
{
    [Fact]
    public void NestedListenerOwner_StillConstructs_AndItsUnrelatedMemberStillBinds()
    {
        // The control half: a gate that drops the whole owning class instead of just its
        // interface-typed members would also make this pass-looking test fail, which is the point.
        using var owner = new NestedListenerOwner();

        Assert.Equal("owner", owner.Name);
    }

    [Fact]
    public void NestedListenerOwner_PropertyPosition_IsSkipped()
    {
        Assert.Null(typeof(NestedListenerOwner).GetProperty("Listener"));
    }

    [Fact]
    public void NestedListenerOwner_ParameterPosition_IsSkipped()
    {
        Assert.Null(typeof(NestedListenerOwner).GetMethod("Attach"));
    }

    [Fact]
    public void NestedListenerOwner_ReturnPosition_IsSkipped()
    {
        Assert.Null(typeof(NestedListenerOwner).GetMethod("Current"));
    }

    [Fact]
    public void NestedListenerOwner_NestedInterface_IsNeverDeclaredAsANestedType()
    {
        // A fix that "declares nested interfaces" instead of gating them would satisfy the member
        // assertions above by emitting NestedListenerOwner.IListener; this pins the chosen
        // behaviour.
        Assert.Null(typeof(NestedListenerOwner).GetNestedType("IListener"));
    }

    [Fact]
    public void TheNestedInterface_ExistsNowhereInTheGeneratedAssembly()
    {
        // Namespace-root spelling is what a leak would actually produce, and no per-type assertion
        // above can see it: `IListener` would sit next to `NestedListenerOwner`, not inside it.
        var strays = typeof(NestedListenerOwner).Assembly
            .GetTypes()
            .Where(type => type.Name is "IListener" or "Listener")
            .Select(type => type.FullName)
            .OrderBy(name => name)
            .ToArray();

        Assert.Empty(strays);
    }
}
