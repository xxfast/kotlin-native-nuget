using TestLibrary;
using TestLibrary.Collartag;

namespace IntegrationTests;

/// <summary>
/// A value class's own methods and getters returning a nullable result, one per representative
/// family, null and non-null. A value-class member has no error slot (ADR-014), so each binds on
/// the ordinary member route's own wire: the null pointer for a reference result, the
/// <c>bool</c> result plus a <c>valueOut</c> out slot for a value-type one.
///
/// Mylo's collar tag has his name on it; Oreo lost hers in the garden.
/// </summary>
public class ValueClassNullableResultTests
{
    private static readonly CollarTag Mylo = new("Mylo");
    private static readonly CollarTag Blank = new("");

    [Fact]
    public void Nickname_NullableReferenceGetter_ReadsBothValues()
    {
        string? mylo = Mylo.Nickname;

        Assert.Equal("Mylo the brave", mylo);
        Assert.Null(Blank.Nickname);
    }

    [Fact]
    public void InitialCount_NullableHasValueGetter_ReadsBothValues()
    {
        int? mylo = Mylo.InitialCount;

        Assert.Equal(4, mylo);
        Assert.Null(Blank.InitialCount);
    }

    [Fact]
    public void Motto_NullableString_ReadsBothValues()
    {
        Assert.Equal("MYLO!", Mylo.Motto(true));
        Assert.Equal("Mylo", Mylo.Motto(false));
        Assert.Null(Blank.Motto(true));
    }

    [Fact]
    public void Letters_NullablePrimitive_ReadsBothValues()
    {
        Assert.Equal(4, Mylo.Letters());
        Assert.Null(Blank.Letters());
    }

    [Fact]
    public void Temper_NullableEnum_ReadsBothValues()
    {
        Temper? mylo = Mylo.Temper();

        Assert.Equal(TestLibrary.Collartag.Temper.Grumpy, mylo);
        Assert.Null(Blank.Temper());
    }

    [Fact]
    public void Bell_NullableObject_ReadsBothValues()
    {
        using Bell? bell = Mylo.Bell();

        Assert.Equal("Mylo-ding", bell!.Tone);
        Assert.Null(Blank.Bell());
    }

    [Fact]
    public void Names_NullableList_ReadsBothValues()
    {
        IReadOnlyList<string>? names = Mylo.Names();

        Assert.Equal(new[] { "Mylo", "olyM" }, names);
        Assert.Null(Blank.Names());
    }

    [Fact]
    public void Mishap_NullableThrowable_ReadsBothValues()
    {
        Exception? mishap = Mylo.Mishap();

        Assert.IsType<KotlinInvalidOperationException>(mishap);
        Assert.Equal("Mylo slipped the collar", mishap!.Message);
        Assert.Null(Blank.Mishap());
    }

    [Fact]
    public void OwnBellAndAllNames_NonNullHandleResults_Reconstruct()
    {
        using Bell bell = Mylo.OwnBell();

        Assert.Equal("Mylo-dong", bell.Tone);
        Assert.Equal(new[] { "Mylo" }, Mylo.AllNames());
    }
}
