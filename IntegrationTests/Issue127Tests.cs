using System.Reflection;
using TestLibrary;
using TestLibrary.Issue127;

namespace IntegrationTests;

/// <summary>
/// Issue #127 / ADR-123: a <c>Flow</c> or <c>StateFlow</c> whose element is a Kotlin collection
/// spelled its element as <c>global::TestLibrary.Kotlin.Collections.Set</c>, a namespace that does
/// not exist, with the type argument dropped, so the generated <c>Interop.cs</c> failed this
/// project's compile with <c>CS0234</c> while <c>packNuget</c> stayed green. The fact that this
/// file compiles at all is therefore most of the test.
/// <para>
/// The gap is visible on one declaration: in <see cref="NodeHub.Visible"/> the <c>List&lt;Kind&gt;</c>
/// parameter is already spelled <c>IReadOnlyList&lt;Kind&gt;</c> (ADR-114) while the flow element
/// on the same signature was not. The property route and the ADR-119 suspend route settled on
/// <c>IReadOnlyList&lt;T&gt;</c> / <c>IReadOnlySet&lt;T&gt;</c> /
/// <c>IReadOnlyDictionary&lt;K, V&gt;</c> for the same Kotlin types, so this route agrees with
/// them in the signature and, through the <c>nuget_list_*</c> helpers, at runtime.
/// </para>
/// <para>
/// The refused elements (<c>Pair</c>, a nullable collection) are absent and named
/// <c>SKIPPED_UNSUPPORTED_PROPERTY</c>, asserted by reflection because an absent member is
/// invisible to the compiler in the other direction.
/// </para>
/// <para>
/// The hub watches two nodes: 1 is Oreo, 2 is Mylo, 3 is the empty windowsill spot.
/// </para>
/// </summary>
public class Issue127Tests
{
    // ---- Cell 1: the issue's shape. StateFlow<Set<NodeId>>, a projecting component. ----

    [Fact]
    public void Items_StateFlowOfSet_ValueExposesEveryElement()
    {
        using var hub = new NodeHub();

        IReadOnlySet<NodeId> items = hub.Items.Value;

        Assert.Equal(3, items.Count);
        Assert.Equal([1, 2, 3], items.Select(node => node.Value).Order());
    }

    [Fact]
    public async Task Items_StateFlowOfSet_ReplaysTheCurrentValueToACollector()
    {
        using var hub = new NodeHub();
        var cts = new CancellationTokenSource();
        var seen = new List<IReadOnlySet<NodeId>>();

        await foreach (IReadOnlySet<NodeId> snapshot in hub.Items.WithCancellation(cts.Token))
        {
            seen.Add(snapshot);
            cts.Cancel(); // a StateFlow never completes on its own
        }

        Assert.Equal([1, 2, 3], seen[0].Select(node => node.Value).Order());
    }

    // ---- Cell 2: the same route, scalar component, so cell 1 cannot pass by treating all alike. ----

    [Fact]
    public void Plain_StateFlowOfListOfString_ValueExposesEveryElement()
    {
        using var hub = new NodeHub();

        IReadOnlyList<string> plain = hub.Plain.Value;

        Assert.Equal(["Oreo", "Mylo"], plain);
    }

    // ---- Cell 3: the map kind, two type arguments on one element. ----

    [Fact]
    public void Counts_StateFlowOfMap_ValueExposesEveryEntry()
    {
        using var hub = new NodeHub();

        IReadOnlyDictionary<string, int> counts = hub.Counts.Value;

        Assert.Equal(2, counts.Count);
        Assert.Equal(4, counts["Oreo"]);
        Assert.Equal(4, counts["Mylo"]);
    }

    // ---- Cell 4: a plain Flow, handle components, several emissions. ----

