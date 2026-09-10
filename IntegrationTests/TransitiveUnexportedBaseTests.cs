using TestLibrary.Vessel;

namespace IntegrationTests;

/// <summary>
/// ADR-101, transitive half: <c>Dinghy : Skiff : Vessel</c>, where only the *middle* link is
/// outside the export set. The base walk is one hop today, so it finds <c>Skiff</c>, sees it is
/// unexported and gives up, and <c>Dinghy</c> is generated with no base at all: <c>Vessel</c> is
/// lost even though it is exported and generated right next door.
///
/// The contract has two halves that pull in opposite directions, and both are asserted here.
/// The nearest *exported* base must be kept, so <c>Sail</c> is reached through a
/// <c>Vessel</c>-typed reference (inherited, not copied). The dropped middle's own members must
/// re-home onto <c>Dinghy</c>, so <c>Row()</c> and <c>Oars</c> are callable with no
/// <c>Skiff</c> type anywhere on the surface.
///
/// The cats' bathtub navy: Mylo captains the <c>Vessel</c>, Oreo bails.
/// </summary>
public class TransitiveUnexportedBaseTests
{
    [Fact]
    public void Dinghy_ExtendsTheNearestExportedBase_NotNothing()
    {
        Assert.Equal(typeof(Vessel), typeof(Dinghy).BaseType);
    }

    [Fact]
    public void Sail_IsInheritedFromTheExportedGrandBase_ThroughABaseTypedReference()
    {
        // The half a "re-home everything onto Dinghy" fix would fail: the inheritance relation
        // itself is the contract, not just the reachability of the members.
        using Vessel v = new Dinghy();

        Assert.Equal("Dinghy sails", v.Sail());
    }

    [Fact]
    public void Row_DeclaredOnTheDroppedMiddle_ReHomesOntoDinghy()
    {
        using var d = new Dinghy();

        Assert.Equal("rowing with 2 oars", d.Row());
    }

    [Fact]
    public void Oars_PropertyOnTheDroppedMiddle_ReHomesOntoDinghy()
    {
        using var d = new Dinghy();

        Assert.Equal(2, d.Oars);
    }

    [Fact]
    public void Bail_DeclaredOnDinghyItself_SurvivesTheDroppedMiddle()
    {
        // The half that "just don't export the class" would also satisfy, and must not.
        using var d = new Dinghy();

        Assert.Equal("bailing", d.Bail());
    }

    [Fact]
    public void NoTypeNamedSkiff_IsEmittedForTheDroppedMiddle()
    {
        // The other admissible shape -- generating the unexported middle anyway to hold the chain
        // together -- is explicitly not what we chose. Interop.cs compiles into
        // IntegrationTests.dll, so that assembly is the right haystack.
        var emitted = typeof(Dinghy).Assembly
            .GetTypes()
            .Where(t => t.Name == "Skiff")
            .ToList();

        Assert.Empty(emitted);
    }
}
