using System.Reflection;
using TestLibrary.Issue122;

namespace IntegrationTests;

/// <summary>
/// Issue #122 / ADR-119: a <c>suspend</c> member returning <c>List&lt;T&gt;</c> rendered the type
/// argument away, as bare <c>List</c>, so the generated <c>Interop.cs</c> failed this project's
/// compile with <c>CS0305</c> while <c>packNuget</c> stayed green. The fact that this file compiles
/// is therefore most of the test.
/// <para>
/// The precedent is on the same class: <see cref="Existing.Members"/> (the property route) already
/// spells the same <c>List&lt;Member&gt;</c> as <c>IReadOnlyList&lt;Member&gt;</c> and reads it
/// through the <c>nuget_list_*</c> helpers. <see cref="Existing.FetchAsync"/> now agrees with it,
/// in the signature and at runtime. The two are compared element for element below.
/// </para>
/// <para>
/// Every other generic return on the route (<c>Pair</c>, a nullable collection) is absent and
/// named <c>SKIPPED_UNSUPPORTED_RETURN</c>, asserted by reflection because an absent member is
/// invisible to the compiler in the other direction.
/// </para>
/// <para>
/// Oreo runs the headcount; Mylo is on it, and would like that reflected in the total.
/// </para>
/// </summary>
public class Issue122Tests
{
    private static readonly string[] Names = ["Oreo", "Mylo", "Biscuit"];

    // ---- The issue's shape: both spellings on one sealed arm, for the same element type. ----

    [Fact]
    public async Task FetchAsync_ListReturnOnASealedArm_AgreesWithThePropertyRoute()
    {
        using var oreo = new Member(1, "Oreo");
        using var mylo = new Member(2, "Mylo");
        using var biscuit = new Member(3, "Biscuit");
        using var factory = new AssignmentFactory();
        using Existing existing = factory.Existing([oreo, mylo, biscuit]);

        IReadOnlyList<Member> viaProperty = existing.Members;
        IReadOnlyList<Member> viaSuspend = await existing.FetchAsync(limit: 3, offset: 0);

        Assert.Equal(viaProperty.Select(m => m.Id), viaSuspend.Select(m => m.Id));
        Assert.Equal(viaProperty.Select(m => m.Name), viaSuspend.Select(m => m.Name));
        foreach (Member member in viaSuspend) member.Dispose();
        foreach (Member member in viaProperty) member.Dispose();
    }

    /// <summary>
    /// The parameters still reach the Kotlin body: a slice that the property route can never
    /// produce, so a fix that answered the property instead of the method would fail here.
    /// </summary>
    [Fact]
    public async Task FetchAsync_WithAWindow_ReturnsTheSlice()
    {
        using var oreo = new Member(1, "Oreo");
        using var mylo = new Member(2, "Mylo");
        using var biscuit = new Member(3, "Biscuit");
        using var factory = new AssignmentFactory();
        using Existing existing = factory.Existing([oreo, mylo, biscuit]);

        IReadOnlyList<Member> page = await existing.FetchAsync(limit: 1, offset: 1);

        Assert.Single(page);
        Assert.Equal("Mylo", page[0].Name);
        page[0].Dispose();
    }

    [Fact]
    public void FetchAsync_IsSpelledAsThePropertyRouteSpellsIt()
    {
        MethodInfo fetch = typeof(Existing).GetMethod("FetchAsync")!;
        PropertyInfo members = typeof(Existing).GetProperty("Members")!;

        Assert.Equal(typeof(Task<IReadOnlyList<Member>>), fetch.ReturnType);
        Assert.Equal(typeof(IReadOnlyList<Member>), members.PropertyType);
    }

    // ---- The three kinds on an ordinary class. ----

    [Fact]
    public async Task TagsAsync_ListOfString_RoundTrips()
    {
        using var headcount = new Headcount(Names);

        IReadOnlyList<string> tags = await headcount.TagsAsync();

        Assert.Equal(Names, tags);
    }

    [Fact]
    public async Task IdsAsync_SetOfInt_RoundTrips()
    {
        using var headcount = new Headcount(Names);

        IReadOnlySet<int> ids = await headcount.IdsAsync();

        Assert.Equal(new HashSet<int> { 0, 1, 2 }, ids);
    }

    [Fact]
    public async Task AgesAsync_MapOfStringToInt_RoundTrips()
    {
        using var headcount = new Headcount(Names);

        IReadOnlyDictionary<string, int> ages = await headcount.AgesAsync();

        Assert.Equal(3, ages.Count);
        Assert.Equal(4, ages["Oreo"]);
        Assert.Equal(4, ages["Mylo"]);
        Assert.Equal(7, ages["Biscuit"]);
    }

    /// <summary>
    /// A bare-enum element leaves Kotlin as its int ordinal and is cast back per element (ADR-097),
    /// so this crosses the per-element projection, not only the identity box the cells above use.
    /// </summary>
    [Fact]
    public async Task TempersAsync_ListOfEnum_ProjectsEachElement()
    {
        using var headcount = new Headcount(Names);

        IReadOnlyList<Temper> tempers = await headcount.TempersAsync();

        // Name length modulo three: Oreo (4) -> 1, Mylo (4) -> 1, Biscuit (7) -> 1.
        Assert.Equal([Temper.Hungry, Temper.Hungry, Temper.Hungry], tempers);
    }

    // ---- The top-level route. ----

    [Fact]
    public async Task EveryoneAsync_TopLevelListReturn_RoundTrips()
    {
        IReadOnlyList<string> everyone = await AssignmentSample.EveryoneAsync("Cat");

        Assert.Equal(["Cat Oreo", "Cat Mylo"], everyone);
    }

    // ---- The refusal arm: absent, never present-and-broken. ----

    [Theory]
    [InlineData("PairedAsync")]
    [InlineData("MaybeAsync")]
    public void ARefusedGenericReturn_IsAbsentFromTheClass(string name)
    {
        Assert.Null(typeof(Headcount).GetMethod(name));
    }

    /// <summary>
    /// Requirement 4: the awaited result is materialised through <c>NugetMarshal.ReadList</c>, whose
    /// <c>finally</c> disposes the wire handle, so repeated awaits do not accumulate handles. Not a
    /// leak detector, only a smoke test that the read path survives being hit in a loop.
    /// </summary>
    [Fact]
    public async Task TagsAsync_RepeatedAwaits_StayCorrect()
    {
        using var headcount = new Headcount(Names);

        for (int i = 0; i < 50; i++)
        {
            Assert.Equal(Names, await headcount.TagsAsync());
        }
    }
}
