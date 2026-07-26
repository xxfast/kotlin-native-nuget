using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-040: a Kotlin function/property whose declared return type is a Kotlin interface must
/// surface in C# as `IFoo`/`IFoo?`, backed by a generated concrete `sealed class Foo : IFoo`
/// wrapper. Oreo and Mylo do the polymorphism proving here - a cat's best friend can be another
/// cat you've never even instantiated a wrapper for.
/// </summary>
public class InterfaceReturnTests
{
    [Fact]
    public void Befriend_ThenFriend_ReturnsBefriendedPetAsIPet()
    {
        using var oreo = new Cat("Oreo", 9);
        using var mylo = new Cat("Mylo", 3);

        oreo.Befriend(mylo);

        using IPet? friend = oreo.Friend;
        Assert.NotNull(friend);
        Assert.Equal("Mylo", friend!.Name);
        Assert.Equal(4, friend.Legs);
        Assert.Equal("Meow! My name is Mylo", friend.Speak());
        Assert.Equal("Hi, I'm Mylo", friend.Greet());
    }

    [Fact]
    public void Friend_NullWhenNeverBefriended()
    {
        using var oreo = new Cat("Oreo", 9);
        Assert.Null(oreo.Friend);
    }

    [Fact]
    public void Friend_CanBeSetBackToNull()
    {
        using var oreo = new Cat("Oreo", 9);
        using var mylo = new Cat("Mylo", 3);

        oreo.Befriend(mylo);
        Assert.NotNull(oreo.Friend);

        oreo.Friend = null;
        Assert.Null(oreo.Friend);
    }

    [Fact]
    public void ClosestFriend_WhenNoFriendSet_ReturnsSelfAsIPet()
    {
        using var oreo = new Cat("Oreo", 9);
        using IPet closest = oreo.ClosestFriend();
        Assert.Equal("Oreo", closest.Name);
        Assert.Equal(4, closest.Legs);
    }

    [Fact]
    public void ClosestFriend_WhenFriendSet_ReturnsFriendAsIPet()
    {
        using var oreo = new Cat("Oreo", 9);
        using var mylo = new Cat("Mylo", 3);
        oreo.Befriend(mylo);

        using IPet closest = oreo.ClosestFriend();
        Assert.Equal("Mylo", closest.Name);
    }

    [Fact]
    public void MaybeFriend_NullWhenNoFriendSet()
    {
        using var oreo = new Cat("Oreo", 9);
        Assert.Null(oreo.MaybeFriend());
    }

    [Fact]
    public void MaybeFriend_ReturnsFriendWhenSet()
    {
        using var oreo = new Cat("Oreo", 9);
        using var mylo = new Cat("Mylo", 3);
        oreo.Befriend(mylo);

        using IPet? maybe = oreo.MaybeFriend();
        Assert.NotNull(maybe);
        Assert.Equal("Mylo", maybe!.Name);
    }

    [Fact]
    public void Self_ReturnsOwningCatAsIPet()
    {
        using var oreo = new Cat("Oreo", 9);
        using IPet self = oreo.Self;
        Assert.Equal("Oreo", self.Name);
        Assert.Equal("Meow! My name is Oreo", self.Speak());
    }

    // The seam that fails if the backing class ever tries to resolve the concrete Kotlin type:
    // the runtime object is an anonymous `object : Pet` with no generated C# wrapper at all.
    [Fact]
    public void StrayPet_AnonymousKotlinObject_DispatchesThroughIPet()
    {
        using IPet stray = PetKt.strayPet();
        Assert.Equal("Whiskers the Stray", stray.Name);
        Assert.Equal(3, stray.Legs);
        Assert.Null(stray.Nickname);
        Assert.Equal("Mrrp?", stray.Speak());
        Assert.Equal("Hi, I'm Whiskers the Stray", stray.Greet()); // interface default implementation
        Assert.Equal("eyes the yarn warily but doesn't fetch it", stray.Fetch("yarn"));
        stray.Nap(); // void dispatch export - just must not throw
    }

    [Fact]
    public void Fetch_PassesStringInAndReturnsString()
    {
        using var oreo = new Cat("Oreo", 9);
        Assert.Equal("Oreo fetches the ball", oreo.Fetch("ball"));
    }

    [Fact]
    public void Nap_VoidDispatchExport_DoesNotThrow()
    {
        using var oreo = new Cat("Oreo", 9);
        oreo.Nap();
    }

    [Fact]
    public void Nickname_PresentOnCat()
    {
        using var oreo = new Cat("Oreo", 9);
        Assert.Equal("Oreoy", oreo.Nickname);
    }

    [Fact]
    public void Nickname_AbsentOnStrayPet()
    {
        using IPet stray = PetKt.strayPet();
        Assert.Null(stray.Nickname);
    }

    // Pins the fresh-StableRef-per-return semantics: disposing a wrapper returned from a
    // property/method must not affect the owning Cat's own handle.
    [Fact]
    public void DisposingReturnedFriendWrapper_DoesNotAffectOwningCat()
    {
        using var oreo = new Cat("Oreo", 9);
        using var mylo = new Cat("Mylo", 3);
        oreo.Befriend(mylo);

        IPet? firstRead = oreo.Friend;
        Assert.NotNull(firstRead);
        firstRead!.Dispose();

        // The owning Cat's handle is independent - it must still be perfectly usable.
        Assert.Equal("Oreo", oreo.Name);
        Assert.Equal("Meow! My name is Oreo", oreo.Speak());

        // A second read mints an independent wrapper too - still works after the first was disposed.
        using IPet? secondRead = oreo.Friend;
        Assert.NotNull(secondRead);
        Assert.Equal("Mylo", secondRead!.Name);
    }
}
