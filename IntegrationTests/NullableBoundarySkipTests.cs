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
    /// Part A2, per-call callback route: a nullable payload or a nullable lambda return is declined.
    /// Each of the four is its own line of the selector, so each is its own assertion: the value and
    /// handle payloads abort the Kotlin compile of generated code today, the reference payload
    /// compiles and kills the host on a real null, and the nullable lambda RETURN compiles and
    /// NPEs at a generated <c>!!</c>.
    /// </summary>
    [Fact]
    public void NullableLambdaPayload_OnThePerCallRoute_HasNoCsharpMember()
    {
        Assert.Null(typeof(ClawStrip).GetMethod("EachTumble"));
        Assert.Null(typeof(ClawStrip).GetMethod("EachStrip"));
        Assert.Null(typeof(ClawStrip).GetMethod("EachNeighbour"));
        Assert.Null(typeof(ClawStrip).GetMethod("AskWeave"));
    }

    /// <summary>
    /// Part A2, stored callback route: the add/remove PAIR goes together. A skip applied to one half
    /// only would leave a subscription that can never be cancelled, or a cancel for a subscription
    /// that cannot be made, so both names are asserted in one cell.
    /// </summary>
    [Fact]
    public void NullableLambdaPayload_OnTheStoredRoute_DropsBothHalvesOfThePair()
    {
        Assert.Null(typeof(ClawStrip).GetMethod("AddRinger"));
        Assert.Null(typeof(ClawStrip).GetMethod("RemoveRinger"));
    }
}
