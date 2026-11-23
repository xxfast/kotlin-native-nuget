using System.Runtime.CompilerServices;
using TestLibrary.Adoption;

namespace IntegrationTests;

/// <summary>
/// ADR-243 (issue #610). A returned read-only <c>List</c> of handles will become a disposable
/// collection whose <c>Dispose()</c> releases every element. A consumer who forgot to keep an
/// element is then holding a wrapper whose handle is the zero sentinel. These facts pin what that
/// consumer sees, by doing the collection's <c>Dispose()</c> by hand: read the list, dispose every
/// element, then touch one. A Kotlin-bridged member must throw <see cref="ObjectDisposedException"/>,
/// never crash the host or answer garbage.
///
/// One fact per Kotlin-bridged probe, so a native crash is attributable to exactly one member
/// route. Each probe asserts the live answer first, so a red result is the dispose and not a
/// broken fixture.
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

    // ---- Kotlin-bridged members on a disposed element: ObjectDisposedException ----

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

    // ---- `object` members on a disposed plain-class or interface element: managed defaults ----
    //
    // The generator emits Kotlin-bridged `Equals`/`GetHashCode`/`ToString` for data classes only.
    // A plain class (`Kitten`) or an interface backing wrapper (`Adoptable`) inherits the managed
    // `object` defaults, which never touch the handle, so after dispose they are the correct, safe
    // answer: no throw, reference equality, runtime identity hash, type name.

    private static void AssertManagedDefaults(object disposed, object live)
    {
        Assert.Equal(disposed.GetType().FullName, disposed.ToString());
        Assert.Equal(RuntimeHelpers.GetHashCode(disposed), disposed.GetHashCode());
        Assert.True(disposed.Equals(disposed));
        Assert.False(disposed.Equals(live));
        Assert.False(live.Equals(disposed));   // disposed as the ARGUMENT, live receiver
    }

    [Fact]
    public void AdoptablePet_ObjectMembersAfterDispose_AreTheManagedDefaults()
    {
        using var centre = new AdoptionCentre();
        IReadOnlyList<IAdoptable> pets = centre.Pets;
        IReadOnlyList<IAdoptable> again = centre.Pets;
        Assert.Equal("Oreo", pets[0].Name);

        Release(pets);

        try
        {
            AssertManagedDefaults(pets[0], again[0]);
        }
        finally
        {
            Release(again);
        }
    }

    [Fact]
    public void Kitten_ObjectMembersAfterDispose_AreTheManagedDefaults()
    {
        using var centre = new AdoptionCentre();
        IReadOnlyList<Kitten> kittens = centre.Kittens();
        IReadOnlyList<Kitten> again = centre.Kittens();
        Assert.Equal("Oreo", kittens[0].Name);

        Release(kittens);

        try
        {
            AssertManagedDefaults(kittens[0], again[0]);
        }
        finally
        {
            Release(again);
        }
    }

    // ---- Data-class receiver: the three bridges reach Kotlin, so they must be guarded ----
    //
    // `AdoptionRecord` is a data class that keeps a handle (class-typed component plus a `var`), so
    // its `Equals`/`GetHashCode`/`ToString` call Kotlin. Disposed RECEIVER only: the disposed
    // ARGUMENT case (`live.Equals(disposed)`) is tracked as a separate bug and kills the host today.

    [Fact]
    public void Record_DayAfterDispose_ThrowsObjectDisposedException()
    {
        using var centre = new AdoptionCentre();
        var paperwork = centre.Record();
        Assert.Equal(3, paperwork.Day);

        paperwork.Dispose();

        Assert.Throws<ObjectDisposedException>(() => paperwork.Day);
    }

    [Fact]
    public void Record_EqualsAfterDispose_ThrowsObjectDisposedException()
    {
        using var centre = new AdoptionCentre();
        var paperwork = centre.Record();
        using var live = centre.Record();
        Assert.True(paperwork.Equals(live));   // Kotlin data equals: same Oreo, same day

        paperwork.Dispose();

        Assert.Throws<ObjectDisposedException>(() => paperwork.Equals(live));
    }

    [Fact]
    public void Record_GetHashCodeAfterDispose_ThrowsObjectDisposedException()
    {
        using var centre = new AdoptionCentre();
        var paperwork = centre.Record();
        using var live = centre.Record();
        Assert.Equal(live.GetHashCode(), paperwork.GetHashCode());

        paperwork.Dispose();

        Assert.Throws<ObjectDisposedException>(() => paperwork.GetHashCode());
    }

    [Fact]
    public void Record_ToStringAfterDispose_ThrowsObjectDisposedException()
    {
        using var centre = new AdoptionCentre();
        var paperwork = centre.Record();
        Assert.Contains("day=3", paperwork.ToString());

        paperwork.Dispose();

        Assert.Throws<ObjectDisposedException>(() => paperwork.ToString());
    }
}
