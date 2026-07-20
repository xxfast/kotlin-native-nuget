using TestLibrary;
using TestLibrary.Models;

namespace IntegrationTests;

/// <summary>
/// ADR-066: the forward export set as a reachability closure from module roots. Every type here
/// except <see cref="Newsroom"/> itself lives one Gradle module away, in <c>:test-models</c>,
/// where the nuget plugin is not applied. Today's <c>getAllFiles()</c> choke point never sees
/// them; these tests define the C# API that must exist once the export set walks the reference
/// graph from <see cref="Newsroom"/> instead of stopping at the module boundary. Oreo and Mylo,
/// naturally, are the top story.
/// </summary>
public class NewsroomReachabilityTests
{
    [Fact]
    public void Latest_ReachableViaPlainReturnType_ReturnsQualifiedTopStory()
    {
        using var newsroom = new Newsroom();
        using TopStory story = newsroom.Latest();

        Assert.Equal("Oreo escapes the cardboard box (again)", story.Title);
        Assert.Equal(1, story.Rank);
    }

    [Fact]
    public void Latest_Byline_ReachableOnlyTransitively_ThroughAnAlreadyAdmittedType()
    {
        // Nothing in Newsroom returns a Byline directly -- it only enters the closure as a
        // member of the already-admitted TopStory. Proves the walk iterates, not just one hop.
        using var newsroom = new Newsroom();
        using TopStory story = newsroom.Latest();
        using Byline? byline = story.Byline;

        Assert.NotNull(byline);
        Assert.Equal("Mylo", byline!.Name);
    }

    [Fact]
    public async Task Stream_ReachableOnlyViaATypeArgument_EmitsQualifiedTopStory()
    {
        // The Flow<T> element-type route maps its element by simple name at five call sites
        // (ADR-066 verification), so an unqualified `TopStory` would only compile by accident
        // when namespaces happen to coincide. `TestLibrary.Models.TopStory` and `TestLibrary`
        // (Newsroom's own namespace) are different namespaces on purpose, so this fails loudly
        // (CS0246 inside the generated Interop.cs) rather than silently.
        using var newsroom = new Newsroom();
        var stories = new List<TopStory>();

        await foreach (TopStory story in newsroom.Stream())
        {
            stories.Add(story);
        }

        Assert.Equal(2, stories.Count);
        Assert.Equal("Oreo escapes the cardboard box (again)", stories[0].Title);
        using (Byline? firstByline = stories[0].Byline)
        {
            Assert.Equal("Mylo", firstByline?.Name);
        }
        Assert.Null(stories[1].Byline);

        foreach (var story in stories) story.Dispose();
    }

    [Fact]
    public void Archive_ReachableViaACollectionTypeArgument_ReturnsQualifiedTopStoryList()
    {
        using var newsroom = new Newsroom();
        IReadOnlyList<TopStory> archive = newsroom.Archive();

        Assert.Equal(2, archive.Count);

        using TopStory first = archive[0];
        Assert.Equal("Oreo escapes the cardboard box (again)", first.Title);

        using TopStory second = archive[1];
        Assert.Null(second.Byline);
    }

    [Fact]
    public void Code_CrossModuleValueClass_BindsAsUnwrappedValue()
    {
        using var newsroom = new Newsroom();
        var code = newsroom.Code();

        Assert.Equal("BREAKING-001", code.Value);
    }

    [Fact]
    public void StoryCode_Type_IsAValueType_NotAnIDisposableHandle()
    {
        // ADR-066's most-likely-to-ship-silently regression: a cross-module value class reports
        // Modifier.INLINE, never Modifier.VALUE. Every classification site that tests for
        // Modifier.VALUE alone would misclassify StoryCode as an ordinary class and export it as
        // an opaque IDisposable handle instead of an unwrapped value -- valid-compiling, wrong
        // API, no diagnostic. This is the guard against exactly that.
        var type = typeof(StoryCode);

        Assert.True(type.IsValueType);
        Assert.False(typeof(IDisposable).IsAssignableFrom(type));
    }

    [Fact]
    public void Uri_CharSequenceDelegatingValueClass_AuthorDeclaredMemberSurvives()
    {
        using var newsroom = new Newsroom();
        var uri = newsroom.Uri();

        Assert.Equal("CATS.NEWS/OREO-ESCAPES", uri.Shout());
    }

    [Fact]
    public void StoryUri_Type_DelegatedCharSequenceMembersAreSkipped_AuthorDeclaredMemberIsNot()
    {
        // The amended SKIPPED_INHERITED_MEMBER filter (ADR-066 forces this correction to
        // ADR-064): `get`/`subSequence`/`length`, forwarded by `CharSequence by value`
        // delegation, report origin == KOTLIN_LIB and parentDeclaration == StoryUri itself --
        // identical, cross-module, to a hand-written member on both signals. Only "a supertype
        // declares a member with this simple name" tells them apart. `Shout()` is genuinely
        // author-declared and must survive the same filter that drops the delegated three.
        var type = typeof(StoryUri);

        Assert.Null(type.GetMethod("Get"));
        Assert.Null(type.GetMethod("SubSequence"));
        Assert.Null(type.GetProperty("Length"));
        Assert.NotNull(type.GetMethod("Shout"));
    }

    [Fact]
    public void Sponsor_OutOfScopeDependencyType_NeverBinds_AndDoesNotBreakTheRestOfTheFacade()
    {
        // dev.other.core.Advertisement is reachable from Newsroom.Sponsor(), but its package
        // sits outside rootPackage, so the admission predicate must skip it with
        // SKIPPED_UNEXPORTED_DEPENDENCY_TYPE, not bind it as a handle, and not take the rest of
        // Newsroom down with it. The diagnostic itself (and its `include("dev.other.core")`
        // hint) is asserted at the KSP/Tier 1 level, not observable from compiled C#; the
        // absence of a `Sponsor` member, alongside every sibling member staying present, is
        // what's observable here.
        Assert.Null(typeof(Newsroom).GetMethod("Sponsor"));

        Assert.NotNull(typeof(Newsroom).GetMethod("Latest"));
        Assert.NotNull(typeof(Newsroom).GetMethod("Stream"));
        Assert.NotNull(typeof(Newsroom).GetMethod("Archive"));
        Assert.NotNull(typeof(Newsroom).GetMethod("Code"));
        Assert.NotNull(typeof(Newsroom).GetMethod("Uri"));
        Assert.NotNull(typeof(Newsroom).GetMethod("Echo"));
    }

    [Fact]
    public void Echo_CyclicPairAcrossTheModuleBoundary_ClosureTerminates()
    {
        // Whisker.purr: Purr? and Purr.whisker: Whisker? -- the closure's visited set (keyed on
        // qualified name) must terminate on the second visit instead of walking forever. Just
        // reaching this test at all (packNuget completing) is half the assertion; the other half
        // is that both hops resolve to the original pair.
        using var newsroom = new Newsroom();
        using Whisker whisker = newsroom.Echo();

        Assert.Equal("Oreo", whisker.Label);

        using Purr? purr = whisker.Purr;
        Assert.NotNull(purr);
        Assert.Equal("prrrrr", purr!.Sound);

        using Whisker? whiskerAgain = purr.Whisker;
        Assert.NotNull(whiskerAgain);
        Assert.Equal("Oreo", whiskerAgain!.Label);
    }
}
