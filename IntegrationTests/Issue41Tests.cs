using TestLibrary;
using TestLibrary.Issue41;

namespace IntegrationTests;

/// <summary>
/// Issue <a href="https://github.com/xxfast/kotlin-native-nuget/issues/41">#41</a>: a
/// root-namespace class referencing a type that lives in a sub-namespace of <c>Interop.cs</c>
/// renders the reference as a bare simple name in property, constructor-parameter,
/// <c>new List&lt;T&gt;(count)</c>, <c>NugetMarshal.FromHandle&lt;T&gt;</c>, <c>new T(handle)</c>
/// and <c>Copy</c> positions, so the generated file fails with CS0246.
/// <para>
/// <see cref="Issue41Bundle"/> lives in the root Kotlin package and so lands in the bare
/// <c>TestLibrary</c> namespace; <see cref="Issue41Thing"/> lives in the <c>issue41</c>
/// sub-package and so lands in <c>TestLibrary.Issue41</c>. Only a <c>global::</c>-qualified (or
/// <c>using</c>-covered) reference resolves across that hop. These tests cannot even compile until
/// it does -- the failure is in the generated code, not here -- which is exactly the red signal.
/// </para>
/// <para>
/// The cats are travelling: Oreo rides in the bundle with a whole list of his things, Mylo has
/// exactly one and refuses to discuss it.
/// </para>
/// </summary>
public class Issue41Tests
{
    [Fact]
    public void Issue41Thing_InItsOwnSubNamespace_ConstructsAndReadsBack()
    {
        // Baseline: the sub-namespace type is fine on its own. Only the cross-namespace
        // reference from the root namespace is broken, so this must stay green either way.
        using var thing = new Issue41Thing("Crinkle ball", 40);

        Assert.Equal("Crinkle ball", thing.Name);
        Assert.Equal(40, thing.Weight);
    }

    [Fact]
    public void Issue41Bundle_Constructor_TakesTheSubNamespaceTypeInBothPositions()
    {
        // Constructor-parameter positions: IReadOnlyList<Issue41Thing> and Issue41Thing.
        using var ball = new Issue41Thing("Crinkle ball", 40);
        using var mouse = new Issue41Thing("Felt mouse", 15);
        using var collar = new Issue41Thing("Bell collar", 22);

        using var bundle = new Issue41Bundle(new List<Issue41Thing> { ball, mouse }, collar);

        Assert.NotNull(bundle);
    }

    [Fact]
    public void Issue41Bundle_Things_ReadsTheListPropertyAndItsElements()
    {
        // Property + `new List<Issue41Thing>(count)` + FromHandle<Issue41Thing> positions.
        using var ball = new Issue41Thing("Crinkle ball", 40);
        using var mouse = new Issue41Thing("Felt mouse", 15);
        using var collar = new Issue41Thing("Bell collar", 22);
        using var bundle = new Issue41Bundle(new List<Issue41Thing> { ball, mouse }, collar);

        IReadOnlyList<Issue41Thing> things = bundle.Things;

        Assert.Equal(2, things.Count);
        using Issue41Thing first = things[0];
        using Issue41Thing second = things[1];
        Assert.Equal("Crinkle ball", first.Name);
        Assert.Equal(40, first.Weight);
        Assert.Equal("Felt mouse", second.Name);
        Assert.Equal(15, second.Weight);
    }

    [Fact]
    public void Issue41Bundle_One_ReadsTheScalarObjectProperty()
    {
        // Property + `new Issue41Thing(handle)` positions, without the collection in the way.
        using var ball = new Issue41Thing("Crinkle ball", 40);
        using var collar = new Issue41Thing("Bell collar", 22);
        using var bundle = new Issue41Bundle(new List<Issue41Thing> { ball }, collar);

        using Issue41Thing one = bundle.One;

        Assert.Equal("Bell collar", one.Name);
        Assert.Equal(22, one.Weight);
    }

    [Fact]
    public void Issue41Bundle_Copy_ReplacingOne_ReturnsANewBundle()
    {
        // Copy parameter + return positions.
        using var ball = new Issue41Thing("Crinkle ball", 40);
        using var mouse = new Issue41Thing("Felt mouse", 15);
        using var collar = new Issue41Thing("Bell collar", 22);
        using var harness = new Issue41Thing("Walking harness", 90);
        using var bundle = new Issue41Bundle(new List<Issue41Thing> { ball, mouse }, collar);

        using Issue41Bundle copied = bundle.Copy(bundle.Things, harness);

        using Issue41Thing copiedOne = copied.One;
        Assert.Equal("Walking harness", copiedOne.Name);
        Assert.Equal(90, copiedOne.Weight);

        // The original is untouched.
        using Issue41Thing originalOne = bundle.One;
        Assert.Equal("Bell collar", originalOne.Name);

        // And the list component survived the round trip through Copy.
        IReadOnlyList<Issue41Thing> copiedThings = copied.Things;
        Assert.Equal(2, copiedThings.Count);
        using Issue41Thing copiedFirst = copiedThings[0];
        Assert.Equal("Crinkle ball", copiedFirst.Name);
    }

    [Fact]
    public void Issue41Bundle_Copy_ReplacingThings_ReturnsANewBundle()
    {
        // Copy's collection parameter position, driven from a freshly built list rather than
        // the bundle's own, so the IReadOnlyList<Issue41Thing> parameter type is exercised
        // against a plain C#-constructed List<T>.
        using var ball = new Issue41Thing("Crinkle ball", 40);
        using var collar = new Issue41Thing("Bell collar", 22);
        using var harness = new Issue41Thing("Walking harness", 90);
        using var bundle = new Issue41Bundle(new List<Issue41Thing> { ball }, collar);

        using Issue41Bundle copied = bundle.Copy(new List<Issue41Thing> { ball, harness }, collar);

        IReadOnlyList<Issue41Thing> copiedThings = copied.Things;
        Assert.Equal(2, copiedThings.Count);
        using Issue41Thing copiedSecond = copiedThings[1];
        Assert.Equal("Walking harness", copiedSecond.Name);

        using Issue41Thing only = Assert.Single(bundle.Things);
        Assert.Equal("Crinkle ball", only.Name);
    }

    [Fact]
    public void Issue41Bundle_ToString_RendersTheKotlinDataClassForm()
    {
        using var ball = new Issue41Thing("Crinkle ball", 40);
        using var collar = new Issue41Thing("Bell collar", 22);
        using var bundle = new Issue41Bundle(new List<Issue41Thing> { ball }, collar);

        Assert.Equal(
            "Issue41Bundle(things=[Issue41Thing(name=Crinkle ball, weight=40)], "
                + "one=Issue41Thing(name=Bell collar, weight=22))",
            bundle.ToString());
    }
}
