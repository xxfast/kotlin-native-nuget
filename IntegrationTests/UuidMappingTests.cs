using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// Issue <a href="https://github.com/xxfast/kotlin-native-nuget/issues/56">#56</a> part 3 /
/// ADR-106: <c>kotlin.uuid.Uuid</c> maps to <see cref="Guid"/> and <c>Uuid?</c> to <c>Guid?</c> at
/// every ordinary position: property (val and var), return, constructor parameter and method
/// parameter. The human decision fixes the wire as the hex-dash <em>string</em> rather than the two
/// 64-bit halves the ADR picks; no assertion here can tell the difference, which is exactly why the
/// byte-order vector below is the load-bearing one.
/// <para>
/// Today <c>Uuid</c> is an unexported stdlib dependency, so <see cref="Microchip"/> has no
/// <c>ChipId</c>, no usable constructor, and none of the methods. This file cannot compile until
/// the feature ships.
/// </para>
/// <para>
/// Oreo has been re-chipped once, Mylo never has.
/// </para>
/// </summary>
public class UuidMappingTests
{
    // The vector Kotlin builds with Uuid.parse(). Every one of the 16 bytes is distinct, so any
    // reordering (endian flip, halves swapped, .NET's mixed-endian Guid(byte[]) ctor) changes the
    // rendered string. One assertion, total coverage of the byte order.
    private const string WellKnown = "00112233-4455-6677-8899-aabbccddeeff";

    private static Microchip Chipped() => new Microchip(Guid.Parse(WellKnown), null);

    // --- The byte-order vector: top-level non-null return ---

    [Fact]
    public void WellKnownChip_TopLevelReturn_RendersTheSameStringKotlinParsed()
    {
        Guid chip = MicrochipKt.wellKnownChip();

        Assert.Equal(WellKnown, chip.ToString());
    }

    [Fact]
    public void WellKnownChip_TopLevelReturn_EqualsTheParsedGuid()
    {
        Assert.Equal(Guid.Parse(WellKnown), MicrochipKt.wellKnownChip());
    }

    // --- Guid.Empty <-> Uuid.NIL, across the static (object) export path ---

    [Fact]
    public void Registry_Nil_StaticReturn_IsGuidEmpty()
    {
        Assert.Equal(Guid.Empty, MicrochipRegistry.Nil());
    }

    [Fact]
    public void Registry_IsNil_StaticParameter_TrueForGuidEmpty()
    {
        Assert.True(MicrochipRegistry.IsNil(Guid.Empty));
    }

    [Fact]
    public void Registry_IsNil_StaticParameter_FalseForARealChip()
    {
        Assert.False(MicrochipRegistry.IsNil(Guid.Parse(WellKnown)));
    }

    [Fact]
    public void GuidEmpty_RoundTripsThroughEchoUnchanged()
    {
        // The all-zero value must not be mistaken for "absent" anywhere on the wire.
        using var chip = Chipped();

        Assert.Equal(Guid.Empty, chip.Echo(Guid.Empty));
    }

    // --- A random Guid through ctor, val property and echo ---

    [Fact]
    public void RandomGuid_RoundTripsThroughConstructorAndValProperty()
    {
        // A version-4 Guid has the variant bits set, so the high bit of the low half is set: a
        // signed/unsigned slip on either side shows up here and not in the well-known vector.
        var minted = Guid.NewGuid();

        using var chip = new Microchip(minted, null);

        Assert.Equal(minted, chip.ChipId);
    }

    [Fact]
    public void RandomGuid_RoundTripsThroughEchoMethod()
    {
        var minted = Guid.NewGuid();
        using var chip = Chipped();

        Assert.Equal(minted, chip.Echo(minted));
    }

    [Fact]
    public void RandomGuid_SurvivesTheFullLoop_CtorThenPropertyThenEcho()
    {
        var minted = Guid.NewGuid();

        using var chip = new Microchip(minted, null);

        Assert.Equal(minted, chip.Echo(chip.ChipId));
    }

    // --- Uuid? property: null and a value, read and written ---

    [Fact]
    public void PreviousChipId_NullableVarProperty_ReadsNullWhenKotlinHoldsNull()
    {
        using var chip = new Microchip(Guid.Parse(WellKnown), null);

        // Compile-time contract: Guid?, not Guid.
        Guid? previous = chip.PreviousChipId;

        Assert.Null(previous);
    }

    [Fact]
    public void PreviousChipId_NullableVarProperty_ReadsTheValueSetByTheConstructor()
    {
        var retired = Guid.NewGuid();

        using var chip = new Microchip(Guid.Parse(WellKnown), retired);

        Assert.Equal(retired, chip.PreviousChipId);
    }

