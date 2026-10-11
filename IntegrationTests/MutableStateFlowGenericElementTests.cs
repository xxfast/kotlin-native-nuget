using TestLibrary;
using TestLibrary.Boxshelf;
using TestLibrary.Cat;
using Outcomes = TestLibrary.Outcome;

namespace IntegrationTests;

/// <summary>
/// ADR-071 over ADR-208: a <c>MutableStateFlow</c> whose element is a closed generic class
/// instantiation (<c>MutableStateFlow&lt;Box&lt;String&gt;&gt;</c>) is a
/// <c>KotlinMutableStateFlow&lt;Box&lt;string&gt;&gt;</c>: <c>.Value</c> is settable, and
/// <c>CompareAndSet</c> and the <c>Update</c> family work, on the property, held-return and awaited
/// routes, like any other object-handle element. The written box is borrowed. A generic sealed
/// element (<c>Outcome&lt;Int&gt;</c>) is settable the same way. The Kotlin fixture is
/// <c>BoxDisplay</c> in <c>test-library/.../test/boxshelf/BoxShelves.kt</c>, whose <c>*Label</c>
/// members read what Kotlin holds after each write.
///
/// Oreo (black, white in the middle) rearranges the display. Mylo (brown and creamy) naps in
/// whichever box ends up in front.
/// </summary>
public class MutableStateFlowGenericElementTests
{
    // --- property ------------------------------------------------------------------------------

    [Fact]
    public void Property_SetValue_KotlinHoldsTheWrittenBox()
    {
        using var display = new BoxDisplay();
        using var second = new Box<string>("second");

        display.Front.Value = second;

        Assert.Equal("second", display.FrontLabel());
        using Box<string> read = display.Front.Value;
        Assert.Equal("second", read.Value);
    }

    [Fact]
    public void Property_SetValue_BorrowsTheBox()
    {
        using var display = new BoxDisplay();
        var lent = new Box<string>("lent");
        display.Front.Value = lent;

        // Still the caller's: usable after the write, and disposing it takes nothing from Kotlin.
        Assert.Equal("lent", lent.Value);
        lent.Dispose();

        Assert.Equal("lent", display.FrontLabel());
    }

    [Fact]
    public void Property_CompareAndSet_SwapsOnTheCurrentBoxOnly()
    {
        using var display = new BoxDisplay();
        KotlinMutableStateFlow<Box<string>> front = display.Front;
        using Box<string> first = front.Value;
        using var next = new Box<string>("next");

        Assert.True(front.CompareAndSet(first, next));
        Assert.Equal("next", display.FrontLabel());
        // `first` is no longer the value, so the same call misses and changes nothing.
        Assert.False(front.CompareAndSet(first, first));
        Assert.Equal("next", display.FrontLabel());
    }

    [Fact]
    public void Property_UpdateFamily_RunsOverCompareAndSet()
    {
        using var display = new BoxDisplay();
        KotlinMutableStateFlow<Box<string>> front = display.Front;
        using var updated = new Box<string>("updated");
        using var again = new Box<string>("again");
        using var last = new Box<string>("last");

        front.Update(_ => updated);
        Assert.Equal("updated", display.FrontLabel());

        Assert.Same(again, front.UpdateAndGet(_ => again));
        Assert.Equal("again", display.FrontLabel());

        using Box<string> previous = front.GetAndUpdate(_ => last);
        Assert.Equal("again", previous.Value);
        Assert.Equal("last", display.FrontLabel());
    }

    [Fact]
    public void Property_NullableElement_SetsABoxAndNull()
    {
        using var display = new BoxDisplay();
        KotlinMutableStateFlow<Box<string>?> spare = display.Spare;
        Assert.Null(spare.Value);

        using var kept = new Box<string>("kept");
        spare.Value = kept;
        Assert.Equal("kept", display.SpareLabel());
        using (Box<string>? read = spare.Value)
        {
            Assert.Equal("kept", read!.Value);
        }

        spare.Value = null;
        Assert.Equal("none", display.SpareLabel());
        Assert.Null(spare.Value);
    }

    // --- held return and awaited return ---------------------------------------------------------

    [Fact]
    public void HeldReturn_SetValueAndCompareAndSet_WriteTheHeldFlow()
    {
        using var display = new BoxDisplay();
        using KotlinMutableStateFlow<Box<int>> window = display.Window();
        using var seven = new Box<int>(7);
        using var eight = new Box<int>(8);

        window.Value = seven;
        Assert.Equal(7, display.WindowCount());

        Assert.True(window.CompareAndSet(seven, eight));
        Assert.Equal(8, display.WindowCount());
        using Box<int> read = window.Value;
        Assert.Equal(8, read.Value);
    }

    [Fact]
    public async Task AwaitedReturn_SetValue_WritesTheAwaitedFlow()
    {
        using var display = new BoxDisplay();
        using KotlinMutableStateFlow<Box<int>> window = await display.UnveiledAsync();
        using var nine = new Box<int>(9);

        window.Value = nine;

        Assert.Equal(9, display.WindowCount());
        using Box<int> read = window.Value;
        Assert.Equal(9, read.Value);
    }

    // --- generic sealed element -----------------------------------------------------------------

    [Fact]
    public void GenericSealedElement_SetValue_KotlinHoldsTheWrittenArm()
    {
        using var display = new BoxDisplay();
        KotlinMutableStateFlow<Outcomes.Outcome<int>> verdict = display.Verdict;
        using var refused = new Outcomes.Outcome.Err<int>("no dinner");

        verdict.Value = refused;

        Assert.Equal("Err(message=no dinner)", display.VerdictLabel());
        using Outcomes.Outcome<int> read = verdict.Value;
        Assert.IsType<Outcomes.Outcome.Err<int>>(read);
    }

    [Fact]
    public void GenericSealedElement_CompareAndSet_UsesKotlinEquals()
    {
        using var display = new BoxDisplay();
        KotlinMutableStateFlow<Outcomes.Outcome<int>> verdict = display.Verdict;
        // A data arm: a C#-built `Ok(1)` equals the `Ok(1)` Kotlin started with.
        using var one = new Outcomes.Outcome.Ok<int>(1);
        using var two = new Outcomes.Outcome.Ok<int>(2);

        Assert.True(verdict.CompareAndSet(one, two));
        Assert.Equal("Ok(value=2)", display.VerdictLabel());
        Assert.False(verdict.CompareAndSet(one, one));
    }
}
