using System;
using TestLibrary.Orchard;

namespace IntegrationTests;

/// <summary>
/// Issue #613: an open Kotlin class with subclasses nested inside it. The nested C# subclasses must
/// keep their nesting (<c>Fruit.Apple</c>) and the bindings must compile with warnings as errors,
/// which a redeclared <c>Native_Create</c>/<c>Native_Dispose</c> (CS0108) breaks.
/// </summary>
public class OrchardTests
{
    [Fact]
    public void Apple_IsNestedInsideFruit()
    {
        Assert.Same(typeof(Fruit), typeof(Fruit.Apple).DeclaringType);
        Assert.Same(typeof(Fruit), typeof(Fruit.Apple).BaseType);
        Assert.Same(typeof(Fruit), typeof(Fruit.Pear).DeclaringType);
        Assert.Null(typeof(Quince).DeclaringType);
    }

    [Fact]
    public void Apple_ConstructsWithItsOwnDefaultName()
    {
        using var oreosApple = new Fruit.Apple();
        Assert.Equal("apple", oreosApple.Name);
    }

    [Fact]
    public void Apple_ConstructsWithAName()
    {
        using var myloSnack = new Fruit.Apple("Mylo's milky apple");
        Assert.Equal("Mylo's milky apple", myloSnack.Name);
    }

    [Fact]
    public void Apple_ConstructsWithANullName()
    {
        using var nameless = new Fruit.Apple(null);
        Assert.Null(nameless.Name);
    }

    [Fact]
    public void Fruit_BaseStillConstructs()
    {
        using var plain = new Fruit();
        Assert.Null(plain.Name);
        Assert.Equal("a fruit ripens slowly", plain.Ripen());
        Assert.Equal("green", plain.Colour);
    }

    [Fact]
    public void Apple_OverrideDispatchesThroughAFruitReference()
    {
        using var apple = new Fruit.Apple();
        Fruit fruit = apple;
        Assert.Equal("Oreo bats the apple off the counter", fruit.Ripen());
        Assert.Equal("red", fruit.Colour);
    }

    [Fact]
    public void Pick_ReturnsAnAppleTypedAsFruit()
    {
        using Fruit picked = OrchardSample.Pick();
        Assert.Equal("apple", picked.Name);
        Assert.Equal("Oreo bats the apple off the counter", picked.Ripen());
    }

    [Fact]
    public void Pear_HasItsOwnConstructorShape()
    {
        using var ripe = new Fruit.Pear(4);
        using var hard = new Fruit.Pear(1);
        Assert.Equal("pear", ripe.Name);
        Assert.Equal(4, ripe.Ripeness);
        Assert.True(ripe.ForMylo());
        Assert.False(hard.ForMylo());
        Assert.Equal("pear ripens slowly", ripe.Ripen());
    }

    [Fact]
    public void Basket_RoundTripsAnApple()
    {
        using var apple = new Fruit.Apple("Oreo's black-and-white apple");
        using var basket = new Basket(apple);
        using Fruit.Apple carried = basket.Apple;
        Assert.Equal("Oreo's black-and-white apple", carried.Name);
        Assert.Equal("red", carried.Colour);
    }

    [Fact]
    public void Quince_TopLevelSubclassStillWorks()
    {
        using var quince = new Quince();
        Fruit fruit = quince;
        Assert.Equal("quince", fruit.Name);
        Assert.Equal("quince ripens slowly", fruit.Ripen());
        Assert.Equal("green", fruit.Colour);
    }

    [Fact]
    public void BlightException_IsNestedInsideOrchardException()
    {
        Assert.Same(typeof(OrchardException), typeof(OrchardException.BlightException).DeclaringType);
        Assert.Same(typeof(OrchardException), typeof(OrchardException.BlightException).BaseType);
    }

    [Fact]
    public void Inspect_ReturnsTheNestedExceptionAsAHandle()
    {
        using OrchardException.BlightException blight = OrchardSample.Inspect();
        Assert.NotNull(blight);
        Assert.IsAssignableFrom<IDisposable>(blight);
    }
}
