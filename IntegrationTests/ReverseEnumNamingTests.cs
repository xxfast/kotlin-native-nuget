using TestLibrary.Enums;
using TestLibrary.Test.Enums;

namespace IntegrationTests;

// ADR-006 2026-10-02 amendment: the C# enum Test.Enums.VetTriage (members OK, HTTPTimeout, IOError,
// Win32NT) binds in Kotlin as OK, HTTP_TIMEOUT, IO_ERROR, WIN32_NT. The Kotlin sample names every
// entry, so it compiling is the naming check; this round trip is the ordinal check for every member.
public class ReverseEnumNamingTests
{
    [Theory]
    [InlineData(VetTriage.Ok, VetTriage.HttpTimeout)]
    [InlineData(VetTriage.HttpTimeout, VetTriage.IoError)]
    [InlineData(VetTriage.IoError, VetTriage.Win32Nt)]
    [InlineData(VetTriage.Win32Nt, VetTriage.Ok)]
    public void AcronymEnumEntries_RoundTripByOrdinal(VetTriage triage, VetTriage expected)
    {
        // Oreo's clinic code climbs one rung through the C# desk, by way of Kotlin.
        VetTriage result = VetTriageSample.EscalateTriage(triage);

        Assert.Equal(expected, result);
    }
}
