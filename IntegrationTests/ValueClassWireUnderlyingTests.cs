using TestLibrary.Cat;
using TestLibrary.Furcoat;

namespace IntegrationTests;

/// <summary>
/// ADR-235: a value class over <c>Instant</c>, <c>Duration</c>, <c>Uuid</c>, <c>ByteArray</c>, a
/// collection, another value class or a plain interface binds as a record struct whose underlying
/// property is that type's C# spelling (<c>DateTimeOffset</c>, <c>TimeSpan</c>, <c>Guid</c>,
/// <c>byte[]</c>, <c>IReadOnlyList&lt;string&gt;</c>, the inner struct, <c>IRumbler</c>) and crosses
/// on that type's own wire. Kotlin's <c>init</c> runs on the re-wrap, so an invalid struct fails
/// at the crossing with the usual <c>KotlinArgumentException</c>.
///
/// Oreo (black, white middle) and Mylo (brown and creamy) hang their coats of record on one rack:
/// a sunbeam start, a snooze, a chip, a paw print, a toy basket, a shelter tag and a purr.
/// </summary>
public class ValueClassWireUnderlyingTests
{
    private static readonly DateTimeOffset OreoSunbeam = DateTimeOffset.FromUnixTimeSeconds(1_700_000_000);
    private static readonly Guid OreoChipId = Guid.Parse("0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9");
    private static readonly Guid MyloChipId = Guid.Parse("1b2c3d4e-5f60-7182-93a4-b5c6d7e8f90a");

    // Mylo implements the interface himself, so the interface underlying's slot is exercised from
    // the C# side too, not only through a Kotlin-backed wrapper.
    private sealed class MyloRumbler : IRumbler
    {
        public string Owner => "Mylo";
        public string Rumble(int times) => "Mylo: " + string.Concat(Enumerable.Repeat("mrrp", times));
        public void Dispose() { }
    }

    // --- Instant ---------------------------------------------------------------------------

    [Fact]
    public void Instant_KotlinMinted_ReadsAsDateTimeOffset()
    {
        SunbeamStart start = FurcoatSample.OreoStart();
        Assert.Equal(OreoSunbeam, start.At);
    }

    [Fact]
    public void Instant_ParamAndReturn_RoundTrips()
    {
        var start = new SunbeamStart(OreoSunbeam);
        Assert.Equal(start, FurcoatSample.EchoStart(start));
    }

    [Fact]
    public void Instant_Nullable_RoundTripsNullAndValue()
    {
        Assert.Null(FurcoatSample.MaybeStart(null));
        Assert.Equal(new SunbeamStart(OreoSunbeam), FurcoatSample.MaybeStart(new SunbeamStart(OreoSunbeam)));
    }

    [Fact]
    public void Instant_InitRunsInKotlin_RejectsAPreEpochSunbeam()
    {
        Assert.ThrowsAny<ArgumentException>(
            () => FurcoatSample.EchoStart(new SunbeamStart(DateTimeOffset.FromUnixTimeSeconds(-60))));
    }

    // --- Duration --------------------------------------------------------------------------

    [Fact]
    public void Duration_KotlinMinted_ReadsAsTimeSpan()
    {
        Assert.Equal(TimeSpan.FromMinutes(45), FurcoatSample.MyloSnooze().Span);
    }

    [Fact]
    public void Duration_ParamReachesKotlinIntact()
    {
        Assert.Equal(20L, FurcoatSample.SnoozeMinutes(new SnoozeSpan(TimeSpan.FromMinutes(20))));
    }

    [Fact]
    public void Duration_ParamAndReturn_RoundTrips()
    {
        var snooze = new SnoozeSpan(TimeSpan.FromMinutes(90));
        Assert.Equal(snooze, FurcoatSample.EchoSnooze(snooze));
    }

