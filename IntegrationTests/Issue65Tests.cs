using TestLibrary.Issue65;

namespace IntegrationTests;

/// <summary>
/// Issue <a href="https://github.com/xxfast/kotlin-native-nuget/issues/65">#65</a>: Kotlin allows
/// C# reserved words as identifiers, and the forward generator <c>@</c>-escapes only the
/// <em>method</em> name, never a <em>parameter</em> name. A Kotlin parameter called
/// <c>abstract</c> / <c>default</c> / <c>params</c> / <c>string</c> / <c>ref</c> is therefore
/// emitted verbatim into both the public wrapper and the <c>[DllImport]</c> extern, and
/// <c>Interop.cs</c> does not compile (CS1001 / CS1041). The red for this issue is consequently
/// "everything red": the generated file fails to build, so these tests cannot even be compiled
/// against it.
/// <para>
/// Expected once fixed: parameter names render as verbatim identifiers, so a consumer writes
/// <c>new Issue65Article(@abstract: "...")</c> or passes positionally, and the <em>call</em> sites
/// inside the generated wrapper are escaped too. <see cref="Describe_KeywordScalarParameter_NamedArgument_ReachesNative"/>
/// is the cell that catches a half fix: <c>Native_Describe(_handle, default, out ...)</c> is legal
/// C# (the <c>default</c> literal, <c>0</c> for <c>int</c>), so a generator that escapes only the
/// declaration site compiles and silently marshals <c>0</c>.
/// </para>
/// <para>
/// The cats staff the copy desk: Mylo writes the abstract and falls asleep on it, Oreo takes the
/// byline.
/// </para>
/// </summary>
public class Issue65Tests
{
    [Fact]
    public void Constructor_KeywordParameter_AcceptsVerbatimNamedArgument()
    {
        using var article = new Issue65Article(
            @abstract: "Mylo slept through the entire press conference.",
            title: "Cat Naps Through Budget Address");

        Assert.Equal("Mylo slept through the entire press conference.", article.Abstract);
        Assert.Equal("Cat Naps Through Budget Address", article.Title);
    }

    [Fact]
    public void Constructor_KeywordParameter_AcceptsPositionalArgument()
    {
        using var article = new Issue65Article(
            "Oreo, black with a white middle, reviewed the biscuits.",
            "Biscuit Review: Nine Out Of Nine");

        Assert.Equal("Oreo, black with a white middle, reviewed the biscuits.", article.Abstract);
        Assert.Equal("Biscuit Review: Nine Out Of Nine", article.Title);
    }

    [Fact]
    public void Describe_KeywordScalarParameter_NamedArgument_ReachesNative()
    {
        using var article = new Issue65Article(@abstract: "Mylo, brown and creamy.", title: "Milo");

        // If the generated call site emits the bare C# `default` literal instead of the escaped
        // parameter, this compiles but returns "Milo x0".
        Assert.Equal("Milo x7", article.Describe(@default: 7));
    }

    [Fact]
    public void Describe_KeywordScalarParameter_Positional_ReachesNative()
    {
        using var article = new Issue65Article(@abstract: "Oreo, mid-biscuit.", title: "Oreo");

        Assert.Equal("Oreo x3", article.Describe(3));
    }

    [Fact]
    public void Tag_KeywordCollectionParameter_MarshalsTheWholeList()
    {
        using var article = new Issue65Article(@abstract: "Two cats.", title: "Roll Call");

        IReadOnlyList<string> tags = new List<string> { "Oreo", "Mylo", "biscuit" };

        // 4 + 4 + 7: an empty or dropped list would come back 0.
        Assert.Equal(15, article.Tag(@params: tags));
        Assert.Equal(15, article.Tag(tags));
    }

    [Fact]
    public void Greet_TopLevelKeywordParameter_RoundTripsTheString()
    {
        Assert.Equal("Meow, Oreo", Issue65Sample.issue65Greet(@string: "Oreo"));
        Assert.Equal("Meow, Mylo", Issue65Sample.issue65Greet("Mylo"));
    }

    [Fact]
    public void Byline_TopLevelKeywordObjectParameter_ReadsThroughTheHandle()
    {
        using var article = new Issue65Article(
            @abstract: "Mylo declined to comment.",
            title: "Sunbeam Occupied Until Further Notice");

        Assert.Equal(
            "Sunbeam Occupied Until Further Notice by Oreo",
            Issue65Sample.issue65Byline(@ref: article));
        Assert.Equal(
            "Sunbeam Occupied Until Further Notice by Oreo",
            Issue65Sample.issue65Byline(article));
    }
}
