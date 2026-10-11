using TestLibrary;
using TestLibrary.Cat;
using Xunit;

namespace IntegrationTests;

/// <summary>
/// ADR-071 amendment (value-class element write): a <c>MutableStateFlow&lt;V&gt;</c> of an
/// exported value class binds a settable <c>KotlinMutableStateFlow&lt;V&gt;</c>. The write crosses
/// as the underlying (<c>v.Id</c>, <c>v.Naps</c>), exactly as the synchronous value-class setter
/// does, and Kotlin re-wraps it. <c>CompareAndSet</c> and the <c>Update</c> family compare with
/// Kotlin <c>equals</c>, which for a value class is structural over the underlying, so a freshly
/// constructed <c>CatId</c> matches the stored one.
///
/// Every asserted write lands on a value that differs from the starting one, so a write that
/// never lands cannot pass.
///
/// Oreo's chip is re-registered; Mylo naps.
/// </summary>
public class MutableStateFlowValueClassElementTests
{
    [Fact]
    public void SettableValue_ValueClassOverString_CrossesAsTheString_OreoIsRechipped()
    {
        using var tracker = new CatMoodTracker("Oreo");
        Assert.Equal(new CatId("oreo-chip"), tracker.ChipId.Value);

        tracker.ChipId.Value = new CatId("oreo-9");

        Assert.Equal(new CatId("oreo-9"), tracker.ChipId.Value);
        Assert.Equal("oreo-9", tracker.CurrentChipId());
    }

    [Fact]
    public void SettableValue_ValueClassOverInt_CrossesAsTheInt_MyloNapsMore()
    {
        using var tracker = new CatMoodTracker("Mylo");
        Assert.Equal(new NapCount(3), tracker.Naps.Value);

        tracker.Naps.Value = new NapCount(7);

        Assert.Equal(new NapCount(7), tracker.Naps.Value);
    }

    [Fact]
    public void CompareAndSet_ValueClassElement_ComparesWithKotlinEquals()
    {
        using var tracker = new CatMoodTracker("Oreo");
        tracker.ChipId.Value = new CatId("oreo-9");

        // A freshly constructed expect equals the stored value by its underlying.
        Assert.True(tracker.ChipId.CompareAndSet(new CatId("oreo-9"), new CatId("mylo-2")));
        Assert.Equal("mylo-2", tracker.CurrentChipId());

        // A stale expect no longer matches, and the value stays put.
        Assert.False(tracker.ChipId.CompareAndSet(new CatId("oreo-9"), new CatId("tom-1")));
        Assert.Equal("mylo-2", tracker.CurrentChipId());
    }

    [Fact]
    public void UpdateFamily_ValueClassElement_RetriesThroughCompareAndSet()
    {
        using var tracker = new CatMoodTracker("Mylo");

        tracker.Naps.Update(n => new NapCount(n.Naps + 1));
        Assert.Equal(new NapCount(4), tracker.Naps.Value);

        NapCount after = tracker.Naps.UpdateAndGet(n => new NapCount(n.Naps * 2));
        Assert.Equal(new NapCount(8), after);

        NapCount before = tracker.Naps.GetAndUpdate(n => new NapCount(n.Naps + 2));
        Assert.Equal(new NapCount(8), before);
        Assert.Equal(new NapCount(10), tracker.Naps.Value);
    }

    [Fact]
    public void SettableValue_ValueClassElement_HeldMethodReturn_SharesStorageWithTheProperty()
    {
        using var tracker = new CatMoodTracker("Oreo");
        using var reader = tracker.ChipReader();

        reader.Value = new CatId("oreo-7");

        Assert.Equal(new CatId("oreo-7"), tracker.ChipId.Value);
        Assert.Equal("oreo-7", tracker.CurrentChipId());
        Assert.True(reader.CompareAndSet(new CatId("oreo-7"), new CatId("oreo-8")));
        Assert.Equal("oreo-8", tracker.CurrentChipId());
    }

    [Fact]
    public async Task SettableValue_ValueClassElement_AwaitedReturn_SharesStorageWithTheProperty()
    {
        using var tracker = new CatMoodTracker("Mylo");
        using var reader = await tracker.AwaitChipReaderAsync();

        reader.Value = new CatId("mylo-5");

        Assert.Equal("mylo-5", tracker.CurrentChipId());
    }

    [Fact]
    public void SettableValue_NullableValueClassElement_WritesValuesAndNull()
    {
        using var tracker = new CatMoodTracker("Oreo");
        Assert.Null(tracker.SpareChipId.Value);
        Assert.Equal(-1, tracker.CurrentSpareNaps());

        tracker.SpareChipId.Value = new CatId("spare-1");
        tracker.SpareNaps.Value = new NapCount(4);

        Assert.Equal(new CatId("spare-1"), tracker.SpareChipId.Value);
        Assert.Equal(new NapCount(4), tracker.SpareNaps.Value);
        Assert.Equal(4, tracker.CurrentSpareNaps());

        tracker.SpareChipId.Value = null;
        tracker.SpareNaps.Value = null;

        Assert.Null(tracker.SpareChipId.Value);
        Assert.Null(tracker.SpareNaps.Value);
        Assert.Equal(-1, tracker.CurrentSpareNaps());
    }

    [Fact]
    public void SettableValue_DefaultOfAStringValueClass_ThrowsBeforeItCrosses()
    {
        using var tracker = new CatMoodTracker("Oreo");
        tracker.ChipId.Value = new CatId("oreo-9");

        ArgumentException thrown =
            Assert.Throws<ArgumentException>(() => tracker.ChipId.Value = default);
        Assert.Contains("CatId", thrown.Message);
        Assert.Throws<ArgumentException>(
            () => tracker.ChipId.CompareAndSet(default, new CatId("x")));

        // A nullable element must not read a default as "clear the flow".
        tracker.SpareChipId.Value = new CatId("spare-1");
        Assert.Throws<ArgumentException>(() => tracker.SpareChipId.Value = default(CatId));
        Assert.Equal(new CatId("spare-1"), tracker.SpareChipId.Value);

        Assert.Equal("oreo-9", tracker.CurrentChipId());
    }
}