    [Fact]
    public void Duration_Nullable_RoundTripsNullAndValue()
    {
        Assert.Null(FurcoatSample.MaybeSnooze(null));
        Assert.Equal(new SnoozeSpan(TimeSpan.FromMinutes(5)), FurcoatSample.MaybeSnooze(new SnoozeSpan(TimeSpan.FromMinutes(5))));
    }

    [Fact]
    public void Duration_InitRunsInKotlin_RejectsANegativeSnooze()
    {
        Assert.ThrowsAny<ArgumentException>(
            () => FurcoatSample.EchoSnooze(new SnoozeSpan(TimeSpan.FromMinutes(-5))));
    }

    // --- Uuid ------------------------------------------------------------------------------

    [Fact]
    public void Uuid_KotlinMinted_ReadsAsGuid()
    {
        Assert.Equal(OreoChipId, FurcoatSample.OreoChip().Id);
    }

    [Fact]
    public void Uuid_ParamAndReturn_RoundTrips()
    {
        var chip = new ChipSerial(MyloChipId);
        Assert.Equal(chip, FurcoatSample.EchoChip(chip));
    }

    [Fact]
    public void Uuid_Nullable_RoundTripsNullAndValue()
    {
        Assert.Null(FurcoatSample.MaybeChip(null));
        Assert.Equal(new ChipSerial(OreoChipId), FurcoatSample.MaybeChip(new ChipSerial(OreoChipId)));
    }

    [Fact]
    public void Uuid_InitRunsInKotlin_RejectsTheNilChip()
    {
        Assert.ThrowsAny<ArgumentException>(() => FurcoatSample.EchoChip(new ChipSerial(Guid.Empty)));
    }

    // --- ByteArray -------------------------------------------------------------------------

    [Fact]
    public void ByteArray_KotlinMinted_ReadsAsBytes()
    {
        Assert.Equal(new byte[] { 4, 1, 1, 1, 1 }, FurcoatSample.MyloPaw().Pads);
    }

    [Fact]
    public void ByteArray_ParamAndReturn_RoundTripsTheBytes()
    {
        // A byte[] record struct compares by reference, so the bytes are the assertion.
        PawPrint echoed = FurcoatSample.EchoPaw(new PawPrint(new byte[] { 9, 8, 7 }));
        Assert.Equal(new byte[] { 9, 8, 7 }, echoed.Pads);
    }

    [Fact]
    public void ByteArray_Nullable_RoundTripsNullAndValue()
    {
        Assert.Null(FurcoatSample.MaybePaw(null));
        Assert.Equal(new byte[] { 3 }, FurcoatSample.MaybePaw(new PawPrint(new byte[] { 3 }))!.Value.Pads);
    }

    [Fact]
    public void ByteArray_InitRunsInKotlin_RejectsAnEmptyPrint()
    {
        Assert.ThrowsAny<ArgumentException>(() => FurcoatSample.EchoPaw(new PawPrint(Array.Empty<byte>())));
    }

    [Fact]
    public void ByteArray_CollectionElement_RoundTrips()
    {
        IReadOnlyList<PawPrint> prints = FurcoatSample.PawPrints(3);
        Assert.Equal(new byte[] { 2 }, prints[1].Pads);
        Assert.Equal(3, FurcoatSample.CountPads(prints));
    }

    // --- Collection ------------------------------------------------------------------------

    [Fact]
    public void Collection_KotlinMinted_ReadsAsReadOnlyList()
    {
        Assert.Equal(new[] { "mouse", "feather" }, FurcoatSample.OreoBasket().Toys);
    }

    [Fact]
    public void Collection_ParamAndReturn_RoundTripsTheToys()
    {
        ToyBasket echoed = FurcoatSample.EchoBasket(new ToyBasket(new List<string> { "yarn", "box" }));
        Assert.Equal(new[] { "yarn", "box" }, echoed.Toys);
    }

    [Fact]
    public void Collection_Nullable_RoundTripsNullAndValue()
    {
        Assert.Null(FurcoatSample.MaybeBasket(null));
        Assert.Equal(new[] { "laser" }, FurcoatSample.MaybeBasket(new ToyBasket(new List<string> { "laser" }))!.Value.Toys);
    }

