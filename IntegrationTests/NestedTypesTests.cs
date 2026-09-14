using System;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;
using System.Threading.Tasks;
using TestLibrary.Cat;
using TestLibrary.Nested;

namespace IntegrationTests;

/// <summary>
/// ADR-133: a public nested <c>class</c>, <c>object</c>, <c>interface</c> or <c>enum class</c>
/// declared inside an exported non-generic class or object is declared as a real C# nested type
/// (<c>Aviary.Perch</c>), generalising the ADR-009 rule that already nests a sealed arm inside its
/// base. Before this ADR every kind was absent: <c>SKIPPED_NESTED_DECLARATION</c> at the
/// declaration and <c>UNDECLARED_CLASS</c>/<c>UNDECLARED_ENUM</c>/<c>UNDECLARED_INTERFACE</c> at
/// every member typed with one.
///
/// The three <c>issue54</c> gate fixtures pin the declaration seam and flip in
/// <see cref="NestedClassGateTests"/>, <see cref="NestedEnumGateTests"/> and
/// <see cref="NestedInterfaceGateTests"/>. This file covers what those cannot see, because a
/// declared nested type has to cross the bridge as well as exist:
/// <list type="bullet">
/// <item>the nested class at a <em>return</em> and a <em>parameter</em> position (round trip),</item>
/// <item>the nested enum read from a property and passed back in,</item>
/// <item>the nested interface implemented in C# and called back from Kotlin, and returned,</item>
/// <item>a nested static class under a class owner, and a nested class under an <em>object</em>
/// owner, which travels a different translator path,</item>
/// <item>depth 2 (<c>Aviary.Middle.Inner</c>), where the export prefix chain has three segments.</item>
/// </list>
///
/// Oreo takes the top perch. Mylo registers a complaint with <c>Registry</c>.
/// </summary>
public class NestedTypesTests
{
    // --- Nested class: declared, constructible, and round trips ---

    [Fact]
    public void NestedClass_IsDeclaredAsANestedType_UnderItsOwner()
    {
        Type? perch = typeof(Aviary).GetNestedType("Perch");

        Assert.NotNull(perch);
        Assert.True(perch!.IsNested, "expected Perch to be nested, not flattened to namespace root");
        Assert.Same(typeof(Aviary), perch.DeclaringType);
    }

    [Fact]
    public void NestedClass_IsConstructibleAndDisposable_OnItsOwn()
    {
        using var perch = new Aviary.Perch(3);

        Assert.Equal("perch@3", perch.Describe());
    }

    [Fact]
    public void NestedClass_ReturnedFromTheOwner_AndPassedBackIn()
    {
        // The full round trip: `aviary_perch_create` mints the handle inside Kotlin, C#
        // holds it, and HeightOf unwraps it back into Kotlin. A declaration-only implementation
        // satisfies the reflection cell above and fails this one.
        using var aviary = new Aviary("Oreo");
        using var perch = aviary.PerchAt(5);

        Assert.Equal("perch@5", perch.Describe());
        Assert.Equal(5, aviary.HeightOf(perch));
    }

    [Fact]
    public void NestedClass_AtDepthTwo_RoundTrips()
    {
        using var aviary = new Aviary("Mylo");
        using var inner = aviary.Inner(2);

        Assert.NotNull(typeof(Aviary).GetNestedType("Middle"));
        Assert.NotNull(typeof(Aviary.Middle).GetNestedType("Inner"));
        Assert.Equal("inner@2", inner.Describe());
    }

    // --- Nested object: a nested static class, statics only ---

    [Fact]
    public void NestedObject_IsANestedStaticClass_WithStaticMembers()
    {
        // A Kotlin `object` renders as `public static class` (CatRegistry today), so the nested one
        // is a nested static class: abstract + sealed in metadata, and reachable only by its
        // statics. Deliberately no member returns it: a position typed with a static class is
        // CS0722, and nothing gates an object-typed position today: ADR-133 has to add that gate
        // when it removes the nested one (see ProbeOuter.Single).
        Type? defaults = typeof(Aviary).GetNestedType("Defaults");

        Assert.NotNull(defaults);
        Assert.True(defaults!.IsAbstract && defaults.IsSealed, "expected a static class");
        Assert.Equal(12, Aviary.Defaults.Capacity());
    }

