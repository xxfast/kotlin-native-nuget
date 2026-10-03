using System;
using TestLibrary;
using TestLibrary.Models;

namespace IntegrationTests;

public class GenericDependencyOwnerTests
{
    [Fact]
    public void OwnerReachedOnlyThroughLid_IsDeclaredOnceAtNamespaceLevel()
    {
        Type owner = Assert.Single(typeof(ParcelDesk).Assembly.GetTypes(),
            type => type.FullName == "TestLibrary.Models.Parcel`1");
        Assert.Same(typeof(Parcel<>), owner);
        Assert.False(owner.IsNested);
        Assert.Null(owner.GetNestedType("Lid"));
        Assert.Null(typeof(ParcelDesk).GetMethod("Lid"));
    }

    [Fact]
    public void DependencyGenericOwner_RoundTripsPrimitivePayload()
    {
        using var oreo = new Parcel<int>(7);
        Assert.Equal(7, oreo.Value);
    }

    [Theory]
    [InlineData("Oreo")]
    [InlineData("Mylo")]
    public void DependencyGenericOwner_RoundTripsStringPayload(string name)
    {
        using var parcel = new Parcel<string>(name);
        Assert.Equal(name, parcel.Value);
    }
}