using TestLibrary;
using TestLibrary.Cat;
using TestLibrary.Stickers;

namespace IntegrationTests;

/// <summary>
/// ADR-225: a generic value class's own members and secondary constructors bind on its C# record
/// struct, for every closed instantiation. One export per member serves every instantiation: the
/// receiver crosses as the underlying at the class's own <c>T</c> (the bare-<c>T</c> wire for
/// <c>Sticker&lt;T&gt;</c>, a plain <c>long</c> for the phantom <c>Decal&lt;T&gt;</c>). A secondary
/// constructor runs in Kotlin at C# construction, so its <c>init</c> runs there; the positional one
/// still does not. A member with its own type parameters binds on any value class, generic or not
/// (<c>Collar</c>). The Kotlin fixture is <c>test-library/.../test/stickers/Stickers.kt</c>.
///
/// Oreo (black, white in the middle) collects the stickers. Mylo (brown and creamy) peels them off.
/// </summary>
public class GenericValueClassMemberTests
{
    // --- members reading the receiver --------------------------------------------------------

    [Fact]
    public void Member_OnClosedInstantiations_ReadsTheReceiver()
    {
        Assert.Equal("sticker of 3", new Sticker<int>(3).Describe());
        Assert.Equal("sticker of Oreo", new Sticker<string>("Oreo").Describe());
        Assert.Equal("sticker of SLEEPY", new Sticker<Mood>(Mood.Sleepy).Describe());
    }

    [Fact]
    public void Property_ConvertingNothing_ReadsTheReceiver()
    {
        Assert.True(new Sticker<string?>("Mylo").Shiny);
        Assert.False(new Sticker<string?>(null).Shiny);
        Assert.True(new Sticker<int>(0).Shiny);
    }

    [Fact]
    public void Property_TypedAtTheClassTypeParameter_ComesBackThroughTheTWire()
    {
        using var oreo = new Cat("Oreo");

        Assert.Equal(5, new Sticker<int>(5).Peeled);
        Assert.Equal("Mylo", new Sticker<string>("Mylo").Peeled);
        using Cat peeled = new Sticker<Cat>(oreo).Peeled;
        Assert.Equal("Oreo", peeled.Name);
    }

    // --- the class's own T in and the struct out ---------------------------------------------

    [Fact]
    public void Member_TakingAndReturningTheClassTypeParameter_RoundTrips()
    {
        using var oreo = new Cat("Oreo");
        using var mylo = new Cat("Mylo");

        Sticker<Cat> swapped = new Sticker<Cat>(oreo).Swap(mylo);
        using Cat back = swapped.Value;

        Assert.Equal("Mylo", back.Name);
        Assert.Equal(new Sticker<int>(8), new Sticker<int>(7).Swap(8));
        Assert.Equal(new Sticker<string?>(null), new Sticker<string?>("Oreo").Swap(null));
    }

    // --- init and faults ---------------------------------------------------------------------

    [Fact]
    public void Member_RunsInitOnTheReceiver_InKotlin()
    {
        Assert.Throws<KotlinArgumentException>(() => new Sticker<int>(-1).Describe());
        Assert.Throws<KotlinArgumentException>(() => new Sticker<int>(3).Swap(-1));
    }

    [Fact]
    public void Member_ThatThrows_SurfacesTheKotlinException()
    {
        Assert.Equal("intact", new Sticker<string>("Oreo").Tear());
        KotlinInvalidOperationException torn =
            Assert.Throws<KotlinInvalidOperationException>(() => new Sticker<string>("torn").Tear());
        Assert.Contains("Mylo tore it", torn.Message);
    }

    // Exact ArgumentException: the C# guard refuses the null receiver before anything crosses.
    [Fact]
    public void Member_OnADefaultStruct_ThrowsInCSharp_ForANonNullSlot()
    {
        Assert.Equal("badge of Oreo", Stickers.Badge().Describe());
        ArgumentException refused =
            Assert.Throws<ArgumentException>(() => default(Badge<string>).Describe());
        Assert.Equal("default(Badge) carries no Value; construct a Badge instead", refused.Message);
        Assert.Null(refused.ParamName);
    }

    // --- secondary constructors --------------------------------------------------------------

    [Fact]
    public void SecondaryConstructor_NotMentioningT_RunsInKotlin_AndBuildsTheStruct()
    {
        Assert.Equal("abab", new Sticker<string>("ab", 2).Value);
        Assert.Equal(new Sticker<string>("OreoOreo"), new Sticker<string>("Oreo", 2));
    }

    [Fact]
    public void SecondaryConstructor_OverTheClassTypeParameter_PicksOneCat()
    {
        using var oreo = new Cat("Oreo");
        using var mylo = new Cat("Mylo");

        Assert.Equal(2, new Sticker<int>(1, 2, true).Value);
        Assert.Equal("Oreo", new Sticker<string>("Oreo", "Mylo", false).Value);
        using Cat picked = new Sticker<Cat>(oreo, mylo, true).Value;
        Assert.Equal("Mylo", picked.Name);
    }

