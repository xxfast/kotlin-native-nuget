using System.Reflection;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// Issue #109 / ADR-114. A collection-typed parameter on a <c>Flow</c>/<c>StateFlow</c>-returning
/// or <c>suspend</c> member was spelled by pasting the declaration's own Kotlin type name through
/// <c>ClassName.bestGuess</c>, which drops the type arguments:
/// <code>
/// e: CNameExports.kt:97:10 One type argument expected for 'interface List&lt;out E&gt; : Collection&lt;E&gt;'.
/// e: CNameExports.kt:342:8 One type argument expected for 'interface Set&lt;out E&gt; : Collection&lt;E&gt;'.
/// </code>
/// <c>packNuget</c> died at that compile, so the whole package failed to build, not just the
/// member. The fact that this file compiles at all is therefore most of the test: pre-fix there
/// is no package to reference.
///
/// The collection crosses as one opaque handle to the same boxed wire container the synchronous
/// route uses, with Kotlin copying out of it eagerly before <c>launch</c> and C# disposing it as
/// soon as the native call returns (ADR-114 alternative 1). The C# consequence asserted here is
/// that the public parameter is the collection, never <c>IntPtr</c>.
///
/// Three routes, asserted separately, because they are three hand-written copies of the same
/// mistake and they have drifted before:
/// <list type="bullet">
/// <item><c>_collect</c>: <see cref="TreatBoard.Servings"/>, <see cref="TreatBoard.Feeding"/></item>
/// <item><c>_value</c>: <see cref="TreatBoard.Served"/>, <see cref="TreatBoard.Rations"/></item>
/// <item><c>_async</c>: <see cref="TreatBoard.ForgetAsync"/>, <see cref="TreatBoard.TallyAsync"/>,
/// and the top-level <see cref="TreatRoutes.ForgetAllTreatsAsync"/></item>
/// </list>
///
/// The control (a collection parameter on the ordinary synchronous route) is already covered by
/// <see cref="CollectionParameterCleanupTests"/> against <c>Auditor</c>/<c>Ledger</c>, so it is
/// not duplicated here.
///
/// Oreo (black with the white bib) and Mylo (brown and creamy) audit the treat board nightly.
/// It has never once balanced.
/// </summary>
public class LegacyRouteCollectionParameterTests
{
    private static readonly string[] Kinds = ["biscuit", "milo"];
    private static readonly int[] Portions = [3, 5];

    // --- _value: the StateFlow route's synchronous read ---

    [Fact]
    public void Served_StateFlowWithAListParameter_ReadsThroughTheCollection()
    {
        // The reported shape: `fun served(kinds: List<String>): StateFlow<String>`. Pre-fix the
        // package does not build; the fix before this one made it `Served(IntPtr kinds)`.
        using var board = new TreatBoard();

        Assert.Equal("biscuit x2, milo x2", board.Served(Kinds).Value);
    }

    [Fact]
    public void Served_RepeatedValueReads_StayCorrect()
    {
        // `.Value` re-invokes the member through the captured parameters on every read
        // (CirFlowRenderer.kt:293-296), so each read re-marshals the whole collection. That is
        // the path a leak or a use-after-free would show up on.
        using var board = new TreatBoard();
        var served = board.Served(Kinds);

        for (int i = 0; i < 50; i++)
        {
            Assert.Equal("biscuit x2, milo x2", served.Value);
        }
    }

    [Fact]
    public void Rations_IntElement_NeedsNoConversionAtTheSeam()
    {
        // `List<Int>`: a component that needs no conversion, next to `Served`'s `List<String>`
        // that does. One of the two alone would prove nothing about the other.
        using var board = new TreatBoard();

        Assert.Equal(8, board.Rations(Portions).Value);
    }

    [Fact]
    public void Served_EmptyCollection_IsNotASpecialCase()
    {
        using var board = new TreatBoard();

        Assert.Equal("", board.Served([]).Value);
        Assert.Equal(0, board.Rations([]).Value);
    }

