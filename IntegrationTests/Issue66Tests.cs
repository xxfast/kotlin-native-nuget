using TestLibrary.Issue66;

namespace IntegrationTests;

/// <summary>
/// Issue <a href="https://github.com/xxfast/kotlin-native-nuget/issues/66">#66</a>: every generated
/// synchronous P/Invoke carries a trailing <c>out IntPtr error</c> exception slot (ADR-024), so a
/// Kotlin parameter literally named <c>error</c> renders
/// <c>Native_Create(..., string? error, out IntPtr error)</c> in the <c>[DllImport]</c> extern
/// (CS0100, duplicate parameter name) and
/// <c>IntPtr handle = Native_Create(error, ..., out IntPtr error);</c> in the wrapper body (CS0136).
/// The red for this issue is therefore "everything red": <c>Interop.cs</c> does not compile, so
/// these tests cannot even be built against it.
/// <para>
/// Expected once fixed: the generator renames the <em>user</em> parameter at C# render time to
/// <c>error_</c> (looping to uniqueness if that is taken) across the public wrapper, the extern,
/// the synthesized <c>Copy</c> and every use site. The <em>property</em> stays <c>Error</c>. These
/// assertions are deliberately <em>name-agnostic</em> — every argument below is positional, never
/// named — because the exact mangled parameter name is pinned by a Kotlin unit test, not here.
/// </para>
/// <para>
/// The cats run the newsdesk: Oreo files the copy, Mylo is the recurring incident.
/// </para>
/// </summary>
public class Issue66Tests
{
    [Fact]
    public void Constructor_ErrorParameter_RoundTripsThroughTheErrorProperty()
    {
        using var state = new Issue66StoryState("Mylo knocked the water bowl over", "Kitchen Flood", 7);

        Assert.Equal("Mylo knocked the water bowl over", state.Error);
        Assert.Equal("Kitchen Flood", state.Title);
        Assert.Equal(7, state.Edition);
    }

    [Fact]
    public void Constructor_NullErrorArgument_LeavesTheErrorPropertyNull()
    {
        using var state = new Issue66StoryState(null, "Nothing To Report", 7);

        Assert.Null(state.Error);
        Assert.Equal("Nothing To Report", state.Title);
    }

    [Fact]
    public void Constructor_OmittingOverload_KeepsErrorAndDefaultsEdition()
    {
        // The synthesized overload drops the trailing defaulted `edition` but still declares the
        // colliding `error` parameter, so it is a render path of its own. Edition defaults to 3,
        // not 0: an overload wired to the wrong Kotlin constructor shows up as a wrong value.
        using var state = new Issue66StoryState("Oreo raided the treat jar", "Biscuit Heist");

        Assert.Equal("Oreo raided the treat jar", state.Error);
        Assert.Equal("Biscuit Heist", state.Title);
        Assert.Equal(3, state.Edition);
    }

    [Fact]
    public void Describe_ScalarErrorParameter_ReachesNative()
    {
        using var state = new Issue66StoryState(null, "Evening Edition", 7);

        Assert.Equal(
            "Evening Edition: Mylo sat on the keyboard",
            state.Describe("Mylo sat on the keyboard"));
    }

    [Fact]
    public void Count_CollectionErrorParameter_MarshalsTheWholeList()
    {
        using var state = new Issue66StoryState(null, "Incident Log", 7);

        IReadOnlyList<string> incidents = new List<string> { "Oreo", "Mylo", "biscuit" };

        // 4 + 4 + 7: an empty or dropped list would come back 0.
        Assert.Equal(15, state.Count(incidents));
    }

    [Fact]
    public void Summarise_TopLevelObjectErrorParameter_ReadsThroughTheHandle()
    {
        using var state = new Issue66StoryState("Mylo again", "Sunbeam Occupied", 9);

        Assert.Equal("Sunbeam Occupied #9 (Mylo again)", Issue66Sample.issue66Summarise(state));
    }

    [Fact]
    public void Summarise_TopLevelObjectErrorParameter_HandlesNullError()
    {
        using var state = new Issue66StoryState(null, "All Quiet", 9);

        Assert.Equal("All Quiet #9 (no error)", Issue66Sample.issue66Summarise(state));
    }

    [Fact]
    public void Copy_ErrorParameter_ChangesErrorAndKeepsTitle()
    {
        using var original = new Issue66StoryState("Oreo, mid-biscuit", "Roll Call", 4);
        using var amended = original.Copy("Mylo, mid-nap", "Roll Call", 4);

        Assert.Equal("Mylo, mid-nap", amended.Error);
        Assert.Equal("Roll Call", amended.Title);
        Assert.Equal(4, amended.Edition);
        Assert.Equal("Oreo, mid-biscuit", original.Error);
    }
}
