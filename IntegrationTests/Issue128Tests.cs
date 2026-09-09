using System.Reflection;
using TestLibrary.Issue128;

namespace IntegrationTests;

/// <summary>
/// Issue <a href="https://github.com/xxfast/kotlin-native-nuget/issues/128">#128</a>: a parameter
/// whose <em>type</em> is opt-in-marked makes every arity of the callable illegal, so ADR-096's
/// trailing-omitting overload cannot repair the constructor ADR-115 dropped. Every arity is skipped
/// and the type becomes factory-only in C#.
///
/// <para>
/// The Kotlin fixture is <c>test-library/.../issue128/Issue128Sample.kt</c>, with the marked
/// <c>Grooming</c> enum one Gradle module away in <c>:test-models</c>.
/// </para>
///
/// <para>
/// <b>The real test is structural, and it already ran.</b> Before the fix, <c>nugetGen</c> emitted
/// <c>GroomingPlan(name)</c> and <c>GroomingPlan()</c>, <c>compileKotlin</c> rejected both
/// (<c>CNameExports.kt:11437:74 Cattery bookkeeping, not a public API</c>), <c>packNuget</c> failed
/// and this assembly could not build. It builds with no <c>optIn</c> / <c>-opt-in=</c> anywhere in
/// any build script, which is issue #128 requirement 3. What is left for xunit is the shape of the
/// surface that fix produces.
/// </para>
///
/// <para>
/// <b>Not observable from here:</b> the <c>SKIPPED_OPT_IN_MARKER</c> line per arity and the
/// <c>WARNING_NO_PUBLIC_CONSTRUCTOR</c> that follows. Those live in
/// <c>Tier1OptInMarkedParameterArityTest</c>.
/// </para>
/// </summary>
public class Issue128Tests
{
    private const BindingFlags PublicInstance = BindingFlags.Public | BindingFlags.Instance;

    [Fact]
    public void GroomingPlan_HasNoPublicConstructor()
    {
        // Not "the declared arity is gone": *no* arity is. Before the fix there were two, both
        // uncallable from Kotlin.
        Assert.Empty(typeof(GroomingPlan).GetConstructors(PublicInstance));
    }

    [Fact]
    public void GroomingPlan_IsStillReachableThroughItsFactory()
    {
        // The control for an over-wide skip: the type is kept on purpose, and the Kotlin factory
        // is the reason keeping it is not pointless.
        using GroomingPlan plan = Issue128Sample.Plan();

        Assert.Equal("Oreo", plan.Name);
    }

    [Fact]
    public void GroomingPlan_HasNoCopy()
    {
        // `copy` is built at full arity, so it always saw the marked parameter. Pinned because the
        // arities around it moved.
        Assert.Null(typeof(GroomingPlan).GetMethod("Copy", PublicInstance));
    }

    [Fact]
    public void GroomingPlan_HasNoGroomingMember()
    {
        // The marked type has no C# projection, so nothing may reference it. A `Grooming` property
        // would be a dangling reference, not a leak of a value.
        Assert.Null(typeof(GroomingPlan).GetProperty("Grooming", PublicInstance));
        Assert.Empty(
            typeof(GroomingPlan).Assembly
                .GetTypes()
                .Where(type => type.Name == "Grooming")
                .Select(type => type.FullName)
                .ToArray());
    }

    [Fact]
    public void Schedule_TheFunctionHalf_IsNotExportedAtAnyArity()
    {
        // Requirement 4: the same rule on a function, not just a constructor.
        Assert.Empty(
            typeof(Issue128Sample)
                .GetMethods(BindingFlags.Public | BindingFlags.Static)
                .Where(method => method.Name == "Schedule")
                .ToArray());
    }

    [Fact]
    public void GroomingLog_KeepsItsOmittingOverloads()
    {
        // The over-skip guard. `ledger`'s marker sits on the PROPERTY and its type is a plain
        // string, so omitting it is legal Kotlin and ADR-115 gate (b) still repairs the
        // constructor. A fix keyed on the marker rather than on the parameter's type deletes both
        // of these, and no absence assertion elsewhere would notice.
        Assert.Equal(2, typeof(GroomingLog).GetConstructors(PublicInstance).Length);

        using var log = new GroomingLog("tidy");
        using var fallback = new GroomingLog();

        Assert.Equal("tidy", log.Note);
        Assert.Equal("clean", fallback.Note);
        Assert.Null(typeof(GroomingLog).GetProperty("Ledger", PublicInstance));
    }
}
