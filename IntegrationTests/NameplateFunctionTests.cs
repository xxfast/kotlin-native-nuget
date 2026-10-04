using TestLibrary.Nameplate.Engraved;

namespace IntegrationTests;

/// <summary>
/// ADR-188 amendment: the function half of the <c>nameplate</c> pair. This file imports only
/// <c>TestLibrary.Nameplate.Engraved</c>, so <c>"Oreo".Nameplate()</c> resolves to the extension
/// method. See <see cref="NameplatePropertyTests"/> for the property half.
/// </summary>
public class NameplateFunctionTests
{
    [Fact]
    public void ExtensionFunction_InItsOwnNamespace_BindsBesideTheSameNamedProperty()
    {
        Assert.Equal("engraved:Oreo", "Oreo".Nameplate());
    }
}
