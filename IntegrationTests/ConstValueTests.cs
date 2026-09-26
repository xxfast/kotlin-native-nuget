using System.Reflection;
using TestLibrary;
using TestLibrary.Objectprops;

namespace IntegrationTests;

/// <summary>
/// Kotlin <c>const val</c> reaches C# as a C# <c>const</c> with the value baked in. ROADMAP line 25:
/// the value is read out of the Kotlin source text by a regex today, which emits illegal C# for a
/// one-line <c>object</c>/companion body, a <c>;</c>-separated pair and a trailing <c>//</c>
/// comment, and silently wrong values (or unresolved C# names) for underscores inside a string, a
/// string template, a <c>\$</c> escape, a raw string, a shift, <c>Int.MIN_VALUE</c> and an
/// expression over another const. The approved end state reads the compiler's evaluated constant
/// and renders every one of these as the evaluated literal.
/// <para>
/// The red signal is a whole-project build break, not N failing tests: the fixtures make the
/// generated <c>Interop.cs</c> itself illegal C#, so <c>IntegrationTests</c> does not compile
/// until the value source changes.
/// </para>
/// <para>
/// Every new row pins three things through <see cref="AssertConst"/>: the field is a real C#
/// <c>const</c> (<see cref="FieldInfo.IsLiteral"/>, not a <c>static readonly</c> or a property, so
/// switch labels and attribute arguments still work), its C# type is exactly the mapped one, and
/// its value is exactly the Kotlin value. <c>Assert.Equal(6, x)</c> alone would pass for a
/// <c>long</c> <c>6</c> too. A <c>const</c> is a compile-time literal, so nothing crosses the C ABI
/// and there are no leak rows.
/// </para>
/// <para>
/// Oreo and Mylo run the pantry: Oreo reads the labels, Mylo counts the treats.
/// </para>
/// </summary>
public class ConstValueTests
{
    [Fact]
    public void MaxLivesIsNine()
    {
        Assert.Equal(9, Constants.MaxLives);
    }

    [Fact]
    public void GreetingIsHelloWorld()
    {
        Assert.Equal("Hello, world!", Constants.Greeting);
    }

    [Fact]
    public void PiApproxIsThreePointOneFour()
    {
        Assert.Equal(3.14, Constants.PiApprox);
    }

    [Fact]
    public void IsDebugIsFalse()
    {
        Assert.False(Constants.IsDebug);
    }

    // --- shapes: one-line bodies, trailing comments, expressions (objectprops/TreatPantry.kt) ---

    /// <summary>
    /// A one-line <c>object</c> body with a <c>;</c>-separated pair. Today <c>Top</c> renders as
    /// <c>"tuna"; const val BOTTOM = 2 };</c> and <c>Bottom</c> as <c>2 };</c>.
    /// </summary>
    [Fact]
    public void OneLineObject_SemicolonPair_EachConstKeepsOnlyItsOwnValue()
    {
        Assert.Equal("tuna", PantryShelf.Top);
        Assert.Equal(2, PantryShelf.Bottom);
        AssertConst(typeof(PantryShelf), nameof(PantryShelf.Top), "tuna");
        AssertConst(typeof(PantryShelf), nameof(PantryShelf.Bottom), 2);
    }

    /// <summary>
    /// A one-line <c>companion object</c> body inside a one-line class body: today the value
    /// captures both closing braces. The PascalCase Kotlin name is kept verbatim.
    /// </summary>
    [Fact]
    public void OneLineCompanion_ConstIsExact()
    {
        Assert.Equal("Oreo's jar", PantryJar.DefaultName);
        AssertConst(typeof(PantryJar), nameof(PantryJar.DefaultName), "Oreo's jar");
    }

    /// <summary>Today the generated terminating <c>;</c> lands inside the trailing comment.</summary>
    [Fact]
    public void TrailingLineComment_IsNotPartOfTheValue()
    {
        Assert.Equal(5, PantryTally.Trailing);
        AssertConst(typeof(PantryTally), nameof(PantryTally.Trailing), 5);
    }

    /// <summary>
    /// <c>REF = TRAILING + 1</c> renders the evaluated literal <c>6</c> (approved), not the C#
    /// expression; today it renders <c>TRAILING + 1</c>, which is CS0103 (the C# name is
    /// <c>Trailing</c>).
    /// </summary>
    [Fact]
    public void ExpressionOverSiblingConst_IsTheEvaluatedLiteral()
    {
        Assert.Equal(6, PantryTally.Ref);
        AssertConst(typeof(PantryTally), nameof(PantryTally.Ref), 6);
    }