    [Fact]
    public void Served_ValueLambdaCapturesTheCallersList_SoLaterReadsSeeMutations()
    {
        // ADR-114 answer 3: the `() => Native_ServedValue(...)` lambda captures the caller's
        // IReadOnlyList by reference, so a post-call mutation is visible to later `.Value` reads.
        // Documented behaviour, not an accident, and it is the observable difference from
        // `_collect`, which snapshots at subscription.
        using var board = new TreatBoard();
        var kinds = new List<string> { "biscuit" };
        var served = board.Served(kinds);

        Assert.Equal("biscuit x2", served.Value);

        kinds.Add("milo");

        Assert.Equal("biscuit x2, milo x2", served.Value);
    }

    // --- _collect: the Flow route ---

    [Fact]
    public async Task Servings_FlowWithAListParameter_CollectsEveryElement()
    {
        using var board = new TreatBoard();
        var seen = new List<string>();

        await foreach (var s in board.Servings(Kinds))
        {
            seen.Add(s);
        }

        Assert.Equal(Kinds, seen);
    }

    [Fact]
    public async Task Feeding_CollectionOfAnExportedClass_CrossesEachElementAsAHandle()
    {
        // An object element is a different projection from a boxed `string`/`int`, so a fix that
        // only handled boxed primitives would leave this one broken.
        using var board = new TreatBoard();
        using var mouse = new Toy("mouse", "grey");
        using var ball = new Toy("ball", "red");
        var seen = new List<string>();

        await foreach (var s in board.Feeding([mouse, ball]))
        {
            seen.Add(s);
        }

        Assert.Equal(new[] { "mouse (grey)", "ball (red)" }, seen);
    }

    [Fact]
    public async Task Servings_EmptyCollection_EmitsNothing()
    {
        using var board = new TreatBoard();
        var seen = new List<string>();

        await foreach (var s in board.Servings([]))
        {
            seen.Add(s);
        }

        Assert.Empty(seen);
    }

    [Fact]
    public async Task Servings_ResubscribingRemarshalsTheCollection()
    {
        // The collect delegate runs once per subscription, so the handle is created and disposed
        // per subscription too. Two passes over one KotlinFlow is the cheapest way to catch a
        // handle that was disposed once and reused.
        using var board = new TreatBoard();
        var flow = board.Servings(Kinds);

        var first = new List<string>();
        await foreach (var s in flow) first.Add(s);

        var second = new List<string>();
        await foreach (var s in flow) second.Add(s);

        Assert.Equal(Kinds, first);
        Assert.Equal(Kinds, second);
    }

    // --- _async: the suspend route ---

    [Fact]
    public async Task ForgetAsync_SuspendWithASetParameter_RoundTrips()
    {
        // Issue #109's second shape verbatim: `suspend fun forget(ids: Set<String>)`.
        using var board = new TreatBoard();

        Assert.Equal(2, await board.ForgetAsync(new HashSet<string> { "oreo", "mylo" }));
    }

    [Fact]
    public async Task TallyAsync_SuspendWithAListParameter_RoundTrips()
    {
        using var board = new TreatBoard();

        Assert.Equal(8, await board.TallyAsync(Portions));
    }

    [Fact]
    public async Task ForgetAsync_EmptySet_IsNotASpecialCase()
    {
        using var board = new TreatBoard();

        Assert.Equal(0, await board.ForgetAsync(new HashSet<string>()));
    }

    [Fact]
    public async Task ForgetAllTreatsAsync_TopLevelSuspendWithASetParameter_RoundTrips()
    {
        // The top-level suspend route is broken *differently*: `toBridgeTypeName` preserves the
        // type argument so the Kotlin compiles, but C# still gets `ForgetAllTreatsAsync(IntPtr)`.
        Assert.Equal(3, await TreatRoutes.ForgetAllTreatsAsync(
            new HashSet<string> { "oreo", "mylo", "biscuit" }));
    }

