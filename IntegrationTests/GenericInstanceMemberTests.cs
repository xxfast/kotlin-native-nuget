using TestLibrary.Cat;
using TestLibrary.Boxshelf;

namespace IntegrationTests;

/// <summary>
/// ADR-208 part D: a closed instantiation of an exported generic class (<c>Box&lt;String&gt;</c>,
/// <c>Box&lt;Cat&gt;</c>, <c>Box&lt;Box&lt;Int&gt;&gt;</c>, <c>Box&lt;String&gt;?</c>) binds as
/// <c>Box&lt;string&gt;</c> etc. at every member position: a property (get, and set for a
/// <c>var</c>), a member return and parameter, on a class, an object, a companion and at the top
/// level, as a constructor parameter, an extension receiver, a suspend result and a Flow element.
/// A returned wrapper is the caller's to dispose; a passed one is borrowed. The Kotlin fixture is
/// <c>test-library/.../test/boxshelf/BoxShelves.kt</c>, where every body reads through the box it
/// was handed.
///
/// Oreo (black, white in the middle) labels her box. Mylo (brown and creamy) sits in the other.
/// </summary>
public class GenericInstanceMemberTests
{
    // --- class property ---------------------------------------------------------------------

    [Fact]
    public void ValProperty_ReadsAStringBox()
    {
        using var shelf = new BoxShelf();
        using Box<string> label = shelf.Label;

        Assert.Equal("Oreo", label.Value);
    }

    [Fact]
    public void ValProperty_EachReadIsAFreshWrapperOverTheSameBox()
    {
        using var shelf = new BoxShelf();
        using Box<string> first = shelf.Label;
        using Box<string> second = shelf.Label;

        Assert.NotSame(first, second);
        Assert.Equal(first.Value, second.Value);
    }

    [Fact]
    public void VarProperty_SetThenGet_RoundTripsAHandleArgument()
    {
        using var shelf = new BoxShelf();
        using (Box<Cat> before = shelf.Favourite)
        using (Cat mylo = before.Value)
        {
            Assert.Equal("Mylo", mylo.Name);
        }

        using var oreo = new Cat("Oreo");
        using var boxed = new Box<Cat>(oreo);
        shelf.Favourite = boxed;

        using Box<Cat> after = shelf.Favourite;
        using Cat cat = after.Value;
        Assert.Equal("Oreo", cat.Name);
    }

    [Fact]
    public void VarProperty_SetterBorrows_TheCallersBoxStaysUsable()
    {
        using var shelf = new BoxShelf();
        using var oreo = new Cat("Oreo");
        using var boxed = new Box<Cat>(oreo);

        shelf.Favourite = boxed;

        using Cat still = boxed.Value;
        Assert.Equal("Oreo", still.Name);
    }

    // --- member parameter and return ---------------------------------------------------------

    [Fact]
    public void Parameter_KotlinReadsThroughTheBorrowedBox()
    {
        using var shelf = new BoxShelf();
        using var mine = new Box<string>("catnip");

        Assert.Equal("catnip", shelf.Peek(mine));
        Assert.Equal("catnip", mine.Value);
    }

    [Fact]
    public void Parameter_AReturnedBoxPassesStraightBackIn()
    {
        using var shelf = new BoxShelf();
        using Box<string> label = shelf.Label;

        Assert.Equal("Oreo", shelf.Peek(label));
    }

    [Fact]
    public void NullableParameter_NullAndValue()
    {
        using var shelf = new BoxShelf();
        using var mine = new Box<string>("ribbon");

        Assert.Equal("none", shelf.PeekMaybe(null));
        Assert.Equal("ribbon", shelf.PeekMaybe(mine));
    }

    [Fact]
    public void NullableReturn_NullAndValue()
    {
        using var shelf = new BoxShelf();

        Assert.Null(shelf.Maybe(false));
        using Box<string>? here = shelf.Maybe(true);
        Assert.NotNull(here);
        Assert.Equal("here", here.Value);
    }

    [Fact]
    public void NestedReturn_InnerBoxMaterialisesThroughItsOwnFactory()
    {
        using var shelf = new BoxShelf();
        using Box<Box<int>> outer = shelf.Nested();
        using Box<int> inner = outer.Value;

        Assert.Equal(7, inner.Value);
    }

    [Fact]
    public void EnumArgumentReturn_ReadsTheEnum()
    {
        using var shelf = new BoxShelf();
        using Box<Mood> mood = shelf.MoodBox();

        Assert.Equal(Mood.Grumpy, mood.Value);
    }

    [Fact]
    public void ListOfBoxes_EachElementIsItsOwnWrapper()
    {
        using var shelf = new BoxShelf();
        IReadOnlyList<Box<string>> stack = shelf.Stack();

        Assert.Equal(new[] { "top", "bottom" }, stack.Select(box => box.Value).ToArray());
        foreach (Box<string> box in stack) box.Dispose();
    }

    // --- constructor, companion, object, top level, extension ---------------------------------

    [Fact]
    public void ConstructorParameter_KotlinReadsTheBox()
    {
        using var mine = new Box<string>("marble");
        using var plinth = new BoxPlinth(mine);

        Assert.Equal("plinth of marble", plinth.Engraving);
    }

    [Fact]
    public void Companion_ReturnsABox()
    {
        using Box<int> spare = BoxShelf.Spare();

        Assert.Equal(9, spare.Value);
    }

    [Fact]
    public void Object_ReturnAndParameter()
    {
        using Box<string> fresh = BoxDepot.Fresh();
        Assert.Equal("fresh", fresh.Value);

        using var mylo = new Cat("Mylo");
        using var boxed = new Box<Cat>(mylo);
        Assert.Equal(4, BoxDepot.Weigh(boxed));
    }

    [Fact]
    public void TopLevel_PropertyAndParameter()
    {
        using Box<string> top = BoxShelves.TopBox;
        Assert.Equal("top shelf", top.Value);

        using var nine = new Box<int>(9);
        Assert.Equal(9, BoxShelves.Unbox(nine));
    }

    [Fact]
    public void ExtensionReceiver_BindsOnTheClosedInstantiation()
    {
        using var four = new Box<int>(4);

        Assert.Equal(8, four.Doubled);
    }

    // --- suspend and Flow routes ---------------------------------------------------------------

    [Fact]
    public async Task SuspendResult_IsAnOwnedBox()
    {
        using var courier = new BoxCourier();
        using Box<string> later = await courier.LaterAsync();

        Assert.Equal("later", later.Value);
    }

    [Fact]
    public async Task SuspendParameter_KotlinReadsTheBorrowedBoxAfterSuspending()
    {
        using var courier = new BoxCourier();
        using var mine = new Box<string>("feather");

        Assert.Equal("peeked feather", await courier.LaterPeekAsync(mine));
        Assert.Equal("feather", mine.Value);
    }

    [Fact]
    public async Task TopLevelSuspendResult_IsAnOwnedBox()
    {
        using Box<string> later = await BoxShelves.LaterBoxAsync("bell");

        Assert.Equal("bell", later.Value);
    }

    [Fact]
    public async Task FlowElement_EachItemIsAnOwnedBox()
    {
        using var courier = new BoxCourier();

        var seen = new List<string>();
        await foreach (Box<string> box in courier.Stream)
        {
            using (box) seen.Add(box.Value);
        }

        Assert.Equal(new[] { "first", "second" }, seen);
    }

    [Fact]
    public void StateFlowElement_ValueIsAnOwnedBox()
    {
        using var courier = new BoxCourier();
        using Box<int> latest = courier.Latest.Value;

        Assert.Equal(1, latest.Value);
    }
}
