using TestLibrary.Skipremarks;

namespace IntegrationTests;

/// <summary>
/// Boundary nullability, the DECLINED half: parts B (nullable map keys) and A2 (nullable payloads on
/// the per-call and stored callback routes). Both become named skips, so the only thing a C#
/// consumer can observe is absence, and absence is what this file asserts, with the reflection
/// pattern <c>XmlDocTests</c> already uses for the shipped <c>ClawStrip</c> drops. The diagnostic
/// text and the owner remark are pinned Kotlin-side in Tier 1, not here.
///
/// <para>Why absence is worth a test rather than nothing: today every one of these members is either
/// GENERATED (the map-key cells render <c>IReadOnlyDictionary&lt;string?, int&gt;</c> over
/// <c>NugetMarshal.ReadMap&lt;string?, int&gt;</c>, which is CS8714 against that helper's
/// <c>where TKey : notnull</c> and so aborts the generated-bindings build) or generates Kotlin that
/// does not compile (the nullable lambda payloads). A skip that only silenced the warning while
/// still rendering a member would leave this assembly uncompilable, and absence is exactly what
/// tells the two apart.</para>
///
/// <para>Mylo's perch scores are unrankable because half the perches have no name.</para>
/// </summary>
public class NullableBoundarySkipTests
{
    /// <summary>
    /// Part B: nullable map keys are declined at a RETURN, a PROPERTY and a NESTED component
    /// position, matching the input position ADR-083 already declined. Three positions, three
    /// planner sites; a fix that patched only the return gate leaves two of these present.
    /// </summary>
    [Fact]
    public void NullableMapKey_AtEveryReadPosition_HasNoCsharpMember()
    {
        Assert.Null(typeof(ClawStrip).GetMethod("PerchScores"));
        Assert.Null(typeof(ClawStrip).GetProperty("Tallies"));
        Assert.Null(typeof(ClawStrip).GetMethod("NestedScores"));

        // The input-position twin, shipped since ADR-083: the new skips must join it rather than
        // replace it, and the survivors around them must stay.
        Assert.Null(typeof(ClawStrip).GetMethod("RankPerches"));
        Assert.NotNull(typeof(ClawStrip).GetMethod("Shred"));
        Assert.NotNull(typeof(ClawStrip).GetProperty("Height"));
    }

    /// <summary>
    /// Per-call callback route: a nullable VALUE, REFERENCE and HANDLE payload. Each once
    /// aborted the generated Kotlin or killed the host on a real null; ADR-239 binds each as a box
    /// Kotlin retains (none for null) and C# reads with <c>NugetMarshal.FromHandle&lt;T&gt;</c>, so a
    /// null arrives as null.
    /// </summary>
    [Fact]
    public void NullableLambdaPayload_OnThePerCallRoute_CrossesNull()
    {
        using var strip = new ClawStrip("Oreo");
        var seen = new List<string>();

        strip.EachTumble(n => seen.Add(n?.ToString() ?? "no tumble"));
        strip.EachStrip(s => seen.Add(s ?? "no strip"));
        strip.EachNeighbour(c => seen.Add(c?.ToString() ?? "no neighbour"));

        Assert.Equal(new[] { "no tumble", "no strip", "no neighbour" }, seen);
    }

    /// <summary>
    /// ADR-239: a nullable lambda RETURN binds on the per-call route, and a C# callback's null
    /// reaches Kotlin as null rather than tripping a <c>!!</c>.
    /// </summary>
    [Fact]
    public void NullableLambdaResult_OnThePerCallRoute_CrossesNull()
    {
        using var strip = new ClawStrip("Oreo");

        Assert.Null(strip.AskWeave(_ => null));
        Assert.Equal("weave 1", strip.AskWeave(n => $"weave {n}"));
    }

    /// <summary>
    /// Stored callback route: a nullable payload once failed both halves of the pair in
    /// generated Kotlin. ADR-239 binds the pair on the managed-listener route: subscribing and
    /// disposing works, and the remove half is internal to the subscription.
    /// </summary>
    [Fact]
    public void NullableLambdaPayload_OnTheStoredRoute_BindsThePair()
    {
        using var strip = new ClawStrip("Oreo");

        using (IDisposable ringer = strip.AddRinger(_ => { }))
        {
            Assert.NotNull(ringer);
        }
        Assert.Null(typeof(ClawStrip).GetMethod("RemoveRinger"));
    }
}
