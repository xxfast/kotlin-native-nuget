using System;
using TestLibrary.Nestedsealedowner;

namespace IntegrationTests;

public class NestedSealedOwnerTests
{
    [Fact]
    public void DeferredNestedSealedOwnerAndDescendants_AreAbsent()
    {
        Assert.Null(typeof(Owner).GetNestedType("NestedSealed"));
        Assert.DoesNotContain(typeof(Owner).Assembly.GetTypes(), type =>
            type.FullName?.StartsWith("TestLibrary.Nestedsealedowner.Owner+", StringComparison.Ordinal) == true);
        Assert.Null(typeof(Owner).GetMethod("Tag"));
        Assert.Null(typeof(Owner).GetMethod("ReadTag"));
        Assert.Null(typeof(Owner).GetProperty("Badge"));
        Assert.Null(typeof(Owner).GetMethod("Note"));
    }

    [Fact]
    public void AdmittedOuterOwner_ControlStillRoundTrips()
    {
        using var oreo = new Owner();
        Assert.Equal(7, oreo.Control());
    }
}