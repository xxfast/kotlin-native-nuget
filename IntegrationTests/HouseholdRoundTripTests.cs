using TestLibrary;
using TestLibrary.Household;

namespace IntegrationTests;

// ROADMAP "Tooling & Test Integrity": the adversarial reverse fixture. Every reverse fixture
// before Test.Nullability bound exactly one class per namespace, and no exported
// nullable-returning function ever threw. Test.Household is Oreo and Mylo's house — three
// bound classes sharing one namespace (Cat, Toy, Household), plus an oblivious island
// (StrayCat) — deliberately combining every hard case that shallowness let slip through CI:
//
//   C# IntegrationTests
//     -> (forward bridge, Interop.cs)        HouseholdSample.*
//       -> Kotlin test-library             HouseholdSample.kt
//         -> (reverse bridge, ADR-050/051/053) test.household.{Cat,Toy,Household,StrayCat}
//           -> real C# TestDependency NuGet  Test.Household.{Cat,Toy,Household,StrayCat}
public class HouseholdRoundTripTests
{
    [Fact]
    public void DescribeCat_Oreo_ReturnsNameAndAge()
    {
        // Oreo: black with white in the middle, like the biscuit. Cat.Describe and Toy.Describe
        // deliberately share a method name across two different bound types (not an overload
        // set — each type declares it exactly once).
        string result = HouseholdSample.describeCat("Oreo", 4);
        Assert.Equal("Oreo, age 4", result);
    }

    [Fact]
    public void DescribeToy_FeatherWand_ReturnsLabel()
    {
        // Same method name as Cat.Describe, different bound type.
        string result = HouseholdSample.describeToy("feather wand");
        Assert.Equal("a toy called feather wand", result);
    }

    [Fact]
    public void FavoriteToyRoundTrip_Mylo_SetsGetsAndClearsToNull()
    {
        // Mylo: brown and creamy, like the drink Milo. Cat.FavoriteToy is a settable, nullable
        // handle-typed property that cross-references Toy from the same namespace.
        string? result = HouseholdSample.favoriteToyRoundTrip("Mylo", 3, "cream ribbon");
        Assert.Equal("cream ribbon", result);
    }

    [Fact]
    public void FindToyLabel_OreoIndoorEligible_ReturnsLabel()
    {
        // Household.FindToy has several parameters ahead of its nullable annotated return —
        // guards the metadata reader's positional-vs-SequenceNumber parameter lookup bug.
        string? result = HouseholdSample.findToyLabel("Oreo", 2, indoorOnly: true, "feather wand");
        Assert.Equal("feather wand", result);
    }

    [Fact]
    public void FindToyLabel_MyloNotIndoorOnly_ReturnsNull()
    {
        // Same parameter shape, `indoorOnly` flipped: if the reader's parameter lookup were
        // shifted, this would silently read the wrong parameter and pass regardless.
        string? result = HouseholdSample.findToyLabel("Mylo", 2, indoorOnly: false, "squeaky mouse");
        Assert.Null(result);
    }

    [Fact]
    public void FetchFavoriteToyLabelOrThrow_Oreo_ReturnsFeatherWand()
    {
        // The reverse-bound Household.FetchFavorite never throws (reverse exception propagation
        // is Phase 11); this is its non-null happy path.
        string? result = HouseholdSample.fetchFavoriteToyLabelOrThrow("Oreo");
        Assert.Equal("feather wand", result);
    }

    [Fact]
    public void FetchFavoriteToyLabelOrThrow_Mylo_ReturnsNull()
    {
        // Mylo has no favourite on record in this household — a nullable return that is
        // genuinely null, not a thrown exception.
        string? result = HouseholdSample.fetchFavoriteToyLabelOrThrow("Mylo");
        Assert.Null(result);
    }

    [Fact]
    public void FetchFavoriteToyLabelOrThrow_BlankCatName_ThrowsArgumentException()
    {
        // Guards bug 3: an *exported* nullable-returning function that throws instead of
        // returning null — the forward two-call nullable P/Invoke's throwing path (the pattern
        // that used to omit hasSyncErrorOut and SIGBUS the host process).
        Assert.ThrowsAny<ArgumentException>(
            () => HouseholdSample.fetchFavoriteToyLabelOrThrow(""));
    }

    [Fact]
    public void FetchFavoriteToyLabelOrThrow_BlankCatName_IsExactType_KotlinArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => HouseholdSample.fetchFavoriteToyLabelOrThrow(""));
        Assert.IsType<KotlinArgumentException>(ex);
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
    }

    [Fact]
    public void AnnounceStray_MatchingName_ReturnsAnnouncement()
    {
        // Oblivious island (`#nullable disable`): binds non-null, but StrayCat.Announce still
        // legitimately returns a value here — the happy path.
        string result = HouseholdSample.announceStray("Ghost", "Ghost");
        Assert.Equal("a stray named Ghost", result);
    }

    [Fact]
    public void AnnounceStray_MismatchedName_ThrowsInvalidOperationException()
    {
        // StrayCat.Announce returns a genuine C# null here even though its oblivious signature
        // reads `string`, not `string?`. The generated Kotlin stub's ADR-053 decision-1a
        // fail-fast guard should turn that into an IllegalStateException naming the member —
        // not corrupt memory, not silently return garbage, and not crash the process (unlike a
        // reverse-thunk exception, this null never crosses as an exception at all: it crosses as
        // an ordinary IntPtr.Zero, and the guard that rejects it runs entirely in Kotlin).
        Assert.ThrowsAny<InvalidOperationException>(
            () => HouseholdSample.announceStray("Ghost", "Whiskers"));
    }

    [Fact]
    public void AnnounceStray_MismatchedName_IsExactType_KotlinInvalidOperationException()
    {
        var ex = Assert.ThrowsAny<InvalidOperationException>(
            () => HouseholdSample.announceStray("Ghost", "Whiskers"));
        Assert.IsType<KotlinInvalidOperationException>(ex);
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.IllegalStateException", ke.KotlinType);
    }
}