    // --- Nested enum: ordinal wire, top-level extension class ---

    [Fact]
    public void NestedEnum_IsDeclaredNested_AndReadsFromAProperty()
    {
        Type? kind = typeof(Aviary).GetNestedType("Kind");

        Assert.NotNull(kind);
        Assert.True(kind!.IsEnum);

        using var aviary = new Aviary("Oreo");
        Assert.Equal(Aviary.Kind.Outdoor, aviary.Habitat);
        Assert.Equal(0, (int)Aviary.Kind.Indoor);
        Assert.Equal(1, (int)Aviary.Kind.Outdoor);
    }

    [Fact]
    public void NestedEnum_IsAcceptedAtAParameterPosition()
    {
        using var aviary = new Aviary("Oreo");

        Assert.Equal("Oreo/indoor", aviary.Rename(Aviary.Kind.Indoor));
    }

    [Fact]
    public void NestedEnum_ExtensionClass_StaysAtNamespaceLevel()
    {
        // CS1109: extension methods cannot live in a nested class, so the extension class for Kind
        // is the top-level AviaryKindExtensions, not Aviary.KindExtensions. If the generator ever
        // nests it, Interop.cs stops compiling and this test never runs; the assertion here is the
        // positive half, that the extension is still callable and no nested one exists.
        Assert.Null(typeof(Aviary).GetNestedType("KindExtensions"));
        Assert.Equal("outdoor", Aviary.Kind.Outdoor.Label());
    }

    // --- Extensions ON a nested receiver: the owner chain, not the bare simple name ---

    [Fact]
    public void ExtensionOnANestedReceiver_BindsUnderTheOwnerChain()
    {
        // ADR-133 amendment. The member route already chains (`Perch.describe()` exports as
        // `aviary_perch_describe`); an extension on the same receiver still binds under the bare
        // simple name today, so it lands in `PerchExtensions` behind `perch_summarize`. That is
        // the symbol a top-level `Perch`, or another owner's nested `Perch`, also claims. Measured
        // 2026-09-13: that duplicate is absorbed silently by the numbering suffix
        // (`inner_describe` + `inner_describe_2`), not reported as ERROR_C_ENTRY_POINT_COLLISION,
        // so a second owner can move an already-published symbol. Tier1NestedTypesTest pins it.
        //
        // The calls resolve by namespace, not by class name, so the two value assertions pass
        // either way: they are here so a fix that renames the class without keeping the extension
        // callable (or loses the ADR-013 `Get`-prefixed property half) is still caught. The
        // class-name facts below are the discriminator.
        using var aviary = new Aviary("Oreo");
        using var perch = aviary.PerchAt(9);

        Assert.Equal("perch@9 (ext)", perch.Summarize());
        Assert.True(perch.GetIsHigh());

        Assembly assembly = typeof(Aviary).Assembly;
        Assert.NotNull(assembly.GetType("TestLibrary.Nested.AviaryPerchExtensions"));
        Assert.Null(assembly.GetType("TestLibrary.Nested.PerchExtensions"));
        // CS1109 again: the chain-named class is at namespace level, never nested in its owner.
        Assert.Null(typeof(Aviary).GetNestedType("PerchExtensions"));
        Assert.Null(typeof(Aviary).GetNestedType("AviaryPerchExtensions"));
    }

    // --- Nested interface: implemented from C#, and returned from Kotlin ---

    private sealed class CountingKeeper : Aviary.IKeeper
    {
        public int Greetings { get; private set; }

        public string Greet()
        {
            Greetings++;
            return "hello from CSharp";
        }

        public void Dispose() { }
    }

    [Fact]
    public void NestedInterface_ImplementedInCSharp_IsCalledBackFromKotlin()
    {
        using var aviary = new Aviary("Oreo");
        var keeper = new CountingKeeper();

        Assert.Equal("hello from CSharp @ Oreo", aviary.GreetVia(keeper));
        Assert.Equal(1, keeper.Greetings);
    }

