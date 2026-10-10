using TestLibrary;
using TestLibrary.Cat;
using Xunit;

namespace IntegrationTests;

/// <summary>
/// A C# <c>record struct</c> always has a <c>default</c>, and for a value class over a String or
/// an exported class its underlying is then null. Kotlin could never have built that value, so
/// the generated C# refuses it before the call with an <see cref="ArgumentException"/> naming the
/// struct, at every position a value class crosses into a non-null Kotlin slot: constructor
/// parameter, property setter, method parameter, <c>List</c> component, the struct's own members
/// and the nullable <c>V?</c> spelling (where a null still means null, but a default does not).
///
/// Each cell also proves the refused call changed nothing and that a real value still crosses.
///
/// Oreo's chip is "oreo-1"; Mylo wears the collar.
/// </summary>
public class ValueClassDefaultGuardTests
{
    private const string NoId = "default(CatId) carries no Id; construct a CatId instead";
    private const string NoCat = "default(CatResult) carries no Cat; construct a CatResult instead";

    [Fact]
    public void Constructor_DefaultCatId_ThrowsBeforeItCrosses()
    {
        ArgumentException thrown =
            Assert.Throws<ArgumentException>(() => new ChipLedger(default(CatId)));
        Assert.StartsWith(NoId, thrown.Message);
    }

    [Fact]
    public void Setter_DefaultCatId_ThrowsAndLeavesTheProperty()
    {
        using var ledger = new ChipLedger(new CatId("oreo-1"));

        ArgumentException thrown =
            Assert.Throws<ArgumentException>(() => ledger.Current = default);
        Assert.StartsWith(NoId, thrown.Message);
        Assert.Equal("oreo-1", ledger.CurrentId());

        ledger.Current = new CatId("oreo-2");
        Assert.Equal("oreo-2", ledger.CurrentId());
    }

    [Fact]
    public void Setter_DefaultHandleBackedValueClass_Throws()
    {
        using var ledger = new ChipLedger(new CatId("oreo-1"));

        ArgumentException thrown =
            Assert.Throws<ArgumentException>(() => ledger.Wearer = default);
        Assert.StartsWith(NoCat, thrown.Message);

        using var mylo = new Cat("Mylo", 9);
        ledger.Wearer = new CatResult(mylo);
        Assert.Equal("Mylo", ledger.Wearer.Name);
    }

    [Fact]
    public void MethodParameter_DefaultValueClass_Throws()
    {
        using var ledger = new ChipLedger(new CatId("oreo-1"));

        Assert.StartsWith(
            NoId, Assert.Throws<ArgumentException>(() => ledger.Register(default)).Message);
        Assert.StartsWith(
            NoCat,
            Assert.Throws<ArgumentException>(() => ledger.RegisterWearer(default)).Message);

        Assert.Equal("registered oreo-3", ledger.Register(new CatId("oreo-3")));
        using var mylo = new Cat("Mylo", 9);
        Assert.Equal("registered Mylo", ledger.RegisterWearer(new CatResult(mylo)));
    }

    [Fact]
    public void NullableSlot_NullIsNull_ButADefaultThrows()
    {
        using var ledger = new ChipLedger(new CatId("oreo-1"));
        ledger.Spare = new CatId("spare-1");

        // A non-null default must not be read as "clear it".
        Assert.StartsWith(
            NoId,
            Assert.Throws<ArgumentException>(() => ledger.Spare = default(CatId)).Message);
        Assert.Equal("spare-1", ledger.SpareId());
        Assert.Throws<ArgumentException>(() => ledger.RegisterSpare(default(CatId)));

        ledger.Spare = null;
        Assert.Equal("none", ledger.SpareId());
        Assert.Equal("none", ledger.RegisterSpare(null));
        Assert.Equal("spare-2", ledger.RegisterSpare(new CatId("spare-2")));
    }

    [Fact]
    public void ListComponent_DefaultValueClass_Throws()
    {
        using var ledger = new ChipLedger(new CatId("oreo-1"));

        var withDefault = new List<CatId> { new CatId("oreo-1"), default };
        Assert.StartsWith(
            NoId,
            Assert.Throws<ArgumentException>(() => ledger.RegisterAll(withDefault)).Message);
        Assert.Throws<ArgumentException>(
            () => ledger.RegisterNamed(new List<string> { "Oreo", "Mylo" }, default));

        Assert.Equal(2, ledger.RegisterAll(new List<CatId> { new("oreo-1"), new("mylo-2") }));
        Assert.Equal(8, ledger.RegisterNamed(new List<string> { "Oreo", "Mylo" }, new("oreo-1")));
    }

    [Fact]
    public void OwnMember_OnADefaultValueClass_Throws()
    {
        CatId none = default;

        Assert.StartsWith(NoId, Assert.Throws<ArgumentException>(() => none.Length).Message);
        Assert.Throws<ArgumentException>(() => none.IsValid());
        Assert.StartsWith(
            NoCat, Assert.Throws<ArgumentException>(() => default(CatResult).IsAlive()).Message);

        Assert.Equal(6, new CatId("oreo-1").Length);
    }

    // The same null with no value class around it: a null string pointer in a non-null Kotlin
    // `String` slot is an access violation in the export, so the flow's write lambda rejects it
    // with the ArgumentNullException a non-null object element already gets.
    [Fact]
    public void MutableStateFlowOfString_NullWrite_ThrowsBeforeItCrosses()
    {
        using var tracker = new CatMoodTracker("Oreo");

        Assert.Throws<ArgumentNullException>(() => tracker.CollarColour.Value = null!);
        Assert.Throws<ArgumentNullException>(
            () => tracker.CollarColour.CompareAndSet(null!, "blue"));
        Assert.Throws<ArgumentNullException>(
            () => tracker.CollarColour.CompareAndSet("red", null!));
        Assert.Equal("red", tracker.CollarColour.Value);

        tracker.CollarColour.Value = "blue";
        Assert.Equal("blue", tracker.CollarColour.Value);
    }
}
