using System.Reflection;
using TestLibrary.Ledge;

namespace IntegrationTests;

/// <summary>
/// ADR-101 amendment: `class Ledge : Shelf(), Groomable` must render
/// `public class Ledge : Shelf, IGroomable`. Keeping a base class no longer empties the interface
/// list, and `override` in C# means "overrides a base *class* member", so `Groom()` (which
/// implements an interface member the base knows nothing about) is `virtual`, not `override`.
///
/// Mylo grooms himself on the window ledge; the ledge itself is just a shelf, three up.
///
/// The compile is most of the proof. `IGroomable g = new Ledge()` is CS0266 while the interface
/// list is dropped, and the generated `public override string Groom()` is CS0115 against a `Shelf`
/// that declares no `Groom`. The assertions make the rest of the rule visible: the base survives,
/// the inherited default is bound on `Ledge`, and `Groom` is a fresh virtual slot.
/// </summary>
public class InterfaceBesideBaseTests
{
    [Fact]
    public void Ledge_IsAnIGroomable()
    {
        // The assignment itself is the assertion: no cast, no `IsAssignableFrom` escape hatch.
        using IGroomable groomable = new Ledge();

        Assert.NotNull(groomable);
    }

    [Fact]
    public void Ledge_KeepsItsBaseClass()
    {
        Assert.Equal(typeof(Shelf), typeof(Ledge).BaseType);
    }

    [Fact]
    public void Ledge_ReachesTheBaseMemberThroughAnInterfaceReference()
    {
        using IGroomable groomable = new Ledge();

        // Both halves of the base list are live on the one handle.
        Assert.Equal(3, ((Shelf)groomable).Height());
    }

    [Fact]
    public void Ledge_Groom_RunsItsOwnOverride()
    {
        using IGroomable groomable = new Ledge();

        Assert.Equal("Mylo: groomed", groomable.Groom());
    }

    [Fact]
    public void Ledge_Brushes_BindsTheInheritedDefault()
    {
        using IGroomable groomable = new Ledge();

        // Kotlin's default body, bound on `Ledge` because the interface list demands it.
        Assert.Equal(1, groomable.Brushes());
    }

    [Fact]
    public void Ledge_Groom_IsVirtual()
    {
        MethodInfo groom = typeof(Ledge).GetMethod("Groom")!;

        Assert.True(groom.IsVirtual);
    }

    [Fact]
    public void Ledge_Groom_IsNotAnOverride()
    {
        MethodInfo groom = typeof(Ledge).GetMethod("Groom")!;

        // `Shelf` declares no `Groom`, so the slot starts on `Ledge`. An `override` here is CS0115.
        Assert.Equal(typeof(Ledge), groom.GetBaseDefinition().DeclaringType);
    }
}
