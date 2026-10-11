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
