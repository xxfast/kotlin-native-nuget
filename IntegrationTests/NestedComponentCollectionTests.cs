using TestLibrary.Clinic;

namespace IntegrationTests;

/// <summary>
/// ADR-099 ("Forward, nested collection components: the inner handle in the component slot, disposed
/// by whoever minted it"). Every member below targets <c>WardBoard</c> in
/// <c>NestedCollectionsSample.kt</c>, one test per mechanism rather than the easiest one.
///
/// The two positions fail differently today, and the return one is the more urgent half:
///
/// <list type="bullet">
/// <item><description><b>Return position</b> (<c>Grid</c>, <c>RunsByPatient</c>) is a shipped
/// bind-then-throw landmine. The result gate is the wider <c>isBridgeableComponent()</c>, which
/// already recurses through <c>Collection</c>, so <c>fun grid(): List&lt;List&lt;String&gt;&gt;</c>
/// binds, compiles, and emits <c>FromHandle&lt;IReadOnlyList&lt;string&gt;&gt;</c> -- which has no
/// branch for that <c>T</c>, falls through to <c>Materialize</c>, finds no <c>Factories</c> entry
/// and throws <c>NotSupportedException</c>. Same shape <c>List&lt;Mood&gt;</c> had before ADR-097,
/// one position over. No fixture declared a nested return before this one, which is why nobody has
/// hit it. These two tests are first in the file because they are the shipped bug.</description>
/// </item>
/// <item><description><b>Input position</b> (everything else) is a clean named
/// <c>SKIPPED_UNSUPPORTED_INPUT</c> since ADR-097 narrowed <c>List</c>'s callable-input gate, so
/// those members do not exist and their tests fail to compile rather than at runtime.</description>
/// </item>
/// </list>
///
/// The last cell is the inverse: <c>LogSparse</c> (<c>List&lt;List&lt;String&gt;?&gt;</c>) must stay
/// absent, because <c>isWrappableComponent()</c>'s <c>Nullable</c> branch would admit it
/// automatically the instant <c>Collection</c> becomes wrappable, and it would bind with a write
/// projection that has no null arm. ADR-099 guards it explicitly; this asserts the guard is there.
///
/// The ward board is Oreo's and Mylo's. Rows are ragged, no row is a prefix of another, and no enum
/// cell uses ordinal 0 alone, so a recursion that flattened, transposed or dropped the innermost
/// level cannot pass any of these by coincidence.
/// </summary>
public class NestedComponentCollectionTests
{
    [Fact]
    public void WardBoard_Grid_NestedListReturn_MaterializesEveryRow()
    {
        using var board = new WardBoard();

        // THE LANDMINE. This member binds and compiles today; FromHandle<IReadOnlyList<string>>
        // has no branch, so it reaches Materialize and throws NotSupportedException. The rows are
        // ragged (2 then 1) so a read that flattened to four elements or padded to a rectangle
        // fails visibly rather than passing on a square grid.
        IReadOnlyList<IReadOnlyList<string>> grid = board.Grid();

        Assert.Equal(2, grid.Count);
        Assert.Equal(new[] { "oreo", "mylo" }, grid[0]);
        Assert.Equal(new[] { "biscuit" }, grid[1]);
    }

    [Fact]
    public void WardBoard_RunsByPatient_NestedMapValueReturn_MaterializesEveryEntry()
    {
        using var board = new WardBoard();

        // The second half of the landmine, and genuinely distinct code from Grid's: this goes
        // through the Map value slot's read projection, over a nested value whose leaf is an
        // ADR-097 enum. Mylo's single run is a different length from Oreo's two, and Playful
        // (ordinal 2) is not the default, so an all-zeros or same-length read cannot pass.
        IReadOnlyDictionary<string, IReadOnlyList<Mood>> runs = board.RunsByPatient();

        Assert.Equal(2, runs.Count);
        Assert.Equal(new[] { Mood.Calm, Mood.Playful }, runs["oreo"]);
        Assert.Equal(new[] { Mood.Anxious }, runs["mylo"]);
    }

    [Fact]
    public void WardBoard_LogGrid_NestedListParameter_RoundTripsEveryRow()
    {
        using var board = new WardBoard();

        // The restatement: List outside, List inside, nothing converting at either level, so the
        // only machinery under test is the recursion. Kotlin joins rows with ';' and elements with
        // ',', so a flattened lowering produces "oreo,mylo,biscuit" and fails.
        string logged = board.LogGrid(new[]
        {
            new[] { "oreo", "mylo" },
            new[] { "biscuit" },
        });

        Assert.Equal("oreo,mylo;biscuit", logged);
    }

    [Fact]
    public void WardBoard_ChartRuns_MapWithNestedEnumValue_RoundTripsEveryEntry()
    {
        using var board = new WardBoard();

        // Outer Map: the KEY slot needs no conversion at the seam, the VALUE slot is a nested
        // collection whose leaf is an enum that does. The only input cell where both kinds of slot
        // sit in one collection. Kotlin sorts by key, so Mylo leads regardless of insertion order,
        // and his single Anxious run is a different length from Oreo's two.
        string chart = board.ChartRuns(new Dictionary<string, IReadOnlyList<Mood>>
        {
            ["oreo"] = new[] { Mood.Calm, Mood.Playful },
            ["mylo"] = new[] { Mood.Anxious },
        });

        Assert.Equal("mylo=ANXIOUS;oreo=CALM,PLAYFUL", chart);
    }

