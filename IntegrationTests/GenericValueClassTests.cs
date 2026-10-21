using TestLibrary;
using TestLibrary.Cat;
using TestLibrary.Stickers;

namespace IntegrationTests;

/// <summary>
/// ADR-220: a generic value class (<c>value class Sticker&lt;T&gt;(val value: T)</c>) renders as a
/// positional <c>public readonly record struct Sticker&lt;T&gt;(T Value)</c>. A closed instantiation
/// (<c>Sticker&lt;Int&gt;</c>, <c>Sticker&lt;String&gt;</c>, <c>Sticker&lt;Mood&gt;</c>,
/// <c>Sticker&lt;Cat&gt;</c>) crosses as its substituted underlying at a return, a parameter, a
/// nullable position, a property and a constructor parameter. A member's or a top-level function's
/// own <c>T</c> inside one crosses on the bare-<c>T</c> wire, null in-band. A phantom
/// <c>Decal&lt;T&gt;(val serial: Long)</c> is generic only in its C# spelling. The struct has no
/// <c>CreateChecked</c>, so Kotlin's <c>init</c> runs at the first crossing, not at C# construction.
/// The Kotlin fixture is <c>test-library/.../test/stickers/Stickers.kt</c>.
///
/// Oreo (black, white in the middle) collects the stickers. Mylo (brown and creamy) peels them off.
/// </summary>
public class GenericValueClassTests
{
    // --- the struct shape --------------------------------------------------------------------

    [Fact]
    public void Sticker_IsAGenericRecordStruct_WithValueEquality()
    {
        var oreo = new Sticker<string>("Oreo");

        Assert.True(typeof(Sticker<string>).IsValueType);
        Assert.True(typeof(Sticker<>).IsGenericTypeDefinition);
        Assert.Equal(new Sticker<string>("Oreo"), oreo);
        Assert.True(oreo == new Sticker<string>("Oreo"));
        Assert.Equal("Mylo", (oreo with { Value = "Mylo" }).Value);
    }

    // --- closed instantiations ---------------------------------------------------------------

    [Fact]
    public void ClosedPrimitive_RoundTripsAtReturnAndParameter()
    {
        Sticker<int> treats = Stickers.Count();

        Assert.Equal(new Sticker<int>(3), treats);
        Assert.Equal(new Sticker<int>(4), Stickers.Bump(treats));
    }

    [Fact]
    public void ClosedString_CrossesAtAParameter()
    {
        Assert.Equal("OREO", Stickers.Shout(new Sticker<string>("Oreo")));
    }

    [Fact]
    public void ClosedNullable_IsNullWhenAbsent_AndTheStructWhenPresent()
    {
        Sticker<string>? none = Stickers.Missing();
        Sticker<string>? oreo = Stickers.Found();

        Assert.Null(none);
        Assert.Equal(new Sticker<string>("Oreo"), oreo);
    }

    [Fact]
    public void ClosedEnum_CrossesAtReturn_AndAsANullableParameter()
    {
        Sticker<Mood> nap = Stickers.Nap();

        Assert.Equal(new Sticker<Mood>(Mood.Sleepy), nap);
        Assert.Equal("Snoozing Mylo", Stickers.Describe(nap));
        Assert.Equal("Purring Oreo", Stickers.Describe(new Sticker<Mood>(Mood.Happy)));
        Assert.Equal("no mood", Stickers.Describe(null));
    }

    [Fact]
    public void ClosedExportedClass_BorrowsAtAParameter_AndOwnsAtANullableReturn()
    {
        using var mylo = new Cat("Mylo");
        Assert.Equal("Mylo", Stickers.NameOf(new Sticker<Cat>(mylo)));

        Sticker<Cat>? adopted = Stickers.Adopt("Oreo");
        Assert.NotNull(adopted);
        using Cat oreo = adopted.Value.Value;
        Assert.Equal("Oreo", oreo.Name);
        Assert.Null(Stickers.Adopt(null));
    }

    [Fact]
    public void ClosedInstantiation_InsideAList_CrossesPerElement()
    {
        IReadOnlyList<Sticker<int>> doubled =
            Stickers.Tally(new[] { new Sticker<int>(1), new Sticker<int>(2) });

        Assert.Equal(new[] { new Sticker<int>(2), new Sticker<int>(4) }, doubled);
    }

    // --- a function's own T inside the struct ------------------------------------------------

    [Fact]
    public void OwnTypeParameter_TopLevel_CrossesInsideTheStruct()
    {
        using var mylo = new Cat("Mylo");
        Sticker<Cat> stuck = Stickers.Stick(mylo);
        using Cat peeled = stuck.Value;

        Assert.Equal("Mylo", peeled.Name);
        Assert.Equal("x", Stickers.Peel(new Sticker<string>("x")));
        Assert.Equal(3, Stickers.Peel(Stickers.Stick(3)));
        Assert.Equal(Mood.Grumpy, Stickers.Peel(Stickers.Stick(Mood.Grumpy)));
    }

