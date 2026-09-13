using System.Linq;
using System.Reflection;
using TestLibrary.Parcel;

namespace IntegrationTests;

/// <summary>
/// ADR-101 amendment (2026-09-13): `class LabelledCrate : Crate&lt;string&gt;` declares
/// `describe(tag: Int)`, an overload of the generic base's `describe(tag: T)`. KSP hands the
/// subclass a substituted `describe(String)` parented to it, which must stay off the subclass.
///
/// Oreo's treat crate is described by contents; Mylo's by number, since he cannot read. Exactly
/// one `Describe` may reach C# on the subclass, and it is the `int` one.
/// </summary>
public class GenericBaseOverloadTests
{
    [Fact]
    public void LabelledCrate_DescribesThroughItsOwnIntOverload()
    {
        using var crate = new LabelledCrate("apple");
        Assert.Equal("#7:apple", crate.Describe(7));
    }

    [Fact]
    public void LabelledCrate_DeclaresExactlyOneDescribeTakingInt()
    {
        MethodInfo[] describes = typeof(LabelledCrate)
            .GetMethods()
            .Where(method => method.Name == "Describe")
            .ToArray();

        Assert.Single(describes);
        Assert.Equal(typeof(int), describes[0].GetParameters().Single().ParameterType);
        Assert.Equal(typeof(LabelledCrate), describes[0].GetBaseDefinition().DeclaringType);
    }

    [Fact]
    public void GenericBase_CarriesNoDescribeOfItsOwn()
    {
        MethodInfo[] describes = typeof(Crate<string>)
            .GetMethods()
            .Where(method => method.Name == "Describe")
            .ToArray();

        Assert.Empty(describes);
    }

    [Fact]
    public void LabelledCrate_StillInheritsItemFromGenericBase()
    {
        using var crate = new LabelledCrate("Mylo");
        Assert.Equal("Mylo", crate.Item);
    }
}