    // The secondary constructor runs Kotlin's init at C# construction; the positional one does not.
    [Fact]
    public void SecondaryConstructor_RunsInitAtCSharpConstruction()
    {
        Assert.Throws<KotlinArgumentException>(() => new Sticker<int>(3, -1, true));
        Assert.Equal(-1, new Sticker<int>(-1).Value);
    }

    // --- a phantom type parameter ------------------------------------------------------------

    [Fact]
    public void Phantom_MembersAndPropertyConvertNothing()
    {
        Assert.Equal("decal #7", new Decal<Cat>(7L).Describe());
        Assert.Equal("decal #7", new Decal<string>(7L).Describe());
        Assert.Equal(14L, new Decal<Cat>(7L).Doubled);
    }

    [Fact]
    public void Phantom_SecondaryConstructor_RunsInKotlin()
    {
        Assert.Equal(42L, new Decal<Cat>("42").Serial);
        Assert.Equal(new Decal<int>(9L), new Decal<int>("9"));
        Assert.Throws<KotlinFormatException>(() => new Decal<Cat>("not a number"));
    }

    // --- a bound the trampoline checks -------------------------------------------------------

    [Fact]
    public void EnumSelfBound_Member_RunsThroughTheTrampoline()
    {
        Assert.Equal("dozing SLEEPY", Stickers.Doze().Label());
        Assert.Equal("dozing HAPPY", new Doze<Mood>(Mood.Happy).Label());
    }

    // The struct header carries no `where` for `T : Enum<T>`, so C# constructs a `Doze<int>`; the
    // member's receiver read checks the bound in Kotlin and the call throws, the process lives on.
    [Fact]
    public void Member_OnAViolatedBound_IsRefusedInKotlin()
    {
        KotlinInvalidCastException refused =
            Assert.Throws<KotlinInvalidCastException>(() => new Doze<int>(3).Label());
        Assert.Contains("Enum", refused.Message);
        Assert.Equal("dozing SLEEPY", new Doze<Mood>(Mood.Sleepy).Label());
    }

    // --- a member's own type parameters on a value class -------------------------------------

    [Fact]
    public void MemberOwnTypeParameter_OnAGenericValueClass_Binds()
    {
        using var mylo = new Cat("Mylo");

        Assert.Equal("Oreo+7", new Sticker<string>("Oreo").PairWith(7));
        Assert.Equal("3+Mylo", new Sticker<int>(3).PairWith("Mylo"));
        using Cat handed = new Sticker<int>(1).Hand(mylo);
        Assert.Equal("Mylo", handed.Name);
        Assert.Equal(Mood.Grumpy, new Sticker<string>("Oreo").Hand(Mood.Grumpy));
    }

    [Fact]
    public void MemberOwnTypeParameter_OnAPhantom_Binds()
    {
        Assert.Equal("decal #7 on Oreo", new Decal<Cat>(7L).StickOn("Oreo"));
    }

    [Fact]
    public void MemberOwnTypeParameter_OnANonGenericValueClass_Binds()
    {
        using var oreo = new Cat("Oreo");
        Collar collar = Stickers.Collar("t1");

        Assert.Equal("t1:Mylo", collar.Tagged("Mylo"));
        Assert.Equal("t1:4", collar.Tagged(4));
        using Cat worn = collar.Wear(oreo);
        Assert.Equal("Oreo", worn.Name);
        Assert.Null(collar.Wear<string?>(null));
    }

    // The struct's own T and the member's own R are distinct C# type parameters.
    [Fact]
    public void MemberOwnTypeParameter_IsDeClashedFromTheStructTypeParameter()
    {
        var pairWith = typeof(Sticker<>).GetMethod("PairWith");

        Assert.NotNull(pairWith);
        Assert.True(pairWith.IsGenericMethodDefinition);
        Assert.Single(pairWith.GetGenericArguments());
        Assert.NotEqual(typeof(Sticker<>).GetGenericArguments()[0].Name, pairWith.GetGenericArguments()[0].Name);
    }

    // --- suspend members ---------------------------------------------------------------------

    [Fact]
    public async Task SuspendMember_OverTheClassTypeParameter_ReturnsATask()
    {
        using var oreo = new Cat("Oreo");

        Assert.Equal(3, await new Sticker<int>(3).PeelLaterAsync());
        Assert.Equal("Mylo", await new Sticker<string>("Mylo").PeelLaterAsync());
        using Cat later = await new Sticker<Cat>(oreo).PeelLaterAsync();
        Assert.Equal("Oreo", later.Name);
    }

    [Fact]
    public async Task SuspendMember_ConvertingNothing_ReturnsATask()
    {
        Assert.Equal("sticker of Oreo", await new Sticker<string>("Oreo").DescribeLaterAsync());
        Assert.Equal(7L, await new Decal<Cat>(7L).SerialLaterAsync());
    }

    [Fact]
    public async Task SuspendMember_RunsInitOnTheReceiver_InKotlin()
    {
        await Assert.ThrowsAsync<KotlinArgumentException>(() => new Sticker<int>(-1).PeelLaterAsync());
    }
}