    [Fact]
    public void NestedInterface_ReturnedFromKotlin_UsesTheWrapperNestedBesideIt()
    {
        // The ADR-040 backing wrapper follows its interface scope now: Aviary.Keeper : IKeeper
        // nested in the owner, never a namespace-root Keeper.
        using var aviary = new Aviary("Mylo");
        using Aviary.IKeeper keeper = aviary.CurrentKeeper();

        Assert.Equal("hi from Mylo", keeper.Greet());
        Assert.NotNull(typeof(Aviary).GetNestedType("IKeeper"));
        Assert.Null(typeof(Aviary).Assembly.GetType("TestLibrary.Nested.IKeeper"));
    }

    private sealed class CountingKeeperForRegistry : Registry.IKeeper
    {
        public int Greetings { get; private set; }

        public string Greet()
        {
            Greetings++;
            return "hello from the registry desk";
        }

        public void Dispose() { }
    }

    [Fact]
    public void TwoOwners_EachWithANestedIKeeper_BothBridgeIntoCSharp()
    {
        // ADR-084's bridge state is named from the simple name alone and rendered in the root
        // namespace's CirBridgeHelper, so `Aviary.Keeper` and `Registry.Keeper` both want to be
        // `KeeperBridgeState` (CS0101) with two `keeperImpl` pattern variables in one block
        // (CS0128).
        //
        // Both interfaces need a *return* position for that to be true, not just the parameter
        // position exercised here: measured 2026-09-13, the bridge plans are built from
        // `CirTranslator.interfaceBackingClasses`, the RETURN-reachable subset. With
        // `Registry.greetVia` alone, `Registry.IKeeper` had no plan at all, `NugetBridge.HandleFor`
        // fell through to its `NotSupportedException` arm, and this test killed the entire test
        // host with a native `kotlin.NullPointerException` ("Test host process crashed") instead of
        // failing. `Registry.currentKeeper()` is in the fixture to close that.
        using var aviary = new Aviary("Oreo");
        var atTheAviary = new CountingKeeper();
        var atTheDesk = new CountingKeeperForRegistry();

        Assert.Equal("hello from CSharp @ Oreo", aviary.GreetVia(atTheAviary));
        Assert.Equal("hello from the registry desk @ registry", Registry.GreetVia(atTheDesk));
        Assert.Equal(1, atTheAviary.Greetings);
        Assert.Equal(1, atTheDesk.Greetings);
    }

    // --- Interface return on the legacy suspend and Flow routes (ADR-040 x ADR-019) ---

    [Fact]
    public async Task NestedInterface_ReturnedFromASuspendFunction_IsTypedAsTheInterface()
    {
        // The value assertion passes either way: the wrapper `Aviary.Keeper` implements
        // `Aviary.IKeeper`, so a wrapper-typed Task still assigns here. The reflection cell below
        // is the discriminator, and this one is what proves the route actually runs.
        using var aviary = new Aviary("Mylo");
        using Aviary.IKeeper keeper = await aviary.CurrentKeeperLaterAsync();

        Assert.Equal("hi from Mylo", keeper.Greet());
    }

    [Fact]
    public void SuspendInterfaceReturn_IsSpelledAsTheInterface_NotTheBackingWrapper()
    {
        // ADR-040: a consumer never sees the backing wrapper at a declared position. The legacy
        // suspend route spells the completion's result with `nestedCsName()`, which for an
        // interface is the wrapper, so the declared signature is `Task<Aviary.Keeper>` today.
        // GetMethods, not GetMethod: the suspend route also emits a CancellationToken overload.
        MethodInfo member = Assert.Single(
            typeof(Aviary).GetMethods().Where(method => method.Name == "CurrentKeeperLaterAsync"),
            method => method.GetParameters().All(parameter => parameter.IsOptional));
        Assert.Equal(typeof(Task<Aviary.IKeeper>), member.ReturnType);

        // The same route, declared at the top level and returning the nested interface: the
        // chain and the interface spelling have to be right together.
        MethodInfo topLevel = Assert.Single(
            typeof(AviaryRoutes).GetMethods().Where(method => method.Name == "AnyKeeperLaterAsync"),
            method => method.GetParameters().All(parameter => parameter.IsOptional));
        Assert.Equal(typeof(Task<Aviary.IKeeper>), topLevel.ReturnType);
    }

