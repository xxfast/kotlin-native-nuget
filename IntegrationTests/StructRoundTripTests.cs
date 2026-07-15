using TestLibrary.Test.Enums;
using TestLibrary.Structs;

namespace IntegrationTests;

// ADR-056: proves a C# struct crosses the reverse bridge end to end —
//   C# IntegrationTests
//     -> (forward bridge, Interop.cs)        StructsSample.* functions
//       -> Kotlin test-library             StructsSample.kt
//         -> (reverse bridge, ADR-056)       test.structs.{Point,Metrics,Profile,Geometry,Cattery,Cattery2}
//           -> real C# TestDependency NuGet Test.Structs.{Point,Metrics,Profile,Geometry,Cattery,Cattery2}
//
// This now covers the full v1 component vocabulary: int (Point), long/float/double (Metrics), and
// string/bool/char/bound-enum (Profile) — the latter group was broken in an earlier generator pass
// (real compiler diagnostics: an unresolved cross-package enum import, and "String"/"CPointer<...>",
// "Char"/"UShort", "UByte"/"Boolean" argument type mismatches — see the walking-skeleton report),
// fixed by kotlin-dev, and re-verified here against the real pipeline. It also covers a SETTABLE
// struct-typed property on a bound class (Cattery.CurrentProfile) — the one piece of ADR-056's v1
// Scope nothing exercised end to end before this fixture.
//
// `gradeCode` (an Int code point, not a raw Kotlin Char) on the Profile-related tests below works
// around a SEPARATE, unrelated forward-direction (ADR-006/048) bug: CirTypeMapping.kt's
// KOTLIN_TO_CSHARP_PARAM table has no entry for "Char" and silently falls back to IntPtr, an 8-byte
// pointer marshal for what is actually Kotlin's 2-byte Char at the C ABI. That is a real bug,
// reported (not fixed) here — it is not part of ADR-056, and the REVERSE struct's own Char
// component (Profile.Grade) is unaffected: Cattery2/Cattery below prove it round-trips correctly.
//
// The trailing tests cover ADR-056 deferred scope (struct methods + computed properties): members
// on Point/Profile themselves, not Geometry/Cattery2. They fail until kotlin-dev lands binding.
public class StructRoundTripTests
{
    [Fact]
    public void TranslatePointDescription_MovesBothComponents()
    {
        string result = StructsSample.translatePointDescription(1, 2, 10, 20);
        Assert.Equal("11,22", result);
    }

    [Fact]
    public void TranslatePointDescription_NegativeDelta_MovesBothComponents()
    {
        string result = StructsSample.translatePointDescription(5, 5, -3, -1);
        Assert.Equal("2,4", result);
    }

    // Struct parameter, non-struct (string) return — Geometry.Describe(Point): string.
    [Fact]
    public void DescribePoint_RendersBothComponents()
    {
        string result = StructsSample.describePoint(4, 5);
        Assert.Equal("(4, 5)", result);
    }

    // TWO struct parameters in one signature — Geometry.Manhattan(Point, Point): int.
    [Theory]
    [InlineData(0, 0, 3, 4, 7)]
    [InlineData(1, 2, 1, 2, 0)]
    [InlineData(-2, -3, 2, 3, 10)]
    public void ManhattanDistance_TwoStructParameters(int x1, int y1, int x2, int y2, int expected)
    {
        int result = StructsSample.manhattanDistance(x1, y1, x2, y2);
        Assert.Equal(expected, result);
    }

    // Decision 2a: the Kotlin surface is an immutable data class — equality is BY VALUE, and two
    // independent bridge calls with identical inputs return values that compare equal (not just
    // reference-equal to themselves). No close()/Disposable exists for a struct-backed value.
    [Fact]
    public void PointValueEquality_HoldsAcrossIndependentBridgeCalls()
    {
        bool result = StructsSample.pointValueEqualityRoundTrip();
        Assert.True(result);
    }

    // Instance method (ADR-051 handle class) taking and returning a struct with long/float/double
    // components — the "pass-through" vocabulary beyond Point's plain ints, and the "instance
    // members of bound classes" half of ADR-056's v1 Scope.
    [Fact]
    public void WeighCat_InstanceMethodStructParamAndReturn()
    {
        string result = StructsSample.weighCat("Oreo", 100, 4.0f, 38.5, 1.5f);
        Assert.Equal("Oreo:150,6.0,38.5", result);
    }

    [Fact]
    public void WeighCat_DifferentCat_ScalesIndependently()
    {
        string result = StructsSample.weighCat("Mylo", 120, 5.0f, 38.8, 2.0f);
        Assert.Equal("Mylo:240,10.0,38.8", result);
    }

    // Decision 2a again, with a mixed-type (long/float/double) struct returned from an instance
    // method: two separate calls with identical inputs must produce equal Metrics values.
    [Fact]
    public void MetricsValueEquality_HoldsAcrossIndependentBridgeCalls()
    {
        bool result = StructsSample.metricsValueEqualityRoundTrip("Oreo", 100, 4.0f, 38.5, 1.5f);
        Assert.True(result);
    }

