using System.Reflection;
using TestLibrary.Perchvar;

namespace IntegrationTests;

/// <summary>
/// ROADMAP line 28 / ADR-113: a `var` on an exported Kotlin interface must render
/// `{ get; set; }` on the generated C# interface, not `{ get; }`. `Tally` declares four plannable
/// `var`s, one per setter shape (a value, a string, a nullable exported handle, a collection), and
/// one `var lastSlip: Throwable?` whose setter ADR-107 refuses, so `ITally.LastSlip` must stay
/// `{ get; }` while the rest widen.
///
/// Three Kotlin-implemented doors reach the same setters:
/// <list type="bullet">
/// <item>`Abacus : Tally`, the ordinary implementer: its public `virtual` setters satisfy `ITally`
/// implicitly.</item>
/// <item>`TrainingClicker : Scoreboard(), Tally`, case D: one `override var` satisfies both
/// `Scoreboard`'s `open val` and `Tally`'s `var`. The public `Count` has to stay a get-only
/// `override` (CS0546 forbids adding a setter to it), so the setter is reachable only through an
/// explicit `ITally.Count` implementation beside it. Without that, the widened `ITally` is CS0535
/// on `TrainingClicker`.</item>
/// <item>`TrainingClicker.AsTally()`, the ADR-040 backing wrapper: the same Kotlin object seen only
/// as `ITally`.</item>
/// </list>
///
/// Every write is read back through `Describe()`, which is Kotlin's own dispatch, so the value is
/// observed by Kotlin rather than echoed by a C# getter. These tests do not compile until
/// `ITally` carries setters: writing `tally.Count = 5` against `int Count { get; }` is CS0200.
///
/// Mylo is doing clicker training. Oreo keeps knocking the clicker off the desk.
/// </summary>
public class InterfaceVarPropertyTests
{
    // --- The interface declaration ---

    [Theory]
    [InlineData("Count")]
    [InlineData("Label")]
    [InlineData("Toy")]
    [InlineData("Names")]
    public void ITally_PlannableVar_HasASetter(string name)
    {
        PropertyInfo? property = typeof(ITally).GetProperty(name);

        Assert.NotNull(property);
        Assert.NotNull(property!.GetMethod);
        Assert.NotNull(property.SetMethod);
    }

    [Fact]
    public void ITally_ThrowableVar_StaysGetOnly()
    {
        // Present first, so this goes green on the feature and not on a total drop of the member.
        PropertyInfo? lastSlip = typeof(ITally).GetProperty("LastSlip");

        Assert.NotNull(lastSlip);
        Assert.NotNull(lastSlip!.GetMethod);
        Assert.Null(lastSlip.SetMethod);
    }

    // --- Case D: TrainingClicker, a read-only base plus an interface var ---

    [Fact]
    public void TrainingClicker_CountWrittenThroughITally_IsSeenByKotlin()
    {
        using var clicker = new TrainingClicker();

        ITally tally = clicker;
        tally.Count = 5;

        Assert.Equal(5, tally.Count);
        Assert.Equal(5, clicker.Count);
        Assert.Equal("clicker: 5 clicks for Mylo with no pompom", clicker.Describe());
    }

    [Fact]
    public void TrainingClicker_LabelWrittenThroughITally_IsSeenByKotlin()
    {
        using var clicker = new TrainingClicker();

        ITally tally = clicker;
        tally.Label = "Mylo's treat clicker";

        Assert.Equal("Mylo's treat clicker", clicker.Label);
        Assert.Equal("Mylo's treat clicker: 0 clicks for Mylo with no pompom", clicker.Describe());
    }

    [Fact]
    public void TrainingClicker_ToyWrittenThroughITally_IsSeenByKotlin_AndClearsBackToNull()
    {
        using var clicker = new TrainingClicker();
        using var pompom = new Pompom("orange");

        ITally tally = clicker;
        tally.Toy = pompom;

        Assert.Equal("clicker: 0 clicks for Mylo with orange pompom", clicker.Describe());
        using (Pompom? toy = clicker.Toy)
        {
            Assert.Equal("orange", toy!.Colour);
        }

        // Oreo batted the pompom under the sofa.
        tally.Toy = null;

        Assert.Null(clicker.Toy);
        Assert.Equal("clicker: 0 clicks for Mylo with no pompom", clicker.Describe());
    }

    [Fact]
    public void TrainingClicker_NamesWrittenThroughITally_IsSeenByKotlin()
    {
        using var clicker = new TrainingClicker();

        ITally tally = clicker;
        tally.Names = new[] { "Mylo", "Oreo" };

        Assert.Equal(new[] { "Mylo", "Oreo" }, clicker.Names.ToArray());
        Assert.Equal("clicker: 0 clicks for Mylo, Oreo with no pompom", clicker.Describe());
    }