    [Fact]
    public void Collection_InitRunsInKotlin_RejectsAnOverfullBasket()
    {
        Assert.ThrowsAny<ArgumentException>(
            () => FurcoatSample.EchoBasket(new ToyBasket(new List<string> { "a", "b", "c", "d" })));
    }

    [Fact]
    public void Collection_CollectionElement_RoundTrips()
    {
        IReadOnlyList<ToyBasket> baskets = FurcoatSample.Baskets();
        Assert.Equal(new[] { "yarn", "box" }, baskets[1].Toys);
        Assert.Equal(3, FurcoatSample.CountToys(baskets));
    }

    [Fact]
    public void Collection_StructComparesItsListByContent()
    {
        var toys = new List<string> { "yarn" };
        Assert.Equal(new ToyBasket(toys), new ToyBasket(toys));
        // Two distinct lists with the same toys in the same order are the same basket.
        var oreo = new ToyBasket(new List<string> { "yarn", "mouse" });
        var mylo = new ToyBasket(new List<string> { "yarn", "mouse" });
        Assert.Equal(oreo, mylo);
        Assert.True(oreo == mylo);
        Assert.Equal(oreo.GetHashCode(), mylo.GetHashCode());
        // Order is part of a list's content.
        Assert.NotEqual(oreo, new ToyBasket(new List<string> { "mouse", "yarn" }));
        Assert.NotEqual(oreo, new ToyBasket(new List<string> { "yarn" }));
        // Every crossing copies, so the echoed struct holds a new list, and still equals the original.
        var basket = new ToyBasket(toys);
        ToyBasket echoed = FurcoatSample.EchoBasket(basket);
        Assert.NotSame(basket.Toys, echoed.Toys);
        Assert.Equal(basket, echoed);
        Assert.Equal(basket.GetHashCode(), echoed.GetHashCode());
    }

    [Fact]
    public void ByteArray_StructComparesItsBytesByContent()
    {
        var oreo = new PawPrint(new byte[] { 4, 1, 1 });
        var mylo = new PawPrint(new byte[] { 4, 1, 1 });
        Assert.Equal(oreo, mylo);
        Assert.True(oreo == mylo);
        Assert.False(oreo != mylo);
        Assert.Equal(oreo.GetHashCode(), mylo.GetHashCode());
        Assert.NotEqual(oreo, new PawPrint(new byte[] { 4, 1 }));
        Assert.NotEqual(oreo, new PawPrint(new byte[] { 1, 1, 4 }));
        // A crossing copies the bytes, and the echo still equals what went in.
        PawPrint echoed = FurcoatSample.EchoPaw(oreo);
        Assert.NotSame(oreo.Pads, echoed.Pads);
        Assert.Equal(oreo, echoed);
        // A nested struct over a bytes struct compares through it.
        Assert.Equal(new PawCast(oreo), new PawCast(mylo));
        Assert.Equal(new PawCast(oreo), FurcoatSample.EchoCast(new PawCast(mylo)));
    }