    /// <summary>
    /// <c>TreatPantry.CAPACITY * 2</c> across owners: <c>24</c>. Today it renders the Kotlin
    /// text, and C# has no member <c>TreatPantry.CAPACITY</c>.
    /// </summary>
    [Fact]
    public void ExpressionOverAnotherOwnersConst_IsTheEvaluatedLiteral()
    {
        Assert.Equal(24, PantryTally.Doubled);
        AssertConst(typeof(PantryTally), nameof(PantryTally.Doubled), 24);
    }

    // --- every primitive type once (Constants.kt) ---

    [Fact]
    public void Byte_IsSbyte()
    {
        Assert.IsType<sbyte>(Constants.NapOffset);
        AssertConst(typeof(Constants), nameof(Constants.NapOffset), (sbyte)-8);
    }

    /// <summary>Today <c>255u</c> is copied verbatim: a <c>uint</c> literal does not convert to <c>byte</c> (CS0266).</summary>
    [Fact]
    public void UByte_IsByte()
    {
        Assert.IsType<byte>(Constants.FullBowl);
        AssertConst(typeof(Constants), nameof(Constants.FullBowl), (byte)255);
    }

    [Fact]
    public void Short_IsShort()
    {
        Assert.IsType<short>(Constants.ColdestTile);
        AssertConst(typeof(Constants), nameof(Constants.ColdestTile), (short)-12);
    }

    /// <summary>Today <c>65535u</c> is copied verbatim: a <c>uint</c> literal does not convert to <c>ushort</c> (CS0266).</summary>
    [Fact]
    public void UShort_IsUshort()
    {
        Assert.IsType<ushort>(Constants.KibbleStash);
        AssertConst(typeof(Constants), nameof(Constants.KibbleStash), (ushort)65535);
    }

    /// <summary>Above <c>int.MaxValue</c>: a reader that unwraps the Kotlin <c>UInt</c> to <c>Int</c> goes negative.</summary>
    [Fact]
    public void UInt_AboveIntMax_IsUint()
    {
        Assert.IsType<uint>(Constants.TreatsEver);
        AssertConst(typeof(Constants), nameof(Constants.TreatsEver), 4_000_000_000U);
    }

    [Fact]
    public void Long_FromUnsuffixedLiteral_IsLong()
    {
        Assert.IsType<long>(Constants.NapMillis);
        AssertConst(typeof(Constants), nameof(Constants.NapMillis), 5_400_000L);
    }

    /// <summary><c>ULong.MAX_VALUE</c>: a reader that unwraps the Kotlin <c>ULong</c> to <c>Long</c> gets <c>-1</c>.</summary>
    [Fact]
    public void ULong_AtMax_IsUlong()
    {
        Assert.IsType<ulong>(Constants.StarsCounted);
        AssertConst(typeof(Constants), nameof(Constants.StarsCounted), ulong.MaxValue);
    }

    [Fact]
    public void Float_IsFloat()
    {
        Assert.IsType<float>(Constants.MyloCreaminess);
        AssertConst(typeof(Constants), nameof(Constants.MyloCreaminess), 0.75f);
    }

    // --- integers ---

    /// <summary>Today <c>1 shl 3</c> is copied verbatim (CS1002/CS1519).</summary>
    [Fact]
    public void ShiftExpression_IsTheEvaluatedLiteral()
    {
        Assert.Equal(8, Constants.LegCount);
        AssertConst(typeof(Constants), nameof(Constants.LegCount), 8);
    }

    [Fact]
    public void HexWithUnderscores_IsItsValue()
    {
        AssertConst(typeof(Constants), nameof(Constants.WhiskerMask), 0xFFFF);
    }

    [Fact]
    public void NegativeInt_IsItsValue()
    {
        AssertConst(typeof(Constants), nameof(Constants.FloorTemp), -3);
    }

    /// <summary>Today the text <c>Int.MIN_VALUE</c> is copied, and C# has no name <c>Int</c>.</summary>
    [Fact]
    public void IntMinValue_IsItsValue()
    {
        AssertConst(typeof(Constants), nameof(Constants.FewestNaps), int.MinValue);
    }

    /// <summary><c>-9223372036854775808L</c> is legal C# only as the literal-negation special case.</summary>
    [Fact]
    public void LongMinValue_IsItsValue()
    {
        AssertConst(typeof(Constants), nameof(Constants.OldestNap), long.MinValue);
    }

    // --- floating point specials ---

    [Fact]
    public void FloatNaN_IsNaN()
    {
        Assert.True(float.IsNaN(Constants.MysteryWeight));
        AssertConstField(typeof(Constants), nameof(Constants.MysteryWeight), typeof(float));
    }

