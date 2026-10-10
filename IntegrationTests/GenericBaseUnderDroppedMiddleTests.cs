using System.Reflection;
using TestLibrary.Parcel;

namespace IntegrationTests;

/// <summary>
/// ADR-101 amendment (2026-10-10): `class Barge : Keel()` where `open class Keel : Crate&lt;Int&gt;(7)`
/// sits outside the export root. C# sees `public class Barge : Crate&lt;int&gt;`, reaches the generic
/// base's members through it, and declares only what Barge and the dropped Keel declare: the
/// re-homed `Weigh()` and the `suspend` override `LoadAsync()`, which the generic `Crate&lt;T&gt;`
/// cannot carry itself (ADR-147).
///
/// Before this, the shape failed generation outright; with only the base spelling fixed, Barge
/// restated `Crate&lt;T&gt;.Item` as `public override int Item` (CS0506).
/// </summary>
public class GenericBaseUnderDroppedMiddleTests
{
    private const BindingFlags DeclaredOnly =
        BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly;

    [Fact]
    public void Barge_ExtendsCrateOfInt_ThroughTheDroppedKeel()
    {
        Assert.Equal(typeof(Crate<int>), typeof(Barge).BaseType);
    }

    [Fact]
    public async Task Barge_ReachesTheGenericBaseMembers_ThroughACrateReference()
    {
        await using var barge = new Barge();
        Crate<int> crate = barge;

        Assert.Equal(7, crate.Item);
        Assert.Equal("3:7", crate.Describe(3));
        Assert.Equal(5, barge.Pick(5));
    }

    [Fact]
    public async Task Barge_CallsItsOwnOverrides()
    {
        await using var barge = new Barge();

        Assert.Equal("barge:7", barge.Weigh());
        Assert.Equal("barge loaded 7", await barge.LoadAsync());
    }

    /// <summary>
    /// The generic base's own members are inherited, never restated: KSP parents `item` and
    /// `describe(tag: T)` to Keel, the class that closes `T`, and Keel does not declare them.
    /// </summary>
    [Fact]
    public void Barge_DeclaresOnlyWhatBargeAndKeelDeclare()
    {
        Assert.Null(typeof(Barge).GetProperty("Item", DeclaredOnly));
        Assert.DoesNotContain(
            typeof(Barge).GetMethods(DeclaredOnly),
            method => method.Name is "Describe" or "Pick" or "Label");

        Assert.NotNull(typeof(Barge).GetMethod("Weigh", DeclaredOnly));
        Assert.NotNull(typeof(Barge).GetMethod("LoadAsync", DeclaredOnly));
        Assert.Null(typeof(Crate<int>).GetMethod("LoadAsync"));
    }
}
