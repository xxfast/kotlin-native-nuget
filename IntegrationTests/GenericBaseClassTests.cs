using TestLibrary.Parcel;

namespace IntegrationTests;

/// <summary>
/// Item 15 / ADR-101: `class NamedParcel : Parcel&lt;String&gt;(name)` in Kotlin must render as
/// `public class NamedParcel : Parcel&lt;string&gt;` in C#, with the type argument spelled.
///
/// Oreo gets a named parcel. What matters here is that the inherited `Value` getter reaches
/// through the generic base's own export, that the derived type really is a `Parcel&lt;string&gt;`,
/// and that disposing through a base-typed reference frees the same handle.
/// </summary>
public class GenericBaseClassTests
{
    [Fact]
    public void NamedParcel_InheritsValueFromGenericBase()
    {
        using var parcel = new NamedParcel("Oreo");
        Assert.Equal("Oreo", parcel.Value);
    }

    [Fact]
    public void NamedParcel_IsAssignableToClosedGenericBase()
    {
        using var parcel = new NamedParcel("Oreo");
        Assert.IsAssignableFrom<Parcel<string>>(parcel);
    }

    [Fact]
    public void NamedParcel_DeclaresItsOwnMember()
    {
        using var parcel = new NamedParcel("Oreo");
        Assert.Equal("own:Oreo", parcel.Own());
    }

    [Fact]
    public void NamedParcel_DisposesThroughBaseTypedReference()
    {
        using Parcel<string> parcel = new NamedParcel("Mylo");
        Assert.Equal("Mylo", parcel.Value);
    }
}
