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
        // ADR-196: Lid lives on the non-generic holder beside Parcel<T>, never inside it.
        Assert.Null(owner.GetNestedType("Lid"));
        Assert.True(typeof(Parcel).IsAbstract && typeof(Parcel).IsSealed);
    }

    [Fact]
    public void LidOfAGenericDependencyOwner_RoundTripsThroughTheDesk()
    {
        using var desk = new ParcelDesk();
        using Parcel.Lid lid = desk.Lid();
        Assert.Equal(3, lid.Number);
        using var spare = new Parcel.Lid(5);
        Assert.Equal(5, spare.Number);
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