    [Fact]
    public void DoubleNegativeInfinity_IsNegativeInfinity()
    {
        Assert.True(double.IsNegativeInfinity(Constants.ColdestFloor));
        AssertConst(typeof(Constants), nameof(Constants.ColdestFloor), double.NegativeInfinity);
    }

    // --- chars ---

    [Fact]
    public void Char_Plain()
    {
        AssertConst(typeof(Constants), nameof(Constants.OreoInitial), 'O');
    }

    [Fact]
    public void Char_NewlineEscape()
    {
        AssertConst(typeof(Constants), nameof(Constants.NewlineMeow), '\n');
    }

    [Fact]
    public void Char_EscapedSingleQuote()
    {
        AssertConst(typeof(Constants), nameof(Constants.Apostrophe), '\'');
    }

    [Fact]
    public void Char_EscapedBackslash()
    {
        AssertConst(typeof(Constants), nameof(Constants.Backslash), '\\');
    }

    // --- strings ---

    /// <summary>Today every <c>_</c> is stripped from the value, strings included: <c>"snakecasevalue"</c>.</summary>
    [Fact]
    public void UnderscoresInsideAString_Survive()
    {
        Assert.Equal("snake_case_value", Constants.SnakeToy);
        AssertConst(typeof(Constants), nameof(Constants.SnakeToy), "snake_case_value");
    }

    /// <summary>Today the template text <c>"v$SNAKE_TOY"</c> is copied: legal C#, wrong value.</summary>
    [Fact]
    public void StringTemplateOverAConst_IsInterpolated()
    {
        AssertConst(typeof(Constants), nameof(Constants.ToyTag), "vsnake_case_value");
    }

    [Fact]
    public void ClosingBraceInsideAString_Survives()
    {
        AssertConst(typeof(Constants), nameof(Constants.Braced), "Oreo } Mylo");
    }

    [Fact]
    public void CommentMarkerInsideAString_Survives()
    {
        AssertConst(typeof(Constants), nameof(Constants.VetUrl), "http://vet.example/oreo");
    }

    /// <summary>Today <c>\$</c> is copied verbatim, an unrecognized C# escape (CS1009).</summary>
    [Fact]
    public void EscapedDollar_IsABareDollar()
    {
        AssertConst(typeof(Constants), nameof(Constants.TreatPrice), "cost $5");
    }

    [Fact]
    public void EscapedQuoteAndBackslash_Survive()
    {
        AssertConst(typeof(Constants), nameof(Constants.QuotedPurr), "Mylo said \"mrrp\" \\ twice");
    }

    [Fact]
    public void LiteralConcatenation_IsEvaluated()
    {
        AssertConst(typeof(Constants), nameof(Constants.BothCats), "OreoMylo");
    }

    /// <summary>
    /// Today the capture stops at the line end and renders <c>"""Oreo: black;</c> (CS8997). The
    /// comparison normalises CRLF: the fixture's working copy is CRLF on Windows and LF on CI, and
    /// whether the Kotlin compiler normalises a raw string's separator is not pinned here.
    /// </summary>
    [Fact]
    public void MultiLineRawString_KeepsItsLineBreak()
    {
        Assert.Equal("Oreo: black\nMylo: brown", Constants.RollCall.Replace("\r\n", "\n"));
        AssertConstField(typeof(Constants), nameof(Constants.RollCall), typeof(string));
    }

    /// <summary>A value on the line after <c>=</c>: the regex route gets this right; keep it.</summary>
    [Fact]
    public void ValueOnTheLineAfterEquals_IsKept()
    {
        AssertConst(typeof(Constants), nameof(Constants.WrappedGreeting), "Mylo, dinner!");
    }

    // --- helpers ---

    /// <summary>
    /// The field is a real C# <c>const</c> of exactly <typeparamref name="T"/>, holding exactly
    /// <paramref name="expected"/>. The raw constant value is compared boxed, so a <c>long</c>
    /// <c>6</c> does not equal an <c>int</c> <c>6</c>.
    /// </summary>
    private static void AssertConst<T>(Type owner, string name, T expected)
        where T : notnull
    {
        FieldInfo field = AssertConstField(owner, name, typeof(T));
        Assert.Equal((object)expected, field.GetRawConstantValue());
    }

    private static FieldInfo AssertConstField(Type owner, string name, Type expectedType)
    {
        FieldInfo? field = owner.GetField(name, BindingFlags.Public | BindingFlags.Static);
        Assert.NotNull(field);
        Assert.True(field.IsLiteral, $"{owner.Name}.{name} must be a C# const, not a static field");
        Assert.Equal(expectedType, field.FieldType);
        return field;
    }
}