    // --- the ownership window on the throwing path ---

    [Fact]
    public async Task AuditAsync_Throws_AndTheHandleWindowStillCloses()
    {
        // The eager-copy model's whole point is that no callback owns the handle. C# cannot see a
        // leak directly (there is no live-handle export), so hammering the throwing path is the
        // behavioural net, exactly as CollectionParameterCleanupTests is for the sync route.
        using var board = new TreatBoard();

        for (int i = 0; i < 50; i++)
        {
            var ex = await Assert.ThrowsAnyAsync<InvalidOperationException>(
                () => board.AuditAsync(Kinds));
            Assert.Equal("audit failed: 2 entries do not balance", ex.Message);
        }

        // Still healthy afterwards, so the failures did not corrupt anything shared.
        Assert.Equal(8, await board.TallyAsync(Portions));
    }

    [Fact]
    public async Task Review_FlowThrows_AndTheHandleWindowStillCloses()
    {
        using var board = new TreatBoard();

        for (int i = 0; i < 50; i++)
        {
            await Assert.ThrowsAnyAsync<InvalidOperationException>(async () =>
            {
                await foreach (var _ in board.Review(Kinds)) { }
            });
        }

        Assert.Equal("biscuit x2, milo x2", board.Served(Kinds).Value);
    }

    // --- the refusal arm, and the control ---

    [Fact]
    public void Paired_NonCollectionGenericParameter_IsAbsent()
    {
        // ADR-114 keeps the refusal fallback for a generic parameter that is not a supported
        // collection. `Pair<String, Int>` emits `entry: Pair` today, which is the same build
        // break as issue #109, so it must be absent rather than present-but-broken. The
        // SKIPPED_UNSUPPORTED_INPUT behind it is asserted at Tier 1, where the KSP log is
        // readable (Tier1LegacyRouteCollectionParameterTest), matching the LambdaTypeArgument
        // precedent.
        Assert.Null(typeof(TreatBoard).GetMethod("Paired"));
        Assert.Empty(typeof(TreatBoard).GetMember("Paired"));
    }

    [Fact]
    public void ServedAll_NoCollectionParameter_StillBinds()
    {
        // Control for the class: the refusal above must not take the rest of it down.
        using var board = new TreatBoard();

        Assert.Equal("biscuit", board.ServedAll().Value);
    }

    [Fact]
    public void CollectionParameters_AreSpelledAsCollections_NotIntPtr()
    {
        // The signature assertion matters as much as the behaviour one. `Served(IntPtr kinds)`
        // compiles fine on the C# side; it is just uncallable. Reflection is what makes
        // "the parameter is the collection" a pinned fact rather than an inference from the
        // calls above happening to compile.
        AssertParameterType(typeof(TreatBoard), "Served", 0, typeof(IReadOnlyList<string>));
        AssertParameterType(typeof(TreatBoard), "Rations", 0, typeof(IReadOnlyList<int>));
        AssertParameterType(typeof(TreatBoard), "Servings", 0, typeof(IReadOnlyList<string>));
        AssertParameterType(typeof(TreatBoard), "Feeding", 0, typeof(IReadOnlyList<Toy>));
        AssertParameterType(typeof(TreatBoard), "ForgetAsync", 0, typeof(IReadOnlySet<string>));
        AssertParameterType(typeof(TreatBoard), "TallyAsync", 0, typeof(IReadOnlyList<int>));
        AssertParameterType(typeof(TreatRoutes), "ForgetAllTreatsAsync", 0, typeof(IReadOnlySet<string>));
    }

    private static void AssertParameterType(Type owner, string method, int index, Type expected)
    {
        MethodInfo info = owner.GetMethod(method)
            ?? throw new InvalidOperationException($"{owner.Name}.{method} was not generated");
        ParameterInfo parameter = info.GetParameters()[index];

        Assert.Equal(expected, parameter.ParameterType);
    }
}
