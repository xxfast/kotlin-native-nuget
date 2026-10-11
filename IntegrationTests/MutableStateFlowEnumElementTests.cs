using TestLibrary;
using TestLibrary.Cat;
using Xunit;

namespace IntegrationTests;

/// <summary>
/// ADR-071 amendment (enum element write): a <c>MutableStateFlow&lt;Mood&gt;</c> binds a
/// settable <c>KotlinMutableStateFlow&lt;Mood&gt;</c>. The write crosses as the enum's ordinal,
/// exactly as the synchronous enum setter does (<c>(int)v</c> in C#, <c>Mood.entries[value]</c>
/// in Kotlin), and the read keeps going through the ADR-094 <c>Factories</c> entry.
///
/// Every asserted mood has a NON-ZERO ordinal (<c>Sleepy</c> = 1, <c>Grumpy</c> = 2) except
/// <c>Happy</c> (0), which is only ever written after a non-zero one, so a write that always
/// sends 0 or never lands cannot pass (ADR-097's rule).
///
/// A nullable enum element (<c>MutableStateFlow&lt;Mood?&gt;</c>, <c>Hunch</c>) is settable too:
/// the same ordinal behind a has-value slot, so writing <c>null</c> and writing <c>Happy</c>
/// (ordinal 0) are different writes, which <c>CurrentHunch()</c> reads back from Kotlin.
///
/// A value-class element is settable too (its own cells live in
/// <c>MutableStateFlowValueClassElementTests</c>): <c>ChipId</c> is a
/// <c>KotlinMutableStateFlow&lt;CatId&gt;</c>.
///
/// Mylo naps (Sleepy); Oreo sulks (Grumpy).
/// </summary>
public class MutableStateFlowEnumElementTests
{
    [Fact]
    public void SettableValue_EnumElement_CrossesAsOrdinal_OreoTurnsGrumpy()
    {
        using var tracker = new CatMoodTracker("Oreo");

        Assert.Equal(Mood.Sleepy, tracker.Outlook.Value);

        tracker.Outlook.Value = Mood.Grumpy;

        Assert.Equal(Mood.Grumpy, tracker.Outlook.Value);
        Assert.Equal(Mood.Grumpy, tracker.CurrentOutlook());
    }

    [Fact]
    public void SettableValue_EnumElement_HeldMethodReturn_SharesStorageWithTheProperty()
    {
        using var tracker = new CatMoodTracker("Mylo");
        using var dial = tracker.OutlookDial();

        dial.Value = Mood.Grumpy;
        Assert.Equal(Mood.Grumpy, tracker.Outlook.Value);

        dial.Value = Mood.Happy;
        Assert.Equal(Mood.Happy, tracker.Outlook.Value);
        Assert.Equal(Mood.Happy, tracker.CurrentOutlook());
    }

    [Fact]
    public void SettableValue_EnumElement_OutOfRangeOrdinal_ThrowsInsteadOfCrashing()
    {
        using var tracker = new CatMoodTracker("Mylo");

        // `Mood.entries[99]` throws inside the setter export's error slot.
        Assert.ThrowsAny<KotlinException>(() => tracker.Outlook.Value = (Mood)99);

        // The write did not land, and the route still answers.
        Assert.Equal(Mood.Sleepy, tracker.Outlook.Value);
        tracker.Outlook.Value = Mood.Grumpy;
        Assert.Equal(Mood.Grumpy, tracker.CurrentOutlook());
    }

    // --- ADR-071 (nullable enum element write): `MutableStateFlow<Mood?>` is settable too. The
    // write is a has-value slot ahead of the ordinal slot, so null and Happy (ordinal 0) differ.

    [Fact]
    public void NullableEnumElement_SetEntryThenNull_RoundTrips()
    {
        using var tracker = new CatMoodTracker("Oreo");
        Assert.Null(tracker.Hunch.Value);
        Assert.Equal("none", tracker.CurrentHunch());

        tracker.Hunch.Value = Mood.Grumpy;
        Assert.Equal(Mood.Grumpy, tracker.Hunch.Value);
        Assert.Equal("GRUMPY", tracker.CurrentHunch());

        tracker.Hunch.Value = null;
        Assert.Null(tracker.Hunch.Value);
        Assert.Equal("none", tracker.CurrentHunch());
    }

