using TestLibrary.Cat;
using TestLibrary.Parcel;

namespace IntegrationTests;

/// <summary>
/// ADR-147: a public method declared on a generic class binds as an instance method on the C#
/// generic carrier, with every `T` position crossing as the boxed handle the property getter
/// already uses. Oreo's crate is described with whatever tag its contents are typed as; Mylo's is
/// still numbered, because he cannot read.
/// </summary>
public class GenericMethodTests
{
    [Fact]
    public void Crate_Int_DescribeTakesTheTypeParameter()
    {
        using var crate = new Crate<int>(3);
        Assert.Equal("7:3", crate.Describe(7));
    }

    [Fact]
    public void Crate_String_DescribeTakesTheTypeParameter()
    {
        using var crate = new Crate<string>("apple");
        Assert.Equal("ripe:apple", crate.Describe("ripe"));
    }

    [Fact]
    public void Crate_Int_PickReturnsTheTypeParameter()
    {
        using var crate = new Crate<int>(3);
        Assert.Equal(7, crate.Pick(7));
    }

    /// <summary>
    /// The no-box half of the `T` parameter wire: an `INugetHandle` wrapper contributes its own
    /// live handle, so nothing is minted on the way in. `Cat` has no `toString` override, so what
    /// comes back is Kotlin's identity rendering; asserting on the class name is all that is
    /// deterministic, and it is enough to prove the tag reached Kotlin as a `Cat`.
    /// </summary>
    [Fact]
    public void Crate_Cat_DescribeTakesAnExportedClassAtTheTypeParameter()
    {
        using var oreo = new Cat("Oreo", 9);
        using var mylo = new Cat("Mylo", 9);
        using var crate = new Crate<Cat>(oreo);
        Assert.Contains("Cat", crate.Describe(mylo));
    }

    [Fact]
    public void Crate_Cat_PickReturnsAFreshWrapperOverTheSameKotlinObject()
    {
        using var oreo = new Cat("Oreo", 9);
        using var crate = new Crate<Cat>(oreo);
        using Cat picked = crate.Pick(oreo);
        Assert.Equal("Oreo", picked.Name);
    }

    [Fact]
    public void Crate_NonTPositions_BindThroughThePlan()
    {
        using var crate = new Crate<string>("apple");
        Assert.Equal("lot-2:apple", crate.Label("lot", 2));
    }

    [Fact]
    public void LabelledCrate_InheritsDescribeOfString_AndKeepsItsOwnDescribeOfInt()
    {
        using var crate = new LabelledCrate("apple");
        Assert.Equal("ripe:apple", crate.Describe("ripe"));
        Assert.Equal("#7:apple", crate.Describe(7));
    }
}
