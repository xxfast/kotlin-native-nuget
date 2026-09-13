using System.Reflection;
using TestLibrary;
using TestLibrary.Unrouted;

namespace IntegrationTests;

/// <summary>
/// ADR-064 amendment (unrouted positions). <c>Flow</c>, a lambda, a <c>suspend</c> lambda and a
/// generic declaration each have a legacy route at <em>some</em> owner/position, and nowhere else:
/// research H measured every combination (<c>research/H-observed-matrix.md</c>) and found most of
/// them vanish from both halves of the bridge with no diagnostic at all. The fix names every silent
/// one; the Kotlin fixture is
/// <c>test-library/.../test/unrouted/UnroutedPositionsSample.kt</c> plus
/// <c>UnroutedTopLevelFlow.kt</c>.
///
/// <para>
/// <b>Not observable from here:</b> the diagnostic. Its kind
/// (<c>SKIPPED_UNSUPPORTED_INPUT</c> / <c>_RETURN</c> / <c>_COMBINATION</c> by position), its
/// wording and the qualified member name it must carry are all processor-level and are pinned in
/// <c>Tier1UnroutedPositionsTest.kt</c>, exactly as <see cref="Issue113Tests"/> explains for the
/// opt-in markers. From compiled C# only the shape of the surface is visible: the skipped members
/// are gone, and the routed ones are still callable.
/// </para>
///
/// <para>
/// <b>The absence trap.</b> Absence assertions cannot catch a skip that is too wide, so every owner
/// below is checked for a control member first (<c>OkOnClass</c>, <c>OkOnDock</c>,
/// <c>OkOnObject</c>, <c>OkOnInterface</c>), and the four genuinely-routed cells are asserted
/// present rather than merely unmentioned.
/// </para>
///
/// <para>
/// The depot is Oreo's (black, white in the middle) and the dock is Mylo's (brown and creamy). Both
/// cats insist their own paperwork still works while the unroutable freight is turned away at the
/// gate.
/// </para>
/// </summary>
public class UnroutedPositionsTests
{
    private const BindingFlags PublicInstance = BindingFlags.Public | BindingFlags.Instance;
    private const BindingFlags PublicStatic = BindingFlags.Public | BindingFlags.Static;

    /// <summary>
    /// Resolved by name, never by <c>typeof</c>: a static class whose every member is skipped may
    /// not be emitted at all after the fix, and a <c>typeof</c> against it would then stop the test
    /// project compiling rather than reporting a result.
    /// </summary>
    private static Type? TopLevelType(string simpleName) =>
        typeof(Depot).Assembly.GetType($"TestLibrary.Unrouted.{simpleName}");

    private static void AssertAllAbsent(Type owner, BindingFlags flags, params string[] names)
    {
        foreach (string name in names)
        {
            Assert.Null(owner.GetMethod(name, flags));
        }
    }

    // -----------------------------------------------------------------------------------------
    // Controls. If one of these ever goes null the absence facts below prove nothing.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void EveryOwnerInTheFixtureKeepsItsControlMember()
    {
        Assert.NotNull(typeof(Depot).GetMethod("OkOnClass", PublicInstance));
        Assert.NotNull(typeof(Dock).GetMethod("OkOnDock", PublicInstance));
        Assert.NotNull(typeof(ManifestDesk).GetMethod("OkOnManifestDesk", PublicInstance));
        Assert.NotNull(typeof(IManifest).GetMethod("OkOnInterface"));
        Assert.NotNull(TopLevelType("DepotRegistry")?.GetMethod("OkOnObject", PublicStatic));
    }

    // -----------------------------------------------------------------------------------------
    // Silent drops, one fact per owner.
    // -----------------------------------------------------------------------------------------

    /// <summary>
    /// Oreo's depot: everything a class-owner route does not reach. The one Flow at a return and
    /// the one lambda at a parameter that DO bind are asserted present further down, so this fact
    /// cannot pass by the whole class having disappeared.
    /// </summary>
    [Fact]
    public void ClassMembersAtUnroutedPositionsAreAbsent()
    {
        AssertAllAbsent(
            typeof(Depot),
            PublicInstance,
            "FlowParamOnClass",
            "CallbackReturnOnClass",
            "GenericReturnOnClass",
            "GenericParamOnClass",
            "StructuralOnClass",
            "SuspendCallbackParamOnClass",
            "FlowElementOnClass",
            "CallbackElementOnClass",
            "GenericElementOnClass");
    }

