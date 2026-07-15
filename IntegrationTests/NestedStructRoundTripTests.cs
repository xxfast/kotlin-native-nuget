using TestLibrary.Structs;

namespace IntegrationTests;

// ADR-059: proves a nested struct component (a struct field inside a struct) crosses the reverse
// bridge end to end, flattened depth-first pre-order on the wire and reassembled as a nested
// Kotlin `data class` on the surface —
//   C# IntegrationTests
//     -> (forward bridge, Interop.cs)        NestedSample.* functions
//       -> Kotlin test-library             NestedSample.kt
//         -> (reverse bridge, ADR-059)       test.structs.{Litter,Nest,Litters,Shelter},
//                                            test.nested.{Nursery,Nurseries}
//           -> real C# TestDependency NuGet Test.Structs.{Litter,Nest,Litters,Shelter},
//                                            Test.Nested.{Nursery,Nurseries}
//
// Litter is Shape A OUTER nesting a Shape A component (Profile: the CONVERTED string/bool/char/
// enum vocabulary) and a Shape B component (Extent: DIRECT ints) — A-in-A and B-in-A in one type.
// Nest is Shape B OUTER nesting struct components through all three Shape B sources (a public
// field, a settable auto-property, an init-only auto-property) — A-in-B and B-in-B. Nursery is
// DEPTH 2 (Nursery -> Litter -> Profile) and declared in a DIFFERENT C# namespace
// (Test.Nested, not Test.Structs) from the struct it nests, which is the only way to exercise
// the cross-package import sites nesting creates.
//
// `Litters.Merge(Litter, Litter)` is a deliberate adversarial fixture member (24 flattened ABI
// arguments, over the 22-argument CFunction ceiling) that the generator must SKIP with a
// `skipped_abi_arity_limit` diagnostic. It has no Kotlin binding by design, so it is not, and
// must never be, called from this test file.
//
// These tests are EXPECTED TO FAIL (not even compile) until kotlin-dev lands the fixed-point
// struct classification (reader) and recursive abiArgs/structConstruction (generators) ADR-059
// specifies. That is the intended TDD failure mode — do not weaken these assertions to make them
// pass early.
public class NestedStructRoundTripTests
{
    // Nested struct in (Litter: 8 leaves — Mother{Tag,Active,Grade,Mood}, Basket{Width,Height},
    // Count, Mood), string out. Mother.Mood (Playful) and the outer Mood (Calm) are deliberately
    // different so a DFS-order bug cannot pass by rendering the same value twice.
    [Fact]
    public void LitterSummary_RendersEveryLeaf()
    {
        string result = NestedSample.litterSummary("Oreo", 5);
        Assert.Equal("Oreox5@3x4/Calm", result);
    }

    [Fact]
    public void LitterSummary_DifferentCatAndCount()
    {
        string result = NestedSample.litterSummary("Mylo", 9);
        Assert.Equal("Mylox9@3x4/Calm", result);
    }

    // Nested struct in AND out: Litters.Grow reconstructs the receiver (nested ctor + nested
    // initializer) and returns a new Litter via 8 out-pointers. Every int leaf below (3, 4, count,
    // by) is distinct, so a misaligned out-pointer cannot pass by coincidence.
    [Fact]
    public void GrowLitter_GrowsBasketAndCount_KeepsMotherAndMood()
    {
        string result = NestedSample.growLitter("Mylo", 2, 10);
        Assert.Equal("Mylo|A|PLAYFUL|13x14|12|CALM", result);
    }

    [Fact]
    public void GrowLitter_DifferentCatAndDelta()
    {
        string result = NestedSample.growLitter("Oreo", 6, 3);
        Assert.Equal("Oreo|A|PLAYFUL|6x7|9|CALM", result);
    }

    // DEPTH 2, both directions, ACROSS a Kotlin package boundary (Nursery: test.nested, its
    // Litter component: test.structs). Compiles only if the generated Nursery.kt imports
    // test.structs.Litter and the generated shim `using`s Test.Structs.
    [Fact]
    public void Rehome_GrowsNestedLitter_AndIncrementsRoom()
    {
        string result = NestedSample.rehome("Oreo", 7);
        Assert.Equal("Oreo/3/4/8", result);
    }

    [Fact]
    public void Rehome_DifferentCatAndRoom()
    {
        string result = NestedSample.rehome("Mylo", 12);
        Assert.Equal("Mylo/3/4/13", result);
    }

    // Two struct parameters of DIFFERENT nesting depth in one signature (Litter: 8 leaves,
    // Extent: 2 leaves) — the shape where an abiArgs expansion bug shows up as a misaligned
    // argument rather than a type error.
    [Fact]
    public void CompareLitter_ComparesNestedAndFlatStructAreas()
    {
        string result = NestedSample.compareLitter("Oreo", 4, 5);
        Assert.Equal("6vs20", result);
    }

    [Fact]
    public void CompareLitter_DifferentDimensions()
    {
        string result = NestedSample.compareLitter("Mylo", 6, 2);
        Assert.Equal("6vs12", result);
    }

    // Members on a nesting struct: a computed property (Summary, reconstructed receiver), an
    // instance method returning the struct itself (Grow), and a companion static factory (Single).
    [Fact]
    public void LitterMembers_ComputedPropertyInstanceMethodAndStaticFactory()
    {
        string result = NestedSample.litterMembers("Oreo");
        Assert.Equal("Oreox4@2x3/Calm|5|1", result);
    }

    [Fact]
    public void LitterMembers_DifferentCat()
    {
        string result = NestedSample.litterMembers("Mylo");
        Assert.Equal("Mylox4@2x3/Calm|5|1", result);
    }

    // Shape B outer, all three nested component sources (Collar via a public field, Centre via a
    // settable auto-prop, Bounds via an init-only auto-prop), through a bound HANDLE class's
    // settable struct property: setter = flattened in-args, getter = flattened out-pointers.
    [Fact]
    public void ShelterNest_RoundTripsThroughSettableStructProperty()
    {
        string result = NestedSample.shelterNest("Red", 7, 8);
        Assert.Equal("Red/7,8/30/True|Red|7", result);
    }

    [Fact]
    public void ShelterNest_DifferentColourAndPosition()
    {
        string result = NestedSample.shelterNest("Blue", 1, 2);
        Assert.Equal("Blue/1,2/30/True|Blue|1", result);
    }

    // Value semantics survive nesting: equality is DEEP (composes through every nested data
    // class), and there is still nothing to close(). A change two levels down (mother.tag) is
    // observable.
    [Fact]
    public void NestedValueEquality_HoldsAcrossCopy_AndDiffersTwoLevelsDown()
    {
        bool result = NestedSample.nestedValueEquality();
        Assert.True(result);
    }
}
