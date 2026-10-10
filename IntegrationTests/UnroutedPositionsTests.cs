using System.Reflection;
using TestLibrary;
using TestLibrary.Unrouted;

namespace IntegrationTests;

/// <summary>
/// ADR-064 amendment (unrouted positions). <c>Flow</c>, a lambda, a <c>suspend</c> lambda and a
/// generic declaration each bind at <em>some</em> owner/positions and nowhere else: research H
/// measured every combination (<c>research/H-observed-matrix.md</c>) and found most of them vanish
/// from both halves of the bridge with no diagnostic at all. The fix names every silent one. Since
/// ADR-160 a per-call lambda parameter binds off the plan on every ordinary owner, and since
/// ADR-197 so does a class or object member's own type parameter (<c>fun &lt;T&gt;</c>), and since
/// ADR-208 a closed instantiation of an exported generic class (<c>Box&lt;Int&gt;</c>) at every
/// position; all three are asserted present below rather than absent. The Kotlin fixture is
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
            // ADR-208: `GenericReturnOnClass`, `GenericParamOnClass` and `GenericElementOnClass`
            // BIND now (a closed `Box<Int>` is an ordinary handle at every position) and are called
            // in GenericInstantiationsBindOnEveryOwner instead of being absent here.
            "SuspendCallbackParamOnClass",
            "FlowElementOnClass",
            "CallbackElementOnClass");
    }

    /// <summary>
    /// No <b>legacy</b> route is keyed to an object owner at all, so the Flow return that binds on a
    /// class is gone here. The lambda parameter no longer is: ADR-160 moved it onto the ADR-062
    /// plan, which is keyed to the position rather than to the owner kind, and it is asserted present
    /// at the end of this fact.
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
            // ADR-160 moved the per-call lambda parameter onto the ADR-062 plan, which is keyed to
            // the position rather than to the owner kind, so `CallbackParamOnObject` BINDS now and
            // is asserted present below instead of absent here.
            // ADR-208: `GenericReturnOnObject` and `GenericParamOnObject` bind too.
            "CallbackReturnOnObject");

        // ADR-160: the object position's per-call lambda parameter is the one cell this matrix row
        // lost. Asserted positively so the row cannot silently go back to refusing it.
        Assert.NotNull(registry!.GetMethod("CallbackParamOnObject", PublicStatic));
    }

    /// <summary>
    /// Interface defaults. Only the two genuinely-silent ones are absent (the generic return left
    /// the list with ADR-208 and is called through the interface below): a Flow <em>return</em>
    /// and a lambda <em>parameter</em> declared as an interface default are declared on
    /// <c>IManifest</c> (ADR-160 for the lambda, ADR-174 for the Flow) and are called through it in
    /// <see cref="InterfaceDefaultFlowAndLambdaAreCallableThroughTheInterfaceType"/>.
    /// </summary>
    [Fact]
    public void InterfaceDefaultsAtUnroutedPositionsAreAbsent()
    {
        AssertAllAbsent(
            typeof(IManifest),
            PublicInstance,
            "FlowParamOnInterface",
            "StructuralOnInterface");
        AssertAllAbsent(
            typeof(ManifestDesk),
            PublicInstance,
            "FlowParamOnInterface",
            "StructuralOnInterface");
    }

    /// <summary>
    /// ADR-174 (backlog: interface default Flow/lambda invisible through the interface type). Every
    /// call goes through an <c>IManifest</c>-typed reference, the ADR-040 backing wrapper around a
    /// <c>ManifestDesk</c>: the lambda default invokes the caller's callback, and the Flow default is
    /// collected to completion from Kotlin's own <c>flowOf(1)</c>.
    /// </summary>
    [Fact]
    public async Task InterfaceDefaultFlowAndLambdaAreCallableThroughTheInterfaceType()
    {
        await using IManifest manifest = UnroutedPositionsSample.MakeManifest();

        int seen = 0;
        manifest.CallbackParamOnInterface(value => seen = value);
        Assert.Equal(1, seen);

        var items = new List<int>();
        await foreach (int value in manifest.FlowReturnOnInterface()) items.Add(value);
        Assert.Equal(new[] { 1 }, items);
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
            "StructuralOnExtension",
            "FlowParamOnTopLevel",
            // ADR-160: the top-level per-call lambda parameter binds off the plan now, exactly as
            // the class-method one does; asserted present below. ADR-208: so does the generic
            // parameter (`GenericParamOnTopLevel`), called in GenericInstantiationsBindOnEveryOwner.
            "StructuralRefusedOnTopLevel");

        // ADR-160: the top-level per-call lambda parameter is the cell this row lost. Asserted
        // positively so the row cannot silently go back to refusing it.
        Assert.NotNull(sample!.GetMethod("CallbackParamOnTopLevel", PublicStatic));

        // An extension does NOT share the file-named class: it is emitted on a per-receiver
        // `{Receiver}Extensions` static class, which is why every `*OnExtension` name asserted
        // against `UnroutedPositionsSample` above proves nothing on its own. The real claim is made
        // here, against the owner that could carry them -- and `CallbackParamOnExtension` is the one
        // ADR-160 binds, so it is asserted present rather than absent.
        Type? extensions = TopLevelType("DepotExtensions");
        Assert.NotNull(extensions);
        AssertAllAbsent(
            extensions,
            PublicStatic,
            "FlowReturnOnExtension",
            "FlowParamOnExtension",
            "StructuralOnExtension");
        Assert.NotNull(extensions!.GetMethod("CallbackParamOnExtension", PublicStatic));

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

    /// <summary>
    /// ADR-197: the class and object structural own-<c>&lt;T&gt;</c> cells left this matrix when a
    /// member function's own type parameter moved onto the ADR-062 plan. Asserted by calling them,
    /// so the row cannot quietly go back to refusing them.
    /// </summary>
    [Fact]
    public void ClassAndObjectStructuralGenericsBind()
    {
        using var depot = new Depot();
        Assert.Equal("Oreo", depot.StructuralOnClass("Oreo"));
        Assert.Equal(7, depot.StructuralOnClass(7));
        Assert.Equal(9, DepotRegistry.StructuralOnObject(9));
    }

    [Fact]
    public void TopLevelStructuralGenericStillBinds()
    {
        Assert.Equal("Mylo", UnroutedPositionsSample.StructuralOnTopLevel("Mylo"));
        Assert.Equal(7, UnroutedPositionsSample.StructuralOnTopLevel(7));
    }

    /// <summary>
    /// Row 24: two secondary constructors each carry one unroutable parameter (a lambda, a Flow)
    /// and are dropped, but the good primary keeps the class constructible -- the class-level "no
    /// public constructor" fallback must not have swallowed it. The third secondary, over a
    /// `Box<Int>`, binds since ADR-208.
    /// </summary>
    [Fact]
    public void DockIsStillConstructibleViaItsPrimaryConstructor()
    {
        Assert.NotNull(typeof(Dock).GetConstructor([typeof(int)]));
        using Dock dock = new(7);
        Assert.Equal(7, dock.OkOnDock());
        Assert.Equal(2, typeof(Dock).GetConstructors().Length);

        using var box = new TestLibrary.Cat.Box<int>(3);
        using Dock boxed = new(8, box);
        Assert.Equal(8, boxed.OkOnDock());
    }

    /// <summary>
    /// ADR-208: the generic cells left this matrix when a closed instantiation of an exported
    /// generic class became an ordinary handle at every position. Asserted by calling each one, on
    /// every owner the matrix has, so no row can quietly go back to refusing them.
    /// </summary>
    [Fact]
    public async Task GenericInstantiationsBindOnEveryOwner()
    {
        using var seven = new TestLibrary.Cat.Box<int>(7);

        using var depot = new Depot();
        using (TestLibrary.Cat.Box<int> returned = depot.GenericReturnOnClass())
        {
            Assert.Equal(1, returned.Value);
        }
        Assert.Equal(7, depot.GenericParamOnClass(seven));
        Assert.Empty(depot.GenericElementOnClass());

        using (TestLibrary.Cat.Box<int> returned = DepotRegistry.GenericReturnOnObject())
        {
            Assert.Equal(1, returned.Value);
        }
        Assert.Equal(7, DepotRegistry.GenericParamOnObject(seven));

        await using IManifest manifest = UnroutedPositionsSample.MakeManifest();
        using (TestLibrary.Cat.Box<int> returned = manifest.GenericReturnOnInterface())
        {
            Assert.Equal(1, returned.Value);
        }

        Assert.Equal(7, UnroutedPositionsSample.GenericParamOnTopLevel(seven));
        using (TestLibrary.Cat.Box<int> returned = depot.GenericReturnOnExtension())
        {
            Assert.Equal(1, returned.Value);
        }
    }
}