    [Fact]
    public void OwnTypeParameter_UnboundedNullValue_CrossesAsNull()
    {
        Assert.Null(Stickers.Peel(default(Sticker<string?>)));
        Assert.Null(Stickers.Stick<string?>(null).Value);
    }

    [Fact]
    public void OwnNullableTypeParameter_CarriesNullInBand()
    {
        Sticker<string?> blank = Stickers.Blank<string>(null);

        Assert.Null(blank.Value);
        Assert.Equal("Mylo", Stickers.Blank<string>("Mylo").Value);
    }

    [Fact]
    public void OwnBoundTypeParameter_PassesAPresentValue()
    {
        Assert.Equal("Oreo", Stickers.Firm(new Sticker<string>("Oreo")));
    }

    // --- default(Sticker<T>) into a non-null slot throws in C# -------------------------------

    // Exact ArgumentException, not the KotlinArgumentException subclass: the C# guard must refuse
    // the null underlying before anything crosses.
    [Fact]
    public void DefaultClosedSticker_IntoANonNullStringSlot_ThrowsInCSharp()
    {
        Assert.Throws<ArgumentException>(() => Stickers.Shout(default(Sticker<string>)));
    }

    [Fact]
    public void DefaultSticker_IntoABoundOwnTypeParameterSlot_ThrowsInCSharp()
    {
        Assert.Throws<ArgumentException>(() => Stickers.Firm(default(Sticker<string>)));
    }

    // --- init runs in Kotlin at the first crossing -------------------------------------------

    [Fact]
    public void Init_DoesNotRunAtCSharpConstruction_ButAtTheFirstCrossing()
    {
        var peeled = new Sticker<int>(-1);

        Assert.Equal(-1, peeled.Value);
        Assert.Throws<KotlinArgumentException>(() => Stickers.Bump(peeled));
        Assert.Throws<KotlinArgumentException>(() => Stickers.Stick(-1));
    }

    // --- a phantom type parameter ------------------------------------------------------------

    [Fact]
    public void PhantomTypeParameter_CrossesAsItsUnderlying()
    {
        Decal<Cat> decal = Stickers.OreoDecal();

        Assert.True(typeof(Decal<>).IsGenericTypeDefinition);
        Assert.Equal(new Decal<Cat>(7L), decal);
        Assert.Equal(7L, decal.Serial);
        Assert.Equal(42L, Stickers.Serial(new Decal<Cat>(42L)));
    }

    // --- a class: constructor parameter, properties, member's own T -------------------------

    [Fact]
    public void StickerBook_CarriesClosedInstantiations_AsConstructorParametersAndProperties()
    {
        using var book = new StickerBook(new Sticker<string>("Oreo's"), new Sticker<int>(12));

        Assert.Equal(new Sticker<string>("Oreo's"), book.Cover);
        Assert.Equal(new Sticker<int>(12), book.Pages);
        book.Pages = new Sticker<int>(13);
        Assert.Equal(13, book.Pages.Value);

        Assert.Null(book.Favourite);
        book.Favourite = new Sticker<Mood>(Mood.Happy);
        Assert.Equal(new Sticker<Mood>(Mood.Happy), book.Favourite);
        book.Favourite = null;
        Assert.Null(book.Favourite);

        Assert.Equal(new Decal<Cat>(9L), book.Decal);
    }

    [Fact]
    public void StickerBook_RunsInitWhenAValueCrossesIn()
    {
        Assert.Throws<KotlinArgumentException>(
            () => new StickerBook(new Sticker<string>("Mylo's"), new Sticker<int>(-1)));

        using var book = new StickerBook(new Sticker<string>("Mylo's"), new Sticker<int>(1));
        Assert.Throws<KotlinArgumentException>(() => book.Pages = new Sticker<int>(-1));
        Assert.Equal(1, book.Pages.Value);
    }

    [Fact]
    public void StickerBook_MemberOwnTypeParameter_CrossesInsideTheStruct()
    {
        using var book = new StickerBook(new Sticker<string>("Oreo's"), new Sticker<int>(1));
        using var oreo = new Cat("Oreo");

        Assert.Equal("Mylo", book.Peel(book.Stick("Mylo")));
        using Cat stuck = book.Stick(oreo).Value;
        Assert.Equal("Oreo", stuck.Name);
        Assert.Equal(Mood.Sleepy, book.Peel(new Sticker<Mood>(Mood.Sleepy)));
    }
}