    [Fact]
    public void ByteArray_StructInASetAndAMap_MatchesByContent()
    {
        IReadOnlySet<PawPrint> prints = FurcoatSample.PawSet();
        Assert.Equal(2, prints.Count);
        Assert.Equal(3, FurcoatSample.CountSetPads(prints));
        // A lookup with a freshly built struct over equal bytes matches the Kotlin-minted element.
        Assert.Contains(new PawPrint(new byte[] { 1 }), prints);
        Assert.Contains(new PawPrint(new byte[] { 2, 2 }), prints);
        Assert.DoesNotContain(new PawPrint(new byte[] { 2 }), prints);
        IReadOnlyDictionary<string, PawPrint> owned = FurcoatSample.PawsByOwner();
        Assert.Equal(new byte[] { 4, 4, 4 }, owned["Oreo"].Pads);
        Assert.Equal(new PawPrint(new byte[] { 4, 4, 4 }), owned["Oreo"]);
        Assert.Equal(3, FurcoatSample.CountMapPads(owned));
        // Equal bytes are one key, so the second assignment replaces Oreo with Mylo.
        var byPaw = new Dictionary<PawPrint, string>
        {
            [new PawPrint(new byte[] { 1 })] = "Oreo",
            [new PawPrint(new byte[] { 1 })] = "Mylo",
            [new PawPrint(new byte[] { 2 })] = "Oreo",
        };
        Assert.Equal(2, byPaw.Count);
        Assert.Equal("Mylo,Oreo", FurcoatSample.OwnersByPaw(byPaw));
        Assert.Equal("Mylo", byPaw[new PawPrint(new byte[] { 1 })]);
        // A set built in C# collapses equal prints before it crosses.
        var set = new HashSet<PawPrint> { new(new byte[] { 3 }), new(new byte[] { 3 }) };
        Assert.Single(set);
        Assert.Equal(1, FurcoatSample.CountSetPads(set));
    }

    [Fact]
    public void Collection_StructInAHashSet_MatchesByContent()
    {
        var baskets = new HashSet<ToyBasket>
        {
            new(new List<string> { "yarn" }),
            new(new List<string> { "yarn" }),
            new(new List<string> { "mouse" }),
        };
        Assert.Equal(2, baskets.Count);
        Assert.Contains(new ToyBasket(new List<string> { "mouse" }), baskets);
        Assert.Contains(FurcoatSample.Baskets()[0], baskets);
    }

    // --- Nested value class ----------------------------------------------------------------

    [Fact]
    public void Nested_OverAReferenceUnderlying_RefusesDefaultAtTheCrossing()
    {
        Assert.Equal(new byte[] { 5 }, FurcoatSample.EchoCast(new PawCast(new PawPrint(new byte[] { 5 }))).Print.Pads);
        Assert.Throws<ArgumentException>(() => FurcoatSample.EchoCast(default));
        Assert.Throws<ArgumentException>(() => FurcoatSample.EchoCast(new PawCast(default)));
    }

    [Fact]
    public void Nested_KotlinMinted_ReadsTheInnerStruct()
    {
        ShelterTag tag = FurcoatSample.MyloTag();
        Assert.Equal(new ChipSerial(MyloChipId), tag.Serial);
        Assert.Equal(MyloChipId, tag.Serial.Id);
    }

    [Fact]
    public void Nested_ParamAndReturn_RoundTrips()
    {
        var tag = new ShelterTag(new ChipSerial(OreoChipId));
        Assert.Equal(tag, FurcoatSample.EchoTag(tag));
    }

    [Fact]
    public void Nested_Nullable_RoundTripsNullAndValue()
    {
        Assert.Null(FurcoatSample.MaybeTag(null));
        var tag = new ShelterTag(new ChipSerial(OreoChipId));
        Assert.Equal(tag, FurcoatSample.MaybeTag(tag));
    }

    [Fact]
    public void Nested_OuterInitRunsInKotlin_RejectsAnFTag()
    {
        var serial = new ChipSerial(Guid.Parse("f0000000-0000-4000-8000-000000000001"));
        Assert.ThrowsAny<ArgumentException>(() => FurcoatSample.EchoTag(new ShelterTag(serial)));
    }

    [Fact]
    public void Nested_InnerInitRunsInKotlin_RejectsTheNilChip()
    {
        Assert.ThrowsAny<ArgumentException>(
            () => FurcoatSample.EchoTag(new ShelterTag(new ChipSerial(Guid.Empty))));
    }

    // --- Plain interface -------------------------------------------------------------------

    [Fact]
    public void Interface_KotlinMinted_DispatchesThroughTheWrapper()
    {
        PurrVoice voice = FurcoatSample.OreoVoice();
        using IRumbler rumbler = voice.Rumbler;
        Assert.Equal("Oreo", rumbler.Owner);
        Assert.Equal("Oreo: prrprr", rumbler.Rumble(2));
    }

