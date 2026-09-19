using System.Reflection;
using TestLibrary.Cat;
using TestLibrary.Garage;

namespace IntegrationTests;

/// <summary>
/// ADR-075 / ADR-101 amendment: the abstract method walk keys on whether the Kotlin member has a
/// body, not on which class declares it. Two halves, one predicate.
///
/// Half A, <c>Vehicle</c>: a class's own `abstract fun honk()` has no body, so it renders
/// `public abstract string Honk();` and <c>Truck</c>'s `override` compiles. Before the fix the walk
/// treated a class-declared `abstract fun` as implemented and dropped it, so `Truck.Honk` was
/// `public override` against nothing (CS0115).
///
/// Half B, <c>Vault</c>: `Register.tally` is a generic interface default the planner declines
/// structurally. It still has a body, so it must be dropped from <c>Vault</c> rather than rendered
/// `public abstract T Tally(T)` (CS0246 in the generated file, CS0534 on any C# subclass).
/// `: IRegister` stays, and <c>StrongRoom</c> needs no C# subclass to prove it: the generated
/// <c>StrongRoom</c> is itself the concrete leaf.
///
/// Mylo is the truck. He honks at 5am and it is not a request.
/// </summary>
public class AbstractMethodTests
{
    [Fact]
    public void Truck_OverridesTheAbstractFun_ReadsThroughBothStaticTypes()
    {
        using var truck = new Truck("T1");

        Assert.Equal("HONK", truck.Honk());
        Assert.Equal("HONK", ((Vehicle)truck).Honk());
    }

    [Fact]
    public void Truck_ConcreteBaseMethod_DispatchesBackThroughTheKotlinOverride()
    {
        using var truck = new Truck("T1");

        // `describe()` is Kotlin's own body on the abstract base calling `honk()`, so this proves
        // the override is wired on the Kotlin object, not just on the C# facade.
        Assert.Equal("T1 says HONK", truck.Describe());
        Assert.Equal("T1 says HONK", ((Vehicle)truck).Describe());
    }

    [Fact]
    public void Truck_ConcreteBaseProperty_ReadsThroughBothStaticTypes()
    {
        using var truck = new Truck("T1");

        Assert.Equal("T1", truck.Plate);
        Assert.Equal("T1", ((Vehicle)truck).Plate);
    }

    [Fact]
    public void Vehicle_DeclaredAbstractFun_RendersAbstract()
    {
        MethodInfo? honk = typeof(Vehicle).GetMethod("Honk");

        Assert.NotNull(honk);
        Assert.True(honk!.IsAbstract);
        Assert.Equal(typeof(string), honk.ReturnType);
    }

    [Fact]
    public void Vehicle_ConcreteMethod_StaysNonAbstract()
    {
        MethodInfo? describe = typeof(Vehicle).GetMethod("Describe");

        Assert.NotNull(describe);
        Assert.False(describe!.IsAbstract);
    }

    [Fact]
    public void Truck_Overrides_RatherThanHidesTheAbstractFun()
    {
        MethodInfo honk = typeof(Truck).GetMethod("Honk")!;

        Assert.False(honk.IsAbstract);
        Assert.Equal(typeof(Vehicle), honk.GetBaseDefinition().DeclaringType);
    }

    [Fact]
    public void Vault_DeclinedInheritedDefault_IsDroppedRatherThanRenderedAbstract()
    {
        Assert.Null(typeof(Vault).GetMethod("Tally"));
    }

    [Fact]
    public void Vault_StaysAbstractAndKeepsTheInterface()
    {
        Assert.True(typeof(Vault).IsAbstract);
        Assert.Contains(typeof(IRegister), typeof(Vault).GetInterfaces());
    }

    [Fact]
    public void StrongRoom_IsTheConcreteLeafUnderVault()
    {
        Assert.Equal(typeof(Vault), typeof(StrongRoom).BaseType);
        Assert.False(typeof(StrongRoom).IsAbstract);

        // No C# subclass needed: nothing unimplemented is inherited, so this just constructs.
        using var strongRoom = new StrongRoom();

        Assert.IsAssignableFrom<Vault>(strongRoom);
    }

    // --- The walk spells a class-typed position, or refuses it named (Hauler) ---

    /// <summary>
    /// Cell (a): the abstract return is <c>cat.Toy</c>, exported from another namespace. A bare
    /// <c>Toy</c> in the generated <c>TestLibrary.Garage</c> file names nothing (CS0246), so this
    /// asserting at all means the walk spelled it fully qualified.
    /// </summary>
    [Fact]
    public void Hauler_AbstractReturn_TypedWithAnExportedClassFromAnotherNamespace()
    {
        using var hauler = new CatHauler("H1");

        using Toy cargo = hauler.Cargo();
        using Toy viaBase = ((Hauler)hauler).Cargo();

        Assert.IsType<Toy>(cargo);
        Assert.Equal("Catnip Banana", cargo.Name);
        Assert.Equal("Green", viaBase.Color);
    }

    [Fact]
    public void Hauler_AbstractReturn_IsDeclaredAbstractWithTheCrossNamespaceType()
    {
        MethodInfo? cargo = typeof(Hauler).GetMethod("Cargo");

        Assert.NotNull(cargo);
        Assert.True(cargo!.IsAbstract);
        Assert.Equal(typeof(Toy), cargo.ReturnType);
    }

    /// <summary>
    /// Cell (b): the nested <c>Hitch.Pin</c> at a parameter AND at the return. The parameter arm is
    /// a separate hand-spelling from the return arm, so the round trip is what keeps a return-only
    /// fix red.
    /// </summary>
    [Fact]
    public void Hauler_AbstractNestedTypedParameter_RoundTrips()
    {
        using var hauler = new CatHauler("H1");
        using var pin = new Hitch.Pin("tow");

        using Hitch.Pin latched = hauler.Latch(pin);
        using Hitch.Pin viaBase = ((Hauler)hauler).Latch(pin);

        Assert.Equal("tow-latched-to-H1", latched.Label);
        Assert.Equal("tow-latched-to-H1", viaBase.Label);
    }

    [Fact]
    public void Hauler_AbstractNestedTypedMethod_UsesTheNestedTypeAtBothPositions()
    {
        MethodInfo? latch = typeof(Hauler).GetMethod("Latch");
        Type pin = typeof(Hitch).GetNestedType("Pin")!;

        Assert.NotNull(latch);
        Assert.True(latch!.IsAbstract);
        Assert.Equal(pin, latch.ReturnType);
        Assert.Equal(pin, Assert.Single(latch.GetParameters()).ParameterType);
    }

    /// <summary>
    /// Cell (c): <c>padding()</c> returns <c>hidden.Nesting.Lining</c>, which nothing declares in
    /// C#. No surface at all is the contract, so reflection is the only way to assert it: a bare
    /// <c>Lining Padding()</c> would not compile, and a fully qualified one cannot exist.
    /// </summary>
    [Fact]
    public void Hauler_AbstractReturn_TypedWithAnUnexportedClass_IsDroppedRatherThanSpelled()
    {
        Assert.Null(typeof(Hauler).GetMethod("Padding"));
        Assert.Null(typeof(CatHauler).GetMethod("Padding"));
    }

    [Fact]
    public void CatHauler_IsTheConcreteLeafUnderHauler()
    {
        Assert.True(typeof(Hauler).IsAbstract);
        Assert.Equal(typeof(Hauler), typeof(CatHauler).BaseType);
        Assert.False(typeof(CatHauler).IsAbstract);
        Assert.False(typeof(CatHauler).GetMethod("Cargo")!.IsAbstract);
    }
}
