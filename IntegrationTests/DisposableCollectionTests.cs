using System.Collections.ObjectModel;
using System.Reflection;
using System.Runtime.CompilerServices;
using TestLibrary;
using TestLibrary.Adoption;
using TestLibrary.Petlist;
using IPet = TestLibrary.Cat.IPet;

namespace IntegrationTests;

/// <summary>
/// ADR-243 (issue #610). A returned read-only <c>List</c> whose elements carry a handle is a
/// <see cref="KotlinList{T}"/>: still a <c>ReadOnlyCollection&lt;T&gt;</c>, and also
/// <see cref="IDisposable"/>, releasing every element it built that the caller did not
/// <c>Keep</c>. The static type narrows only at direct returns C# cannot override or implement;
/// everywhere else it stays <c>IReadOnlyList&lt;T&gt;</c> and only the runtime type changes.
///
/// A consumer who forgot to keep an element is then holding a wrapper whose handle is the zero
/// sentinel, so the first section pins what that consumer sees: a Kotlin-bridged member throws
/// <see cref="ObjectDisposedException"/>, never crashes the host or answers garbage. One fact per
/// Kotlin-bridged probe, so a native crash is attributable to exactly one member route, and each
/// probe asserts the live answer first, so a red result is the dispose and not a broken fixture.
///
/// Oreo and Mylo went home with a family, and the family gave them straight back.
/// </summary>
public class DisposableCollectionTests
{
    /// <summary>What the collection's <c>Dispose()</c> does to every element, done by hand.</summary>
    private static void Release<T>(IEnumerable<T> elements) where T : IDisposable
    {
        foreach (T element in elements) element.Dispose();
    }

    /// <summary>A C# adopter's own pet: Kotlin can hold it, the collection must never dispose it.</summary>
    private sealed class CountingStray(string name) : IAdoptable
    {
        public int Disposals { get; private set; }
        public string Name { get; } = name;
        public string Greet() => $"Hi, I'm {Name}, the stray from next door";
        public void Dispose() => Disposals++;
    }

    /// <summary>A C# override of the open member: the static type there must stay the interface.</summary>
    private sealed class MyRescue : Rescue
    {
        public override IReadOnlyList<Kitten> Strays() => new[] { new Kitten("Mylo") };
    }

    // ---- Kotlin-bridged members on a disposed element: ObjectDisposedException ----

