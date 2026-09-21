using TestLibrary.Roster;

namespace IntegrationTests;

// ADR-155: a C# member returning or taking a BCL collection, consumed from Kotlin as an eagerly
// copied Kotlin collection over a single `[count][slots]` buffer.
//
//   C# IntegrationTests (this file)
//     -> (forward bridge)               RosterSample.*   (top-level funs, ADR-007)
//       -> Kotlin test-library          RosterSample.kt
//         -> (reverse bridge, ADR-155)  test.roster.{Roster, Tag, ILabelled}
//           -> real C# TestDependency   Test.Roster.{Roster, Tag, ILabelled}
//
// So every assertion below crosses the bridge four times, and the collection twice: once forward
// as a Kotlin value flattened to a string or an int, once back as the reverse buffer under test.
// A row that only proved "a list came back" would prove the forward half, which shipped in
// ADR-011; each row here names the reverse ELEMENT or CONTAINER seam it stands on.
//
// One row per mechanism, deliberately not per type: an int element needs no conversion at all, a
// string element needs one CoTaskMem allocation per slot, a handle element needs a GCHandle per
// slot with an owner on the Kotlin side, an enum element needs an ordinal, a map interleaves, a
// null element is a zero slot and a null collection is a zero POINTER. A fixture trimmed to
// `IReadOnlyList<int>` would go green with every one of those still wrong.
//
// Four fixture members cannot be asserted from here, because their feature is ABSENCE: the array
// `Roster.Kennels()`, the struct-element `Roster.Beds()`, and the `AddRange(IEnumerable<string>)` /
// `AddRange(List<string>)` pair that collapses to one Kotlin signature and is dropped as a whole
// set. There is no diagnostics seam at this layer (the reverse diagnostics are asserted in the
// plugin's NugetExtractApiIntegrationTest, against the real reader), so the assertion available
// here is the negative one every other skipped reverse member relies on: RosterSample.kt never
// names them, and the rest of `Roster` still binds, which every row below is.
public class RosterRoundTripTests
{
    // `string` elements: one StringToCoTaskMemUTF8 slot each, read and freed per element by the
    // Kotlin side. Two elements, because a one-element list is also what a buffer whose count was
    // written wrong looks like.
    [Fact]
    public void Names_CrossAsAList() =>
        Assert.Equal("Oreo,Mylo", RosterSample.JoinedNames());

    // `int` elements: the ONE element kind that needs no conversion in either direction. The
    // control row, not the feature.
    [Fact]
    public void Ages_CrossWithNoElementConversion() =>
        Assert.Equal(16, RosterSample.SummedAges());

    // A NULLABLE element, present and absent in one collection: the null is a zero SLOT. A reader
    // that resolved nullability after unwrapping to the element binds `String` here and this row
    // fails on the second element only.
    [Fact]
    public void NullableElement_KeepsTheNull() =>
        Assert.Equal("O,<null>", RosterSample.JoinedNicknames());

    // A NULLABLE COLLECTION: a zero POINTER, which is a different thing from a zero slot and from
    // an empty buffer. `isNullable` has failed to be extended for a new RirTypeRef three times
    // (ADR-053), and each time the symptom was a non-null binding over a null value.
    [Fact]
    public void NullableCollection_IsNullNotEmpty() =>
        Assert.True(RosterSample.MaybeNamesIsNull());

    // A MAP: the interleaved `[count][k][v]...` buffer, with a key that needs a string conversion
    // and a `double` value that has to be BIT-CAST into its 8-byte slot rather than widened. Read
    // by key on the Kotlin side, so a buffer written in the wrong order cannot pass by accident,
    // and non-integral, so a widened value reads 9 rather than 9.5 instead of reading correctly.
    [Fact]
    public void Map_CrossesAsAMap_WithABitCastValue() =>
        Assert.Equal(9.5, RosterSample.ScoreOf("Oreo"));

    // Bound-INTERFACE elements: the other handle-shaped slot kind. Per ADR-070 Decision 3 the
    // Kotlin value of each slot is the interface's own handle wrapper, dispatching through its
    // slot table, so this row is red for a generator that resolved the element to the concrete
    // class or lost the interface import the collection's type arguments are the only path to.
    [Fact]
    public void InterfaceElement_DispatchesThroughTheInterface() =>
        Assert.Equal("chip,bell", RosterSample.JoinedLabels());

