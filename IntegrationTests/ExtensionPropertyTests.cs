using Test.Menagerie;
using TestLibrary;
using TestLibrary.Cat;
using TestLibrary.Clinic;

namespace IntegrationTests;

public class ExtensionPropertyTests
{
    [Fact]
    public void Cat_GetIsKitten_ReturnsTrueForNewCatWithNineLives()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.True(cat.GetIsKitten());
    }

    [Fact]
    public void Cat_GetIsKitten_ReturnsFalseForCatWithFewLivesLeft()
    {
        using var cat = new Cat("Mylo", 3);
        Assert.False(cat.GetIsKitten());
    }

    [Fact]
    public void Cat_GetIsKitten_ReturnsFalseAtExactlySevenLives()
    {
        using var cat = new Cat("Oreo", 7);
        Assert.False(cat.GetIsKitten());
    }

    [Fact]
    public void Cat_GetLabel_ReturnsNameWithMood()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.Equal("Oreo (sleepy)", cat.GetLabel());
    }

    [Fact]
    public void Cat_GetLabel_ReturnsMyloWithMood()
    {
        using var cat = new Cat("Mylo", 9);
        Assert.Equal("Mylo (sleepy)", cat.GetLabel());
    }

    [Fact]
    public void String_GetWordCount_ReturnsTwoForTwoWords()
    {
        Assert.Equal(2, "hello world".GetWordCount());
    }

    [Fact]
    public void String_GetWordCount_ReturnsOneForSingleWord()
    {
        Assert.Equal(1, "Oreo".GetWordCount());
    }

    [Fact]
    public void String_GetWordCount_IgnoresLeadingAndTrailingSpaces()
    {
        Assert.Equal(2, "  Oreo Mylo  ".GetWordCount());
    }

    [Fact]
    public void String_GetWordCount_CountsCatNames()
    {
        Assert.Equal(3, "Oreo and Mylo".GetWordCount());
    }

    // ---- ADR-132 receiver lowering at the extension-property position ----

    // A C#-implemented `Pet`, so an interface receiver has to route through the ADR-084 bridge
    // factory (`NugetMarshal.HandleOf`, no `_handle` field on this object) and dispose the minted
    // transfer handle afterwards. Rex belongs to the neighbours; Oreo tolerates him.
    private sealed class Dog(string name) : IPet
    {
        public string Name { get; } = name;
        public int Legs => 4;
        public string? Nickname => null;
        public string Vibe => "waggy";
        public string Speak() => "Woof!";
        public string Greet() => $"Hi, I'm {Name} the dog";
        public string Fetch(string item) => $"{Name} enthusiastically fetches the {item}";
        public void Nap() { }
        public void Dispose() { }
    }

    // Interface receiver, Kotlin-backed wrapper: `Cat` is an `Animal` is a `Pet`, so the receiver
    // crosses as Oreo's own StableRef handle (nothing minted, nothing to dispose).
    [Fact]
    public void PetReceiver_GetSummary_KotlinBackedCat()
    {
        using var oreo = new Cat("Oreo", 9);
        Assert.Equal("Oreo/4/Meow! My name is Oreo", oreo.GetSummary());
    }

    // Interface receiver, C#-implemented: Kotlin composes the string from three slot invocations
    // back into this `Dog`, so an echo or a Kotlin-side default cannot pass.
    [Fact]
    public void PetReceiver_GetSummary_CSharpImplementedDog_DispatchesAllThreeSlots()
    {
        using IPet rex = new Dog("Rex");
        Assert.Equal("Rex/4/Woof!", rex.GetSummary());
    }

    // Nullable handle receiver, absent: null crosses as IntPtr.Zero, `this?.name` is null on the
    // Kotlin side, and the call site is static dispatch so there is no NullReferenceException.
    [Fact]
    public void NullableCatReceiver_GetNameOrStray_NullCat()
    {
        Cat? none = null;
        Assert.Equal("stray", none.GetNameOrStray());
    }

    // Nullable handle receiver, present: Mylo is home, so the handle crosses and Kotlin reads his
    // name off it.
    [Fact]
    public void NullableCatReceiver_GetNameOrStray_LiveCat()
    {
        using var mylo = new Cat("Mylo", 3);
        Assert.Equal("Mylo", mylo.GetNameOrStray());
    }

    // ---- ADR-132 parity: the receiver shapes the extension-FUNCTION route already binds ----
    //
    // Fixtures: test-library/.../cat/ReceiverParityExtensions.kt. One test per receiver mechanism;
    // see that file's header for why each is a different mechanism rather than a different name.

    // Enum receiver: the ordinal crosses as an INT32 and Kotlin re-reads it out of `Mood.entries`.
    // `Mood` has a property of its own (`description`), so this is also the cell where the enum's
    // own `MoodExtensions` class and the extension-property class of the same name have to coexist.
    // Spelled `TestLibrary.Cat.Mood` in full because `TestLibrary.Clinic` -- imported for the
    // `Patient`/`ChartRef` cells further down -- declares a `Mood` of its own: the bare name is
    // CS0104 in this file, not in the library.
    [Fact]
    public void MoodReceiver_GetEmoji_HappyCat()
    {
        Assert.Equal("=^.^=", TestLibrary.Cat.Mood.Happy.GetEmoji());
    }

    [Fact]
    public void MoodReceiver_GetEmoji_GrumpyCatIsNotHappyCat()
    {
        Assert.Equal(">:(", TestLibrary.Cat.Mood.Grumpy.GetEmoji());
        Assert.Equal("(-.-)zzZ", TestLibrary.Cat.Mood.Sleepy.GetEmoji());
    }

    // Converting receiver: `Guid` crosses as its hex-dash text and Kotlin parses it back, so a
    // receiver whose text was mangled cannot produce the leading block.
    [Fact]
    public void GuidReceiver_GetShortForm_ReturnsLeadingBlock()
    {
        Guid chip = Guid.Parse("123e4567-e89b-12d3-a456-426614174000");
        Assert.Equal("123e4567", chip.GetShortForm());
    }

    // The `var` over a converting receiver: the setter export carries the receiver slot in front of
    // the value slot, and the read afterwards has to find the same Kotlin-side key.
    [Fact]
    public void GuidReceiver_SetNickname_ThenGetNickname_RoundTrips()
    {
        Guid chip = Guid.Parse("7f9c2ba4-0000-4e10-8c1a-11111111abcd");
        Assert.Equal("unnamed chip", chip.GetNickname());

        chip.SetNickname("Oreo's chip");
        Assert.Equal("Oreo's chip", chip.GetNickname());
    }

    // Nullable converting receiver, absent: the null rides the string wire as a null pointer, and
    // the call site needs a `Guid?` local (ADR-132 sub-decision (a): a bare `Guid` is CS1929).
    [Fact]
    public void NullableGuidReceiver_GetIsMissing_NoChip()
    {
        Guid? missing = null;
        Assert.True(missing.GetIsMissing());
    }

    [Fact]
    public void NullableGuidReceiver_GetIsMissing_ChippedCat()
    {
        Guid? present = Guid.Parse("123e4567-e89b-12d3-a456-426614174000");
        Assert.False(present.GetIsMissing());
    }

    // Instant receiver, NON-UTC offset. Mylo was photographed at 05:00 on 2 January 1970 in
    // Melbourne (+10:00), which is still 1 January in UTC: `UtcTicks` answers day 0, the
    // wall-clock `Ticks` would answer day 1. That one-day gap is the whole point of this cell.
    [Fact]
    public void InstantReceiver_GetEpochDay_UsesUtcTicksNotWallClock()
    {
        var melbourneMorning = new DateTimeOffset(1970, 1, 2, 5, 0, 0, TimeSpan.FromHours(10));
        Assert.Equal(0L, melbourneMorning.GetEpochDay());
    }

    // The UTC control beside it, so a fix that ignores the offset entirely still fails the pair.
    [Fact]
    public void InstantReceiver_GetEpochDay_Utc()
    {
        Assert.Equal(1L, DateTimeOffset.FromUnixTimeSeconds(86_400).GetEpochDay());
    }

    // Duration receiver: one tick domain, no conversion at all - the cell that catches a fix that
    // only ever works when there IS a conversion to get right.
    [Fact]
    public void DurationReceiver_GetWholeHours_NinetyMinuteNapIsOneHour()
    {
        Assert.Equal(1L, TimeSpan.FromMinutes(90).GetWholeHours());
    }

    // Nullable String receiver, both branches. The null one is the reason the receiver's DllImport
    // parameter cannot be spelled bare `string` under `<Nullable>enable</Nullable>`.
    [Fact]
    public void NullableStringReceiver_GetOrPlaceholder_NobodyCameThroughTheFlap()
    {
        string? nobody = null;
        Assert.Equal("(no cat)", nobody.GetOrPlaceholder());
    }

    [Fact]
    public void NullableStringReceiver_GetOrPlaceholder_OreoCameThrough()
    {
        Assert.Equal("Oreo", "Oreo".GetOrPlaceholder());
    }

    // Nullable value class over a String underlying: null pointer in-band, the underlying text
    // otherwise. A `CatId?` local for the same CS1929 reason as `Guid?` above.
    [Fact]
    public void NullableValueClassReceiver_GetDisplay_StrayHasNoId()
    {
        CatId? none = null;
        Assert.Equal("anonymous", none.GetDisplay());
    }

    [Fact]
    public void NullableValueClassReceiver_GetDisplay_OreoHasOne()
    {
        CatId? oreo = new CatId("oreo-1");
        Assert.Equal("oreo-1", oreo.GetDisplay());
    }

    // The same nullable value class one underlying over: `ChartRef` wraps a `Patient` handle, so
    // the receiver is reconstructed from a StableRef rather than from text, and the absent case is
    // `IntPtr.Zero` rather than a null string.
    [Fact]
    public void NullableHandleValueClassReceiver_GetPatientName_NoChartOnFile()
    {
        ChartRef? none = null;
        Assert.Equal("(unfiled)", none.GetPatientName());
    }

    [Fact]
    public void NullableHandleValueClassReceiver_GetPatientName_OreosChart()
    {
        using var patient = new Patient("Oreo");
        ChartRef? chart = new ChartRef(patient);
        Assert.Equal("Oreo", chart.GetPatientName());
    }

    // Folded in by decision: a `var` of NULLABLE-PRIMITIVE type over an INTERFACE receiver. The
    // getter route already ships; the setter takes the has-value fan-out path, which is where the
    // receiver's handle local goes missing. Driven through a Kotlin-implemented `Pet` (a `Cat`),
    // so the Kotlin side sees one stable object across the three crossings.
    [Fact]
    public void PetReceiver_NapQuota_GetSetAndSetNull()
    {
        using var oreo = new Cat("Oreo", 9);
        IPet pet = oreo;

        Assert.Null(pet.GetNapQuota());

        pet.SetNapQuota(3);
        Assert.Equal(3, pet.GetNapQuota());

        pet.SetNapQuota(null);
        Assert.Null(pet.GetNapQuota());
    }

    // Collection receiver: the C# side builds a Kotlin list for the crossing (LeakTests Row 6i
    // holds the lifecycle end of this).
    [Fact]
    public void ListReceiver_GetLongestName_PicksTheLongestCatName()
    {
        var basket = new List<string> { "Oreo", "Mylo", "Whiskers" };
        Assert.Equal("Whiskers", basket.GetLongestName());
    }

    [Fact]
    public void ListReceiver_GetLongestName_EmptyBasket()
    {
        Assert.Equal("(empty basket)", new List<string>().GetLongestName());
    }

    // Bound-interface receiver (ADR-088): a C# interface from the TestDependency package, used as
    // the receiver of a Kotlin extension property. The Kotlin body dispatches back into `describe()`
    // and `legs`, so an echo or a Kotlin-side default cannot produce this string.
    private sealed class Goat : IFeedable
    {
        public string Describe() => "Nibbles the C#-side goat";
        public int Legs => 4;
        public void Feed(string food) { }
        public string? Nickname { get; set; }
    }

    [Fact]
    public void BoundInterfaceReceiver_GetFeedingNote_DispatchesBackIntoTheCSharpGoat()
    {
        IFeedable nibbles = new Goat();
        Assert.Equal("Nibbles the C#-side goat needs 4 bowls", nibbles.GetFeedingNote());
    }
}
