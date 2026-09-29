using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-006 amendment: an enum member function binds as a <c>public static R Name(this Mood mood, ...)</c>
/// extension in the existing <c>MoodExtensions</c> class (receiver named <c>mood</c>, as the ADR-172
/// member properties already are), and a companion function or property binds as a plain static on
/// that same class, because a C# enum cannot declare members and C# 12 has no static extensions.
///
/// Oreo (black, white in the middle) is the grumpy one who hisses when petted; Mylo (brown and
/// creamy) is the sleepy one who dreams of cream. Nothing here writes <c>Mood.nickname</c> or
/// <c>treatsEaten</c>, which <see cref="EnumMemberPropertyContainmentTests"/> owns.
/// </summary>
public class EnumMemberFunctionTests
{
    // No parameters, no conversion; the 1-byte bool return must not read garbage.
    [Fact]
    public void Mood_IsLoudNow_BindsAsAnExtensionMethod()
    {
        Assert.True(Mood.Grumpy.IsLoudNow());
        Assert.False(Mood.Sleepy.IsLoudNow());
        Assert.False(Mood.Happy.IsLoudNow());
    }

    // A String parameter (needs conversion) and its overload partner adding a plain Int.
    [Fact]
    public void Mood_Greet_StringParameterAndIntOverload_RoundTrip()
    {
        Assert.Equal("Purring Oreo greets Mylo", Mood.Happy.Greet("Mylo"));
        Assert.Equal(
            "Snoozing Mylo greets Oreo / Snoozing Mylo greets Oreo",
            Mood.Sleepy.Greet("Oreo", 2));

        // Non-ASCII survives the UTF-8 trip into the member.
        Assert.Equal("Hissing Oreo greets Mylo ♥", Mood.Grumpy.Greet("Mylo ♥"));
    }

    // Named-argument callers pin the receiver parameter name to the enum's camelCase name, the
    // same `mood` the member-property extensions use.
    [Fact]
    public void Mood_MemberFunction_ReceiverParameterIsNamedMood()
    {
        Assert.Equal("Purring Oreo greets Mylo", MoodExtensions.Greet(mood: Mood.Happy, name: "Mylo"));
        Assert.True(MoodExtensions.IsLoudNow(mood: Mood.Grumpy));
    }

    // Another enum as a parameter: the receiver and the argument both cross as ordinals and must
    // not be swapped or collapsed.
    [Fact]
    public void Mood_SharesSunbeamWith_TakesAnotherEnum()
    {
        Assert.True(Mood.Sleepy.SharesSunbeamWith(Mood.Sleepy));
        Assert.False(Mood.Sleepy.SharesSunbeamWith(Mood.Grumpy));
        Assert.False(Mood.Grumpy.SharesSunbeamWith(Mood.Happy));
    }

    // A throwing member function: grumpy Oreo hisses. Contained as a mapped Kotlin exception, and
    // the route still answers afterwards.
    [Fact]
    public void Mood_Pet_ThrowingMember_SurfacesAsMappedKotlinException()
    {
        Assert.Equal(1, Mood.Happy.Pet());

        var ex = Assert.ThrowsAny<InvalidOperationException>(() => Mood.Grumpy.Pet());
        Assert.IsType<KotlinInvalidOperationException>(ex);
        Assert.Equal("Oreo hisses", ex.Message);
        Assert.Equal("kotlin.IllegalStateException", ((IKotlinException)ex).KotlinType);

        Assert.Equal(1, Mood.Sleepy.Pet());
    }

    // A class-typed return hands back an owned wrapper the caller disposes.
    [Fact]
    public void Mood_ToyFor_ReturnsAnOwnedToy()
    {
        using Toy toy = Mood.Grumpy.ToyFor();
        Assert.Equal("Hissing Oreo's mouse", toy.Name);
        Assert.Equal("black", toy.Color);
    }

    // A nullable class-typed return: only a cuddly mood fetches the yarn; grumpy Oreo gets null.
    [Fact]
    public void Mood_FetchToy_NullableWrapperReturn()
    {
        using Toy? yarn = Mood.Sleepy.FetchToy("cream");
        Assert.NotNull(yarn);
        Assert.Equal("Snoozing Mylo's cream yarn", yarn!.Name);
        Assert.Equal("cream", yarn.Color);

        Assert.Null(Mood.Grumpy.FetchToy("black"));
    }

    // A nullable String return: Mylo dreams only when asleep.
    [Fact]
    public void Mood_Dream_NullableStringReturn()
    {
        Assert.Equal("Mylo dreams of cream", Mood.Sleepy.Dream());
        Assert.Null(Mood.Grumpy.Dream());
    }

    // An `abstract fun` with a body on each entry dispatches to the entry it was called on.
    [Fact]
    public void Chatter_AbstractMember_DispatchesToEachEntrysBody()
    {
        Assert.Equal("chirp chirp", Chatter.Chirp.Sound(2));
        Assert.Equal("trrrl trrrl trrrl", Chatter.Trill.Sound(3));
        Assert.Equal("", Chatter.Trill.Sound(0));
        Assert.Equal("chirp", ChatterExtensions.Sound(chatter: Chatter.Chirp, times: 1));
    }

    // Companion functions are plain statics on the extension class.
    [Fact]
    public void Mood_CompanionFunctions_AreStaticsOnTheExtensionsClass()
    {
        Assert.Equal(Mood.Sleepy, MoodExtensions.Fallback());

        Mood? grumpy = MoodExtensions.ByDisplayName("Hissing Oreo");
        Assert.Equal(Mood.Grumpy, grumpy);
        Assert.Null(MoodExtensions.ByDisplayName("Nobody's cat"));
    }

    // Companion `val` and `var` fold in as static properties. This is the only test that touches
    // `lastSeen`, so the process-global write cannot race another assertion.
    [Fact]
    public void Mood_CompanionProperties_AreStaticPropertiesOnTheExtensionsClass()
    {
        Assert.Equal(Mood.Happy, MoodExtensions.HouseFavourite);

        Assert.Equal(Mood.Sleepy, MoodExtensions.LastSeen);
        MoodExtensions.LastSeen = Mood.Grumpy;
        Assert.Equal(Mood.Grumpy, MoodExtensions.LastSeen);
        MoodExtensions.LastSeen = Mood.Sleepy;
        Assert.Equal(Mood.Sleepy, MoodExtensions.LastSeen);
    }
}
