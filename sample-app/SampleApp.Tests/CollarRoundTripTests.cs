using SampleLibrary.Sample.Enums;
using SampleLibrary.Structs;

namespace SampleApp.Tests;

// ADR-058: proves a C# Shape B struct (no public constructor; public fields / settable
// auto-properties) crosses the reverse bridge end to end —
//   C# SampleApp.Tests
//     -> (forward bridge, Interop.cs)        CollarSample.* functions
//       -> Kotlin sample-library             CollarSample.kt
//         -> (reverse bridge, ADR-058)       sample.structs.{Extent,Collar,Collars}
//           -> real C# SampleDependency NuGet Sample.Structs.{Extent,Collar,Collars}
//
// Extent is Shape B, pure public fields, all-`int` (direct) components — the canonical ROADMAP
// shape, and the type that used to be `UnsupportedStructs.Unsupported` before ADR-058 made it
// supported. Collar is Shape B with MIXED component sources (a public field, a `set` auto-prop,
// two `init`-only auto-props) spanning the full v1 vocabulary: int (direct) and
// string/bool/char/enum (converted). Component order is C# declaration order
// (Girth, Colour, Belled, Initial, Mood / Width, Height); every construction below is
// deliberately positional or named to pin that order.
//
// These tests are EXPECTED TO FAIL (not even compile) until kotlin-dev adds
// `sample-library/.../structs/CollarSample.kt` and the reader/generator support Shape B. That is
// the intended TDD failure mode — do not weaken these assertions to make them pass early.
public class CollarRoundTripTests
{
    // Shape B struct as a parameter (object-initializer reconstruction on the C# side),
    // non-struct return. Exercises every Collar component: field (Girth), settable auto-prop
    // (Colour, Mood), init-only auto-props (Belled, Initial).
    [Fact]
    public void DescribeCollar_RendersEveryComponent()
    {
        string result = CollarSample.describeCollar(5, "Oreo", true, 'O', CatMood.Playful);
        Assert.Equal("Oreo 5 O Playful True", result);
    }

    [Fact]
    public void DescribeCollar_QuietMylo()
    {
        string result = CollarSample.describeCollar(6, "Mylo", false, 'M', CatMood.Hungry);
        Assert.Equal("Mylo 6 M Hungry False", result);
    }

    // Shape B struct in AND out (out-pointer return of a mixed-vocabulary struct). Collars.Loosen
    // reconstructs the receiver (object initializer) and returns a new Collar (out-pointers).
    [Fact]
    public void LoosenCollar_GrowsGirthByOne_KeepsEveryOtherComponent()
    {
        string result = CollarSample.loosenCollar(3, "Oreo", true, 'O', CatMood.Playful);
        Assert.Equal("4,Oreo,true,79,PLAYFUL", result);
    }

    [Fact]
    public void LoosenCollar_DifferentCat_ScalesIndependently()
    {
        string result = CollarSample.loosenCollar(10, "Mylo", false, 'M', CatMood.Hungry);
        Assert.Equal("11,Mylo,false,77,HUNGRY", result);
    }

    // TWO Shape B structs of DIFFERENT sub-shapes (Collar: mixed sources; Extent: pure fields) in
    // one signature — this is where an `abiArgs` expansion bug would show up.
    [Fact]
    public void PairCollar_TwoDifferentShapeBStructsInOneSignature()
    {
        string result = CollarSample.pairCollar(2, "Oreo", 3, 4);
        Assert.Equal("Oreo:2*/3x4", result);
    }

    [Fact]
    public void PairCollar_DifferentValues()
    {
        string result = CollarSample.pairCollar(9, "Mylo", 5, 6);
        Assert.Equal("Mylo:9*/5x6", result);
    }

    // Extent: Shape B, pure public fields, no properties at all — proves the public-field-only
    // path in isolation. Covers a computed property (Area), an instance method returning a struct
    // (Grow), and a static factory (Unit) -> Kotlin companion object.
    [Fact]
    public void ExtentMembers_ComputedProperty_InstanceMethodReturningStruct_AndStaticFactory()
    {
        string result = CollarSample.extentMembers(3, 4);
        Assert.Equal("12|5x6|1", result);
    }

    [Fact]
    public void ExtentMembers_DifferentDimensions()
    {
        string result = CollarSample.extentMembers(2, 7);
        Assert.Equal("14|4x9|1", result);
    }

    // Collar members: two computed properties (Label: string, IsLoud: bool), an instance method
    // returning a struct (Resize), and a static factory (Plain) -> Kotlin companion object.
    [Fact]
    public void CollarMembers_LoudPlayfulCat()
    {
        string result = CollarSample.collarMembers(4, "Mylo", CatMood.Playful);
        Assert.Equal("Mylo:4*|true|5|Mylo:1", result);
    }

    [Fact]
    public void CollarMembers_QuietSleepyCat_IsNotLoud()
    {
        string result = CollarSample.collarMembers(7, "Oreo", CatMood.Sleepy);
        Assert.Equal("Oreo:7*|false|8|Oreo:1", result);
    }

    // Decision 2a, restated for a struct whose C# original is MUTABLE (settable fields/props):
    // value equality and copy() survive the crossing exactly as they do for the immutable Shape A
    // structs. There is nothing to close() and no reference identity to leak.
    [Fact]
    public void CollarValueEquality_HoldsAcrossCopy_AndDiffersAfterMutation()
    {
        bool result = CollarSample.collarValueEquality();
        Assert.True(result);
    }

    // The documented defence against the ADR-058 Decision 3 declaration-order hazard: named
    // arguments. Compiles (and produces the components in the right slots) only if the generated
    // component names are exactly `girth`, `colour`, `belled`, `initial`, `mood` — the lower-camel
    // form of the C# member names, in C# declaration order.
    [Fact]
    public void CollarNamedArgs_ComponentNamesMatchDeclarationOrder()
    {
        string result = CollarSample.collarNamedArgs();
        Assert.Equal("1,black,true,O,CALM", result);
    }
}
