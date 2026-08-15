using TestLibrary.Clinic;

namespace IntegrationTests;

/// <summary>
/// ADR-098 part A and the collection half of part B ("Forward, narrow-primitive and <c>Char</c>
/// collection components"). Every member below targets <c>Readings</c> in
/// <c>NarrowComponentCollectionsSample.kt</c>, one test per mechanism rather than the easiest one.
///
/// The shipped <c>Wrap&lt;T&gt;</c> has exactly six branches (<c>string</c>, <c>int</c>,
/// <c>long</c>, <c>float</c>, <c>double</c>, <c>bool</c>) and then throws, while
/// <c>FromHandle&lt;T&gt;</c> already dispatches all ten narrow kinds. The gap is therefore
/// asymmetric: the write side is what is missing, and these tests all push a value *into* Kotlin
/// except <c>Marks</c>, which pulls one back out through the one read-side hole (<c>char</c>).
///
/// Cells: <c>Chart</c> is signed at a <c>List</c> position, <c>Weigh</c> is unsigned-widest at a
/// <c>Set</c> position, <c>Census</c> is narrow in both slots of a <c>Map</c> with the unsigned one
/// in the KEY slot, <c>Trail</c> is the nullable spelling that the shared predicate admits for
/// free, <c>Initials</c> is the <c>Char</c> write side and <c>Marks</c> the <c>Char</c> read side.
///
/// Every numeric assertion pins a value that a widening or a sign error would visibly break:
/// <c>short.MaxValue</c>, a negative <c>short</c>, <c>ulong.MaxValue</c>. Every <c>Char</c>
/// assertion is non-ASCII, because an ANSI-truncating wire passes every ASCII cell in the corpus
/// and that is exactly how the shipped bug survived.
/// </summary>
public class NarrowComponentCollectionTests
{
    [Fact]
    public void Readings_Chart_ListOfShortParameter_RoundTripsEveryElement()
    {
        using var readings = new Readings();

        // Signed narrow at a List position: C#'s `short` is the wire type, so nothing converts at
        // the seam. short.MaxValue would come back as -1 through a byte-narrowed wire, and -2 as
        // 254 through an unsigned one; neither can pass this string.
        string chart = readings.Chart(new short[] { 1, -2, short.MaxValue });

        Assert.Equal("1,-2,32767", chart);
    }

    [Fact]
    public void Readings_Weigh_SetOfULongParameter_RoundTripsEveryElement()
    {
        using var readings = new Readings();

        // Unsigned and the widest of the six, at a Set position (a different native export and a
        // different Kotlin lowering than Chart's). Mylo weighs a bit more than Oreo. ulong.MaxValue
        // is the cell that fails loudly if the box round-trips through a signed Long anywhere.
        string weighed = readings.Weigh(new HashSet<ulong> { 1UL, ulong.MaxValue });

        Assert.Equal("1,18446744073709551615", weighed);
    }

    [Fact]
    public void Readings_Census_MapWithNarrowKeyAndNarrowValue_RoundTripsEveryEntry()
    {
        using var readings = new Readings();

        // Both slots narrow, unsigned KEY beside a signed value, and the key slot projects
        // independently of the value slot. byte 200 is past sbyte range and short -40 is negative,
        // so a sign confusion in either slot shows up. Kotlin sorts by key, so grade 9 leads.
        string census = readings.Census(new Dictionary<byte, short>
        {
            [200] = 32767,
            [9] = -40,
        });

        Assert.Equal("9=-40,200=32767", census);
    }

    [Fact]
    public void Readings_Trail_ListOfNullableShortParameter_RoundTripsNullAndRealElement()
    {
        using var readings = new Readings();

        // isWrappableComponent()'s Nullable branch delegates to the inner type, so admitting Short
        // admits Short? in the same edit. This cell exists so "free" is observed rather than
        // argued. The null sits at a non-first index and the trailing value is negative, so a
        // collapsed or zero-filled slot cannot pass.
        string trail = readings.Trail(new short?[] { 1, null, -3 });

        Assert.Equal("1,null,-3", trail);
    }

    [Fact]
    public void Readings_Initials_ListOfCharParameter_RoundTripsNonAsciiElements()
    {
        using var readings = new Readings();

        // Part B, write side. There is no nuget_wrap_char at all today, so this member does not
        // exist. Both non-ASCII marks are chosen from different UTF-8 lengths (é is two bytes, 日
        // is three), so a wire that truncates to the first UTF-8 byte produces two *different*
        // wrong characters rather than one, and the ASCII 'A' still passing is what tells the two
        // failure modes apart.
        string initials = readings.Initials(new[] { 'é', '日', 'A' });

        Assert.Equal("é日A", initials);
    }

    [Fact]
    public void Readings_Marks_ListOfCharReturn_MaterializesNonAsciiElements()
    {
        using var readings = new Readings();

        // Part B, read side: the one hole in FromHandle<T>, which already dispatches the other ten
        // narrow kinds. Falls through to Materialize<char> and throws NotSupportedException today.
        IReadOnlyList<char> marks = readings.Marks();

        Assert.Equal(new[] { 'é', '日' }, marks);
    }

    [Fact]
    public void Readings_Glyph_CharPropertyGetter_ReturnsNonAsciiCharacter()
    {
        using var readings = new Readings();

        // The property-getter position of the same Char wire. Patient.Grade is 'A', so no shipped
        // cell can tell a correct getter from an ANSI-truncated one; the ADR claims the fix spans
        // parameters, returns AND property getters, and this is the only cell that observes the
        // third. U+03A9 truncates to U+FFFD on the shipped path.
        Assert.Equal('Ω', readings.Glyph);
    }
}
