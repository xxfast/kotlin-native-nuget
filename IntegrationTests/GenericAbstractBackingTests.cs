using TestLibrary.Cubby;

namespace IntegrationTests;

/// <summary>
/// A Kotlin handle that materialises as a generic abstract class at a closed type
/// (<c>Trove&lt;string&gt;</c>) constructs an internal backing wrapper on the non-generic
/// <c>Trove</c> holder, since C# cannot construct the abstract class (CS0144) and cannot nest the
/// wrapper in the generic class (CS7042). Every member answers as the Kotlin subclass behind it.
/// </summary>
public class GenericAbstractBackingTests
{
    [Fact]
    public void GenericAbstractClass_ReturnedClosedAtReferenceType_CallsThrough()
    {
        using Trove<string> trove = CubbySample.Stock();

        Assert.True(typeof(Trove<>).IsAbstract);
        // Materialised as the wrapper, not as the exported subclass.
        Assert.IsNotType<OreoTrove>(trove);
        Assert.Equal("tuna", trove.First);
        Assert.Equal("tuna flake", trove.Pick());
        Assert.Equal("Oreo", trove.Keeper);
        Assert.Equal("Oreo guards the tuna", trove.Describe());
    }

    [Fact]
    public void GenericAbstractClass_ReturnedClosedAtPrimitive_CallsThrough()
    {
        using Trove<int> trove = CubbySample.Rations();

        Assert.Equal(3, trove.First);
        Assert.Equal(6, trove.Pick());
        Assert.Equal("Mylo", trove.Keeper);
        // `MyloTrove` inherits the open member, so Kotlin answers with the base's body.
        Assert.Equal("Mylo keeps 3", trove.Describe());
    }

    [Fact]
    public void ConcreteGenericSubclass_ReturnedAsBase_CallsThrough()
    {
        using Trove<string> coffer = CubbySample.Coffer();

        Assert.Equal("ribbon", coffer.First);
        Assert.Equal("ribbon", coffer.Pick());
        Assert.Equal("coffer", coffer.Keeper);
        Assert.Equal("coffer keeps ribbon", coffer.Describe());
    }

    [Fact]
    public void AbstractClassBelowGenericAbstractBase_ReturnedAsItself_CallsThrough()
    {
        using var hutch = new Hutch();
        using Alcove alcove = hutch.Alcove();

        Assert.True(typeof(Alcove).IsAbstract);
        Trove<string> trove = Assert.IsAssignableFrom<Trove<string>>(alcove);
        // `Pick` and `Keeper` are left open by `Trove<string>` and answered over its exports.
        Assert.Equal("catnip", trove.First);
        Assert.Equal("catnip mouse", trove.Pick());
        Assert.Equal("Oreo", trove.Keeper);
        Assert.Equal("Oreo keeps catnip", trove.Describe());
        Assert.Equal(2, alcove.Depth());
    }

    [Fact]
    public void TwoParameterGenericAbstractClass_ReturnedClosed_CallsThrough()
    {
        using Reckoner<string, int> reckoner = CubbySample.Reckoner();

        Assert.True(typeof(Reckoner<,>).IsAbstract);
        Assert.Equal("breakfast", reckoner.First);
        Assert.Equal(6, reckoner.Count("dinner"));
    }

    [Fact]
    public void ReturnedAbstractClass_PassedBackToKotlin_DispatchesToTheSubclass()
    {
        using var hutch = new Hutch();
        using Alcove alcove = hutch.Alcove();

        // The wrapper's handle goes back in as the parameter; Kotlin dispatches to `OreoAlcove`.
        Assert.Equal("Oreo: catnip mouse at 2", CubbySample.Peek(alcove));
    }

    [Fact]
    public void GenericAbstractWrapper_DisposedTwice_DoesNotThrow()
    {
        Trove<string> trove = CubbySample.Stock();

        Assert.Equal("Oreo", trove.Keeper);
        // The wrapper implements the abstract `Dispose`; LeakTests proves the release.
        Assert.Null(Record.Exception(() => { trove.Dispose(); trove.Dispose(); }));
    }
}
