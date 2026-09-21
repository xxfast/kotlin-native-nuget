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

    // ---------------------------------------------------------------------------------------------
    // Boundary nullability, part C: a bare `Char?`. The wire above is ADR-098's; what is missing is
    // the ADR-079/080 has-value fan-out, which `Char` misses at every position because it is its own
    // `BridgeType` and not a `PrimitiveKind`. The failures differ by position and the property one
    // is not a skip: it is an uncaught processor exception ("Forward property direct nullable getter
    // is invalid for ...Tag.initial: Char") that aborts generation for the WHOLE module, so nothing
    // else in this assembly compiles until it is fixed. The other three positions are named skips
    // today, so their cells are red at C# compile time (no such member).
    //
    // Payloads: null, 'é' (U+00E9, BMP but non-ASCII) and '한' (U+D55C, above 0x7FFF). The last is
    // the sign/width seam -- every value below 0x8000 reads the same through a signed 16-bit slot
    // and an unsigned one, so only a character with the top bit set can catch a `short` where the
    // wire wants `ushort`.
    // ---------------------------------------------------------------------------------------------

    /// <summary>
    /// The PROPERTY position, both directions. The getter is where generation crashes today, so this
    /// is the cell that has to go green first: until it does, no other test in this assembly exists.
    /// A null is written and read back after a non-null one, so a setter that ignores the has-value
    /// flag (and keeps the previous character) fails rather than passing on the initial state.
    /// </summary>
    [Fact]
    public void Tag_NullableCharProperty_ReadsAndWritesNullAndNonAscii()
    {
        using var tag = new Tag(null);
        Assert.Null(tag.Initial);

        tag.Initial = 'é';
        Assert.Equal('é', tag.Initial);

        // Above 0x7FFF: the one value that distinguishes a signed slot from an unsigned one.
        tag.Initial = '한';
        Assert.Equal('한', tag.Initial);

        tag.Initial = null;
        Assert.Null(tag.Initial);
    }

    /// <summary>
    /// The PARAMETER and MEMBER-RETURN positions in one call. Those are two different planner gates
    /// (<c>inputSkipReason</c> and <c>nullableResultShape</c>) and today both name the same hint,
    /// "expose a non-nullable wrapper, or a separate has-value/value pair, instead" -- which is
    /// precisely what this feature builds, so that sentence stops being true for <c>Char?</c>.
    /// </summary>
    [Fact]
    public void Tag_NullableCharParameterAndReturn_RoundTripInOneCall()
    {
        // The cast is not decoration. `Tag`'s public constructor takes `char?` and its internal
        // handle constructor takes `nint`; a bare `char` literal converts implicitly to BOTH
        // (`char` -> `char?` lifted, `char` -> `int` -> `nint` numeric) and neither is better, so
        // `new Tag('O')` is CS0121. That collision is not specific to `Char?` -- any single
        // nullable-numeric constructor parameter has it -- and it is reported as a separate defect
        // rather than papered over here; `new Tag(null)` above is unambiguous because `null` has no
        // conversion to `nint` at all.
        using var tag = new Tag((char?)'O');

        Assert.Null(tag.Echo(null));
        Assert.Equal('é', tag.Echo('é'));
        Assert.Equal('한', tag.Echo('한'));

        // ASCII stays exactly as it was: the change is a has-value pair, not a re-encoding.
        Assert.Equal('O', tag.Echo('O'));
    }

    /// <summary>
    /// The TOP-LEVEL function return, a different (legacy two-call) route from the member return
    /// above, plus the TOP-LEVEL property, which crashes through a different caller than a class
    /// property and so cannot be covered by the <c>Tag.Initial</c> cell.
    /// </summary>
    [Fact]
    public void TagKt_NullableCharAtTopLevel_CoversTheFunctionAndThePropertyRoute()
    {
        Assert.Equal('O', TagKt.FirstLetter("Oreo"));
        Assert.Equal('é', TagKt.FirstLetter("époque"));

        // The empty name is the null arm of the two-call route: no character, no exception.
        Assert.Null(TagKt.FirstLetter(""));

        // Mylo's tag, above 0x7FFF, at the top-level property position.
        Assert.Equal('한', TagKt.MascotInitial);
    }
}
