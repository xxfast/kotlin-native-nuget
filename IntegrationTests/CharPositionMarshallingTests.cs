using TestLibrary.Clinic;

namespace IntegrationTests;

/// <summary>
/// ADR-098 part B, the shipped-bug half. These cells need no new Kotlin: they go against the
/// already-shipped <c>Patient.Tag(char)</c> and <c>Patient.Initial()</c>, which have been silently
/// corrupting every non-ASCII character since they landed.
///
/// The Kotlin side is already correct. The generated C header declares
/// <c>typedef unsigned short libtest_KChar</c>, so Kotlin emits a 2-byte UTF-16 code unit. The
/// generated <c>[DllImport]</c> declares a bare <c>char</c> with no <c>CharSet</c> and no
/// <c>MarshalAs</c>, and the runtime default for that is ANSI: one byte. <c>'é'</c> (U+00E9) is
/// UTF-8 encoded to <c>C3 A9</c>, truncated to <c>0xC3</c>, and read back Kotlin-side as
/// <c>'Ã'</c>. No exception, no diagnostic. The return leg fails differently and worse: the low
/// byte is decoded as a lone invalid ANSI byte and the character is lost to U+FFFD outright.
///
/// Only ASCII cells existed before this file (<c>Tag('O')</c>, <c>Tag('X')</c>,
/// <c>Initial() == 'O'</c>, <c>Grade == 'A'</c>), which is precisely why the bug survived: every
/// one of them passes byte-identically through a one-byte wire. The ASCII cell below is kept as a
/// regression guard for the other direction, since the fix must not perturb them.
///
/// A lone surrogate is deliberately not a cell: it fails to round-trip under every candidate wire
/// shape, so it is degenerate input rather than a marshalling question, and it is deferred as its
/// own item.
/// </summary>
public class CharPositionMarshallingTests
{
    [Fact]
    public void Patient_Tag_AsciiCharParameter_IsUnchangedByTheWidthFix()
    {
        using var patient = new Patient("Oreo");

        // The shape that already passes. ASCII call sites are byte-identical before and after, so
        // this is the guard that the fix is a width directive and not a re-encoding.
        Assert.Equal("O-Oreo", patient.Tag('O'));
    }

    [Fact]
    public void Patient_Tag_LatinCharParameter_ArrivesUncorrupted()
    {
        using var patient = new Patient("Oreo");

        // U+00E9. Two UTF-8 bytes (C3 A9), truncated to 0xC3, so the shipped wire delivers 'Ã'.
        Assert.Equal("é-Oreo", patient.Tag('é'));
    }

    [Fact]
    public void Patient_Tag_CjkCharParameter_ArrivesUncorrupted()
    {
        using var patient = new Patient("Oreo");

        // U+65E5. Three UTF-8 bytes (E6 97 A5), truncated to 0xE6, so the shipped wire delivers
        // 'æ'. A different wrong character from the Latin cell above, which is what proves the
        // failure is a truncating encode rather than a single fixed fallback.
        Assert.Equal("日-Oreo", patient.Tag('日'));
    }

    [Fact]
    public void Patient_Initial_LatinCharReturn_ComesBackUncorrupted()
    {
        // The return leg breaks differently from the parameter leg: not truncated to a plausible
        // wrong character, but lost entirely to U+FFFD, because the low byte decodes as an invalid
        // lone ANSI byte. Émile is Oreo's least favourite housemate.
        using var patient = new Patient("Émile");

        Assert.Equal('É', patient.Initial());
    }

    [Fact]
    public void Patient_Initial_CjkCharReturn_ComesBackUncorrupted()
    {
        // Same leg, a three-byte source character. Both return cells land on U+FFFD today, so
        // unlike the parameter leg the observed failure carries no information about the input --
        // which is why two of them are worth pinning.
        using var patient = new Patient("日向");

        Assert.Equal('日', patient.Initial());
    }
}
