using System;
using TestLibrary.Issue285;

namespace IntegrationTests;

/// <summary>
/// Issue #285: a Kotlin enum entry's internal capitals are lost, so the PascalCase entry
/// <c>SecondValue</c> is only reachable as <c>Example.Secondvalue</c>. The generator lowercases
/// every <c>_</c> segment whole and then uppercases its first character, a rule ADR-006 wrote for
/// <c>SCREAMING_SNAKE_CASE</c> only, so a C# consumer cannot predict the member name from the
/// Kotlin declaration without compiling to read the error or opening <c>Interop.cs</c>.
/// <para>
/// This file cannot compile until the feature ships, which is the red signal: <c>SecondValue</c>,
/// <c>ThirdValueHere</c>, <c>CamelCase</c>, <c>ThirdValue</c>, <c>XMLParserV2</c> and
/// <c>Issue285Limits.MaxRetries</c> do not exist yet (CS0117).
/// </para>
/// <para>
/// The unchanged rows below are load-bearing negative controls, not filler: <c>Ab1c</c>,
/// <c>HappyCat</c>, <c>SnakeCase</c>, <c>HttpStatus</c>, <c>First</c> and <c>MaxNaps</c> all keep
/// today's spelling, so a "keep the Kotlin spelling verbatim" fix (which would emit <c>AB1C</c> and
/// <c>HAPPY_CAT</c>) fails here even though every moving row would pass.
/// </para>
/// <para>
/// No handle is involved anywhere in this file: an enum crosses as its ordinal <c>int</c> in both
/// directions (ADR-006), and a <c>const</c> is a compile-time literal, so there are no leak rows.
/// </para>
/// </summary>
public class Issue285Tests
{
    /// <summary>
    /// The whole member list in one assertion, in declaration order. The ordinals are sequential
    /// 0..9, so <c>Enum.GetNames</c> hands back declaration order.
    /// </summary>
    [Fact]
    public void Example_MemberNames_ArePredictableFromTheKotlinDeclaration()
    {
        Assert.Equal(
            new[]
            {
                "First",
                "SecondValue",
                "ThirdValueHere",
                "Ab1c",
                "HappyCat",
                "SnakeCase",
                "CamelCase",
                "ThirdValue",
                "XMLParserV2",
                "HttpStatus",
            },
            Enum.GetNames<Example>());
    }

    /// <summary>
    /// The rename is a C#-side spelling only: every ordinal stays where ADR-006 put it, so the
    /// native ABI does not move and no published native library is broken by the fix.
    /// </summary>
    [Fact]
    public void Example_Ordinals_DoNotMove()
    {
        Assert.Equal(0, (int)Example.First);
        Assert.Equal(1, (int)Example.SecondValue);
        Assert.Equal(2, (int)Example.ThirdValueHere);
        Assert.Equal(3, (int)Example.Ab1c);
        Assert.Equal(4, (int)Example.HappyCat);
        Assert.Equal(5, (int)Example.SnakeCase);
        Assert.Equal(6, (int)Example.CamelCase);
        Assert.Equal(7, (int)Example.ThirdValue);
        Assert.Equal(8, (int)Example.XMLParserV2);
        Assert.Equal(9, (int)Example.HttpStatus);
    }

    /// <summary>A PascalCase entry keeps its internal capital. The issue's own report.</summary>
    [Fact]
    public void PascalCaseEntry_KeepsItsInternalCapitals()
    {
        Assert.Equal("SecondValue", Example.SecondValue.ToString());
        Assert.Equal("ThirdValueHere", Example.ThirdValueHere.ToString());
    }

    /// <summary>A camelCase entry only gets its first character capitalised.</summary>
    [Fact]
    public void CamelCaseEntry_OnlyGetsAFirstCapital()
    {
        Assert.Equal("CamelCase", Example.CamelCase.ToString());
        Assert.Equal("ThirdValue", Example.ThirdValue.ToString());
    }

