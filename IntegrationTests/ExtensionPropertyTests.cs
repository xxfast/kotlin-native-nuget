using Test.Menagerie;
using TestLibrary;
using TestLibrary.Cat;
using TestLibrary.Clinic;

namespace IntegrationTests;

public class ExtensionPropertyTests
{
    [Fact]
    public void Cat_IsKitten_ReturnsTrueForNewCatWithNineLives()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.True(cat.IsKitten);
    }

    [Fact]
    public void Cat_IsKitten_ReturnsFalseForCatWithFewLivesLeft()
    {
        using var cat = new Cat("Mylo", 3);
        Assert.False(cat.IsKitten);
    }

    [Fact]
    public void Cat_IsKitten_ReturnsFalseAtExactlySevenLives()
    {
        using var cat = new Cat("Oreo", 7);
        Assert.False(cat.IsKitten);
    }

    // ADR-188: extension properties are C# 14 `extension` blocks, and the old `GetXxx`/`SetXxx`
    // static methods are removed outright (no [Obsolete] release). The property compiles to a
    // `get_IsKitten(Cat)` accessor on the same static class; `GetIsKitten` must be gone, for a
    // read-only property (Oreo's kittenhood) and for a mutable one (Mylo's nap quota setter).
    [Fact]
    public void ExtensionProperty_IsACSharp14Property_GetSetMethodsAreRemoved()
    {
        Assert.NotNull(typeof(CatExtensions).GetMethod("get_IsKitten"));
        Assert.Null(typeof(CatExtensions).GetMethod("GetIsKitten"));

        Assert.NotNull(typeof(PetExtensions).GetMethod("get_NapQuota"));
        Assert.NotNull(typeof(PetExtensions).GetMethod("set_NapQuota"));
        Assert.Null(typeof(PetExtensions).GetMethod("GetNapQuota"));
        Assert.Null(typeof(PetExtensions).GetMethod("SetNapQuota"));
    }

    [Fact]
    public void Cat_Label_ReturnsNameWithMood()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.Equal("Oreo (sleepy)", cat.Label);
    }

    [Fact]
    public void Cat_Label_ReturnsMyloWithMood()
    {
        using var cat = new Cat("Mylo", 9);
        Assert.Equal("Mylo (sleepy)", cat.Label);
    }

    [Fact]
    public void String_WordCount_ReturnsTwoForTwoWords()
    {
        Assert.Equal(2, "hello world".WordCount);
    }

    [Fact]
    public void String_WordCount_ReturnsOneForSingleWord()
    {
        Assert.Equal(1, "Oreo".WordCount);
    }

    [Fact]
    public void String_WordCount_IgnoresLeadingAndTrailingSpaces()
    {
        Assert.Equal(2, "  Oreo Mylo  ".WordCount);
    }

    [Fact]
    public void String_WordCount_CountsCatNames()
    {
        Assert.Equal(3, "Oreo and Mylo".WordCount);
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
    public void PetReceiver_Summary_KotlinBackedCat()
    {
        using var oreo = new Cat("Oreo", 9);
        Assert.Equal("Oreo/4/Meow! My name is Oreo", oreo.Summary);
    }

    // Interface receiver, C#-implemented: Kotlin composes the string from three slot invocations
    // back into this `Dog`, so an echo or a Kotlin-side default cannot pass.
    [Fact]
    public void PetReceiver_Summary_CSharpImplementedDog_DispatchesAllThreeSlots()
    {
        using IPet rex = new Dog("Rex");
        Assert.Equal("Rex/4/Woof!", rex.Summary);
    }

    // ADR-188: `val Cat?.nameOrStray` and `fun Cat?.nameOrStray()` share the C# name on one
    // receiver, which C# 14 makes ambiguous at every access (CS9339). The property is skipped
    // (SHADOWED_BY_EXTENSION_FUNCTION) and the function keeps the name, so the two cells below call
    // the function; it lowers the nullable handle receiver the same way the property would have.
    [Fact]
    public void NameOrStray_PropertySkipped_FunctionKeepsTheName()
    {
        Assert.Null(typeof(CatExtensions).GetMethod("get_NameOrStray"));
        Assert.NotNull(typeof(CatExtensions).GetMethod("NameOrStray"));
    }

    // ADR-179's remedy for the skip above: `@CSharpName("HomeTag")` on `val Cat.homeLabel` keeps
    // it apart from `fun Cat.homeLabel()`, so Oreo has both a basket and a tag.
    [Fact]
    public void CSharpNameOnExtensionProperty_KeepsBothNames()
    {
        using var oreo = new Cat("Oreo", 9);
        Assert.Equal("Oreo's basket", oreo.HomeLabel());
        Assert.Equal("Oreo's tag", oreo.HomeTag);
    }

    // Nullable handle receiver, absent: null crosses as IntPtr.Zero, `this?.name` is null on the
    // Kotlin side, and the call site is static dispatch so there is no NullReferenceException.
    [Fact]
    public void NullableCatReceiver_NameOrStray_NullCat()
    {
        Cat? none = null;
        Assert.Equal("stray", none.NameOrStray());
    }

    // Nullable handle receiver, present: Mylo is home, so the handle crosses and Kotlin reads his
    // name off it.
    [Fact]
    public void NullableCatReceiver_NameOrStray_LiveCat()
    {
        using var mylo = new Cat("Mylo", 3);
        Assert.Equal("Mylo", mylo.NameOrStray());
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
    public void MoodReceiver_Emoji_HappyCat()
    {
        Assert.Equal("=^.^=", TestLibrary.Cat.Mood.Happy.Emoji);
    }

    [Fact]
    public void MoodReceiver_Emoji_GrumpyCatIsNotHappyCat()
    {
        Assert.Equal(">:(", TestLibrary.Cat.Mood.Grumpy.Emoji);
        Assert.Equal("(-.-)zzZ", TestLibrary.Cat.Mood.Sleepy.Emoji);
    }

    // Converting receiver: `Guid` crosses as its hex-dash text and Kotlin parses it back, so a
    // receiver whose text was mangled cannot produce the leading block.
    [Fact]
    public void GuidReceiver_ShortForm_ReturnsLeadingBlock()
    {
        Guid chip = Guid.Parse("123e4567-e89b-12d3-a456-426614174000");
        Assert.Equal("123e4567", chip.ShortForm);
    }

    // The `var` over a converting receiver: the setter export carries the receiver slot in front of
    // the value slot, and the read afterwards has to find the same Kotlin-side key.
    [Fact]
    public void GuidReceiver_Nickname_SetThenRead_RoundTrips()
    {
        Guid chip = Guid.Parse("7f9c2ba4-0000-4e10-8c1a-11111111abcd");
        Assert.Equal("unnamed chip", chip.Nickname);

        chip.Nickname = "Oreo's chip";
        Assert.Equal("Oreo's chip", chip.Nickname);
    }

    // Nullable converting receiver, absent: the null rides the string wire as a null pointer, and
    // the call site needs a `Guid?` local (ADR-132 sub-decision (a): a bare `Guid` is CS1929).
    [Fact]
    public void NullableGuidReceiver_IsMissing_NoChip()
    {
        Guid? missing = null;
        Assert.True(missing.IsMissing);
    }

    [Fact]
    public void NullableGuidReceiver_IsMissing_ChippedCat()
    {
        Guid? present = Guid.Parse("123e4567-e89b-12d3-a456-426614174000");
        Assert.False(present.IsMissing);
    }

    // Instant receiver, NON-UTC offset. Mylo was photographed at 05:00 on 2 January 1970 in
    // Melbourne (+10:00), which is still 1 January in UTC: `UtcTicks` answers day 0, the
    // wall-clock `Ticks` would answer day 1. That one-day gap is the whole point of this cell.
    [Fact]
    public void InstantReceiver_EpochDay_UsesUtcTicksNotWallClock()
    {
        var melbourneMorning = new DateTimeOffset(1970, 1, 2, 5, 0, 0, TimeSpan.FromHours(10));
        Assert.Equal(0L, melbourneMorning.EpochDay);
    }

    // The UTC control beside it, so a fix that ignores the offset entirely still fails the pair.
    [Fact]
    public void InstantReceiver_EpochDay_Utc()
    {
        Assert.Equal(1L, DateTimeOffset.FromUnixTimeSeconds(86_400).EpochDay);
    }

    // Duration receiver: one tick domain, no conversion at all - the cell that catches a fix that
    // only ever works when there IS a conversion to get right.
    [Fact]
    public void DurationReceiver_WholeHours_NinetyMinuteNapIsOneHour()
    {
        Assert.Equal(1L, TimeSpan.FromMinutes(90).WholeHours);
    }

    // Nullable String receiver, both branches. The null one is the reason the receiver's DllImport
    // parameter cannot be spelled bare `string` under `<Nullable>enable</Nullable>`.
    [Fact]
    public void NullableStringReceiver_OrPlaceholder_NobodyCameThroughTheFlap()
    {
        string? nobody = null;
        Assert.Equal("(no cat)", nobody.OrPlaceholder);
    }

    [Fact]
    public void NullableStringReceiver_OrPlaceholder_OreoCameThrough()
    {
        Assert.Equal("Oreo", "Oreo".OrPlaceholder);
    }

    // Nullable value class over a String underlying: null pointer in-band, the underlying text
    // otherwise. A `CatId?` local for the same CS1929 reason as `Guid?` above.
    [Fact]
    public void NullableValueClassReceiver_Display_StrayHasNoId()
    {
        CatId? none = null;
        Assert.Equal("anonymous", none.Display);
    }

    [Fact]
    public void NullableValueClassReceiver_Display_OreoHasOne()
    {
        CatId? oreo = new CatId("oreo-1");
        Assert.Equal("oreo-1", oreo.Display);
    }

    // The same nullable value class one underlying over: `ChartRef` wraps a `Patient` handle, so
    // the receiver is reconstructed from a StableRef rather than from text, and the absent case is
    // `IntPtr.Zero` rather than a null string.
    [Fact]
    public void NullableHandleValueClassReceiver_PatientName_NoChartOnFile()
    {
        ChartRef? none = null;
        Assert.Equal("(unfiled)", none.PatientName);
    }

    [Fact]
    public void NullableHandleValueClassReceiver_PatientName_OreosChart()
    {
        using var patient = new Patient("Oreo");
        ChartRef? chart = new ChartRef(patient);
        Assert.Equal("Oreo", chart.PatientName);
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

        Assert.Null(pet.NapQuota);

        pet.NapQuota = 3;
        Assert.Equal(3, pet.NapQuota);

        pet.NapQuota = null;
        Assert.Null(pet.NapQuota);
    }

    // Collection receiver: the C# side builds a Kotlin list for the crossing (LeakTests Row 6i
    // holds the lifecycle end of this).
    [Fact]
    public void ListReceiver_LongestName_PicksTheLongestCatName()
    {
        var basket = new List<string> { "Oreo", "Mylo", "Whiskers" };
        Assert.Equal("Whiskers", basket.LongestName);
    }

    [Fact]
    public void ListReceiver_LongestName_EmptyBasket()
    {
        Assert.Equal("(empty basket)", new List<string>().LongestName);
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

    [Trait("Direction", "Reverse")]
    [Fact]
    public void BoundInterfaceReceiver_FeedingNote_DispatchesBackIntoTheCSharpGoat()
    {
        IFeedable nibbles = new Goat();
        Assert.Equal("Nibbles the C#-side goat needs 4 bowls", nibbles.FeedingNote);
    }

    // ---- ADR-132 amendment (2026-10-04): has-value fan-out receivers at the PROPERTY position
    //
    // Fixtures: the "has-value fan-out receivers (extension PROPERTIES)" section of
    // test-library/.../cat/ReceiverParityExtensions.kt. Each getter answers a null receiver with
    // something other than what the value slot's default would read, so a dropped has-value flag
    // fails here as a wrong value rather than passing by coincidence.

    [Fact]
    public void NullableIntReceiver_LivesOrNone_PassesNullThrough()
    {
        int? nine = 9;
        int? none = null;
        Assert.Equal("9", nine.LivesOrNone);
        Assert.Equal("none", none.LivesOrNone);
    }

    // The twin pair: `val Int.livesLabel` beside `val Int?.livesLabel`, each reached by its own
    // receiver type. "lives:0" for the null one would mean the flag was dropped.
    [Fact]
    public void IntAndNullableIntReceiverTwins_LivesLabel_EachReachTheirOwn()
    {
        int? none = null;
        int? seven = 7;
        Assert.Equal("lives:7", 7.LivesLabel);
        Assert.Equal("lives?:null", none.LivesLabel);
        Assert.Equal("lives?:7", seven.LivesLabel);
    }

    // The `var`: the setter carries the receiver pair, so a null key and a real key stay apart.
    [Fact]
    public void NullableIntReceiver_LivesNote_SetThenRead_KeepsNullAndValueApart()
    {
        int? none = null;
        int? oreo = 41;
        none.LivesNote = "nobody home";
        oreo.LivesNote = "Oreo";
        Assert.Equal("nobody home", none.LivesNote);
        Assert.Equal("Oreo", oreo.LivesNote);
    }

    // `HAPPY` is ordinal 0: a null that lost its flag would read "HAPPY" here, and a null cast
    // straight to `int` would throw before reaching Kotlin at all.
    [Fact]
    public void NullableEnumReceiver_MoodLabel_PassesNullThrough()
    {
        TestLibrary.Cat.Mood? none = null;
        TestLibrary.Cat.Mood? grumpy = TestLibrary.Cat.Mood.Grumpy;
        Assert.Equal("shrug", none.MoodLabel);
        Assert.Equal("GRUMPY", grumpy.MoodLabel);
    }

    // 05:00 on 2 January 2024 at +10:00 is still 1 January in UTC: day 19723. Day 19724 would mean
    // the wall-clock ticks crossed instead of `UtcTicks`.
    [Fact]
    public void NullableInstantReceiver_SeenEpochDay_PassesNullThrough()
    {
        DateTimeOffset? never = null;
        DateTimeOffset? melbourne = new DateTimeOffset(2024, 1, 2, 5, 0, 0, TimeSpan.FromHours(10));
        Assert.Equal(-1L, never.SeenEpochDay);
        Assert.Equal(19723L, melbourne.SeenEpochDay);
    }

    [Fact]
    public void NullableDurationReceiver_NapMinutesOrNone_PassesNullThrough()
    {
        TimeSpan? noNap = null;
        TimeSpan? nap = TimeSpan.FromMinutes(90);
        Assert.Equal(-1L, noNap.NapMinutesOrNone);
        Assert.Equal(90L, nap.NapMinutesOrNone);
    }

    [Fact]
    public void NullableValueClassReceivers_PassNullThrough()
    {
        NapCount? uncounted = null;
        NapCount? four = new NapCount(4);
        Assert.Equal(-1, uncounted.NapsOrUncounted);
        Assert.Equal(4, four.NapsOrUncounted);

        MoodRing? noRing = null;
        MoodRing? sleepy = new MoodRing(TestLibrary.Cat.Mood.Sleepy);
        Assert.Equal("no ring", noRing.RingLabel);
        Assert.Equal("SLEEPY", sleepy.RingLabel);
    }

    // The struct twin that is not a fan-out: `Guid` and `Guid?` each reach their own Kotlin body,
    // and the `Guid?` one gets a real `null`.
    [Fact]
    public void GuidAndNullableGuidReceiverTwins_ChipOwner_EachReachTheirOwn()
    {
        Guid chip = Guid.Parse("123e4567-e89b-12d3-a456-426614174000");
        Guid? sameChip = chip;
        Guid? none = null;
        Assert.Equal("chip:123e4567", chip.ChipOwner);
        Assert.Equal("chip?:123e4567", sameChip.ChipOwner);
        Assert.Equal("chip?:none", none.ChipOwner);
    }

    // A non-Latin initial: one ANSI byte could not carry it, so this also proves the receiver
    // crosses on the two-byte U2 wire.
    [Fact]
    public void NullableCharReceiver_PawPrint_PassesNullThrough()
    {
        char? none = null;
        char? zhuk = 'Ж';
        Assert.Equal("no paw", none.PawPrint);
        Assert.Equal("Ж-paw", zhuk.PawPrint);
    }
}
