using System.Linq;
using System.Reflection;
using TestLibrary;
using TestLibrary.Issue54;

namespace IntegrationTests;

/// <summary>
/// The nested-declaration skip: a plain <c>class</c> or <c>object</c> nested inside an exported
/// class. Its sibling gates for nested enums (<see cref="NestedEnumGateTests"/>) and nested
/// interfaces already skip with a named diagnostic; plain classes and objects are the hole.
///
/// Two shapes, because the two routes into the export set behave differently and a fix that closes
/// one does not close the other:
/// <list type="bullet">
/// <item>(a) a module-local nested class/object (<c>ProbeOuter.Nested</c>, <c>ProbeOuter.Marker</c>)
/// is never collected at all: every root bucket in <c>NugetProcessor.kt</c> filters
/// <c>parentDeclaration == null</c>, and no diagnostic is emitted for the declaration. Members typed
/// with it do skip, but through the generic <c>SKIPPED_UNSUPPORTED_TYPE</c> route whose
/// "expose a bridgeable adapter" hint points at the wrong repair;</item>
/// <item>(b) a dependency module's nested class/object (<c>Broadcast.Schedule</c>,
/// <c>Broadcast.Defaults</c>) that the ADR-066 reachability closure <em>does</em> admit, because the
/// closure filters <c>parentDeclaration</c> for enums only. It is then declared flattened at
/// namespace root under its simple name while references spell <c>Broadcast.Schedule</c>, so the
/// consumer compile fails with CS0426.</item>
/// </list>
///
/// After the fix each nested declaration carries a <c>SKIPPED_NESTED_DECLARATION</c> warning, each
/// member typed with one skips with the <c>UNDECLARED_CLASS</c> reason and a "move it to the top
/// level" hint, and the closure refuses nested dependency classes and objects. The diagnostics
/// themselves are asserted at Tier 1; from compiled C# only the absence of the declarations and
/// their typed members, and the survival of everything around them, is observable.
///
/// The <c>companion object</c> is the carve-out: it is nested by every structural test, but it is
/// how <c>ProbeOuter.Make()</c> reaches C# as a static factory, so a gate that swept it up would
/// silently delete working API.
///
/// Oreo probes every nested cardboard box in the house. Mylo waits for the all-clear, then takes it.
/// </summary>
public class NestedClassGateTests
{
    private const BindingFlags PublicInstance = BindingFlags.Public | BindingFlags.Instance;
    private const BindingFlags PublicStatic = BindingFlags.Public | BindingFlags.Static;

    // --- Shape (a): module-local nested class and object ---

    [Fact]
    public void ProbeOuter_StillConstructs_AndItsUnrelatedMemberStillBinds()
    {
        // The control half: a gate that drops the whole owning class instead of just its
        // nested-typed members would also fail here, which is the point.
        using var outer = new ProbeOuter();

        Assert.Equal("outer", outer.Label);
    }

    [Fact]
    public void ProbeOuter_CompanionFactory_IsTheCarveOut_AndStillWorks()
    {
        using ProbeOuter outer = ProbeOuter.Make();

        Assert.Equal("outer", outer.Label);
    }

    [Fact]
    public void ProbeOuter_NonNullNestedClassReturn_IsSkipped()
    {
        // The static companion factory is named Make too, so this has to ask for the instance
        // method specifically: a bare GetMethod("Make") would find the carve-out and pass for the
        // wrong reason.
        Assert.Null(typeof(ProbeOuter).GetMethod("Make", PublicInstance));
    }

    [Fact]
    public void ProbeOuter_NullableNestedClassReturn_IsSkipped()
    {
        Assert.Null(typeof(ProbeOuter).GetMethod("Maybe", PublicInstance));
    }

    [Fact]
    public void ProbeOuter_NestedObjectReturn_IsSkipped()
    {
        Assert.Null(typeof(ProbeOuter).GetMethod("Single", PublicInstance));
    }

    [Fact]
    public void ProbeOuter_NestedDeclarations_AreNeverDeclaredAsNestedTypes()
    {
        // A fix that "declares nested classes" instead of skipping them would satisfy the member
        // assertions above by emitting ProbeOuter.Nested; this pins the chosen behaviour.
        Assert.Null(typeof(ProbeOuter).GetNestedType("Nested"));
        Assert.Null(typeof(ProbeOuter).GetNestedType("Marker"));
    }

    // --- Shape (b): admitted dependency class, nested class and object ---

    [Fact]
    public void Newsroom_NestedDependencyClassReturn_IsSkipped()
    {
        Assert.Null(typeof(Newsroom).GetMethod("Schedule", PublicInstance));
    }

    [Fact]
    public void Newsroom_NestedDependencyObjectReturn_IsSkipped()
    {
        Assert.Null(typeof(Newsroom).GetMethod("Defaults", PublicInstance));
    }

    [Fact]
    public void Newsroom_SurvivesTheSkip()
    {
        // Same guarantee Sponsor() pins for an unadmitted class: the skip must not take the rest of
        // the facade with it, and Broadcast itself must keep binding.
        Assert.NotNull(typeof(Newsroom).GetMethod("Latest", PublicInstance));
        Assert.NotNull(typeof(Newsroom).GetMethod("Broadcast", PublicInstance));

        using var newsroom = new Newsroom();
        using var broadcast = newsroom.Broadcast();

        Assert.Equal("Radio Mylo 101.1", broadcast.Station);
    }

    // --- Assembly-wide: none of the four nested declarations may exist anywhere ---

    [Fact]
    public void NoneOfTheNestedDeclarations_ExistAnywhereInTheGeneratedAssembly()
    {
        // Shape (b) fails here today in a way the per-member assertions cannot see: the closure
        // admits Broadcast.Schedule and Broadcast.Defaults and declares them as namespace-root
        // types, so types with those names exist even though no reference resolves against them.
        var strays = typeof(ProbeOuter).Assembly
            .GetTypes()
            .Where(type => type.Name is "Nested" or "Marker" or "Schedule" or "Defaults")
            .Select(type => type.FullName)
            .OrderBy(name => name)
            .ToArray();

        Assert.Empty(strays);
    }

    [Fact]
    public void ProbeOuter_CarveOut_DoesNotLeakACompanionType()
    {
        // The companion is bridged as a static member on ProbeOuter, never as its own type. If a
        // gate ever starts declaring nested types, this is where a `Companion` would surface.
        Assert.Null(typeof(ProbeOuter).GetNestedType("Companion"));
        Assert.NotNull(typeof(ProbeOuter).GetMethod("Make", PublicStatic));
    }
}