    [Fact]
    public void Interface_KotlinBacked_ParamReachesKotlin()
    {
        PurrVoice voice = FurcoatSample.OreoVoice();
        using IRumbler rumbler = voice.Rumbler;
        Assert.Equal("Oreo: prrprrprr", FurcoatSample.HearPurr(voice, 3));
    }

    [Fact]
    public void Interface_CSharpImplemented_ParamReachesKotlin()
    {
        using var mylo = new MyloRumbler();
        Assert.Equal("Mylo: mrrpmrrp", FurcoatSample.HearPurr(new PurrVoice(mylo), 2));
    }

    [Fact]
    public void Interface_CSharpImplemented_ReturnsTheSameInstance()
    {
        using var mylo = new MyloRumbler();
        Assert.Same(mylo, FurcoatSample.EchoVoice(new PurrVoice(mylo)).Rumbler);
    }

    [Fact]
    public void Interface_Nullable_RoundTripsNullAndValue()
    {
        Assert.Null(FurcoatSample.MaybeVoice(null));
        using var mylo = new MyloRumbler();
        Assert.Same(mylo, FurcoatSample.MaybeVoice(new PurrVoice(mylo))!.Value.Rumbler);
    }

    [Fact]
    public void Interface_InitRunsInKotlin_RejectsAnOwnerlessPurr()
    {
        using var nobody = new NobodyRumbler();
        Assert.ThrowsAny<ArgumentException>(() => FurcoatSample.EchoVoice(new PurrVoice(nobody)));
    }

    private sealed class NobodyRumbler : IRumbler
    {
        public string Owner => "";
        public string Rumble(int times) => "";
        public void Dispose() { }
    }

    [Fact]
    public void Interface_CollectionElement_RoundTrips()
    {
        IReadOnlyList<PurrVoice> choir = FurcoatSample.Choir();
        try
        {
            Assert.Equal("Mylo", choir[1].Rumbler.Owner);
            Assert.Equal("Oreo,Mylo", FurcoatSample.Owners(choir));
        }
        finally
        {
            foreach (PurrVoice voice in choir) voice.Rumbler.Dispose();
        }
    }

    // --- Members declared on the value classes: the receiver crosses on the underlying's wire ------

    [Fact]
    public void Members_InstantReceiver_MethodPropertyAndValueClassResult()
    {
        var start = new SunbeamStart(OreoSunbeam);
        Assert.Equal(90L, start.MinutesUntil(new SunbeamStart(OreoSunbeam.AddMinutes(90))));
        Assert.Equal(OreoSunbeam.AddHours(1), start.AnHourLater().At);
        Assert.Equal(1_700_000_000L / 60, start.EpochMinutes);
    }

    [Fact]
    public void Members_DurationAndUuidAndNestedReceivers()
    {
        var snooze = new SnoozeSpan(TimeSpan.FromMinutes(25));
        Assert.Equal(TimeSpan.FromMinutes(50), snooze.Doubled().Span);
        Assert.Equal(25L, snooze.Minutes);
        Assert.Equal("0a1b2c3d", new ChipSerial(OreoChipId).Prefix);
        Assert.Equal("1b2c3d4e", new ShelterTag(new ChipSerial(MyloChipId)).ChipPrefix);
    }

    [Fact]
    public void Members_HandleWiredReceivers_MintAndReleaseTheirHandle()
    {
        var paw = new PawPrint(new byte[] { 1, 2 });
        Assert.Equal(2, paw.PadCount);
        Assert.Equal(new byte[] { 1, 2, 3 }, paw.WithPad(3).Pads);
        Assert.True(new ToyBasket(new List<string> { "yarn" }).Has("yarn"));
        using var mylo = new MyloRumbler();
        Assert.Equal("Mylo: mrrpmrrp", new PurrVoice(mylo).Purr(2));
        PurrVoice oreo = FurcoatSample.OreoVoice();
        using IRumbler rumbler = oreo.Rumbler;
        Assert.Equal("Oreo: prr", oreo.Purr(1));
    }

