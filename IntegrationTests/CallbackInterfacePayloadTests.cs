using TestLibrary.Metronome;

namespace IntegrationTests;

/// <summary>
/// ADR-160: a Kotlin interface as the payload of a per-call lambda parameter, where the interface
/// (<c>Drummer</c>) is reachable from C# through that payload and nowhere else. C# reads each
/// payload with <c>NugetMarshal.FromHandle&lt;IDrummer&gt;</c>, which materialises through
/// <c>NugetMarshal.Factories[typeof(IDrummer)]</c>; without a backing wrapper and that key, the
/// read throws <c>NotSupportedException</c> inside the thunk and the consumer's lambda never runs.
/// The payload wrapper is the consumer's to dispose (ADR-036), hence <c>using (drummer)</c>.
/// </summary>
public class CallbackInterfacePayloadTests
{
    [Fact]
    public void Bandstand_EachDrummer_MaterialisesACallbackOnlyInterface()
    {
        using var bandstand = new Bandstand();
        var seen = new List<string>();

        bandstand.EachDrummer(drummer =>
        {
            using (drummer)
            {
                seen.Add($"{drummer.Name}:{drummer.Instrument}");
            }
        });

        Assert.Equal(new List<string> { "Oreo:drum", "Mylo:triangle" }, seen);
    }
}