    /// <summary>
    /// No legacy route is keyed to an object owner at all, so even the two shapes that bind on a
    /// class (a Flow return, a lambda parameter) are gone here.
    /// </summary>
    [Fact]
    public void ObjectMembersAtUnroutedPositionsAreAbsent()
    {
        Type? registry = TopLevelType("DepotRegistry");
        Assert.NotNull(registry);
        AssertAllAbsent(
            registry,
            PublicStatic,
            "FlowReturnOnObject",
            "FlowParamOnObject",
            "CallbackParamOnObject",
            "CallbackReturnOnObject",
            "GenericReturnOnObject",
            "GenericParamOnObject",
            "StructuralOnObject");
    }

    /// <summary>
    /// Interface defaults. Only the three genuinely-silent ones are pinned: a Flow <em>return</em>
    /// and a lambda <em>parameter</em> declared as an interface default ARE re-emitted, on the
    /// implementing class and not on the interface, which is a split-out bug rather than part of
    /// this item, so neither is asserted either way here.
    /// </summary>
    [Fact]
    public void InterfaceDefaultsAtUnroutedPositionsAreAbsent()
    {
        AssertAllAbsent(
            typeof(IManifest),
            PublicInstance,
            "FlowParamOnInterface",
            "GenericReturnOnInterface",
            "StructuralOnInterface");
        AssertAllAbsent(
            typeof(ManifestDesk),
            PublicInstance,
            "FlowParamOnInterface",
            "GenericReturnOnInterface",
            "StructuralOnInterface");
    }

    /// <summary>
    /// Extensions on <c>Depot</c> and the top-level functions share the file-named static class
    /// (ADR-007), except the Flow return, which the fixture keeps in its own file so it can be
    /// disabled on its own.
    /// </summary>
    [Fact]
    public void TopLevelAndExtensionMembersAtUnroutedPositionsAreAbsent()
    {
        Type? sample = TopLevelType("UnroutedPositionsSample");
        Assert.NotNull(sample);
        AssertAllAbsent(
            sample,
            PublicStatic,
            "FlowReturnOnExtension",
            "FlowParamOnExtension",
            "CallbackParamOnExtension",
            "GenericReturnOnExtension",
            "StructuralOnExtension",
            "FlowParamOnTopLevel",
            "CallbackParamOnTopLevel",
            "GenericParamOnTopLevel",
            "StructuralRefusedOnTopLevel");

        // The LIE cell: today this renders `public static Flow<int> FlowReturnOnTopLevel()` against
        // a `Flow<T>` type that exists nowhere in Interop.cs (the class route spells the same thing
        // `KotlinFlow<int>`), so the generated bindings do not compile. There is no top-level Flow
        // route, so the member must be gone -- and its whole static class with it, which is why
        // this resolves the type by name.
        Assert.Null(TopLevelType("UnroutedTopLevelFlow")?.GetMethod("FlowReturnOnTopLevel", PublicStatic));
    }

    // -----------------------------------------------------------------------------------------
    // The four routed cells: these must keep working, not merely stay unwarned.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void ClassFlowReturnStillBinds()
    {
        MethodInfo? flowReturn = typeof(Depot).GetMethod("FlowReturnOnClass", PublicInstance);
        Assert.NotNull(flowReturn);
        Assert.Equal(typeof(KotlinFlow<int>), flowReturn.ReturnType);
    }

    [Fact]
    public void ClassLambdaParameterStillBinds()
    {
        using Depot depot = new();
        List<int> seen = [];
        depot.CallbackParamOnClass(seen.Add);
        Assert.Equal([1], seen);
    }

    [Fact]
    public void TopLevelLambdaReturnStillBinds()
    {
        using KotlinFunc<string, string> echo = UnroutedPositionsSample.CallbackReturnOnTopLevel();

        Assert.Equal("Oreo", echo.Invoke("Oreo"));
    }

    [Fact]
    public void TopLevelStructuralGenericStillBinds()
    {
        Assert.Equal("Mylo", UnroutedPositionsSample.StructuralOnTopLevel("Mylo"));
        Assert.Equal(7, UnroutedPositionsSample.StructuralOnTopLevel(7));
    }

    /// <summary>
    /// Row 24: three secondary constructors each carry one unroutable parameter and are dropped,
    /// but the good primary keeps the class constructible -- the class-level "no public
    /// constructor" fallback must not have swallowed it.
    /// </summary>
    [Fact]
    public void DockIsStillConstructibleViaItsPrimaryConstructor()
    {
        Assert.NotNull(typeof(Dock).GetConstructor([typeof(int)]));
        using Dock dock = new(7);
        Assert.Equal(7, dock.OkOnDock());
        Assert.Single(typeof(Dock).GetConstructors());
    }
}
