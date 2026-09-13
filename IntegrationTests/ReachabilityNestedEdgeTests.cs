using System;
using System.Linq;
using System.Reflection;
using TestLibrary;
using TestLibrary.Models;

namespace IntegrationTests;

/// <summary>
/// ADR-066 amendment: the two reachability edges ADR-133's owner walk assumed but the closure never
/// had. <see cref="NestedClassGateTests"/> pins <em>which declarations exist</em> for a nested type
/// whose owner is already admitted; this file pins the two cases where the owner (or the nested
/// type's own dependency) is reachable <em>only</em> through nesting:
/// <list type="bullet">
/// <item><b>Edge (A), nested to owner chain.</b> <c>Newsroom.Page()</c> returns
/// <c>Almanac.Page</c> and <em>nothing anywhere returns <c>Almanac</c></em>. That absence is the
/// whole fixture: <c>Broadcast</c> cannot prove this edge because <c>Newsroom.Broadcast()</c>
/// already admits it, so its nested types ride an admission the closure got for free. Before the
/// amendment <c>Almanac</c> is never visited at all, so no <c>Almanac</c> is declared and
/// <c>page()</c> skips as <c>UNDECLARED_CLASS</c> advising "move it to the top level of its file",
/// a remedy ADR-133 made false.</item>
/// <item><b>Edge (B), declared nested type to its own member types.</b>
/// <c>Broadcast.Schedule.Timetable()</c> returns the top-level dependency class <c>Timetable</c>,
/// referenced from nowhere else. <c>walkClassMembers</c> descends into an owner's ctor parameters,
/// properties, functions and companion only, never into a nested declaration, so <c>Timetable</c>
/// is neither admitted nor refused; the member then skips as
/// <c>SKIPPED_UNEXPORTED_DEPENDENCY_TYPE</c> advising an <c>include(...)</c> for a package that is
/// already in scope. That is the worse of the two diagnostics: acting on it changes nothing.</item>
/// </list>
///
/// Both fixtures live in <c>:test-models</c>, a separate Gradle module consumed by
/// <c>:test-library</c> via <c>implementation(project(":test-models"))</c> with the nuget plugin
/// deliberately not applied, so these declarations reach KSP as klib declarations
/// (<c>containingFile == null</c>) and go through the dependency route rather than the root walk.
/// Their package <c>...test.models</c> is inside the <c>include("io.github.xxfast.kotlin.native
/// .nuget.test", "dev.other.admitted")</c> scope by segment, which <c>TopStory</c> already proves,
/// so nothing here is a scope question.
///
/// Oreo reads the almanac one page at a time and never the cover; Mylo only wants the timetable
/// slot marked "dinner".
/// </summary>
public class ReachabilityNestedEdgeTests
{
    private const BindingFlags PublicInstance = BindingFlags.Public | BindingFlags.Instance;

    // --- Edge (A): the owner nothing returns ---

    [Fact]
    public void Almanac_IsDeclaredOnce_AtNamespaceLevel_EvenThoughNothingReturnsIt()
    {
        Type[] declarations = typeof(Newsroom).Assembly
            .GetTypes()
            .Where(type => type.Name == "Almanac")
            .ToArray();

        Assert.Single(declarations);
        Assert.False(declarations[0].IsNested, "expected the admitted owner at namespace level");
        Assert.Equal("TestLibrary.Models", declarations[0].Namespace);
    }

    [Fact]
    public void AlmanacPage_IsDeclaredOnce_Nested_UnderTheOwnerItAdmitted()
    {
        Type? page = typeof(Almanac).GetNestedType("Page");

        Assert.NotNull(page);
        Assert.Same(typeof(Almanac), page!.DeclaringType);

        // No flattened namespace-root twin: that was the pre-ADR-133 shape and it failed CS0426.
        Type[] allPages = typeof(Newsroom).Assembly
            .GetTypes()
            .Where(type => type.Name == "Page")
            .ToArray();

        Assert.DoesNotContain(allPages, type => !type.IsNested);
        Assert.Single(allPages, type => type.DeclaringType == typeof(Almanac));
    }

    [Fact]
    public void Newsroom_Page_RoundTripsThroughTheNestedTypeOfAnOwnerNothingReturns()
    {
        using var newsroom = new Newsroom();
        using Almanac.Page page = newsroom.Page();

        Assert.Equal(3, page.Number);
    }

    // --- Edge (B): the dependency type reachable only from a nested type's member ---

    [Fact]
    public void Timetable_IsDeclaredOnce_AtNamespaceLevel()
    {
        Type[] declarations = typeof(Newsroom).Assembly
            .GetTypes()
            .Where(type => type.Name == "Timetable")
            .ToArray();

        Assert.Single(declarations);
        Assert.False(declarations[0].IsNested, "Timetable is top-level in Kotlin and must stay so");
        Assert.Equal("TestLibrary.Models", declarations[0].Namespace);
    }

    [Fact]
    public void BroadcastSchedule_Timetable_BindsAndRoundTrips()
    {
        using var newsroom = new Newsroom();
        using Broadcast.Schedule schedule = newsroom.Schedule();
        using Timetable timetable = schedule.Timetable();

        // Schedule(7) -> slot * 2. If the closure never admitted Timetable, this member is absent
        // entirely rather than wrong, so the compile error is the first signal, not this value.
        Assert.Equal(14, timetable.Slots);
        Assert.NotNull(typeof(Broadcast.Schedule).GetMethod("Timetable", PublicInstance));
    }

    [Fact]
    public void TheRestOfTheFacade_SurvivesBothEdges()
    {
        // The control: admitting an owner on the strength of a nested reference must not disturb
        // the types that were already admitted the ordinary way.
        using var newsroom = new Newsroom();
        using var broadcast = newsroom.Broadcast();

        Assert.Equal("Radio Mylo 101.1", broadcast.Station);
        Assert.NotNull(typeof(Newsroom).GetMethod("Latest", PublicInstance));
    }
}
