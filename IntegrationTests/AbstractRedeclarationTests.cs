using System;
using System.Reflection;
using TestLibrary.Abstractredeclaration;

namespace IntegrationTests;

public class AbstractRedeclarationTests
{
    [Theory]
    [InlineData("Sail")]
    [InlineData("Height")]
    public void RehomedAbstractProperty_OverridesKeptBaseSlot(string name)
    {
        PropertyInfo property = typeof(RedefinedDinghy).GetProperty(name,
            BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly)!;
        Assert.NotNull(property);
        foreach (MethodInfo accessor in property.GetAccessors())
        {
            Assert.True(accessor.IsAbstract);
            Assert.Same(typeof(RedefinedVessel), accessor.GetBaseDefinition().DeclaringType);
            Assert.Equal((MethodAttributes)0, accessor.Attributes & MethodAttributes.NewSlot);
        }
    }

    [Fact]
    public void FreshAbstractProperty_KeepsItsOwnSlot()
    {
        MethodInfo getter = typeof(RedefinedDinghy).GetProperty("Rigging",
            BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly)!.GetMethod!;
        Assert.True(getter.IsAbstract);
        Assert.Same(typeof(RedefinedDinghy), getter.GetBaseDefinition().DeclaringType);
        Assert.NotEqual((MethodAttributes)0, getter.Attributes & MethodAttributes.NewSlot);
        Assert.DoesNotContain(typeof(RedefinedVessel).Assembly.GetTypes(),
            type => type.Name == "RedefinedSkiff");
    }

    [Fact]
    public void ConcreteSubclass_ReadsAndWritesThroughBothKeptBases()
    {
        using var oreo = new RedefinedRowboat();
        RedefinedDinghy dinghy = oreo;
        RedefinedVessel vessel = oreo;
        Assert.Equal("Oreo's canvas", dinghy.Sail);
        Assert.Equal(dinghy.Sail, vessel.Sail);
        vessel.Height = 8;
        Assert.Equal(8, dinghy.Height);
        dinghy.Height = 12;
        Assert.Equal(12, vessel.Height);
        Assert.Equal("Mylo's rope", dinghy.Rigging);
    }

    [Fact]
    public void AbstractIntermediate_DisposeOverridesTheRetainedBaseSlot()
    {
        MethodInfo method = typeof(RedefinedDinghy).GetMethod("Dispose",
            BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly)!;
        Assert.NotNull(method);
        Assert.True(method.IsAbstract);
        Assert.Same(typeof(RedefinedVessel), method.GetBaseDefinition().DeclaringType);
        Assert.Equal((MethodAttributes)0, method.Attributes & MethodAttributes.NewSlot);
    }
}