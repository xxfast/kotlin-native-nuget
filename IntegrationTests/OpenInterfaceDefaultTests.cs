using System.Reflection;
using TestLibrary.Windowsill;

namespace IntegrationTests;

/// <summary>
/// An interface default inherited by an <c>open</c> owner is <c>virtual</c> in C#, so a Kotlin
/// subclass that overrides it renders <c>override</c> and the call through the base type reaches the
/// subclass's body. Before the fix the owner rendered the default non-virtual, and the subclass's
/// <c>override</c> was CS0506. Mylo shreds the carpet post; Oreo kneads the sun hammock.
/// </summary>
public class OpenInterfaceDefaultTests
{
    [Fact]
    public void OpenClass_InheritedDefault_IsVirtual()
    {
        MethodInfo scratch = typeof(ScratchingPost).GetMethod(nameof(ScratchingPost.Scratch))!;

        Assert.True(scratch.IsVirtual && !scratch.IsFinal);
        Assert.Equal(typeof(CarpetPost), typeof(CarpetPost).GetMethod(nameof(CarpetPost.Scratch))!.DeclaringType);
    }

    [Fact]
    public void OpenClass_OverrideThroughTheBaseType_RunsTheSubclassBody()
    {
        using ScratchingPost post = new CarpetPost();

        Assert.Equal("Mylo shreds the carpet post", post.Scratch());
        Assert.Equal(18, post.Claws);
        Assert.Equal("Mylo shreds the carpet post", ((IScratcher)post).Scratch());
    }

    [Fact]
    public void OpenClass_BaseInstance_KeepsTheInterfaceDefault()
    {
        using var post = new ScratchingPost();

        Assert.Equal("a quick scratch", post.Scratch());
        Assert.Equal(10, post.Claws);
        Assert.Equal("a quick scratch", ScratchingPostSample.ScratchOf(post));
    }

    [Fact]
    public void OpenArm_OverrideThroughTheArmType_RunsTheSubclassBody()
    {
        using Lounger.Hammock hammock = new SunHammock();

        Assert.True(hammock is IScratcher);
        Assert.Equal("Oreo kneads the sun hammock", hammock.Scratch());
        Assert.Equal(16, hammock.Claws);
        Assert.Equal("Oreo kneads the sun hammock", ((IScratcher)hammock).Scratch());
    }

    [Fact]
    public void OpenArm_InheritedDefault_IsVirtual()
    {
        MethodInfo scratch = typeof(Lounger.Hammock).GetMethod(nameof(Lounger.Hammock.Scratch))!;

        Assert.True(scratch.IsVirtual && !scratch.IsFinal);
        using var hammock = new Lounger.Hammock();
        Assert.Equal("a quick scratch", hammock.Scratch());
    }
}
