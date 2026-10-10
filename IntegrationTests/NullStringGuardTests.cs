using TestLibrary;
using TestLibrary.Cat;
using TestLibrary.Stamps;
using Xunit;

namespace IntegrationTests;

/// <summary>
/// C# can always pass <c>null!</c> where a <c>string</c> is declared, and a null string pointer
/// in a non-null Kotlin <c>String</c> slot is an access violation inside the export: the process
/// dies. The generated C# refuses it with an <see cref="ArgumentNullException"/> naming the
/// parameter, before anything crosses, on every forward route that fills a non-null Kotlin
/// <c>String</c>: constructor, property setter, method, companion, object and top-level
/// parameters, extension receivers, collection components, <c>suspend</c> and Flow-returning
/// members, sealed arms, interface members and the flow element writes.
///
/// One cell per route family, so each can be run in its own process: before the guard, a cell
/// takes the test host down instead of failing. A nullable <c>string?</c> slot still accepts null.
///
/// Mylo runs the stamp desk; Oreo's parcels get stamped.
/// </summary>
public class NullStringGuardTests
{
    [Fact]
    public void Constructor_NullString_Throws()
    {
        ArgumentNullException thrown =
            Assert.Throws<ArgumentNullException>(() => new StampDesk(null!));
        Assert.Equal("owner", thrown.ParamName);
    }

    [Fact]
    public void Setter_NullString_ThrowsAndLeavesTheProperty()
    {
        using var desk = new StampDesk("Mylo");
        desk.Label = "fragile";

        ArgumentNullException thrown =
            Assert.Throws<ArgumentNullException>(() => desk.Label = null!);
        Assert.Equal("value", thrown.ParamName);
        Assert.Equal("fragile", desk.CurrentLabel());

        // The nullable slot beside it still takes a null.
        desk.Note = "this side up";
        desk.Note = null;
        Assert.Null(desk.Note);
    }

    [Fact]
    public void MethodParameter_NullString_Throws()
    {
        using var desk = new StampDesk("Mylo");

        ArgumentNullException thrown =
            Assert.Throws<ArgumentNullException>(() => desk.Stamp(null!));
        Assert.Equal("text", thrown.ParamName);
        Assert.Equal("Mylo stamped Oreo's parcel", desk.Stamp("Oreo's parcel"));

        // A nullable parameter passes null through.
        Assert.Equal("Mylo stamped nothing", desk.StampOrBlank(null));
    }

    [Fact]
    public void ListComponent_NullString_Throws()
    {
        using var desk = new StampDesk("Mylo");

        // A null element is reported against the collection the caller passed.
        ArgumentNullException thrown = Assert.Throws<ArgumentNullException>(
            () => desk.StampAll(new List<string> { "Oreo", null! }));
        Assert.Equal("texts", thrown.ParamName);
        Assert.Equal(8, desk.StampAll(new List<string> { "Oreo", "Mylo" }));
    }

    [Fact]
    public void SetComponent_NullString_Throws()
    {
        using var desk = new StampDesk("Mylo");

        Assert.Throws<ArgumentNullException>(
            () => desk.StampUnique(new HashSet<string> { "Oreo", null! }));
        Assert.Equal(8, desk.StampUnique(new HashSet<string> { "Oreo", "Mylo" }));
    }

    [Fact]
    public void MapValueComponent_NullString_Throws()
    {
        using var desk = new StampDesk("Mylo");

        Assert.Throws<ArgumentNullException>(
            () => desk.StampKeyed(new Dictionary<string, string> { ["Oreo"] = null! }));
        Assert.Equal(
            8, desk.StampKeyed(new Dictionary<string, string> { ["Oreo"] = "Mylo" }));
    }

    [Fact]
    public async Task SuspendParameter_NullString_Throws()
    {
        using var desk = new StampDesk("Mylo");

        ArgumentNullException thrown = await Assert.ThrowsAsync<ArgumentNullException>(
            () => desk.StampLaterAsync(null!));
        Assert.Equal("text", thrown.ParamName);
        Assert.Equal("Mylo stamped Oreo later", await desk.StampLaterAsync("Oreo"));
    }

    [Fact]
    public async Task FlowParameter_NullString_Throws()
    {
        using var desk = new StampDesk("Mylo");

        await Assert.ThrowsAsync<ArgumentNullException>(async () =>
        {
            await foreach (string _ in desk.StampStream(null!)) { }
        });

        var stamped = new List<string>();
        await foreach (string item in desk.StampStream("Oreo")) stamped.Add(item);
        Assert.Equal(new[] { "Mylo stamped Oreo" }, stamped);
    }

    [Fact]
    public void CompanionParameter_NullString_Throws()
    {
        Assert.Equal(
            "text", Assert.Throws<ArgumentNullException>(() => StampDesk.Measure(null!)).ParamName);
        Assert.Equal(4, StampDesk.Measure("Oreo"));
    }