    [Fact]
    public void Members_InitRunsInKotlinOnTheReceiver()
    {
        Assert.ThrowsAny<ArgumentException>(() => new SnoozeSpan(TimeSpan.FromMinutes(-1)).Doubled());
        Assert.ThrowsAny<ArgumentException>(() => new PawPrint(Array.Empty<byte>()).PadCount);
    }

    // --- Secondary constructors run in Kotlin and hand back the underlying -----------------------

    [Fact]
    public void SecondaryConstructors_BuildTheUnderlyingInKotlin()
    {
        Assert.Equal(TimeSpan.FromMinutes(20), new SnoozeSpan(20).Span);
        Assert.Equal(new byte[] { 7, 7, 7 }, new PawPrint(7, 3).Pads);
        Assert.Equal(new[] { "laser" }, new ToyBasket("laser").Toys);
        Assert.Equal(OreoChipId, new ShelterTag(OreoChipId.ToString()).Serial.Id);
    }

    [Fact]
    public void SecondaryConstructors_InitRunsInKotlin()
    {
        Assert.ThrowsAny<ArgumentException>(() => new SnoozeSpan(-5));
        Assert.ThrowsAny<ArgumentException>(() => new PawPrint(1, 0));
        Assert.ThrowsAny<ArgumentException>(() => new ShelterTag(Guid.Empty.ToString()));
    }

    // --- default(V) of a reference-typed underlying is refused before it crosses ------------------

    [Fact]
    public void Default_ReferenceUnderlying_IsRefusedAtTheCrossing()
    {
        Assert.Throws<ArgumentException>(() => FurcoatSample.EchoPaw(default));
        Assert.Throws<ArgumentException>(() => FurcoatSample.EchoBasket(default));
        Assert.Throws<ArgumentException>(() => FurcoatSample.EchoVoice(default));
        Assert.Throws<ArgumentException>(() => FurcoatSample.MaybePaw(default(PawPrint)));
        Assert.Throws<ArgumentException>(() => FurcoatSample.CountPads(new[] { default(PawPrint) }));
        Assert.Throws<ArgumentException>(() => default(PawPrint).PadCount);
        Assert.Null(FurcoatSample.MaybePaw(null));
    }

    // --- Class positions: constructor, val, nullable var, member param and return -------------

    // The Kotlin-backed `IRumbler` wrapper is this side's own handle; the rack holds the Kotlin
    // object itself, so the wrapper is released as soon as the rack is built.
    private static CoatRack OreoRack()
    {
        PurrVoice voice = FurcoatSample.OreoVoice();
        using IRumbler rumbler = voice.Rumbler;
        return new(
            new SunbeamStart(OreoSunbeam),
            new SnoozeSpan(TimeSpan.FromMinutes(30)),
            new ChipSerial(OreoChipId),
            new PawPrint(new byte[] { 1, 2 }),
            new ToyBasket(new List<string> { "mouse" }),
            new ShelterTag(new ChipSerial(MyloChipId)),
            voice);
    }

    [Fact]
    public void Class_ConstructorParameters_ReachKotlin()
    {
        using CoatRack rack = OreoRack();
        Assert.Equal(
            $"1700000000|30|{OreoChipId}|1,2|mouse|{MyloChipId}|Oreo: prrprr",
            rack.Describe());
    }

    [Fact]
    public void Class_ValProperties_ReadBack()
    {
        using CoatRack rack = OreoRack();
        Assert.Equal(OreoSunbeam, rack.Start.At);
        Assert.Equal(TimeSpan.FromMinutes(30), rack.Snooze.Span);
        Assert.Equal(OreoChipId, rack.Chip.Id);
        Assert.Equal(new byte[] { 1, 2 }, rack.Paw.Pads);
        Assert.Equal(new[] { "mouse" }, rack.Basket.Toys);
        Assert.Equal(MyloChipId, rack.Tag.Serial.Id);
        using IRumbler rumbler = rack.Voice.Rumbler;
        Assert.Equal("Oreo", rumbler.Owner);
    }

