using System.Reflection;
using TestLibrary.Issue121;

namespace IntegrationTests;

/// <summary>
/// Issue <a href="https://github.com/xxfast/kotlin-native-nuget/issues/121">#121</a>: ADR-115's
/// opt-in gate lives in the planners, and the lambda and Flow property arms do not go through them.
/// A marked property was reported <c>SKIPPED_OPT_IN_MARKER</c> and exported anyway, on both sides.
///
/// <para>
/// The Kotlin fixture is <c>test-library/.../issue121/Issue121Sample.kt</c>. Read its KDoc for the
/// cell table.
/// </para>
///
/// <para>
/// <b>The criterion that matters is not here.</b> Issue #121's criterion 3 is that the consuming
/// module compiles with no <c>optIn</c> / <c>-opt-in=</c> flag in any build script. That is proven
/// by <c>packNuget</c> compiling the fixture at all, not by anything xunit can assert: before the
/// fix the generated <c>CNameExports.kt</c> read the marked properties without opting in and the
/// module did not build. What is left for here is the C# half, criterion 2 and criterion 4.
/// </para>
///
/// <para>
/// <b>The absence trap.</b> Absence assertions cannot catch a gate that fires too wide, and a
/// lambda property is easy to drop for a dozen unrelated reasons. So every marked property in the
/// fixture has an unmarked sibling of exactly the same type in the same class, and those controls
/// run first.
/// </para>
///
/// <para>
/// Oreo naps through the refresh. Mylo retries anyway.
/// </para>
/// </summary>
public class Issue121Tests
{
    private const BindingFlags PublicInstance = BindingFlags.Public | BindingFlags.Instance;

    private static bool HasMember(Type type, string name) =>
        type.GetProperty(name, PublicInstance) is not null ||
        type.GetMethod(name, PublicInstance) is not null;

    // -----------------------------------------------------------------------------------------
    // Controls first. A gate that swept up every lambda property would pass every absence test
    // below, so these have to fail loudly before any of them run.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void C1_UnmarkedSiblings_OfTheMarkedProperties_AreStillExported()
    {
        // Same class, same three types (plain lambda, suspend lambda, Flow) as the marked trio.
        Assert.True(HasMember(typeof(Feed), "OnVisible"), "unmarked `() -> Unit` must survive");
        Assert.True(HasMember(typeof(Feed), "OnSettle"), "unmarked `suspend () -> Unit` must survive");
        Assert.True(HasMember(typeof(Feed), "OnPulse"), "unmarked `Flow<Int>` must survive");
        Assert.True(HasMember(typeof(Panel.Live), "OnOpen"), "unmarked sealed-arm lambda must survive");
    }

    [Fact]
    public void C2_TheUnmarkedDelegates_AreExportedAndWork()
    {
        // Criterion 4: these are the intended surface, and they are what makes gating the marked
        // properties cost the consumer nothing.
        using Feed feed = new Feed(3);

        Assert.Equal(0, feed.Retries);
        feed.Retry();
        Assert.Equal(1, feed.Retries);
    }

    [Fact]
    public async Task C3_TheUnmarkedSuspendDelegate_IsExportedAndWorks()
    {
        using Feed feed = new Feed(3);

        Assert.Equal(0, feed.Refreshes);
        await feed.RefreshAsync();
        Assert.Equal(1, feed.Refreshes);
    }

    // -----------------------------------------------------------------------------------------
    // Criterion 2: no C# member for a marked property, on either route.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void Cell1_MarkedPlainLambdaProperty_IsNotExported()
    {
        Assert.False(HasMember(typeof(Feed), "OnRetry"), "the issue's literal reported shape");
    }

    [Fact]
    public void Cell2_MarkedSuspendLambdaProperty_IsNotExported()
    {
        Assert.False(HasMember(typeof(Feed), "OnRefresh"), "the suspend copy of the same arm");
    }

    [Fact]
    public void Cell3_MarkedFlowProperty_IsNotExported()
    {
        // Not named in the issue. The Flow arm shares the fall-through, so it leaked too.
        Assert.False(HasMember(typeof(Feed), "OnTicks"), "the Flow arm of the same fall-through");
    }

    [Fact]
    public void Cell4_MarkedLambdaProperty_OnASealedArm_IsNotExported()
    {
        Assert.False(HasMember(typeof(Panel.Live), "OnClose"), "the SealedClassExports copy");
    }
}