    // Struct PARAMETER with the previously-broken component vocabulary (string/bool/char/enum), on
    // a STATIC method — Cattery2.Announce(Profile): string.
    [Fact]
    public void AnnounceProfile_StructParameterWithFullComponentVocabulary()
    {
        string result = StructsSample.announceProfile("Oreo", true, 'A', CatMood.Playful);
        Assert.Equal("Oreo (Playful) grade A, active", result);
    }

    [Fact]
    public void AnnounceProfile_RestingCat()
    {
        string result = StructsSample.announceProfile("Mylo", false, 'B', CatMood.Sleepy);
        Assert.Equal("Mylo (Sleepy) grade B, resting", result);
    }

    // Struct PARAMETER and RETURN with the same vocabulary — Cattery2.Promote(Profile): Profile.
    // Promote always sets Active=true and Mood=Playful; Tag and Grade pass through unchanged.
    [Fact]
    public void PromoteProfile_StructParameterAndReturn()
    {
        string result = StructsSample.promoteProfile("Mylo", false, 'B', CatMood.Hungry);
        Assert.Equal("Mylo,true,66,PLAYFUL", result);
    }

    // Decision 2a with the full (string/bool/char/enum) component vocabulary: two independently
    // constructed Profile values with identical components compare equal by value.
    [Fact]
    public void ProfileValueEquality_HoldsWithFullComponentVocabulary()
    {
        bool result = StructsSample.profileValueEqualityRoundTrip();
        Assert.True(result);
    }

    // A SETTABLE struct-typed PROPERTY on a bound class (Cattery.CurrentProfile): the getter reads
    // the constructor default (out-pointer reconstruction), the setter writes a new value
    // (decomposed parameters), and a second get proves the write actually took.
    [Fact]
    public void CatteryCurrentProfile_GetSetRoundTrip()
    {
        string result = StructsSample.catteryCurrentProfileRoundTrip("Household", "Mylo", true, 66, CatMood.Hungry);
        Assert.Equal("unset,false,63,SLEEPY|Mylo,true,66,HUNGRY", result);
    }

    // --- ADR-056 deferred: struct methods + computed properties (reconstruct-on-call) ---
    // These exercise members on the struct data class itself (not Geometry/Cattery2 free-function
    // hosts): get-only computed props, instance methods, and static → companion factories.

    // Point.Magnitude: computed property, not a component; int return from reconstructed receiver.
    [Theory]
    [InlineData(3, -4, 7)]
    [InlineData(0, 0, 0)]
    [InlineData(-2, -3, 5)]
    public void PointMagnitude_ComputedProperty(int x, int y, int expected)
    {
        int result = StructsSample.pointMagnitude(x, y);
        Assert.Equal(expected, result);
    }

    // Point.Offset → Point (struct return / out-pointers), then Format (string conversion).
    [Fact]
    public void PointOffset_InstanceMethodStructReturn_ThenFormat()
    {
        // Oreo naps at (1,2); a zoomie shifts him by (10,20).
        string result = StructsSample.offsetPoint(1, 2, 10, 20);
        Assert.Equal("(11,22)", result);
    }

    [Fact]
    public void PointOffset_NegativeDelta_ThenFormat()
    {
        string result = StructsSample.offsetPoint(5, 5, -3, -1);
        Assert.Equal("(2,4)", result);
    }

    // Point.Origin() static → companion, then Format.
    [Fact]
    public void PointOrigin_StaticFactory_ThenFormat()
    {
        string result = StructsSample.pointOriginFormat();
        Assert.Equal("(0,0)", result);
    }

    // Profile.Label: computed string property with string/enum conversion (not a component).
    [Fact]
    public void ProfileLabel_ComputedStringProperty()
    {
        string result = StructsSample.profileLabel("Oreo", true, 'A', CatMood.Playful);
        Assert.Equal("Oreo:Playful", result);
    }

    [Fact]
    public void ProfileLabel_SleepyMylo()
    {
        string result = StructsSample.profileLabel("Mylo", false, 'B', CatMood.Sleepy);
        Assert.Equal("Mylo:Sleepy", result);
    }

    // Profile.WithMood → Profile (full component vocabulary reconstruct + out-pointers), then
    // Label|IsPlayful so both the string and bool computed properties ride the result.
    [Fact]
    public void ProfileWithMood_InstanceMethodStructReturn_ThenLabelAndIsPlayful()
    {
        // Mylo starts Hungry; a treat flips him to Playful.
        string result = StructsSample.profileWithMood(
            "Mylo", false, 66, CatMood.Hungry, CatMood.Playful);
        Assert.Equal("Mylo:Playful|true", result);
    }

    [Fact]
    public void ProfileWithMood_ToSleepy_IsNotPlayful()
    {
        string result = StructsSample.profileWithMood(
            "Oreo", true, 65, CatMood.Playful, CatMood.Sleepy);
        Assert.Equal("Oreo:Sleepy|false", result);
    }

    // Profile.Resting(tag) static → companion factory, then Label.
    [Fact]
    public void ProfileResting_StaticFactory_ThenLabel()
    {
        string result = StructsSample.profileRestingLabel("Oreo");
        Assert.Equal("Oreo:Sleepy", result);
    }
}