    [Fact]
    public void PreviousChipId_NullableVarProperty_AcceptsAValueThroughTheSetter()
    {
        var retired = Guid.NewGuid();
        using var chip = new Microchip(Guid.Parse(WellKnown), null);

        chip.PreviousChipId = retired;

        Assert.Equal(retired, chip.PreviousChipId);
    }

    [Fact]
    public void PreviousChipId_NullableVarProperty_AcceptsNullBackThroughTheSetter()
    {
        using var chip = new Microchip(Guid.Parse(WellKnown), Guid.NewGuid());

        chip.PreviousChipId = null;

        Assert.Null(chip.PreviousChipId);
    }

    [Fact]
    public void PreviousChipId_NullableVarProperty_HoldsGuidEmptyDistinctlyFromNull()
    {
        // Guid.Empty is a legal value, not a sentinel: the has-value channel must carry the
        // difference and default(Guid) must not read back as null.
        using var chip = new Microchip(Guid.Parse(WellKnown), null);

        chip.PreviousChipId = Guid.Empty;

        Assert.NotNull(chip.PreviousChipId);
        Assert.Equal(Guid.Empty, chip.PreviousChipId!.Value);
    }

    // --- Method parameter ---

    [Fact]
    public void Matches_MethodParameter_TrueForTheChipsOwnId()
    {
        using var chip = Chipped();

        Assert.True(chip.Matches(Guid.Parse(WellKnown)));
    }

    [Fact]
    public void Matches_MethodParameter_FalseForAnotherCatsChip()
    {
        using var chip = Chipped();

        Assert.False(chip.Matches(Guid.NewGuid()));
    }

    // --- Non-null and nullable method returns ---

    [Fact]
    public void Reissue_NonNullMethodReturn_ProducesAFreshChip()
    {
        using var chip = Chipped();

        Guid reissued = chip.Reissue();

        Assert.NotEqual(Guid.Empty, reissued);
        Assert.NotEqual(chip.ChipId, reissued);
    }

    [Fact]
    public void LastRetired_NullableMethodReturn_IsNullForANeverRechippedCat()
    {
        using var chip = new Microchip(Guid.Parse(WellKnown), null);

        Assert.Null(chip.LastRetired());
    }

    [Fact]
    public void LastRetired_NullableMethodReturn_CarriesTheValueWhenPresent()
    {
        var retired = Guid.NewGuid();
        using var chip = new Microchip(Guid.Parse(WellKnown), retired);

        Assert.Equal(retired, chip.LastRetired());
    }

    // --- Nullable method parameter (the has-value fan-out) ---

    [Fact]
    public void Describe_NullableMethodParameter_AcceptsNull()
    {
        using var chip = Chipped();

        Assert.Equal("no tag scanned", chip.Describe(null));
    }

    [Fact]
    public void Describe_NullableMethodParameter_RendersTheKotlinSideSpelling()
    {
        // Kotlin's Uuid.toString() is the lowercase hex-dash form, same as Guid's "D" format.
        using var chip = Chipped();

        Assert.Equal($"scanned {WellKnown}", chip.Describe(Guid.Parse(WellKnown)));
    }

    // --- Nullable in and out on one callable ---

    [Fact]
    public void MaybeEcho_NullableInAndOut_RoundTripsNull()
    {
        using var chip = Chipped();

        Assert.Null(chip.MaybeEcho(null));
    }

    [Fact]
    public void MaybeEcho_NullableInAndOut_RoundTripsAValue()
    {
        var tag = Guid.NewGuid();
        using var chip = Chipped();

        Assert.Equal(tag, chip.MaybeEcho(tag));
    }

    // --- Top-level nullable return ---

    [Fact]
    public void ParseChip_TopLevelNullableReturn_ParsesAWellFormedId()
    {
        Assert.Equal(Guid.Parse(WellKnown), MicrochipKt.parseChip(WellKnown));
    }

    [Fact]
    public void ParseChip_TopLevelNullableReturn_IsNullForRubbish()
    {
        Assert.Null(MicrochipKt.parseChip("Mylo chewed the paperwork"));
    }

    // --- The issue's exact repro: a data class whose sole component is a Uuid ---

    [Fact]
    public void ChipRecord_DataClassConstructorAndProperty_RoundTripTheId()
    {
        var minted = Guid.NewGuid();

        using var record = new ChipRecord(minted);

        Assert.Equal(minted, record.Id);
    }

    [Fact]
    public void ChipRecord_HoldsTheWellKnownVectorUnchanged()
    {
        using var record = new ChipRecord(Guid.Parse(WellKnown));

        Assert.Equal(WellKnown, record.Id.ToString());
    }
}
