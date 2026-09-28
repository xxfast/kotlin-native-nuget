using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// Enum member properties ride the ADR-062 forward property plan: a throwing getter surfaces as a
/// catchable mapped Kotlin exception instead of aborting the host process, and an enum member
/// <c>var</c> binds a <c>SetX(this Mood mood, value)</c> extension beside the bare-named getter.
///
/// Oreo (black, white in the middle) is the grumpy one who refuses to count his lives; Mylo (brown
/// and creamy) eats the treats. Each test owns its own <see cref="Mood"/> entry for any write, so
/// the singleton state one test leaves behind cannot leak into another's assertions.
/// </summary>
public class EnumMemberPropertyContainmentTests
{
    [Fact]
    public void Mood_NineLives_ThrowingGetter_SurfacesAsMappedKotlinException()
    {
        Assert.Equal(9, Mood.Happy.NineLives());

        var ex = Assert.ThrowsAny<InvalidOperationException>(() => Mood.Grumpy.NineLives());
        Assert.IsType<KotlinInvalidOperationException>(ex);
        Assert.Equal("Oreo refuses to count", ex.Message);
        Assert.Equal("kotlin.IllegalStateException", ((IKotlinException)ex).KotlinType);

        // The process is still alive and the route still answers after a throw.
        Assert.Equal(9, Mood.Sleepy.NineLives());
    }

    // Hammering the throwing getter must not destabilise the process or wedge the route: every
    // throw is independently contained and the error envelope is released each time.
    [Fact]
    public void Mood_NineLives_RepeatedThrows_StaySane()
    {
        for (int i = 0; i < 50; i++)
        {
            var ex = Assert.ThrowsAny<InvalidOperationException>(() => Mood.Grumpy.NineLives());
            Assert.Equal("Oreo refuses to count", ex.Message);
        }

        Assert.Equal(9, Mood.Happy.NineLives());
    }

    // A String enum `var` (the type that needs conversion both ways): it starts at the entry's
    // display name, and a C# write is what the next C# read sees.
    [Fact]
    public void Mood_Nickname_Setter_RoundTripsAString()
    {
        Assert.Equal("Purring Oreo", Mood.Happy.Nickname());

        Mood.Happy.SetNickname("Oreo the Biscuit");
        Assert.Equal("Oreo the Biscuit", Mood.Happy.Nickname());

        // Non-ASCII survives the UTF-8 trip in both directions.
        Mood.Happy.SetNickname("Oreo ♥ Mylo");
        Assert.Equal("Oreo ♥ Mylo", Mood.Happy.Nickname());

        // The write lands on that entry only: the other singletons keep their own nicknames.
        Assert.Equal("Hissing Oreo", Mood.Grumpy.Nickname());
    }

    // An Int enum `var` (no conversion) whose setter throws for a negative count. The accepted
    // write round-trips; the rejected one surfaces as a catchable mapped exception and leaves the
    // stored value untouched.
    [Fact]
    public void Mood_TreatsEaten_Setter_RoundTripsAndThrowingSetterIsContained()
    {
        Assert.Equal(0, Mood.Sleepy.TreatsEaten());

        Mood.Sleepy.SetTreatsEaten(5);
        Assert.Equal(5, Mood.Sleepy.TreatsEaten());

        var ex = Assert.ThrowsAny<ArgumentException>(() => Mood.Sleepy.SetTreatsEaten(-1));
        Assert.IsType<KotlinArgumentException>(ex);
        Assert.Equal("Mylo cannot un-eat a treat", ex.Message);
        Assert.Equal("kotlin.IllegalArgumentException", ((IKotlinException)ex).KotlinType);

        // Still alive, and the rejected write did not land.
        Assert.Equal(5, Mood.Sleepy.TreatsEaten());
        Mood.Sleepy.SetTreatsEaten(7);
        Assert.Equal(7, Mood.Sleepy.TreatsEaten());
    }

    // The migration onto the plan must not rename any existing getter (ADR-006's bare spelling):
    // no `GetDescription()`, and the receiver parameter keeps the lowercased enum name `mood`.
    [Fact]
    public void Mood_ExistingGetters_KeepTheirBareNamesAndReceiverName()
    {
        Assert.Equal("The cat is grumpy and doesn't want to be disturbed.", Mood.Grumpy.Description());
        Assert.Equal("Snoozing Mylo", Mood.Sleepy.DisplayName());
        Assert.False(Mood.Grumpy.IsCuddly());
        Assert.True(Mood.Sleepy.IsSleepy());

        // Named-argument callers pin the receiver parameter name.
        Assert.Equal("The cat is happy and content.", MoodExtensions.Description(mood: Mood.Happy));
        Assert.Equal(9, MoodExtensions.NineLives(mood: Mood.Happy));
    }
}
