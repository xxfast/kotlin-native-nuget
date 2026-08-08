using TestLibrary.Clinic;
using TestLibrary.Models;

namespace IntegrationTests;

/// <summary>
/// ADR-082's 2026-08-08 amendment: the ratified <c>SKIPPED_INHERITED_MEMBER</c> filter over-drops
/// an author-declared member whose simple name merely collides with a supertype member. The fix
/// makes the skip signature-level (kind + name + arity + per-position parameter types) instead of
/// simple-name, and value-class export names gain the secondary-constructor-style overload
/// numbering so two declared same-name members can coexist at the C symbol level.
///
/// <see cref="StoryUri"/> (cross-module, <c>:test-models</c>) supplies the over-drop fixture:
/// <c>get(key: String)</c> collides in name and arity with the delegated
/// <c>CharSequence.get(index: Int)</c> but not in parameter type, so it must export while the
/// genuinely delegated <c>get</c>/<c>subSequence</c>/<c>length</c> keep skipping.
///
/// <see cref="ChartId"/> (in-module, <c>:test-library</c>) supplies the numbering fixture: two
/// declared <c>describe</c> overloads that must surface as one natural C# overload set.
/// </summary>
public class ValueClassDeclaredMemberTests
{
    [Fact]
    public void StoryUri_Get_UnrelatedOverloadOfDelegatedIndexer_ReturnsMatchingQueryParameter()
    {
        // "get(key: String)" shares CharSequence.get's name and arity (1) but not its parameter
        // type (String, not Int), so it is a declared, unrelated overload -- not the inherited
        // signature -- and must export under the amended rule.
        var uri = new StoryUri("cats.news/oreo-escapes?cat=oreo&mood=zoomies");

        Assert.Equal("oreo", uri.Get("cat"));
        Assert.Equal("zoomies", uri.Get("mood"));
    }

    [Fact]
    public void StoryUri_Get_UnknownKey_ReturnsEmptyString()
    {
        var uri = new StoryUri("cats.news/oreo-escapes?cat=oreo");

        Assert.Equal(string.Empty, uri.Get("mylo"));
    }

    [Fact]
    public void StoryUri_Type_DeclaredGetExports_ButDelegatedCharSequenceSurfaceStaysAbsent()
    {
        // The declared get(key: String) must be reachable (single-parameter overload set: one
        // String-typed Get). The delegated get(index: Int)/subSequence/length -- the genuine
        // inherited signatures -- must still be entirely absent, not merely shadowed.
        var type = typeof(StoryUri);

        Assert.NotNull(type.GetMethod("Get", new[] { typeof(string) }));
        Assert.Null(type.GetMethod("Get", new[] { typeof(int) }));
        Assert.Null(type.GetMethod("SubSequence"));
        Assert.Null(type.GetProperty("Length"));
    }

    [Fact]
    public void ChartId_Describe_NoArgOverload_ReturnsPlainDescription()
    {
        var chartId = new ChartId("Oreo");

        Assert.Equal("Chart Oreo", chartId.Describe());
    }

    [Fact]
    public void ChartId_Describe_PrefixOverload_ReturnsPrefixedDescription()
    {
        // Distinct output from the no-arg overload above: if the numbered export symbols
        // (chartid_describe / chartid_describe_2) or their DllImport EntryPoints get crossed, one
        // overload silently calls the other's native entry point and this assertion catches it.
        var chartId = new ChartId("Mylo");

        Assert.Equal("Vet visit: Mylo", chartId.Describe("Vet visit:"));
    }

    [Fact]
    public void ChartId_Describe_BothOverloads_AreDistinctNativeEntryPoints()
    {
        var chartId = new ChartId("Oreo");

        string plain = chartId.Describe();
        string prefixed = chartId.Describe("Warning:");

        Assert.NotEqual(plain, prefixed);
        Assert.Equal("Chart Oreo", plain);
        Assert.Equal("Warning: Oreo", prefixed);
    }
}
