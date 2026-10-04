using TestLibrary.Lamplight;

namespace IntegrationTests;

/// <summary>
/// An extension function beside an applicable member of the same name
/// (<c>lamplight/Lantern.kt</c>, <c>lamplight/ext/LanternExtensions.kt</c>). Kotlin's own
/// <c>lantern.glow()</c> picks the member, so the generated export calls the extension through an
/// aliased import. Every callable returns the side that ran: the static
/// <c>LanternExtensions.Glow(lantern)</c> must read "extension", never the member's "member".
/// Instance syntax still reaches the member, as it does in Kotlin and in C#.
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
}