    [Fact]
    public void Pets_ElementUsedAfterCollectionDispose_ThrowsObjectDisposedException()
    {
        using var centre = new AdoptionCentre();
        IAdoptable mylo;
        using (KotlinList<IAdoptable> pets = centre.Pets)
        {
            mylo = pets[1];
            Assert.Equal("Mylo", mylo.Name);
        }

        Assert.Throws<ObjectDisposedException>(() => mylo.Name);
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

    // ---- Dispose and Keep ----

    [Fact]
    public void Kittens_Dispose_ReleasesEveryElement()
    {
        using var centre = new AdoptionCentre();
        Kitten oreo;
        Kitten mylo;
        using (KotlinList<Kitten> kittens = centre.Kittens())
        {
            oreo = kittens[0];
            mylo = kittens[1];
            Assert.Equal("Oreo", oreo.Name);
        }

        Assert.Throws<ObjectDisposedException>(() => oreo.Name);
        Assert.Throws<ObjectDisposedException>(() => mylo.Purr());
    }

    [Fact]
    public void Kittens_KeptElement_StillAnswersAfterDispose_UntilTheCallerDisposesIt()
    {
        using var centre = new AdoptionCentre();
        Kitten oreo;
        Kitten mylo;
        using (KotlinList<Kitten> kittens = centre.Kittens())
        {
            oreo = kittens.Keep(kittens[0]);
            mylo = kittens[1];
        }

        Assert.Equal("Oreo purrs like a tiny engine", oreo.Purr());
        Assert.Throws<ObjectDisposedException>(() => mylo.Name);

        oreo.Dispose();
        Assert.Throws<ObjectDisposedException>(() => oreo.Name);
    }

    [Fact]
    public void Kittens_DisposeTwice_IsSafe()
    {
        using var centre = new AdoptionCentre();
        KotlinList<Kitten> kittens = centre.Kittens();
        Kitten oreo = kittens[0];

        kittens.Dispose();
        kittens.Dispose();

        Assert.Throws<ObjectDisposedException>(() => oreo.Name);
    }

    [Fact]
    public void Kittens_CollectionDisposedThenElementsByHand_IsSafe()
    {
        using var centre = new AdoptionCentre();
        KotlinList<Kitten> kittens = centre.Kittens();

        kittens.Dispose();
        Release(kittens);
        kittens.Dispose();

        Assert.Throws<ObjectDisposedException>(() => kittens[1].Name);
    }

    // ---- Keep contract ----

    [Fact]
    public void Keep_ReturnsTheSameReference()
    {
        using var centre = new AdoptionCentre();
        using KotlinList<IAdoptable> pets = centre.Pets;
        IAdoptable oreo = pets[0];

        using IAdoptable kept = pets.Keep(oreo);

        Assert.Same(oreo, kept);
    }

    [Fact]
    public void Keep_NonElement_ThrowsArgumentException()
    {
        using var centre = new AdoptionCentre();
        using KotlinList<Kitten> kittens = centre.Kittens();
        using KotlinList<Kitten> others = centre.Kittens();
        using var stranger = new Kitten("Oreo");

        Assert.Throws<ArgumentException>(() => kittens.Keep(stranger));
        Assert.Throws<ArgumentException>(() => kittens.Keep(others[0]));   // same name, other list
    }

    [Fact]
    public void Keep_Null_ThrowsArgumentException()
    {
        using var centre = new AdoptionCentre();
        using KotlinList<Kitten> kittens = centre.Kittens();

        Assert.ThrowsAny<ArgumentException>(() => kittens.Keep(null!));
    }

    [Fact]
    public void Keep_AfterDispose_ThrowsObjectDisposedException()
    {
        using var centre = new AdoptionCentre();
        KotlinList<Kitten> kittens = centre.Kittens();
        Kitten mylo = kittens[1];
        kittens.Dispose();

        Assert.Throws<ObjectDisposedException>(() => kittens.Keep(mylo));
    }

    [Fact]
    public void Keep_Twice_KeepsTheElement()
    {
        using var centre = new AdoptionCentre();
        Kitten mylo;
        using (KotlinList<Kitten> kittens = centre.Kittens())
        {
            mylo = kittens.Keep(kittens[1]);
            Assert.Same(mylo, kittens.Keep(kittens[1]));
        }

        Assert.Equal("Mylo", mylo.Name);
        mylo.Dispose();
    }

    // ---- Still a read-only list: assignable, enumerable, castable as today ----

    private static string Roll(IEnumerable<IAdoptable> pets) => string.Join(", ", pets.Select(p => p.Name));

    [Fact]
    public void Pets_AssignsToTheReadOnlyInterfaces_AndEnumeratesLikeAList()
    {
        using var centre = new AdoptionCentre();
        using KotlinList<IAdoptable> pets = centre.Pets;
        IReadOnlyList<IAdoptable> list = pets;
        IEnumerable<IAdoptable> sequence = pets;
        var inferred = pets;

        var called = new List<string>();
        foreach (IAdoptable pet in inferred) called.Add(pet.Name);

        Assert.Same(pets, list);
        Assert.Same(pets, sequence);
        Assert.Equal(2, list.Count);
        Assert.Equal(new[] { "Oreo", "Mylo" }, called);
        Assert.Equal(new[] { "Oreo", "Mylo" }, sequence.Select(p => p.Name));
        Assert.Equal("Oreo, Mylo", Roll(pets));
    }

    [Fact]
    public void Pets_RuntimeCastsThatWorkToday_KeepWorking()
    {
        using var centre = new AdoptionCentre();
        using KotlinList<IAdoptable> pets = centre.Pets;
        IReadOnlyList<IAdoptable> view = pets;

        ReadOnlyCollection<IAdoptable> collection = Assert.IsAssignableFrom<ReadOnlyCollection<IAdoptable>>(view);
        IList<IAdoptable> ilist = Assert.IsAssignableFrom<IList<IAdoptable>>(view);

        Assert.Equal("Mylo", collection[1].Name);
        Assert.True(ilist.IsReadOnly);
        Assert.Throws<NotSupportedException>(() => ilist.Add(new CountingStray("Biscuit")));
    }

    [Fact]
    public void Pets_ExistingCallerShape_ElementsDisposedByHand_ThenTheCollectionTwice_IsSafe()
    {
        using var centre = new AdoptionCentre();
        IReadOnlyList<IAdoptable> pets = centre.Pets;
        try
        {
            Assert.Equal("Oreo", pets[0].Name);
        }
        finally
        {
            foreach (var p in pets) p.Dispose();
        }

        IDisposable collection = Assert.IsAssignableFrom<IDisposable>(pets);
        collection.Dispose();
        collection.Dispose();

        Assert.Throws<ObjectDisposedException>(() => pets[0].Name);
    }

    // ---- Static types: narrowed only at direct returns C# cannot override or implement ----

    private static Type DeclaredType(Type owner, string member) =>
        owner.GetProperty(member)?.PropertyType ?? owner.GetMethod(member)!.ReturnType;

    [Theory]
    [InlineData(typeof(AdoptionCentre), nameof(AdoptionCentre.Pets), typeof(KotlinList<IAdoptable>))]
    [InlineData(typeof(AdoptionCentre), nameof(AdoptionCentre.Kittens), typeof(KotlinList<Kitten>))]
    [InlineData(typeof(AdoptionCentre), nameof(AdoptionCentre.MaybeKittens), typeof(KotlinList<Kitten>))]
    [InlineData(typeof(AdoptionCentre), nameof(AdoptionCentre.KittensOrNone), typeof(KotlinList<Kitten>))]
    [InlineData(typeof(AdoptionCentre), nameof(AdoptionCentre.Litters), typeof(KotlinList<IReadOnlyList<Kitten>>))]
    [InlineData(typeof(AdoptionCentre), nameof(AdoptionCentre.Echo), typeof(KotlinList<IAdoptable>))]
    [InlineData(typeof(AdoptionDrive), nameof(AdoptionDrive.StreetCats), typeof(KotlinList<Kitten>))]
    public void DirectReturn_StaticTypeIsKotlinList(Type owner, string member, Type expected)
    {
        Assert.Equal(expected, DeclaredType(owner, member));
    }

    [Theory]
    [InlineData(typeof(AdoptionCentre), nameof(AdoptionCentre.Names), typeof(IReadOnlyList<string>))]
    [InlineData(typeof(AdoptionCentre), nameof(AdoptionCentre.Shortlist), typeof(IReadOnlyList<Kitten>))]
    [InlineData(typeof(Rescue), nameof(Rescue.Strays), typeof(IReadOnlyList<Kitten>))]
    [InlineData(typeof(IWard), nameof(IWard.Wards), typeof(IReadOnlyList<Kitten>))]
    [InlineData(typeof(Ward), nameof(Ward.Wards), typeof(IReadOnlyList<Kitten>))]
    [InlineData(typeof(FosterHome), nameof(FosterHome.PetSet), typeof(IReadOnlySet<IPet>))]
    [InlineData(typeof(FosterHome), nameof(FosterHome.ByName), typeof(IReadOnlyDictionary<string, IPet>))]
    [InlineData(typeof(FosterHome), nameof(FosterHome.ResidentsLaterAsync), typeof(Task<IReadOnlyList<IPet>>))]
    [InlineData(typeof(FosterHome), nameof(FosterHome.Headcounts), typeof(KotlinFlow<IReadOnlyList<IPet>>))]
    public void OverridableOrOutOfScopePosition_StaticTypeIsUnchanged(Type owner, string member, Type expected)
    {
        Assert.Equal(expected, DeclaredType(owner, member));
    }

    [Fact]
    public void Echo_Parameter_StillTakesAnyReadOnlyList()
    {
        ParameterInfo pets = Assert.Single(typeof(AdoptionCentre).GetMethod(nameof(AdoptionCentre.Echo))!.GetParameters());

        Assert.Equal(typeof(IReadOnlyList<IAdoptable>), pets.ParameterType);
    }

    [Fact]
    public void MaybeKittens_DeclaresNullableElements_OnANonNullList()
    {
        var context = new NullabilityInfoContext();
        NullabilityInfo info = context.Create(typeof(AdoptionCentre).GetMethod(nameof(AdoptionCentre.MaybeKittens))!.ReturnParameter);

        Assert.Equal(NullabilityState.NotNull, info.ReadState);
        Assert.Equal(NullabilityState.Nullable, Assert.Single(info.GenericTypeArguments).ReadState);
    }

    [Fact]
    public void KittensOrNone_DeclaresANullableList_OfNonNullElements()
    {
        var context = new NullabilityInfoContext();
        NullabilityInfo info = context.Create(typeof(AdoptionCentre).GetMethod(nameof(AdoptionCentre.KittensOrNone))!.ReturnParameter);

        Assert.Equal(NullabilityState.Nullable, info.ReadState);
        Assert.Equal(NullabilityState.NotNull, Assert.Single(info.GenericTypeArguments).ReadState);
    }

    // ---- Runtime type is KotlinList at the positions the static type does not narrow ----

    /// <summary>The runtime type is the disposable list, and disposing it as such releases Oreo.</summary>
    private static void AssertDisposableAtRuntime(IReadOnlyList<Kitten> kittens, string firstName)
    {
        Assert.IsType<KotlinList<Kitten>>(kittens);
        Kitten first = kittens[0];
        Assert.Equal(firstName, first.Name);

        ((IDisposable)kittens).Dispose();

        Assert.Throws<ObjectDisposedException>(() => first.Name);
    }

    [Fact]
    public void Strays_OpenMember_RuntimeTypeIsKotlinList()
    {
        using var rescue = new Rescue();

        AssertDisposableAtRuntime(rescue.Strays(), "Oreo");
    }

    [Fact]
    public void Wards_InterfaceMember_RuntimeTypeIsKotlinList()
    {
        using var centre = new AdoptionCentre();
        using IWard ward = centre.Ward();

        AssertDisposableAtRuntime(ward.Wards, "Mylo");
    }

    [Fact]
    public void Shortlist_VarGetter_RuntimeTypeIsKotlinList()
    {
        using var centre = new AdoptionCentre();

        AssertDisposableAtRuntime(centre.Shortlist, "Mylo");
    }

    [Fact]
    public void Litters_InnerList_RuntimeTypeIsKotlinList()
    {
        using var centre = new AdoptionCentre();
        using KotlinList<IReadOnlyList<Kitten>> litters = centre.Litters();

        AssertDisposableAtRuntime(litters[0], "Oreo");
    }

    [Fact]
    public void Shortlist_Setter_StillAcceptsAnyReadOnlyList()
    {
        using var centre = new AdoptionCentre();
        using var oreo = new Kitten("Oreo");
        using var mylo = new Kitten("Mylo");

        centre.Shortlist = new[] { oreo, mylo };
        Assert.Equal("Oreo,Mylo", centre.ShortlistNames());

        using (KotlinList<Kitten> kittens = centre.Kittens())
        {
            centre.Shortlist = kittens;
        }
        Assert.Equal("Oreo,Mylo", centre.ShortlistNames());
    }

    [Fact]
    public void Rescue_CSharpOverride_StillCompilesAndAnswers()
    {
        using var mine = new MyRescue();
        IReadOnlyList<Kitten> strays = mine.Strays();

        Assert.Equal("Mylo", Assert.Single(strays).Name);
        Release(strays);
    }

    // ---- Nullable element, nullable list ----

    [Fact]
    public void MaybeKittens_Dispose_SkipsTheNull_AndReleasesTheRest()
    {
        using var centre = new AdoptionCentre();
        Kitten oreo;
        Kitten mylo;
        using (KotlinList<Kitten?> maybe = centre.MaybeKittens())
        {
            Assert.Null(maybe[1]);
            oreo = maybe[0]!;
            mylo = maybe[2]!;
        }

        Assert.Throws<ObjectDisposedException>(() => oreo.Name);
        Assert.Throws<ObjectDisposedException>(() => mylo.Name);
    }

    [Fact]
    public void MaybeKittens_KeepNull_ThrowsArgumentException()
    {
        using var centre = new AdoptionCentre();
        using KotlinList<Kitten?> maybe = centre.MaybeKittens();

        Assert.ThrowsAny<ArgumentException>(() => maybe.Keep(null));   // the null is an element, still not keepable
    }

    [Fact]
    public void KittensOrNone_Absent_IsNull_AndUsingItIsFine()
    {
        using var centre = new AdoptionCentre();

        using KotlinList<Kitten>? none = centre.KittensOrNone(false);

        Assert.Null(none);
    }

    [Fact]
    public void KittensOrNone_Present_ReleasesItsElements()
    {
        using var centre = new AdoptionCentre();
        Kitten oreo;
        using (KotlinList<Kitten>? some = centre.KittensOrNone(true))
        {
            Assert.NotNull(some);
            oreo = some[0];
        }

        Assert.Throws<ObjectDisposedException>(() => oreo.Name);
    }

    // ---- Nested lists ----

    [Fact]
    public void Litters_OuterDispose_ReleasesInnerElements()
    {
        using var centre = new AdoptionCentre();
        Kitten oreo;
        Kitten mylo;
        using (KotlinList<IReadOnlyList<Kitten>> litters = centre.Litters())
        {
            oreo = litters[0][0];
            mylo = litters[1][0];
        }

        Assert.Throws<ObjectDisposedException>(() => oreo.Name);
        Assert.Throws<ObjectDisposedException>(() => mylo.Name);
    }

    [Fact]
    public void Litters_KeepOnAnInnerList_SurvivesTheOuterDispose()
    {
        using var centre = new AdoptionCentre();
        Kitten oreo;
        Kitten mylo;
        using (KotlinList<IReadOnlyList<Kitten>> litters = centre.Litters())
        {
            var first = Assert.IsType<KotlinList<Kitten>>(litters[0]);
            oreo = first.Keep(first[0]);
            mylo = litters[1][0];
        }

        Assert.Equal("Oreo", oreo.Name);
        Assert.Throws<ObjectDisposedException>(() => mylo.Name);
        oreo.Dispose();
    }

    [Fact]
    public void Litters_KeepAWholeInnerList_KeepsItsElements()
    {
        using var centre = new AdoptionCentre();
        IReadOnlyList<Kitten> first;
        Kitten mylo;
        using (KotlinList<IReadOnlyList<Kitten>> litters = centre.Litters())
        {
            first = litters.Keep(litters[0]);
            mylo = litters[1][0];
        }

        Kitten oreo = first[0];
        Assert.Equal("Oreo", oreo.Name);
        Assert.Throws<ObjectDisposedException>(() => mylo.Name);

        ((IDisposable)first).Dispose();
        Assert.Throws<ObjectDisposedException>(() => oreo.Name);
    }

    // ---- Echo: only bridge-built wrappers are released ----

    [Fact]
    public void Echo_CSharpElement_IsNeverDisposedByTheCollection()
    {
        using var centre = new AdoptionCentre();
        var biscuit = new CountingStray("Biscuit");

        using (KotlinList<IAdoptable> back = centre.Echo(new IAdoptable[] { biscuit }))
        {
            Assert.Same(biscuit, Assert.Single(back));
        }

        Assert.Equal(0, biscuit.Disposals);
        Assert.Equal("Biscuit", biscuit.Name);
    }

    [Fact]
    public void Echo_KotlinBackedArgument_StaysLiveAfterTheReturnedCollectionIsDisposed()
    {
        using var centre = new AdoptionCentre();
        using var oreo = new Kitten("Oreo");
        IAdoptable echoed;

        using (KotlinList<IAdoptable> back = centre.Echo(new IAdoptable[] { oreo }))
        {
            echoed = Assert.Single(back);
            Assert.NotSame(oreo, echoed);   // a fresh wrapper the collection built
        }

        Assert.Equal("Oreo", oreo.Name);
        Assert.Throws<ObjectDisposedException>(() => echoed.Name);
    }

    // ---- Handle-free control ----

    [Fact]
    public void Names_IsNotDisposableAtRuntime()
    {
        using var centre = new AdoptionCentre();
        IReadOnlyList<string> names = centre.Names;

        Assert.Equal(new[] { "Oreo", "Mylo" }, names);
        Assert.False(names is IDisposable);
    }
}