    [Fact]
    public void WardBoard_TallyGroups_SetOfNestedList_RoundTripsEveryGroup()
    {
        using var board = new WardBoard();

        // Cross-kind: outer Set, inner List, so this one member forces NugetSetNative AND
        // NugetListNative into the same generated file. No existing fixture requires both of a
        // single declaration, and that is the helper-gating bug class ROADMAP.md:141 records.
        // Kotlin sorts the joined rows, so "biscuit" precedes "oreo,mylo".
        string tally = board.TallyGroups(new HashSet<IReadOnlyList<string>>
        {
            new[] { "oreo", "mylo" },
            new[] { "biscuit" },
        });

        Assert.Equal("biscuit;oreo,mylo", tally);
    }

    [Fact]
    public void WardBoard_LogCages_ThreeLevelNestedListParameter_RoundTripsEveryLevel()
    {
        using var board = new WardBoard();

        // Depth 3. ADR-099 rejected a one-level cap because the recursion is one `when` arm per
        // function while a cap needs a guard plus a second skip reason in each of four. This is the
        // cell that fails if anyone special-cased depth 1: it is the difference between "arbitrary
        // depth" as a checked claim and as a design aspiration.
        //
        // Three distinct separators ('|', ';', ',') at the three levels, and every level is ragged,
        // so any collapse between levels changes the string.
        string logged = board.LogCages(new[]
        {
            new[]
            {
                new[] { "oreo", "mylo" },
                new[] { "biscuit" },
            },
            new[]
            {
                new[] { "milo" },
            },
        });

        Assert.Equal("oreo,mylo;biscuit|milo", logged);
    }

    [Fact]
    public void WardBoard_TrailGrid_NestedListWithNullableLeaf_RoundTripsNullAndRealElement()
    {
        using var board = new WardBoard();

        // A nullable LEAF under nesting, which ADR-099 admits (unlike LogSparse's nullable
        // collection). It rides ADR-083's component-slot null pointer underneath the recursion, so
        // the two features must compose rather than merely coexist, and it is the cell that forces
        // the inner read lambda to be block-bodied. The null sits at a non-first index in a
        // non-first row, so a collapsed or zero-filled slot cannot pass.
        string trail = board.TrailGrid(new[]
        {
            new string?[] { "oreo" },
            new string?[] { "mylo", null, "my-lo" },
        });

        Assert.Equal("oreo;mylo,null,my-lo", trail);
    }

    [Fact]
    public void WardBoard_WeighLitters_MapWithNestedNarrowPrimitiveLeaf_RoundTripsEveryEntry()
    {
        using var board = new WardBoard();

        // ADR-099 composed with ADR-098: the leaf is a narrow primitive, so nuget_wrap_short has to
        // be gated into the generated file from TWO levels down rather than from a top-level
        // parameter. That is a different question from ChartRuns' enum leaf, which projects to `int`
        // at the call site and needs no new wrap export.
        //
        // short.MaxValue would come back as -1 through a byte-narrowed wire and -40 as 216 through
        // an unsigned one; neither can pass this string. Kotlin sorts by key, so Mylo leads.
        string weighed = board.WeighLitters(new Dictionary<string, IReadOnlyList<short>>
        {
            ["oreo"] = new short[] { 4200, -40 },
            ["mylo"] = new short[] { short.MaxValue },
        });

        Assert.Equal("mylo=32767;oreo=4200,-40", weighed);
    }

    [Fact]
    public void WardBoard_LogSparse_NullableNestedCollectionParameter_IsNotGenerated()
    {
        // The inverse assertion, and the sibling of ADR-097's MoodLedger.logSpans cell.
        //
        // isWrappableComponent()'s Nullable branch delegates to its inner type, so
        // `List<List<String>?>` is admitted AUTOMATICALLY the instant Collection becomes wrappable,
        // whether anyone asked for it or not. Admitted, it binds with a write projection that has no
        // null arm and a read with no zero-handle arm: precisely the trap ADR-097 hit with
        // `List<Mood?>`. ADR-099 adds an explicit `type !is BridgeType.Collection` guard to that
        // branch and keeps the shape a named SKIPPED_UNSUPPORTED_INPUT.
        //
        // Asserted by reflection because there is no callable to invoke. This test passes today for
        // the wrong reason (the whole feature is missing) and must keep passing after the feature
        // lands, which is the only reason it is worth writing.
        Assert.Null(typeof(WardBoard).GetMethod("LogSparse"));

        // ...and the guard must be narrow: the sibling non-nullable shape IS generated.
        Assert.NotNull(typeof(WardBoard).GetMethod("LogGrid"));
    }
}