    // A SET whose element is a bound ENUM declared in ANOTHER bound namespace and used nowhere
    // else on the type. Two mechanisms meet here: the enum ordinal slot, and the import collectors
    // that have to recurse into a collection's type arguments to import the element type at all.
    // SCREAMING_SNAKE, not the C# spelling: a reverse-bound enum's Kotlin entries are generated
    // that way (ReverseEnumTests), and the sample sorts by `CatMood::name`. Nothing to do with
    // collections, but it is what the slot ordinal resolves to.
    [Fact]
    public void Set_OfABoundEnumFromAnotherNamespace() =>
        Assert.Equal("HUNGRY,PLAYFUL", RosterSample.SortedMoods());

    // HANDLE elements: each slot is a fresh strong GCHandle whose owner is the Kotlin wrapper
    // built from it. That the label reads back at all says the handle resolved; that the handles
    // are RELEASED is a separate question, and it is asked in LeakTests/CollectabilityTests.cs,
    // because the alive-count is process-global fixture state.
    [Fact]
    public void HandleElement_IsAUsableWrapper() =>
        Assert.Equal("chip", RosterSample.FirstTagLabel());

    // An `IEnumerable<string>` PARAMETER: Kotlin allocates the buffer inside the memScoped block
    // it already opens for string arguments, and the thunk materializes a List<string> from it
    // before the member runs. Nothing to free on either side, on either path.
    [Fact]
    public void ListParameter_ReachesCSharp() =>
        Assert.Equal(2, RosterSample.EnrollTwo());

    // A SET PARAMETER: the thunk rebuilds a HashSet<string> from the buffer Kotlin wrote, which a
    // list parameter never reaches (NugetCollections.ReadSet). Sorted and joined on the C# side,
    // so an empty or half-read buffer is a different string rather than a smaller count.
    [Fact]
    public void SetParameter_ReachesCSharp() =>
        Assert.Equal("Mylo,Oreo", RosterSample.RosterRoll());

    // A MAP PARAMETER whose value needs the bit-cast (NugetCollections.ReadMap plus the Kotlin
    // interleaved write). Summed, so keys and values written in the wrong order are a wrong number
    // rather than a missing key, and non-integral, so a widened value is visible too.
    [Fact]
    public void MapParameter_ReachesCSharp_WithBitCastValues() =>
        Assert.Equal(7.75, RosterSample.WeighBoth());

    // The two positions ADR-155 INFERS ride the shared conversions rather than verifying it: a
    // collection CONSTRUCTOR parameter, read back through a collection PROPERTY getter, then
    // replaced through its setter and read again. If either turns out to have a hand-written
    // conversion site of its own, this is the row that says so.
    [Fact]
    public void ConstructorParameterAndProperty_RoundTrip() =>
        Assert.Equal("Oreo,Mylo|Mylo", RosterSample.RankingsRoundTrip());

    // The overload pair, and the reason RirCollectionType carries the declared definition name at
    // all. Both C# overloads are applicable to the same argument list, so a thunk that hands over
    // the List<int> it built WITHOUT casting it to its declared parameter type is either CS0121
    // (a shim that does not compile) or, for a pair the compiler can resolve, a silent dispatch to
    // the wrong overload. The two bodies answer differently, which is what makes the silent case
    // visible.
    [Fact]
    public void OverloadPair_DispatchesToDeclaredType() =>
        Assert.Equal("IList,List", RosterSample.PickedBoth());

    // A LAZY `IEnumerable<string>` that throws after one element. User code runs inside the shim's
    // ToArray(), BEFORE the buffer or any string slot exists, so the throw has to arrive as an
    // ordinary ADR-104 managed exception with nothing half-allocated behind it. A shim that
    // enumerated while filling would surface the same message and leak the slots it had written,
    // which is why the ordering is a decision and not an implementation detail.
    [Fact]
    public void ThrowMidEnumeration_SurfacesAsTheManagedException() =>
        Assert.Equal("System.InvalidOperationException|boom", RosterSample.BrokenDescribed());
}
