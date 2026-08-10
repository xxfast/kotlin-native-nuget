using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-090: same-name methods on an exported ordinary class. Kotlin's <c>CatNarrator</c> declares
/// three <c>describe</c> overloads and two <c>rate</c> overloads; the bridge numbers the native
/// exports (<c>catnarrator_describe</c>, <c>_2</c>, <c>_3</c>) while the C# surface stays one
/// natural overload set with no visible numbering.
///
/// Every body returns a distinct string, so a mis-numbered export dispatches to the wrong Kotlin
/// method visibly instead of returning something the right shape by accident.
/// </summary>
public class MethodOverloadTests
{
    [Fact]
    public void Describe_NoArguments_DispatchesToFirstOverload()
    {
        using var narrator = new CatNarrator("Oreo");

        Assert.Equal("Oreo is a cat", narrator.Describe());
    }

    [Fact]
    public void Describe_WithPrefix_DispatchesToSecondOverload()
    {
        using var narrator = new CatNarrator("Oreo");

        Assert.Equal("black-and-white Oreo", narrator.Describe("black-and-white"));
    }

    [Fact]
    public void Describe_WithPrefixAndExcited_DispatchesToThirdOverload()
    {
        using var narrator = new CatNarrator("Mylo");

        Assert.Equal("brown-and-creamy Mylo!!!", narrator.Describe("brown-and-creamy", true));
        Assert.Equal("brown-and-creamy Mylo, quietly", narrator.Describe("brown-and-creamy", false));
    }

    [Fact]
    public void Describe_AllThreeOverloads_ShareOneInstanceAndStayDistinct()
    {
        // One receiver, three arities: proves the numbered exports all reach the same Kotlin
        // object rather than each overload standing up its own state.
        using var narrator = new CatNarrator("Oreo");

        Assert.Equal("Oreo is a cat", narrator.Describe());
        Assert.Equal("the biscuit cat Oreo", narrator.Describe("the biscuit cat"));
        Assert.Equal("the biscuit cat Oreo!!!", narrator.Describe("the biscuit cat", true));
    }

    [Fact]
    public void Rate_WithInt_DispatchesToIntOverload()
    {
        // Int and Mood share one wire shape (both cross as int), so this pair is the one that
        // collides on the private extern name if only the DllImport EntryPoint is numbered.
        using var narrator = new CatNarrator("Mylo");

        Assert.Equal("Mylo rated 10", narrator.Rate(10));
    }

    [Fact]
    public void Rate_WithMood_DispatchesToEnumOverload()
    {
        using var narrator = new CatNarrator("Mylo");

        Assert.Equal("Mylo is grumpy", narrator.Rate(Mood.Grumpy));
        Assert.Equal("Mylo is sleepy", narrator.Rate(Mood.Sleepy));
    }

    [Fact]
    public void CatNarrator_ExposesOverloadsAsOneSet_WithoutNumberedPublicNames()
    {
        // The numbering is a native-export detail. Nothing named Describe_2 / Rate_2 may leak
        // onto the public C# surface.
        var type = typeof(CatNarrator);

        Assert.NotNull(type.GetMethod("Describe", Type.EmptyTypes));
        Assert.NotNull(type.GetMethod("Describe", new[] { typeof(string) }));
        Assert.NotNull(type.GetMethod("Describe", new[] { typeof(string), typeof(bool) }));
        Assert.NotNull(type.GetMethod("Rate", new[] { typeof(int) }));
        Assert.NotNull(type.GetMethod("Rate", new[] { typeof(Mood) }));
        // Property accessors (get_Name) are special-name; only real methods are checked here.
        Assert.DoesNotContain(type.GetMethods(), m => !m.IsSpecialName && m.Name.Contains('_'));
    }
}
