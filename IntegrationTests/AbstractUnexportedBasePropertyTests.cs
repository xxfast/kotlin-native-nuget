using System.Reflection;
using TestLibrary.Lounge;

namespace IntegrationTests;

/// <summary>
/// ADR-075 amendment, base-class owner: an abstract `val`/`var` an exported abstract class inherits
/// from an <b>unexported abstract base class</b> it does not implement must still reach C# as an
/// abstract property on that class.
///
/// `io.github.xxfast.kotlin.native.nuget.hidden.Cushion` is outside `rootPackage`, so ADR-101 drops
/// it from `Lounger`'s base list and the ADR-113 declaration catalog (planned over interfaces only)
/// never holds a plan for its members. `Lounger` implements none of `weave` / `loft` / `stuffing`,
/// so without a fix the members vanish while `Beanbag` still renders `public override` and the
/// generated `Interop.cs` fails CS0115 on its own text, before any consumer subclass gets a chance.
///
/// This is the base-class sibling of <see cref="AbstractUnexportedInterfacePropertyTests"/>
/// (`Nester` : `Nesting`). The consumer compile is the real proof: none of this file builds until
/// the shape is right. The reflection facts pin it so a concrete or `virtual` base property, which
/// would also make the compile pass, does not quietly satisfy the item, and so a green run cannot
/// be bought by accidentally *exporting* `Cushion`. Mylo supervises the corduroy one.
/// </summary>
public class AbstractUnexportedBasePropertyTests
{
    [Fact]
    public void Lounger_RendersAbstract()
    {
        Assert.True(typeof(Lounger).IsAbstract);
    }

    [Fact]
    public void Lounger_InheritedVal_IsAbstractAndGetOnly()
    {
        PropertyInfo? weave = typeof(Lounger).GetProperty("Weave");

        Assert.NotNull(weave);
        Assert.NotNull(weave!.GetGetMethod());
        Assert.True(weave.GetGetMethod()!.IsAbstract);
        Assert.Null(weave.GetSetMethod());
    }

    [Fact]
    public void Lounger_InheritedVar_IsAbstractOnBothAccessors()
    {
        PropertyInfo? loft = typeof(Lounger).GetProperty("Loft");

        Assert.NotNull(loft);
        Assert.NotNull(loft!.GetGetMethod());
        Assert.NotNull(loft.GetSetMethod());
        Assert.True(loft.GetGetMethod()!.IsAbstract);
        Assert.True(loft.GetSetMethod()!.IsAbstract);
    }

    /// <summary>
    /// The control. The abstract-method walk carries no owner-kind guard, so `Cushion.squish`
    /// already renders `public abstract string Squish();` on `Lounger`. It sits in this file so the
    /// property half and the method half of the same unexported base are proved by one run, and so
    /// a fix that "declares" the properties by some second spelling cannot disagree with it.
    /// </summary>
    [Fact]
    public void Lounger_InheritedAbstractMethod_StaysAbstract()
    {
        MethodInfo? squish = typeof(Lounger).GetMethod("Squish");

        Assert.NotNull(squish);
        Assert.True(squish!.IsAbstract);
        Assert.Equal(typeof(Lounger), typeof(Beanbag).GetMethod("Squish")!.GetBaseDefinition().DeclaringType);
    }

    /// <summary>
    /// `Cushion.stuffing` is typed with a nested class, which no property getter can bridge. The
    /// member must be skipped named (`SKIPPED_UNSUPPORTED_PROPERTY`), leaving no C# member on the
    /// abstract class or on the concrete subclass, and above all not crashing the processor.
    /// </summary>
    [Fact]
    public void UnbridgeableInheritedProperty_IsAbsentFromBothClasses()
    {
        Assert.Null(typeof(Lounger).GetProperty("Stuffing"));
        Assert.Null(typeof(Beanbag).GetProperty("Stuffing"));
    }

    /// <summary>
    /// The guard against a green run bought by exporting the base after all: `Cushion` is
    /// unexported, so ADR-101 leaves `Lounger` with no base at all
    /// (`SKIPPED_UNEXPORTED_SUPERTYPE`). If this ever fails, the fixture stopped exercising the
    /// unexported case.
    /// </summary>
    [Fact]
    public void Lounger_CarriesNoBaseTypeForTheUnexportedSupertype()
    {
        Assert.Equal(typeof(object), typeof(Lounger).BaseType);
    }

    [Fact]
    public void Beanbag_Overrides_RatherThanHidesTheInheritedMembers()
    {
        MethodInfo weave = typeof(Beanbag).GetProperty("Weave")!.GetGetMethod()!;
        MethodInfo loft = typeof(Beanbag).GetProperty("Loft")!.GetSetMethod()!;

        Assert.False(weave.IsAbstract);
        Assert.False(loft.IsAbstract);
        Assert.Equal(typeof(Lounger), weave.GetBaseDefinition().DeclaringType);
        Assert.Equal(typeof(Lounger), loft.GetBaseDefinition().DeclaringType);
    }

    [Fact]
    public void Beanbag_ReadThroughALoungerTypedReference_SeesTheOverride()
    {
        using var beanbag = new Beanbag();

        Lounger lounger = beanbag;

        Assert.Equal("corduroy", lounger.Weave);
        Assert.Equal(4, lounger.Loft);
    }

    [Fact]
    public void Beanbag_LoftWrittenThroughTheBase_IsSeenByKotlinDispatch()
    {
        using var beanbag = new Beanbag();

        Lounger lounger = beanbag;

        Assert.Equal("corduroy@4", lounger.Describe());
        Assert.Equal("corduroy squished from 4", lounger.Squish());

        // Oreo flopped on it, so it lost a good deal of its loft.
        lounger.Loft = 1;

        // `describe()` and `squish()` are Kotlin's own dispatch through the overrides, so this
        // proves the write reached the Kotlin object rather than being echoed by the C# getter.
        Assert.Equal(1, beanbag.Loft);
        Assert.Equal("corduroy@1", lounger.Describe());
        Assert.Equal("corduroy squished from 1", beanbag.Squish());
    }

    /// <summary>
    /// The literal ROADMAP case: a subclass written in C#. It compiles only because `Interop.cs`
    /// ships as NuGet `contentFiles` and is `&lt;Compile Include&gt;`d into this assembly, so the
    /// generated `internal Lounger(IntPtr, out NugetHandleTag)` constructor is in-assembly and reachable. The abstract
    /// members it has to satisfy are exactly `Weave`, `Loft`, `Squish()` and the generated
    /// `public abstract void Dispose()`.
    ///
    /// Read-only on purpose: the handle is `IntPtr.Zero`, there is no Kotlin object behind it, so
    /// `Describe()` must never be called on a `PaperBeanbag`.
    /// </summary>
    private sealed class PaperBeanbag : Lounger
    {
        public PaperBeanbag() : base(IntPtr.Zero, out _)
        {
        }

        public override string Weave => "newsprint";

        public override int Loft { get; set; } = 1;

        public override string Squish() => "newsprint crumples";

        public override void Dispose()
        {
        }
    }

    [Fact]
    public void PureCSharpSubclass_OverridesTheInheritedMembers()
    {
        using Lounger lounger = new PaperBeanbag();

        Assert.Equal("newsprint", lounger.Weave);
        Assert.Equal(1, lounger.Loft);
        Assert.Equal("newsprint crumples", lounger.Squish());

        lounger.Loft = 2;

        Assert.Equal(2, lounger.Loft);
    }
}
