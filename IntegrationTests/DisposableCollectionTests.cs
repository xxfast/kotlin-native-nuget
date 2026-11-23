using TestLibrary.Adoption;

namespace IntegrationTests;

/// <summary>
/// ADR-243 (issue #610), round 1 probes. A returned read-only <c>List</c> of handles will become a
/// disposable collection whose <c>Dispose()</c> releases every element. A consumer who forgot to
/// keep an element is then holding a wrapper whose handle is the zero sentinel. These facts pin
/// what that consumer sees today, by doing the collection's <c>Dispose()</c> by hand: read the
/// list, dispose every element, then touch one. The contract is <see cref="ObjectDisposedException"/>,
/// never a crashed host or a garbage answer.
///
/// One fact per probe, so a native crash is attributable to exactly one member route. Each probe
/// asserts the live answer first, so a red result is the dispose and not a broken fixture.
///
/// Oreo and Mylo went home with a family, and the family gave them straight back.
/// </summary>
public class DisposableCollectionTests
{
    /// <summary>What the collection's <c>Dispose()</c> will do to every element.</summary>
    private static void Release<T>(IEnumerable<T> elements) where T : IDisposable
    {
        foreach (T element in elements) element.Dispose();
    }

    // ---- Interface elements: `IAdoptable` read from the `Pets` property getter ----

    [Fact]
    public void Pets_ElementUsedAfterCollectionDispose_ThrowsObjectDisposedException()
    {
        using var centre = new AdoptionCentre();
        IReadOnlyList<IAdoptable> pets = centre.Pets;
        IAdoptable oreo = pets[0];
        Assert.Equal("Oreo", oreo.Name);

        Release(pets);

        Assert.Throws<ObjectDisposedException>(() => oreo.Name);
    }

    [Fact]
    public void AdoptablePet_GreetAfterDispose_ThrowsObjectDisposedException()
    {
        using var centre = new AdoptionCentre();
        IReadOnlyList<IAdoptable> pets = centre.Pets;
        IAdoptable mylo = pets[1];
        Assert.Equal("Hi, I'm Mylo, please adopt me", mylo.Greet());

        Release(pets);

        Assert.Throws<ObjectDisposedException>(() => mylo.Greet());
    }

    [Fact]
    public void AdoptablePet_ToStringAfterDispose_ThrowsObjectDisposedException()
    {
        using var centre = new AdoptionCentre();
        IReadOnlyList<IAdoptable> pets = centre.Pets;
        IAdoptable oreo = pets[0];
        Assert.NotNull(oreo.ToString());

        Release(pets);

        Assert.Throws<ObjectDisposedException>(() => oreo.ToString());
    }

    [Fact]
    public void AdoptablePet_DisposedReceiverEqualsLiveElement_ThrowsObjectDisposedException()
    {
        using var centre = new AdoptionCentre();
        IReadOnlyList<IAdoptable> pets = centre.Pets;
        IReadOnlyList<IAdoptable> again = centre.Pets;
        IAdoptable oreo = pets[0];
        IAdoptable liveOreo = again[0];
        Assert.False(oreo.Equals(liveOreo));   // two fresh Kittens, identity equality in Kotlin

        Release(pets);

        try
        {
            Assert.Throws<ObjectDisposedException>(() => oreo.Equals(liveOreo));
        }
        finally
        {
            Release(again);
        }
    }

    [Fact]
    public void AdoptablePet_LiveReceiverEqualsDisposedArgument_ThrowsObjectDisposedException()
    {
        // ARGUMENT probe: the disposed element is the parameter, the receiver is live.
        using var centre = new AdoptionCentre();
        IReadOnlyList<IAdoptable> pets = centre.Pets;
        IReadOnlyList<IAdoptable> again = centre.Pets;
        IAdoptable oreo = pets[0];
        IAdoptable liveOreo = again[0];
        Assert.False(liveOreo.Equals(oreo));

        Release(pets);

        try
        {
            Assert.Throws<ObjectDisposedException>(() => liveOreo.Equals(oreo));
        }
        finally
        {
            Release(again);
        }
    }

    [Fact]
    public void AdoptablePet_GetHashCodeAfterDispose_ThrowsObjectDisposedException()
    {
        using var centre = new AdoptionCentre();
        IReadOnlyList<IAdoptable> pets = centre.Pets;
        IAdoptable mylo = pets[1];
        _ = mylo.GetHashCode();

        Release(pets);

        Assert.Throws<ObjectDisposedException>(() => mylo.GetHashCode());
    }

    // ---- Plain class elements: `Kitten` read from the `Kittens()` method return ----

    [Fact]
    public void Kitten_NameAfterDispose_ThrowsObjectDisposedException()
    {
        using var centre = new AdoptionCentre();
        IReadOnlyList<Kitten> kittens = centre.Kittens();
        Kitten oreo = kittens[0];
        Assert.Equal("Oreo", oreo.Name);

        Release(kittens);

        Assert.Throws<ObjectDisposedException>(() => oreo.Name);
    }

    [Fact]
    public void Kitten_PurrAfterDispose_ThrowsObjectDisposedException()
    {
        using var centre = new AdoptionCentre();
        IReadOnlyList<Kitten> kittens = centre.Kittens();
        Kitten mylo = kittens[1];
        Assert.Equal("Mylo purrs like a tiny engine", mylo.Purr());

        Release(kittens);

        Assert.Throws<ObjectDisposedException>(() => mylo.Purr());
    }

    [Fact]
    public void Kitten_ToStringAfterDispose_ThrowsObjectDisposedException()
    {
        using var centre = new AdoptionCentre();
        IReadOnlyList<Kitten> kittens = centre.Kittens();
        Kitten oreo = kittens[0];
        Assert.NotNull(oreo.ToString());

        Release(kittens);

        Assert.Throws<ObjectDisposedException>(() => oreo.ToString());
    }

    [Fact]
    public void Kitten_DisposedReceiverEqualsLiveElement_ThrowsObjectDisposedException()
    {
        using var centre = new AdoptionCentre();
        IReadOnlyList<Kitten> kittens = centre.Kittens();
        IReadOnlyList<Kitten> again = centre.Kittens();
        Kitten oreo = kittens[0];
        Kitten liveOreo = again[0];
        Assert.False(oreo.Equals(liveOreo));

        Release(kittens);

        try
        {
            Assert.Throws<ObjectDisposedException>(() => oreo.Equals(liveOreo));
        }
        finally
        {
            Release(again);
        }
    }

    [Fact]
    public void Kitten_LiveReceiverEqualsDisposedArgument_ThrowsObjectDisposedException()
    {
        // ARGUMENT probe: the disposed element is the parameter, the receiver is live.
        using var centre = new AdoptionCentre();
        IReadOnlyList<Kitten> kittens = centre.Kittens();
        IReadOnlyList<Kitten> again = centre.Kittens();
        Kitten oreo = kittens[0];
        Kitten liveOreo = again[0];
        Assert.False(liveOreo.Equals(oreo));

        Release(kittens);

        try
        {
            Assert.Throws<ObjectDisposedException>(() => liveOreo.Equals(oreo));
        }
        finally
        {
            Release(again);
        }
    }

    [Fact]
    public void Kitten_GetHashCodeAfterDispose_ThrowsObjectDisposedException()
    {
        using var centre = new AdoptionCentre();
        IReadOnlyList<Kitten> kittens = centre.Kittens();
        Kitten mylo = kittens[1];
        _ = mylo.GetHashCode();

        Release(kittens);

        Assert.Throws<ObjectDisposedException>(() => mylo.GetHashCode());
    }
}