    /// <summary>
    /// The mixed row that rules out a whole-name gate: <c>XMLParser_V2</c> has an all-caps run, an
    /// internal capital and a <c>_</c>. Only a per-segment rule keeps the acronym AND joins across
    /// the underscore.
    /// </summary>
    [Fact]
    public void MixedSegmentEntry_KeepsTheAcronymAndJoinsAcrossTheUnderscore()
    {
        Assert.Equal("XMLParserV2", Example.XMLParserV2.ToString());
    }

    /// <summary>
    /// Negative controls. An all-caps segment still lowercases (so <c>AB1C</c> is <c>Ab1c</c>, a
    /// documented decision: any rule that preserved it would also have to preserve <c>HAPPY</c>),
    /// and every SCREAMING_SNAKE, snake_case and mixed all-caps row converts exactly as today.
    /// </summary>
    [Fact]
    public void AllCapsAndSnakeEntries_ConvertExactlyAsBefore()
    {
        Assert.Equal("First", Example.First.ToString());
        Assert.Equal("Ab1c", Example.Ab1c.ToString());
        Assert.Equal("HappyCat", Example.HappyCat.ToString());
        Assert.Equal("SnakeCase", Example.SnakeCase.ToString());
        Assert.Equal("HttpStatus", Example.HttpStatus.ToString());
    }

    /// <summary>
    /// The wire leg for a MOVED row and for an UNCHANGED row: both cross as the same ordinal
    /// <c>int</c>, so the C# rename touched nothing on the ABI. Oreo goes out and Oreo comes back.
    /// </summary>
    [Fact]
    public void Example_RoundTripsByOrdinal_ThroughAFunction()
    {
        Assert.Equal(Example.SecondValue, Issue285Sample.EchoExample(Example.SecondValue));
        Assert.Equal(Example.XMLParserV2, Issue285Sample.EchoExample(Example.XMLParserV2));
        Assert.Equal(Example.HappyCat, Issue285Sample.EchoExample(Example.HappyCat));
        Assert.Equal(Example.Ab1c, Issue285Sample.EchoExample(Example.Ab1c));
    }

    /// <summary>
    /// A second ordinal route whose answer differs from its argument, so the round trip above
    /// cannot be passing on an identity shim: Mylo's turn comes after Oreo's, and the last entry
    /// wraps to the first.
    /// </summary>
    [Fact]
    public void Example_NextEntry_MovesByOneOrdinalAndWraps()
    {
        Assert.Equal(Example.ThirdValueHere, Issue285Sample.NextExample(Example.SecondValue));
        Assert.Equal(Example.HttpStatus, Issue285Sample.NextExample(Example.XMLParserV2));
        Assert.Equal(Example.First, Issue285Sample.NextExample(Example.HttpStatus));
    }

    /// <summary>
    /// Where the C# spelling agrees with Kotlin's <c>name</c> and where it still does not. ADR-006
    /// excludes the inherited <c>name</c> property, so <c>ToString()</c> answers the C# spelling;
    /// the fix closes the gap to zero for a Pascal entry and leaves the accepted ADR-006 trade in
    /// place for a SCREAMING_SNAKE one.
    /// </summary>
    [Fact]
    public void KotlinName_AgreesForAPascalEntryAndStillDiffersForAScreamingOne()
    {
        Assert.Equal("SecondValue", Issue285Sample.ExampleKotlinName(Example.SecondValue));
        Assert.Equal(Example.SecondValue.ToString(), Issue285Sample.ExampleKotlinName(Example.SecondValue));

        Assert.Equal("HAPPY_CAT", Issue285Sample.ExampleKotlinName(Example.HappyCat));
        Assert.NotEqual(Example.HappyCat.ToString(), Issue285Sample.ExampleKotlinName(Example.HappyCat));
    }

    /// <summary>
    /// The <c>const val</c> twin of the same defect: the identical expression renames
    /// <c>MaxRetries</c> to <c>Maxretries</c> today. <c>MAX_NAPS</c> is the control beside it,
    /// unchanged before and after.
    /// </summary>
    [Fact]
    public void ConstValNames_FollowTheSameRule()
    {
        Assert.Equal(3, Issue285Limits.MaxRetries);
        Assert.Equal(7, Issue285Limits.MaxNaps);
    }
}
