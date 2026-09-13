using System;
using System.Linq;
using System.Reflection;
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