    [Fact]
    public async Task TopLevelInterface_ReturnedFromASuspendFunction_IsTypedAsTheInterface()
    {
        // ADR-040's own example: `strayPet()` is `IPet`, so `strayPetLater()` is `Task<IPet>`, not
        // `Task<Pet>`. Nothing about this one is nested, which is how it shows the defect is the
        // legacy suspend route's and not ADR-133's.
        using IPet stray = await PetKt.StrayPetLaterAsync();
        Assert.Equal("Mrrp?", stray.Speak());

        MethodInfo member = Assert.Single(
            typeof(PetKt).GetMethods().Where(method => method.Name == "StrayPetLaterAsync"),
            method => method.GetParameters().All(parameter => parameter.IsOptional));
        Assert.Equal(typeof(Task<IPet>), member.ReturnType);
    }

    [Fact]
    public async Task NestedInterface_AsAFlowElement_IsTypedAsTheInterface_AndEveryElementReads()
    {
        // The element spelling is the wrapper today, and the flow is read through
        // `NugetMarshal.FromHandle<T>`, whose Activator branch cannot construct an interface --
        // so the corrected spelling only works if the generator also passes an explicit read
        // lambda. Two elements, so a read that only surfaces the first is distinguishable.
        using var aviary = new Aviary("Oreo");
        var greetings = new List<string>();
        await foreach (Aviary.IKeeper keeper in aviary.Keepers())
        {
            greetings.Add(keeper.Greet());
            keeper.Dispose();
        }

        Assert.Equal(new List<string> { "first keeper of Oreo", "second keeper of Oreo" }, greetings);

        MethodInfo member = Assert.Single(
            typeof(Aviary).GetMethods().Where(method => method.Name == "Keepers"),
            method => method.GetParameters().All(parameter => parameter.IsOptional));
        Assert.Equal(typeof(Aviary.IKeeper), member.ReturnType.GetGenericArguments().Single());
    }

    // --- Object owner: the translator path a class-only implementation misses ---

    [Fact]
    public void NestedClass_UnderAnObjectOwner_IsDeclaredAndRoundTrips()
    {
        Type? entry = typeof(Registry).GetNestedType("Entry");

        Assert.NotNull(entry);
        Assert.Same(typeof(Registry), entry!.DeclaringType);

        using var one = Registry.Lookup(7);
        Assert.Equal("entry#7", one.Describe());
        Assert.Equal("registry", Registry.Label());
    }

    // --- Assembly-wide: nested, not flattened, and declared exactly once ---

    [Fact]
    public void NoNestedDeclaration_IsAlsoFlattenedToNamespaceRoot()
    {
        // The one-declarer rule (issue #54/#110): if both the owner walk and the dependency merge
        // declared a nested type it would be CS0101, and if the flattening route came back there
        // would be a namespace-root twin. Either failure shows up here.
        string[] strays = typeof(Aviary).Assembly
            .GetTypes()
            .Where(type => !type.IsNested && type.Namespace == "TestLibrary.Nested")
            .Where(type => type.Name is "Perch" or "Defaults" or "Kind" or "Keeper" or "IKeeper" or "Inner" or "Entry")
            .Select(type => type.FullName!)
            .OrderBy(name => name)
            .ToArray();

        Assert.Empty(strays);
    }

    [Fact]
    public void TheOwners_KeepBinding()
    {
        // The control: an implementation that drops the owners to make the children fit is
        // distinguishable from one that declares both.
        using var aviary = new Aviary("Oreo");

        Assert.Equal("aviary Oreo", aviary.Label);
        Assert.NotNull(typeof(Aviary).GetMethod("PerchAt", BindingFlags.Public | BindingFlags.Instance));
    }
}
