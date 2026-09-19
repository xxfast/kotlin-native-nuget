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
///
/// `Post : Perch(), Scratchable` is the same shape with the base carrying an unrelated overload of
/// the interface member's name, which a name-only base lookup mistakes for the overridee.
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

    /// <summary>
    /// The harder half of the same rule. `class Post : Perch(), Scratchable` overrides
    /// <c>Scratchable.scratch()</c>, and the base <c>Perch</c> carries an unrelated overload
    /// <c>scratch(strokes: Int)</c>. A base-class lookup keyed on the simple name alone answers
    /// "yes, the base has a Scratch", so <c>Scratch()</c> renders <c>override</c> against a base
    /// whose only <c>Scratch</c> takes an <c>int</c>: CS0115. The overloads are unrelated, so the
    /// zero-arg slot starts on <c>Post</c>.
    /// </summary>
    [Fact]
    public void Post_Scratch_IsAFreshVirtualSlot_NotAnOverrideOfPerchsOverload()
    {
        MethodInfo scratch = typeof(Post).GetMethod("Scratch", Type.EmptyTypes)!;

        Assert.True(scratch.IsVirtual);
        Assert.Equal(typeof(Post), scratch.GetBaseDefinition().DeclaringType);
        // The base's overload is still inherited, and still declared by `Perch`.
        Assert.Equal(typeof(Perch), typeof(Post).GetMethod("Scratch", [typeof(int)])!.DeclaringType);
    }

    [Fact]
    public void Post_Scratch_BothArities_AnswerOnTheOneHandle()
    {
        using var post = new Post();

        // Oreo's own override, then the ledge-side overload he never touched.
        Assert.Equal("Oreo: scratched", post.Scratch());
        Assert.Equal("Perch: 3 strokes", post.Scratch(3));
    }
}
