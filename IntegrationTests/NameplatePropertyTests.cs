using TestLibrary.Nameplate.Worn;

namespace IntegrationTests;

/// <summary>
/// ADR-188 amendment. <c>val String.nameplate</c> (package <c>nameplate.worn</c>) and
/// <c>fun String.nameplate()</c> (package <c>nameplate.engraved</c>) share a C# name and an
/// unexported receiver, but render into two namespaces (ADR-126), so both bind. This file imports
/// only <c>TestLibrary.Nameplate.Worn</c>, so <c>"Oreo".Nameplate</c> resolves to the property with
/// no ambiguity. Its twin, <see cref="NameplateFunctionTests"/>, imports only the other namespace.
/// </summary>
public class NameplatePropertyTests
{
    [Fact]
    public void ExtensionProperty_InItsOwnNamespace_BindsBesideTheSameNamedFunction()
    {
        Assert.Equal("worn:Oreo", "Oreo".Nameplate);
    }
}