    [Fact]
    public void ObjectParameter_NullString_Throws()
    {
        Assert.Equal(
            "text", Assert.Throws<ArgumentNullException>(() => StampLedger.Entry(null!)).ParamName);
        Assert.Equal(4, StampLedger.Entry("Oreo"));
    }

    [Fact]
    public void TopLevelParameter_NullString_Throws()
    {
        Assert.Equal(
            "text",
            Assert.Throws<ArgumentNullException>(() => NullStringSample.StampLength(null!))
                .ParamName);
        Assert.Equal(4, NullStringSample.StampLength("Oreo"));
    }

    [Fact]
    public void ExtensionFunctionReceiver_NullString_Throws()
    {
        string none = null!;

        Assert.Throws<ArgumentNullException>(() => none.Stamped());
        Assert.Equal("[Oreo]", "Oreo".Stamped());
    }

    [Fact]
    public void ExtensionPropertyReceiver_NullString_Throws()
    {
        Assert.Throws<ArgumentNullException>(
            () => TestLibrary.Stamps.StringExtensions.get_StampWidth(null!));
        Assert.Equal(6, TestLibrary.Stamps.StringExtensions.get_StampWidth("Oreo"));
    }

    [Fact]
    public void SealedArmConstructor_NullString_Throws()
    {
        Assert.Equal(
            "colour", Assert.Throws<ArgumentNullException>(() => new Seal.Wax(null!)).ParamName);
    }

    [Fact]
    public void SealedArmMember_NullString_Throws()
    {
        using var wax = new Seal.Wax("red");

        Assert.Equal(
            "text", Assert.Throws<ArgumentNullException>(() => wax.Press(null!)).ParamName);
        Assert.Equal("red wax on Oreo", wax.Press("Oreo"));
    }

    [Fact]
    public void InterfaceMember_NullString_Throws()
    {
        using var ink = new InkStamper("blue");
        IStamper stamper = ink;

        Assert.Equal(
            "text", Assert.Throws<ArgumentNullException>(() => stamper.Press(null!)).ParamName);
        Assert.Equal("blue Oreo", stamper.Press("Oreo"));
    }

    [Fact]
    public void ValueClassConstructor_NullString_Throws()
    {
        Assert.Throws<ArgumentNullException>(() => new CatId(null!));
        Assert.Equal("oreo-1", new CatId("oreo-1").Id);
    }

    [Fact]
    public void GenericInstantiationConstructor_NullString_IsObserved()
    {
        // `Box<T>` takes an unbounded `T`, so C# cannot tell `Box<string>` from `Box<string?>`
        // at run time and Kotlin itself allows a null item: this is not a non-null String slot.
        using var box = new Box<string>(null!);
        Assert.Null(box.Value);
    }

    [Fact]
    public void MutableStateFlowElement_NullString_Throws()
    {
        using var desk = new StampDesk("Mylo");

        Assert.Throws<ArgumentNullException>(() => desk.Title.Value = null!);
        Assert.Throws<ArgumentNullException>(() => desk.Title.CompareAndSet("untitled", null!));
        Assert.Equal("untitled", desk.Title.Value);
    }

    [Fact]
    public async Task MutableSharedFlowElement_NullString_Throws()
    {
        using var desk = new StampDesk("Mylo");

        Assert.Throws<ArgumentNullException>(() => desk.Notices.TryEmit(null!));
        await Assert.ThrowsAsync<ArgumentNullException>(() => desk.Notices.EmitAsync(null!));
        Assert.True(desk.Notices.TryEmit("closed for lunch"));
        Assert.Equal(new[] { "closed for lunch" }, desk.Notices.ReplayCache);
    }

    [Fact]
    public void CallbackReturningNullString_IsRefusedBeforeItCrosses()
    {
        using var desk = new StampDesk("Mylo");

        // The null never reaches Kotlin as a String: the thunk refuses it on the managed side, the
        // callback's own fault path (ADR-161) carries it through the Kotlin call site, and the C#
        // caller gets the original exception back.
        Assert.Throws<ArgumentNullException>(() => desk.StampWith(() => null!));
        Assert.Equal("Mylo stamped Oreo", desk.StampWith(() => "Oreo"));
    }

    [Fact]
    public void InterfaceImplementationReturningNullString_IsRefusedBeforeItCrosses()
    {
        using var desk = new StampDesk("Mylo");

        Exception thrown =
            Assert.ThrowsAny<Exception>(() => desk.StampThrough(new NullStamper(), "Oreo"));
        Assert.Contains("value", thrown.Message);
        Assert.Equal("blank Oreo", desk.StampThrough(new BlankStamper(), "Oreo"));
    }

    private sealed class NullStamper : IStamper
    {
        public string Press(string text) => null!;

        public void Dispose() { }
    }

    private sealed class BlankStamper : IStamper
    {
        public string Press(string text) => $"blank {text}";

        public void Dispose() { }
    }
}