    [Fact]
    public void Class_NullableVarProperties_StartNullAndTakeAValue()
    {
        using CoatRack rack = OreoRack();
        Assert.Null(rack.LastStart);
        Assert.Null(rack.LastSnooze);
        Assert.Null(rack.LastChip);
        Assert.Null(rack.LastPaw);
        Assert.Null(rack.LastBasket);
        Assert.Null(rack.LastTag);
        Assert.Null(rack.LastVoice);

        using var mylo = new MyloRumbler();
        rack.LastStart = new SunbeamStart(OreoSunbeam);
        rack.LastSnooze = new SnoozeSpan(TimeSpan.FromMinutes(12));
        rack.LastChip = new ChipSerial(MyloChipId);
        rack.LastPaw = new PawPrint(new byte[] { 5 });
        rack.LastBasket = new ToyBasket(new List<string> { "yarn" });
        rack.LastTag = new ShelterTag(new ChipSerial(OreoChipId));
        rack.LastVoice = new PurrVoice(mylo);

        Assert.Equal(OreoSunbeam, rack.LastStart!.Value.At);
        Assert.Equal(TimeSpan.FromMinutes(12), rack.LastSnooze!.Value.Span);
        Assert.Equal(MyloChipId, rack.LastChip!.Value.Id);
        Assert.Equal(new byte[] { 5 }, rack.LastPaw!.Value.Pads);
        Assert.Equal(new[] { "yarn" }, rack.LastBasket!.Value.Toys);
        Assert.Equal(OreoChipId, rack.LastTag!.Value.Serial.Id);
        Assert.Same(mylo, rack.LastVoice!.Value.Rumbler);

        rack.LastSnooze = null;
        Assert.Null(rack.LastSnooze);
    }

    [Fact]
    public void Class_NullableVarSetter_RunsInitInKotlin()
    {
        using CoatRack rack = OreoRack();
        Assert.ThrowsAny<ArgumentException>(() => rack.LastSnooze = new SnoozeSpan(TimeSpan.FromMinutes(-1)));
    }

    [Fact]
    public void Class_MemberParamAndReturn_Lengthens()
    {
        using CoatRack rack = OreoRack();
        Assert.Equal(TimeSpan.FromMinutes(45), rack.Lengthen(new SnoozeSpan(TimeSpan.FromMinutes(15))).Span);
    }

    // --- Erased generic position (ADR-171 box pair over the underlying's wire) -----------------

    [Fact]
    public void Erased_GenericClass_DurationUnderlying_RoundTrips()
    {
        var snooze = new SnoozeSpan(TimeSpan.FromMinutes(40));
        using var box = new Box<SnoozeSpan>(snooze);
        Assert.Equal(snooze, box.Value);
    }

    [Fact]
    public void Erased_GenericClass_NestedUnderlying_RoundTrips()
    {
        var tag = new ShelterTag(new ChipSerial(OreoChipId));
        using var box = new Box<ShelterTag>(tag);
        Assert.Equal(tag, box.Value);
    }

    [Fact]
    public void Erased_GenericClass_ByteArrayUnderlying_RoundTrips()
    {
        using var box = new Box<PawPrint>(new PawPrint(new byte[] { 7, 7 }));
        Assert.Equal(new byte[] { 7, 7 }, box.Value.Pads);
    }

    [Fact]
    public void Erased_GenericClass_InitRunsInKotlin()
    {
        Assert.ThrowsAny<ArgumentException>(() => new Box<SnoozeSpan>(new SnoozeSpan(TimeSpan.FromMinutes(-3))));
    }

