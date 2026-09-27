using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// A concretely typed property on a generic class keeps its own declared type (ADR-147), it is
/// never surfaced as <c>T</c>. <c>Slot</c> is instantiated at <c>int</c> and at <c>Cat</c> so
/// <c>T</c> never coincides with a concrete property's type: the window sill is Oreo's spot, two
/// cats share it, and Mylo keeps watch.
/// </summary>
public class GenericClassConcretePropertyTests
{
    [Fact]
    public void IntSlot_Label_IsString()
    {
        using var slot = new Slot<int>(42);
        string label = slot.Label;
        Assert.Equal("window sill", label);
    }

    [Fact]
    public void IntSlot_Count_IsInt()
    {
        using var slot = new Slot<int>(42);
        int count = slot.Count;
        Assert.Equal(2, count);
    }

    [Fact]
    public void IntSlot_Note_RoundTripsThroughSetter()
    {
        using var slot = new Slot<int>(42);
        Assert.Equal("Oreo napped here", slot.Note);
        slot.Note = "Mylo stole the spot";
        Assert.Equal("Mylo stole the spot", slot.Note);
    }

    [Fact]
    public void IntSlot_Keeper_IsCat()
    {
        using var slot = new Slot<int>(42);
        using Cat keeper = slot.Keeper;
        Assert.Equal("Mylo", keeper.Name);
        Assert.Equal(7, keeper.Lives);
    }

    [Fact]
    public void IntSlot_TypedPropertiesStillPassThrough()
    {
        using var slot = new Slot<int>(42);
        Assert.Equal(42, slot.Value);
        Assert.Equal(42, slot.Current);
    }

    [Fact]
    public void CatSlot_ConcretePropertiesKeepTheirOwnTypes()
    {
        using var oreo = new Cat("Oreo", 9);
        using var slot = new Slot<Cat>(oreo);
        Assert.Equal("window sill", slot.Label);
        Assert.Equal(2, slot.Count);
        slot.Note = "Oreo reclaimed it";
        Assert.Equal("Oreo reclaimed it", slot.Note);
    }

    [Fact]
    public void CatSlot_Keeper_IsNotTheTypeArgument()
    {
        using var oreo = new Cat("Oreo", 9);
        using var slot = new Slot<Cat>(oreo);
        using Cat keeper = slot.Keeper;
        using Cat current = slot.Current!;
        Assert.Equal("Mylo", keeper.Name);
        Assert.Equal(7, keeper.Lives);
        Assert.Equal("Oreo", current.Name);
    }

    [Theory]
    [InlineData(nameof(Slot<int>.Label), typeof(string))]
    [InlineData(nameof(Slot<int>.Count), typeof(int))]
    [InlineData(nameof(Slot<int>.Note), typeof(string))]
    [InlineData(nameof(Slot<int>.Keeper), typeof(Cat))]
    public void OpenSlot_ConcretePropertyType_IsNotTypeParameter(string name, Type expected)
    {
        var property = typeof(Slot<>).GetProperty(name)!;
        Assert.False(property.PropertyType.IsGenericParameter);
        Assert.Equal(expected, property.PropertyType);
    }

    [Fact]
    public void OpenSlot_Note_HasPublicSetter()
    {
        var property = typeof(Slot<>).GetProperty(nameof(Slot<int>.Note))!;
        Assert.NotNull(property.GetSetMethod());
    }

    [Fact]
    public void OpenSlot_Current_IsStillTypeParameter()
    {
        var property = typeof(Slot<>).GetProperty(nameof(Slot<int>.Current))!;
        Assert.True(property.PropertyType.IsGenericParameter);
    }
}
