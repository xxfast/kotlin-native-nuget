using System.Linq;
using System.Reflection;
using TestLibrary.Parcel;

namespace IntegrationTests;

/// <summary>
/// ADR-101 amendment (2026-09-13): `class LabelledCrate : Crate&lt;string&gt;` declares
/// `describe(tag: Int)`, an overload of the generic base's `describe(tag: T)`. KSP hands the
/// subclass a substituted `describe(String)` parented to it, which must stay off the subclass.
///
/// ADR-147 flips what that means for C#: `Crate&lt;T&gt;` now carries its own `Describe(T)` on the
/// generic carrier, so the subclass sees two overloads, its own declared `Describe(int)` and the
/// inherited `Describe(string)` declared by `Crate&lt;string&gt;`.
///
/// Oreo's treat crate is described by contents; Mylo's by number, since he cannot read. Both
/// reach C#, and only the `int` one is declared on the subclass.
/// </summary>
public class GenericBaseOverloadTests
{
    [Fact]
    public void LabelledCrate_DescribesThroughItsOwnIntOverload()
    {
        using var crate = new LabelledCrate("apple");
        Assert.Equal("#7:apple", crate.Describe(7));
    }

    /// <summary>
    /// ADR-101 still holds for what the subclass *declares*: `DeclaredOnly` sees exactly the `int`
    /// overload, and the substituted `describe(String)` is not re-declared on it. ADR-147 adds the
    /// other half, that the inherited `string` overload now reaches the subclass from the base.
    /// </summary>
    [Fact]
    public void LabelledCrate_DeclaresExactlyOneDescribeTakingInt()
    {
        MethodInfo[] declared = typeof(LabelledCrate)
            .GetMethods(BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly)
            .Where(method => method.Name == "Describe")
            .ToArray();

        Assert.Single(declared);
        Assert.Equal(typeof(int), declared[0].GetParameters().Single().ParameterType);
        Assert.Equal(typeof(LabelledCrate), declared[0].GetBaseDefinition().DeclaringType);

        MethodInfo[] all = typeof(LabelledCrate)
            .GetMethods()
            .Where(method => method.Name == "Describe")
            .ToArray();

        Assert.Equal(2, all.Length);
        Assert.Equal(
            new[] { typeof(int), typeof(string) },
            all.Select(method => method.GetParameters().Single().ParameterType).OrderBy(type => type.Name).ToArray());
        Assert.Contains(all, method => method.DeclaringType == typeof(Crate<string>));
    }

    /// <summary>
    /// ADR-147: the generic carrier carries the methods declared on the generic class, `Describe(T)`
    /// and `Pick(T)`, where before this ADR it carried only the constructor, `Item` and `Dispose`.
    /// </summary>
    [Fact]
    public void GenericBase_CarriesDescribeAndPickOfItsOwn()
    {
        MethodInfo describe = typeof(Crate<string>)
            .GetMethods()
            .Single(method => method.Name == "Describe");

        Assert.Equal(typeof(string), describe.GetParameters().Single().ParameterType);
        Assert.Equal(typeof(string), describe.ReturnType);

        MethodInfo pick = typeof(Crate<string>)
            .GetMethods()
            .Single(method => method.Name == "Pick");

        Assert.Equal(typeof(string), pick.GetParameters().Single().ParameterType);
        Assert.Equal(typeof(string), pick.ReturnType);
    }

    [Fact]
    public void LabelledCrate_StillInheritsItemFromGenericBase()
    {
        using var crate = new LabelledCrate("Mylo");
        Assert.Equal("Mylo", crate.Item);
    }
}
