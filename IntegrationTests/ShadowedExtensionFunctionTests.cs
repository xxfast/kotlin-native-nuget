using TestLibrary.Lamplight;

namespace IntegrationTests;

/// <summary>
/// An extension function beside an applicable member of the same name
/// (<c>lamplight/Lantern.kt</c>, <c>lamplight/ext/LanternExtensions.kt</c>). Kotlin's own
/// <c>lantern.glow()</c> picks the member, so the generated export calls the extension through an
/// aliased import. Every callable returns the side that ran: the static
/// <c>LanternExtensions.Glow(lantern)</c> must read "extension", never the member's "member".
/// Instance syntax still reaches the member, as it does in Kotlin and in C#. <c>Shine</c>,
/// <c>Swing</c>, <c>AddFlare</c> (a stored-callback pair) and the nullable-receiver property
/// <c>Wick</c> pair a member with an extension declared in the member's own package, where the
/// extension takes a marked C entry point instead of colliding with the member's.
/// </summary>
public class ShadowedExtensionFunctionTests
{
    [Fact]
    public void ExactShadow_StaticForm_ReturnsTheExtension()
    {
        using var lantern = new Lantern();
        Assert.Equal("member", lantern.Glow());
        Assert.Equal("extension", LanternExtensions.Glow(lantern));
    }

    [Fact]
    public void DefaultedMemberShadow_StaticForm_ReturnsTheExtension()
    {
        using var lantern = new Lantern();
        Assert.Equal("member:0", lantern.Dim());
        Assert.Equal("extension", LanternExtensions.Dim(lantern));
    }

    [Fact]
    public void InapplicableMember_StaticForm_ReturnsTheExtension()
    {
        using var lantern = new Lantern();
        Assert.Equal("member:5", lantern.Flicker(5));
        Assert.Equal("extension:5", LanternExtensions.Flicker(lantern, 5L));
    }

    [Fact]
    public void SamePackageExactShadow_StaticForm_ReturnsTheExtension()
    {
        using var lantern = new Lantern();
        Assert.Equal("member", lantern.Shine());
        Assert.Equal("extension", LanternExtensions.Shine(lantern));
    }

    [Fact]
    public void SamePackageShadowWithOverload_EachReachesItsOwnBody()
    {
        using var lantern = new Lantern();
        Assert.Equal("member:5", lantern.Swing(5));
        Assert.Equal("extension:5", LanternExtensions.Swing(lantern, 5));
        Assert.Equal("extension:long:5", LanternExtensions.Swing(lantern, 5L));
    }

    [Fact]
    public void SamePackageStoredCallbackPair_SubscriptionAndExtensionEachReachTheirOwn()
    {
        using var lantern = new Lantern();
        var flares = new List<int>();
        using (lantern.AddFlare(brightness => flares.Add(brightness)))
        {
            lantern.Flare(3);
        }
        lantern.Flare(4);

        Assert.Equal(new[] { 3 }, flares);
        Assert.Equal("extension", LanternExtensions.AddFlare(lantern));
    }

    [Fact]
    public void SamePackageNullableReceiverProperty_ReadsAndWritesTheExtension()
    {
        using var lantern = new Lantern();
        Assert.Equal(1, lantern.Wick);
        Assert.Equal(10, LanternExtensions.get_Wick(lantern));

        LanternExtensions.set_Wick(lantern, 50);

        Assert.Equal(5, lantern.Wick);
        Assert.Equal(50, LanternExtensions.get_Wick(lantern));
    }
}
