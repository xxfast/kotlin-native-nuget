using TestLibrary.Cat;
using TestLibrary.Membergeneric;
using TestLibrary.Nested;
using TestLibrary.Rankings;

namespace IntegrationTests;

/// <summary>
/// ADR-094's enum box and ADR-198's enum bound on the routes that landed beside them: a member
/// function's own type parameter (ADR-197) and the generic types nested in a generic owner
/// (ADR-196). Every one boxes through the single <c>NugetMarshal.Wrap&lt;T&gt;</c>, so the
/// enum's <c>Boxers</c> row serves them all; Kotlin's own <c>toString</c> and <c>name</c> prove the
/// entry, not its ordinal, crossed.
///
/// Oreo sulks (Grumpy); Mylo naps (Sleepy).
/// </summary>
public class EnumGenericRouteInteractionTests
{
    [Fact]
    public void GenericMethod_EnumArgument_RoundTrips()
    {
        using var groomer = new Groomer();

        Assert.Equal(Mood.Grumpy, groomer.Echo(Mood.Grumpy));
        Assert.Equal(Mood.Sleepy, Salon.Echo(Mood.Sleepy));
        Assert.Equal("GRUMPY+SLEEPY", groomer.Pair(Mood.Grumpy, Mood.Sleepy));
    }

    [Fact]
    public void GenericMethod_NullableEnumArgument_CarriesAValueAndNull()
    {
        using var groomer = new Groomer();

        Assert.Null(groomer.Echo<Mood?>(null));
        Assert.Equal(Mood.Happy, groomer.Echo<Mood?>(Mood.Happy));
    }

    [Fact]
    public void HolderNestedGenericType_EnumArgument_RoundTrips()
    {
        using var cozy = new Teapot.Cozy<Mood>(Mood.Grumpy);

        Assert.Equal(Mood.Grumpy, cozy.Pattern);
    }

    [Fact]
    public void InnerGenericTypeOfAGenericOwner_EnumArguments_RoundTrip()
    {
        using var teapot = new Teapot<Mood>(Mood.Sleepy);
        using var infuser = new Teapot.Infuser<Mood, Mood>(teapot, Mood.Grumpy);
        using var strainer = new Teapot.Strainer<Mood>(teapot, 2);

        Assert.Equal("SLEEPY/GRUMPY", infuser.Both());
        Assert.Equal(Mood.Sleepy, strainer.Peek());
        Assert.Equal(Mood.Sleepy, teapot.Item);
    }

    [Fact]
    public void GenericMethod_OwnEnumBound_ReadsTheEntryOnTheKotlinSide()
    {
        Assert.Equal("GOLD#2", Judge.Rank(Medal.Gold));
        Assert.Equal("SLEEPY#1", Judge.Rank(Mood.Sleepy));
    }
}