    [Fact]
    public void NullableEnumElement_NullIsNotOrdinalZero()
    {
        using var tracker = new CatMoodTracker("Mylo");

        // Happy is ordinal 0: written for real, it lands as HAPPY...
        tracker.Hunch.Value = Mood.Happy;
        Assert.Equal("HAPPY", tracker.CurrentHunch());
        Assert.Equal(Mood.Happy, tracker.Hunch.Value);

        // ...and null written over it lands as null, not as the entry at ordinal 0.
        tracker.Hunch.Value = null;
        Assert.Equal("none", tracker.CurrentHunch());
        Assert.Null(tracker.Hunch.Value);
    }

    [Fact]
    public void NullableEnumElement_OutOfRangeOrdinal_ThrowsAndDoesNotLand()
    {
        using var tracker = new CatMoodTracker("Mylo");
        tracker.Hunch.Value = Mood.Sleepy;

        Assert.ThrowsAny<KotlinException>(() => tracker.Hunch.Value = (Mood)99);
        Assert.ThrowsAny<KotlinException>(
            () => { tracker.Hunch.CompareAndSet(Mood.Sleepy, (Mood)99); });

        // Neither write landed, and the route still answers.
        Assert.Equal("SLEEPY", tracker.CurrentHunch());
        tracker.Hunch.Value = null;
        Assert.Null(tracker.Hunch.Value);
    }

    [Fact]
    public void NullableEnumElement_CompareAndSet_MatchesNullAndEntries()
    {
        using var tracker = new CatMoodTracker("Oreo");
        KotlinMutableStateFlow<Mood?> hunch = tracker.Hunch;

        Assert.True(hunch.CompareAndSet(null, Mood.Sleepy));
        Assert.Equal("SLEEPY", tracker.CurrentHunch());
        // No longer null, so expecting null misses and changes nothing.
        Assert.False(hunch.CompareAndSet(null, Mood.Grumpy));
        Assert.Equal("SLEEPY", tracker.CurrentHunch());

        Assert.True(hunch.CompareAndSet(Mood.Sleepy, null));
        Assert.Equal("none", tracker.CurrentHunch());
    }

    [Fact]
    public void NullableEnumElement_UpdateFamily_CarriesNullBothWays()
    {
        using var tracker = new CatMoodTracker("Oreo");
        KotlinMutableStateFlow<Mood?> hunch = tracker.Hunch;

        hunch.Update(mood => mood == null ? Mood.Grumpy : null);
        Assert.Equal("GRUMPY", tracker.CurrentHunch());

        Assert.Null(hunch.UpdateAndGet(mood => mood == Mood.Grumpy ? null : Mood.Happy));
        Assert.Equal("none", tracker.CurrentHunch());

        Assert.Null(hunch.GetAndUpdate(_ => Mood.Sleepy));
        Assert.Equal(Mood.Sleepy, hunch.Value);
    }

    [Fact]
    public void NullableEnumElement_HeldMethodReturn_SharesStorageWithTheProperty()
    {
        using var tracker = new CatMoodTracker("Mylo");
        using KotlinMutableStateFlow<Mood?> dial = tracker.HunchDial();
        Assert.Null(dial.Value);

        dial.Value = Mood.Grumpy;
        Assert.Equal(Mood.Grumpy, tracker.Hunch.Value);
        Assert.Equal(Mood.Grumpy, dial.Value);

        dial.Value = null;
        Assert.Equal("none", tracker.CurrentHunch());
        Assert.Null(dial.Value);
    }

    [Fact]
    public void ValueClassElement_BindsSettable()
    {
        using var tracker = new CatMoodTracker("Oreo");

        Type chipIdType = typeof(CatMoodTracker).GetProperty(nameof(CatMoodTracker.ChipId))!
            .PropertyType;
        Assert.Equal(typeof(KotlinMutableStateFlow<CatId>), chipIdType);
        Assert.Equal(new CatId("oreo-chip"), tracker.ChipId.Value);
    }
}
