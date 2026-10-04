using TestLibrary;
using TestLibrary.Cat;
using TestLibrary.Parcel;
using Xunit;

namespace IntegrationTests;

/// <summary>
/// ADR-094 (write side): a generated enum WRITTEN into an erased generic slot. The read side
/// (<see cref="FlowEnumElementTests"/>) registers every enum in <c>NugetMarshal.Factories</c>; the
/// write side used to fall through <c>Wrap&lt;T&gt;</c> to its <c>INugetHandle</c> test and throw,
/// so <c>new Box&lt;Mood&gt;(Mood.Grumpy)</c> compiled and failed at the first argument.
///
/// Each enum now has a <c>NugetMarshal.Boxers</c> row that calls its own box export, which hands
/// Kotlin the entry the ordinal names. Kotlin's own <c>toString</c> is the proof that the entry,
/// not its ordinal, crossed: an <c>Int</c> smuggled into the slot prints <c>2</c>, and only the
/// real <c>Mood.GRUMPY</c> prints its name.
///
/// Oreo sulks (Grumpy); Mylo naps (Sleepy).
/// </summary>
public class EnumErasedWriteTests
{
    [Fact]
    public void GenericClass_EnumArgument_RoundTripsOreosSulk()
    {
        using var box = new Box<Mood>(Mood.Grumpy);

        Assert.Equal(Mood.Grumpy, box.Value);
        // `measure` scales `value.toString().length`: "GRUMPY" is 6, an ordinal "2" would be 1.
        Assert.Equal(6, box.Measure(length => length));
    }

    [Fact]
    public void GenericClass_MemberTakingT_SeesTheKotlinEntries()
    {
        using var crate = new Crate<Mood>(Mood.Grumpy);

        Assert.Equal("SLEEPY:GRUMPY", crate.Describe(Mood.Sleepy));
        Assert.Equal(Mood.Happy, crate.Pick(Mood.Happy));
        Assert.Equal(Mood.Grumpy, crate.Item);
    }

    [Fact]
    public void GenericClass_NullableEnumArgument_CarriesAValueAndNull()
    {
        using var mylo = new Slot<Mood?>(Mood.Sleepy);
        Assert.Equal(Mood.Sleepy, mylo.Value);
        Assert.Equal(Mood.Sleepy, mylo.Current);
        Assert.Null(mylo.Previous);

        using var stray = new Slot<Mood?>(null);
        Assert.Null(stray.Value);
        Assert.Null(stray.Current);
    }

    [Fact]
    public void GenericClass_EnumHeldAsObject_StillBoxesAsTheEnum()
    {
        // `Boxers` is keyed on the value's runtime type, so `T = object` reaches the enum row too.
        using var crate = new Crate<object>(Mood.Sleepy);

        Assert.Equal("GRUMPY:SLEEPY", crate.Describe(Mood.Grumpy));
    }

    [Fact]
    public void GenericFunction_EnumArgument_RoundTrips()
    {
        Assert.Equal(Mood.Grumpy, Helpers.Identity<Mood>(Mood.Grumpy));
        Assert.Equal(Mood.Sleepy, Helpers.Identity(Mood.Sleepy));
    }

    [Fact]
    public void GenericFunction_NullableEnumArgument_CarriesAValueAndNull()
    {
        Assert.Equal(Mood.Happy, Helpers.Identity<Mood?>(Mood.Happy));
        Assert.Null(Helpers.Identity<Mood?>(null));
    }

    [Fact]
    public void GenericFunction_EnumIntoAGenericClassResult_RoundTrips()
    {
        using Box<Mood> box = Helpers.WrapInBox(Mood.Grumpy);

        Assert.Equal(Mood.Grumpy, box.Value);
        Assert.Equal(6, box.Measure(length => length));
    }

    [Fact]
    public void OutOfRangeOrdinal_ThrowsAtTheBoundary_AndTheRouteStillAnswers()
    {
        // `Mood.entries[99]` throws inside the box export's error slot, before anything is minted.
        Assert.ThrowsAny<KotlinException>(() => new Box<Mood>((Mood)99));
        Assert.ThrowsAny<KotlinException>(() => Helpers.Identity((Mood)99));

        Assert.Equal(Mood.Sleepy, Helpers.Identity(Mood.Sleepy));
    }
}