    /// <summary>
    /// Every emission is materialised on its own, so a fix that reads the first list and reuses it
    /// (or that reads only the replayed <c>Value</c>) fails here. The elements are handles, so the
    /// test owns them and disposes each one.
    /// </summary>
    [Fact]
    public async Task Ticks_FlowOfList_MaterialisesEachEmissionsElements()
    {
        using var hub = new NodeHub();
        var seen = new List<string[]>();

        await foreach (IReadOnlyList<Kind> page in hub.Ticks)
        {
            seen.Add(page.Select(kind => kind.Name).ToArray());
            foreach (Kind kind in page) kind.Dispose();
        }

        Assert.Equal(3, seen.Count);
        Assert.Equal(["nap"], seen[0]);
        Assert.Equal(["zoomies", "snack"], seen[1]);
        Assert.Equal(["nap", "loaf", "window"], seen[2]);
    }

    // ---- Cell 5: parameter and element on one call. ----

    [Fact]
    public void Visible_TakesACollectionParameterAndReturnsCollectionElements()
    {
        using var hub = new NodeHub();
        using var nap = new Kind("nap");
        using var zoomies = new Kind("zoomies");

        IReadOnlyList<NodeId> visible = hub.Visible([nap, zoomies]).Value;

        // One NodeId per kind, valued by the kind's name length: 3 and 7, never an echo.
        Assert.Equal([3, 7], visible.Select(node => node.Value));
    }

    // ---- Requirements 1 and 4: the spelling itself. ----

    [Theory]
    [InlineData(nameof(NodeHub.Items), typeof(KotlinStateFlow<IReadOnlySet<NodeId>>))]
    [InlineData(nameof(NodeHub.Plain), typeof(KotlinStateFlow<IReadOnlyList<string>>))]
    [InlineData(nameof(NodeHub.Counts), typeof(KotlinStateFlow<IReadOnlyDictionary<string, int>>))]
    [InlineData(nameof(NodeHub.Ticks), typeof(KotlinFlow<IReadOnlyList<Kind>>))]
    public void AFlowElement_IsSpelledAsTheOtherRoutesSpellIt(string name, Type expected)
    {
        PropertyInfo property = typeof(NodeHub).GetProperty(name)!;

        Assert.Equal(expected, property.PropertyType);
    }

    [Fact]
    public void Visible_ElementAndParameter_AgreeOnOneSignature()
    {
        MethodInfo visible = typeof(NodeHub).GetMethod(nameof(NodeHub.Visible))!;

        Assert.Equal(typeof(KotlinStateFlow<IReadOnlyList<NodeId>>), visible.ReturnType);
        Assert.Equal(typeof(IReadOnlyList<Kind>), visible.GetParameters().Single().ParameterType);
    }

    /// <summary>
    /// Requirement 4: a Kotlin builtin must never be mapped as a user type, so no member of this
    /// class may name a type under the root namespace followed by a <c>Kotlin</c> package segment.
    /// The Tier 1 suite asserts this over the whole generated file; this is the consumer-side
    /// echo of it, on the members the issue reported.
    /// <para>
    /// The trailing dot is load-bearing: <c>TestLibrary.KotlinStateFlow</c> is a real emitted
    /// type and contains <c>TestLibrary.Kotlin</c> as a substring. What may not appear is a
    /// <c>Kotlin</c> package <em>segment</em>, i.e. <c>TestLibrary.Kotlin.Collections.Set</c>.
    /// </para>
    /// </summary>
    [Fact]
    public void NoMemberNamesTheRootNamespaceFollowedByAKotlinPackage()
    {
        IEnumerable<string> names = typeof(NodeHub).GetProperties().Select(p => p.PropertyType.ToString())
            .Concat(typeof(NodeHub).GetMethods().Select(m => m.ReturnType.ToString()));

        foreach (string name in names)
        {
            Assert.DoesNotContain("TestLibrary.Kotlin.", name, StringComparison.Ordinal);
            Assert.DoesNotContain("TestLibrary.Kotlinx.", name, StringComparison.Ordinal);
        }
    }

    // ---- Cells 6 and 7: refused, absent, never present-and-broken. ----

    [Theory]
    [InlineData("Paired")]
    [InlineData("Maybe")]
    public void ARefusedFlowElement_IsAbsentFromTheClass(string name)
    {
        Assert.Null(typeof(NodeHub).GetProperty(name));
    }
}
