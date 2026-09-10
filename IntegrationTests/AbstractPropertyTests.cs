using System.Reflection;
using TestLibrary.Orchestra;

namespace IntegrationTests;

/// <summary>
/// ADR-075: an exported base class's unimplemented `abstract val` / `abstract var` must render
/// as a C# abstract property, so a subclass's `override` compiles.
///
/// `Instrument` declares `abstract val family` and `abstract var tuning` plus a concrete
/// constructor property `name`; `Violin` overrides both abstract members. Today the property
/// route has no abstract path at all: the base member is planned and rendered as a concrete,
/// non-virtual property, so `Violin`'s `override` fails to compile (CS0506, not the CS0115 the
/// ROADMAP line predicted, which is the abstract-*method* symptom).
///
/// The compile is the real proof. The reflection facts pin the shape so a `virtual` base
/// property, which would also make the compile pass, does not quietly satisfy the item.
/// Oreo insists on retuning the thing every time he walks across it.
/// </summary>
public class AbstractPropertyTests
{
    [Fact]
    public void Violin_OverridesAbstractVal_ReadsThroughBothStaticTypes()
    {
        using var violin = new Violin();

        Assert.Equal("strings", violin.Family);
        Assert.Equal("strings", ((Instrument)violin).Family);
    }

    [Fact]
    public void Violin_OverridesAbstractVar_ReadsThroughBothStaticTypes()
    {
        using var violin = new Violin();

        Assert.Equal("G-D-A-E", violin.Tuning);
        Assert.Equal("G-D-A-E", ((Instrument)violin).Tuning);
    }

    [Fact]
    public void Violin_AbstractVarWrittenThroughTheBase_IsSeenOnTheDerivedType()
    {
        using var violin = new Violin();

        // Oreo walks the strings; the write goes through the abstract base's static type.
        ((Instrument)violin).Tuning = "G-D-A-Oreo";

        Assert.Equal("G-D-A-Oreo", violin.Tuning);
        Assert.Equal("G-D-A-Oreo", ((Instrument)violin).Tuning);
    }

    [Fact]
    public void Violin_AbstractVarWrittenThroughTheBase_IsSeenByKotlinDispatch()
    {
        using var violin = new Violin();

        Assert.Equal("violin (strings, tuned G-D-A-E)", violin.Describe());

        ((Instrument)violin).Tuning = "G-D-A-Mylo";

        // `describe()` is Kotlin's own dispatch through the overrides, so this proves the write
        // reached the Kotlin object rather than being echoed by the C# getter.
        Assert.Equal("violin (strings, tuned G-D-A-Mylo)", violin.Describe());
        Assert.Equal("violin (strings, tuned G-D-A-Mylo)", ((Instrument)violin).Describe());
    }

    [Fact]
    public void Instrument_RendersAbstract()
    {
        Assert.True(typeof(Instrument).IsAbstract);
    }

    [Fact]
    public void Instrument_AbstractVal_IsAbstractAndGetOnly()
    {
        PropertyInfo? family = typeof(Instrument).GetProperty("Family");

        Assert.NotNull(family);
        Assert.NotNull(family!.GetMethod);
        Assert.True(family.GetMethod!.IsAbstract);
        Assert.Null(family.SetMethod);
    }

    [Fact]
    public void Instrument_AbstractVar_IsAbstractOnBothAccessors()
    {
        PropertyInfo? tuning = typeof(Instrument).GetProperty("Tuning");

        Assert.NotNull(tuning);
        Assert.NotNull(tuning!.GetMethod);
        Assert.NotNull(tuning.SetMethod);
        Assert.True(tuning.GetMethod!.IsAbstract);
        Assert.True(tuning.SetMethod!.IsAbstract);
    }

    [Fact]
    public void Instrument_ConcreteVal_StaysNonAbstract()
    {
        PropertyInfo? name = typeof(Instrument).GetProperty("Name");

        Assert.NotNull(name);
        Assert.NotNull(name!.GetMethod);
        Assert.False(name.GetMethod!.IsAbstract);
    }

    [Fact]
    public void Violin_Overrides_RatherThanHidesTheAbstractMembers()
    {
        MethodInfo family = typeof(Violin).GetProperty("Family")!.GetMethod!;
        MethodInfo tuning = typeof(Violin).GetProperty("Tuning")!.SetMethod!;

        Assert.False(family.IsAbstract);
        Assert.False(tuning.IsAbstract);
        Assert.Equal(typeof(Instrument), family.GetBaseDefinition().DeclaringType);
        Assert.Equal(typeof(Instrument), tuning.GetBaseDefinition().DeclaringType);
    }

    [Fact]
    public void Violin_InheritsTheConcreteNameFromTheAbstractBase()
    {
        using var violin = new Violin();

        Assert.Equal("violin", violin.Name);
        Assert.Equal("violin", ((Instrument)violin).Name);
    }
}
