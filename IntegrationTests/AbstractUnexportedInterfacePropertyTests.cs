using System.Reflection;
using TestLibrary.Aviary;

namespace IntegrationTests;

/// <summary>
/// ADR-075 amendment (2026-09-13): an abstract `val`/`var` an exported abstract class inherits
/// from an <b>unexported</b> interface it does not implement must still reach C# as an abstract
/// property on that class.
///
/// `io.github.xxfast.kotlin.native.nuget.hidden.Nesting` is outside `rootPackage`, so ADR-101
/// drops it from `Nester`'s base list and the ADR-113 interface declaration catalog never holds a
/// plan for its members. `Nester` implements none of `material` / `height` / `lining`, so without
/// a fix the members vanish while `Wren` still renders `public override` and the generated
/// `Interop.cs` fails CS0115 on its own text -- before any consumer subclass gets a chance.
///
/// This is the unexported sibling of <see cref="AbstractInterfacePropertyTests"/> (`Bird` :
/// `Feathered`, exported). The consumer compile is the real proof: none of this file builds until
/// the shape is right. The reflection facts pin it so a concrete or `virtual` base property, which
/// would also make the compile pass, does not quietly satisfy the item, and so a green run cannot
/// be bought by accidentally *exporting* `Nesting`. Oreo supervises the twig one.
/// </summary>
public class AbstractUnexportedInterfacePropertyTests
{
    [Fact]
    public void Nester_RendersAbstract()
    {
        Assert.True(typeof(Nester).IsAbstract);
    }

    [Fact]
    public void Nester_InheritedVal_IsAbstractAndGetOnly()
    {
        PropertyInfo? material = typeof(Nester).GetProperty("Material");

        Assert.NotNull(material);
        Assert.NotNull(material!.GetGetMethod());
        Assert.True(material.GetGetMethod()!.IsAbstract);
        Assert.Null(material.GetSetMethod());
    }

    [Fact]
    public void Nester_InheritedVar_IsAbstractOnBothAccessors()
    {
        PropertyInfo? height = typeof(Nester).GetProperty("Height");

        Assert.NotNull(height);
        Assert.NotNull(height!.GetGetMethod());
        Assert.NotNull(height.GetSetMethod());
        Assert.True(height.GetGetMethod()!.IsAbstract);
        Assert.True(height.GetSetMethod()!.IsAbstract);
    }

    /// <summary>
    /// `Nesting.lining` is typed with a nested class, which no property getter can bridge. The
    /// member must be skipped named (`SKIPPED_UNSUPPORTED_PROPERTY`), leaving no C# member on the
    /// abstract class or on the concrete subclass, and above all not crashing the processor.
    /// </summary>
    [Fact]
    public void UnbridgeableInheritedProperty_IsAbsentFromBothClasses()
    {
        Assert.Null(typeof(Nester).GetProperty("Lining"));
        Assert.Null(typeof(Wren).GetProperty("Lining"));
    }

    /// <summary>
    /// The guard against a green run bought by exporting the interface after all: `Nesting` is
    /// unexported, so no `INesting` may exist on the class (ADR-101's `SKIPPED_UNEXPORTED_SUPERTYPE`).
    /// If this ever fails, the fixture stopped exercising the unexported case.
    /// </summary>
    [Fact]
    public void Nester_CarriesNoInterfaceForTheUnexportedSupertype()
    {
        Assert.DoesNotContain(typeof(Nester).GetInterfaces(), i => i.Name == "INesting");
    }

    [Fact]
    public void Wren_Overrides_RatherThanHidesTheInheritedMembers()
    {
        MethodInfo material = typeof(Wren).GetProperty("Material")!.GetGetMethod()!;
        MethodInfo height = typeof(Wren).GetProperty("Height")!.GetSetMethod()!;

        Assert.False(material.IsAbstract);
        Assert.False(height.IsAbstract);
        Assert.Equal(typeof(Nester), material.GetBaseDefinition().DeclaringType);
        Assert.Equal(typeof(Nester), height.GetBaseDefinition().DeclaringType);
    }

    [Fact]
    public void Wren_ReadThroughANesterTypedReference_SeesTheOverride()
    {
        using var wren = new Wren();

        Nester nester = wren;

        Assert.Equal("twig", nester.Material);
        Assert.Equal(3, nester.Height);
    }

    [Fact]
    public void Wren_HeightWrittenThroughTheBase_IsSeenByKotlinDispatch()
    {
        using var wren = new Wren();

        Nester nester = wren;

        Assert.Equal("twig@3", nester.Describe());

        // Mylo sat on the nest, so it settled by a couple of twigs.
        nester.Height = 5;

        // `describe()` is Kotlin's own dispatch through the overrides, so this proves the write
        // reached the Kotlin object rather than being echoed by the C# getter.
        Assert.Equal(5, wren.Height);
        Assert.Equal("twig@5", nester.Describe());
        Assert.Equal("twig@5", wren.Describe());
    }

    /// <summary>
    /// The literal ROADMAP case: a subclass written in C#. It compiles only because `Interop.cs`
    /// ships as NuGet `contentFiles` and is `&lt;Compile Include&gt;`d into this assembly, so the
    /// generated `internal Nester(IntPtr)` constructor is in-assembly and reachable. The abstract
    /// members it has to satisfy are exactly `Material`, `Height` and the generated
    /// `public abstract void Dispose()`.
    ///
    /// Read-only on purpose: the handle is `IntPtr.Zero`, there is no Kotlin object behind it, so
    /// `Describe()` must never be called on a `PaperWren`.
    /// </summary>
    private sealed class PaperWren : Nester
    {
        public PaperWren() : base(IntPtr.Zero)
        {
        }

        public override string Material => "paper";

        public override int Height { get; set; } = 1;

        public override void Dispose()
        {
        }
    }

    [Fact]
    public void PureCSharpSubclass_OverridesTheInheritedMembers()
    {
        using Nester nester = new PaperWren();

        Assert.Equal("paper", nester.Material);
        Assert.Equal(1, nester.Height);

        nester.Height = 2;

        Assert.Equal(2, nester.Height);
    }
}
