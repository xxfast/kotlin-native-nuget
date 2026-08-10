using TestLibrary.Cat;

namespace IntegrationTests;

public class NullableGenericPropertyTests
{
    [Fact]
    public void Slot_String_NullPrevious()
    {
        using var slot = new Slot<string>("hi");
        Assert.Null(slot.Previous);
    }

    [Fact]
    public void Slot_String_PassesCurrentThrough()
    {
        using var slot = new Slot<string>("hi");
        Assert.Equal("hi", slot.Current);
    }

    [Fact]
    public void Slot_Cat_NullPrevious()
    {
        using var oreo = new Cat("Oreo", 9);
        using var slot = new Slot<Cat>(oreo);
        Assert.Null(slot.Previous);
    }

    // Unconstrained generics in C# collapse a null T to default(T), so a value-type
    // instantiation sees 0 where the reference-type ones see null. Mylo gets 42 naps.
    [Fact]
    public void Slot_Int_PreviousCollapsesToDefault()
    {
        using var slot = new Slot<int>(42);
        Assert.Equal(0, slot.Previous);
    }

    [Fact]
    public void Slot_Int_PassesCurrentThrough()
    {
        using var slot = new Slot<int>(42);
        Assert.Equal(42, slot.Current);
    }
}