    [Theory]
    [InlineData("Count")]
    [InlineData("Label")]
    [InlineData("Toy")]
    [InlineData("Names")]
    [InlineData("LastSlip")]
    public void TrainingClicker_PublicProperty_StaysAGetOnlyOverrideOfTheBase(string name)
    {
        PropertyInfo? property = typeof(TrainingClicker).GetProperty(name);

        Assert.NotNull(property);
        Assert.Null(property!.SetMethod);
        Assert.Equal(typeof(TrainingClicker), property.GetMethod!.DeclaringType);
        Assert.Equal(typeof(Scoreboard), property.GetMethod.GetBaseDefinition().DeclaringType);
    }

    [Theory]
    [InlineData("Count")]
    [InlineData("Label")]
    [InlineData("Toy")]
    [InlineData("Names")]
    public void TrainingClicker_ITallySetter_IsAnExplicitImplementation(string name)
    {
        MethodInfo target = InterfaceTarget(typeof(TrainingClicker), "set_" + name);

        Assert.Equal(typeof(TrainingClicker), target.DeclaringType);
        Assert.True(target.IsPrivate);
        Assert.EndsWith("ITally.set_" + name, target.Name);
    }

    [Fact]
    public void TrainingClicker_ThrowableVar_GetsNoExplicitImplementation()
    {
        // `ITally.LastSlip` has no setter, so the public get-only override already satisfies it.
        // An explicit member here would mean case D fired on the class's refusal alone.
        MethodInfo getter = InterfaceTarget(typeof(TrainingClicker), "get_LastSlip");

        Assert.True(getter.IsPublic);
        Assert.Equal("get_LastSlip", getter.Name);
        Assert.DoesNotContain(
            typeof(TrainingClicker).GetProperties(BindingFlags.Instance | BindingFlags.NonPublic | BindingFlags.DeclaredOnly),
            p => p.Name.EndsWith("ITally.LastSlip"));
    }

    [Fact]
    public void TrainingClicker_ThrowableVar_IsReadableThroughITally_AfterKotlinSetsIt()
    {
        using var clicker = new TrainingClicker();

        ITally tally = clicker;
        Assert.Null(tally.LastSlip);

        clicker.Slip();

        Assert.Equal("Oreo knocked the clicker off the desk", tally.LastSlip!.Message);
    }

    // --- The ordinary implementer: Abacus ---

    [Fact]
    public void Abacus_WrittenThroughITally_IsSeenByKotlin()
    {
        using var abacus = new Abacus();
        using var pompom = new Pompom("grey");

        ITally tally = abacus;
        tally.Count = 3;
        tally.Label = "Oreo's abacus";
        tally.Toy = pompom;
        tally.Names = new[] { "Oreo", "Mylo" };

        Assert.Equal("Oreo's abacus: 3 clicks for Oreo, Mylo with grey pompom", abacus.Describe());

        tally.Toy = null;

        Assert.Equal("Oreo's abacus: 3 clicks for Oreo, Mylo with no pompom", abacus.Describe());
    }

    [Theory]
    [InlineData("Count")]
    [InlineData("Label")]
    [InlineData("Toy")]
    [InlineData("Names")]
    public void Abacus_ITallySetter_IsTheImplicitPublicSetter(string name)
    {
        // The control for the case D cell: with no read-only base in the way, the explicit form
        // must not appear. The public setter is what implements the interface.
        MethodInfo target = InterfaceTarget(typeof(Abacus), "set_" + name);

        Assert.True(target.IsPublic);
        Assert.Equal("set_" + name, target.Name);
        Assert.NotNull(typeof(Abacus).GetProperty(name)!.SetMethod);
    }

    [Fact]
    public void Abacus_ThrowableVar_StaysGetOnlyOnTheClass()
    {
        PropertyInfo? lastSlip = typeof(Abacus).GetProperty("LastSlip");

        Assert.NotNull(lastSlip);
        Assert.Null(lastSlip!.SetMethod);
    }

    // --- The ADR-040 backing wrapper: AsTally() ---

    [Fact]
    public void AsTally_WrittenThroughTheBackingWrapper_IsSeenByTheSameKotlinObject()
    {
        using var clicker = new TrainingClicker();
        using var pompom = new Pompom("pink");
        using ITally tally = clicker.AsTally();

        tally.Count = 7;
        tally.Label = "the desk clicker";
        tally.Toy = pompom;
        tally.Names = new[] { "Mylo" };

        Assert.Equal(7, tally.Count);
        Assert.Equal("the desk clicker: 7 clicks for Mylo with pink pompom", clicker.Describe());

        tally.Toy = null;

        Assert.Equal("the desk clicker: 7 clicks for Mylo with no pompom", clicker.Describe());
    }

    private static MethodInfo InterfaceTarget(Type implementer, string interfaceMethodName)
    {
        InterfaceMapping map = implementer.GetInterfaceMap(typeof(ITally));
        int index = Array.FindIndex(map.InterfaceMethods, m => m.Name == interfaceMethodName);

        Assert.True(index >= 0, $"ITally declares no {interfaceMethodName}");
        return map.TargetMethods[index];
    }
}
