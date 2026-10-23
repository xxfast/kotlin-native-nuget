using TestLibrary.Cat;
using TestLibrary.Stickers;

namespace IntegrationTests;

/// <summary>
/// A generic value class over a <c>T?</c> (<c>Opt&lt;T&gt;(val value: T?)</c>) closed at a value
/// type is spelled with the nullable argument (<c>Opt&lt;int?&gt;</c>), so its null survives both
/// ways; a reference type keeps its spelling (<c>Opt&lt;string&gt;</c>). A non-generic value class
/// over a nullable list (<c>TreatTin(val treats: List&lt;Int&gt;?)</c>) keeps "no list" apart from
/// "no tin" as a parameter, a result, under <c>?</c> and inside a <c>Box</c>.
///
/// The Kotlin fixture is <c>test-library/.../test/stickers/Optionals.kt</c>.
///
/// Oreo (black, white in the middle) keeps an empty tin. Mylo (brown and creamy) has no tin at all.
/// </summary>
public class NullableUnderlyingSpellingTests
{
    [Fact]
    public void ValueTypeOpt_KeepsItsNullBothWays()
    {
        Assert.Null(Optionals.OptNone().Value);
        Assert.Equal(3, Optionals.OptSome(3).Value);
        Assert.Equal("none", Optionals.OptState(new Opt<int?>(null)));
        Assert.Equal("5", Optionals.OptState(new Opt<int?>(5)));
    }

    [Fact]
    public void ReferenceTypeOpt_KeepsItsSpelling()
    {
        Opt<string> named = Optionals.OptNamed();

        Assert.Null(named.Value);
        Assert.Equal("none", Optionals.OptName(new Opt<string>(null)));
        Assert.Equal("Oreo", Optionals.OptName(new Opt<string>("Oreo")));
    }

    [Fact]
    public void OwnTypeParameterOpt_RoundTrips()
    {
        Assert.Equal(4L, Optionals.OptOf<long>(4).Value);
        Assert.Null(Optionals.OptOf<string>(null).Value);
    }

    [Fact]
    public void NullableListUnderlying_ParamAndResult_KeepNoListApartFromEmpty()
    {
        Assert.Null(Optionals.TinOf(false).Treats);
        Assert.Equal(new[] { 1, 2 }, Optionals.TinOf(true).Treats);
        Assert.Equal(-1, Optionals.TinSize(new TreatTin(null)));
        Assert.Equal(0, Optionals.TinSize(new TreatTin(Array.Empty<int>())));
    }

    [Fact]
    public void NullableListUnderlying_UnderNullable_KeepsNoTinApartFromNoList()
    {
        Assert.Null(Optionals.MaybeTin(0));
        TreatTin? empty = Optionals.MaybeTin(1);
        Assert.NotNull(empty);
        Assert.Null(empty!.Value.Treats);
        Assert.Equal(new[] { 2 }, Optionals.MaybeTin(2)!.Value.Treats);
        Assert.Equal("absent", Optionals.TinState(null));
        Assert.Equal("empty", Optionals.TinState(new TreatTin(null)));
        Assert.Equal("full", Optionals.TinState(new TreatTin(new[] { 1 })));
    }

    [Fact]
    public void NullableListUnderlying_InABox_RoundTrips()
    {
        using Box<TreatTin> empty = Optionals.BoxedTin(false);
        Assert.Null(empty.Value.Treats);
        Assert.Equal(-1, Optionals.BoxedTinSize(empty));
        using var made = new Box<TreatTin>(new TreatTin(new[] { 1, 2, 3 }));
        Assert.Equal(3, Optionals.BoxedTinSize(made));
        using var none = new Box<TreatTin>(new TreatTin(null));
        Assert.Equal(-1, Optionals.BoxedTinSize(none));
    }
}