    [Fact]
    public void Erased_GenericClass_InstantUuidCollectionAndInterfaceUnderlyings_RoundTrip()
    {
        var start = new SunbeamStart(DateTimeOffset.FromUnixTimeSeconds(1_700_000_000));
        using (var box = new Box<SunbeamStart>(start)) Assert.Equal(start, box.Value);
        var chip = new ChipSerial(OreoChipId);
        using (var box = new Box<ChipSerial>(chip)) Assert.Equal(chip, box.Value);
        var basket = new ToyBasket(new List<string> { "yarn", "mouse" });
        using (var box = new Box<ToyBasket>(basket)) Assert.Equal(basket, box.Value);
        var mylo = new MyloRumbler();
        using (var box = new Box<PurrVoice>(new PurrVoice(mylo))) Assert.Same(mylo, box.Value.Rumbler);
        var cast = new PawCast(new PawPrint(new byte[] { 2 }));
        using (var box = new Box<PawCast>(cast)) Assert.Equal(cast, box.Value);
    }

    [Fact]
    public void Erased_GenericClass_DefaultReferencePayload_IsRefusedBeforeItCrosses()
    {
        Assert.ThrowsAny<ArgumentException>(() => new Box<PawPrint>(default));
    }

    // --- ADR-240: content equality over a set, a map, an interface and a default payload ----------

    [Fact]
    public void Set_StructComparesItsSetIgnoringOrder()
    {
        var oreo = new TestLibrary.Pawpost.PostStack(new HashSet<string> { "bill", "card" });
        var mylo = new TestLibrary.Pawpost.PostStack(new HashSet<string> { "card", "bill" });
        Assert.Equal(oreo, mylo);
        Assert.Equal(oreo.GetHashCode(), mylo.GetHashCode());
        Assert.NotEqual(oreo, new TestLibrary.Pawpost.PostStack(new HashSet<string> { "bill" }));
        Assert.Equal(oreo, TestLibrary.Pawpost.PostOffice.EchoStack(mylo));
    }

    [Fact]
    public void Map_StructComparesSameKeysWithEqualValues()
    {
        var oreo = new TestLibrary.Pawpost.PostLedger(new Dictionary<string, int> { ["bill"] = 1, ["card"] = 2 });
        var mylo = new TestLibrary.Pawpost.PostLedger(new Dictionary<string, int> { ["card"] = 2, ["bill"] = 1 });
        Assert.Equal(oreo, mylo);
        Assert.Equal(oreo.GetHashCode(), mylo.GetHashCode());
        Assert.NotEqual(oreo, new TestLibrary.Pawpost.PostLedger(new Dictionary<string, int> { ["bill"] = 1, ["card"] = 3 }));
        Assert.NotEqual(oreo, new TestLibrary.Pawpost.PostLedger(new Dictionary<string, int> { ["bill"] = 1, ["parcel"] = 2 }));
        Assert.Equal(oreo, TestLibrary.Pawpost.PostOffice.EchoLedger(mylo));
    }

    [Fact]
    public void Interface_StructKeepsReferenceEquality()
    {
        var oreo = new MyloRumbler();
        Assert.Equal(new PurrVoice(oreo), new PurrVoice(oreo));
        Assert.NotEqual(new PurrVoice(oreo), new PurrVoice(new MyloRumbler()));
    }

    [Fact]
    public void Default_ReferencePayloadStructs_EqualEachOtherAndHashToZero()
    {
        Assert.Equal(default(PawPrint), default(PawPrint));
        Assert.Equal(0, default(PawPrint).GetHashCode());
        Assert.Equal(default(ToyBasket), default(ToyBasket));
        Assert.Equal(0, default(ToyBasket).GetHashCode());
        Assert.NotEqual(default(PawPrint), new PawPrint(Array.Empty<byte>()));
        Assert.Equal(0, default(TestLibrary.Pawpost.PostStack).GetHashCode());
        Assert.Equal(0, default(TestLibrary.Pawpost.PostLedger).GetHashCode());
    }
}
