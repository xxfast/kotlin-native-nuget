using TestLibrary.Cat;
using TestLibrary.Issue126;
using TestLibrary.Issue54;

namespace IntegrationTests;

/// <summary>
/// Issue #126. A handle-typed parameter on a <c>Flow</c>/<c>StateFlow</c>-returning or
/// <c>suspend</c> member was spelled <c>IntPtr</c>, because the legacy routes never asked the
/// classifier about a non-generic parameter and fell back to the primitive table. Five overloads
/// of <c>watch</c> therefore collapsed onto one C# signature:
/// <code>
/// Interop.cs(1309,94): error CS0111: Type 'ObservationRadio' already defines a member called
/// 'Watch' with the same parameter types
/// </code>
/// The collision is the symptom. <c>IntPtr</c> is the defect: the sealed arms only have
/// <c>internal</c> handle constructors, so even a single non-overloaded <c>Watch(IntPtr)</c> would
/// have no legitimate caller. Every argument below is therefore obtained from the bridge and never
/// spelled as a pointer, which is the issue's fourth requirement as an executable assertion.
///
/// The return position on the same members is already right, and so is the ordinary ADR-062 plan
/// route for the same types (<see cref="Issue54Tests"/>, <see cref="FlatSealedSubclassTests"/>).
/// Only the legacy parameter position falls back, which is why the assertions here are all on
/// parameters.
///
/// Oreo (black with the white bib) reports in alive. Mylo (brown and creamy) is only ever a rumour
/// from the box, so he arrives as whatever the observation says he is.
/// </summary>
public class LegacyRouteHandleParameterTests
{
    [Fact]
    public void Watch_WithANestedSealedArm_TakesTheArmTypeAndEmitsItsValue()
    {
        // The issue's exact shape: `fun watch(observation: Observation.Alive): StateFlow<String>`.
        // The parameter must be the nested arm `Observation.Alive`, and the value must be derived
        // from the arm's payload, so a pointer that arrived as a number cannot answer it.
        using var radio = new ObservationRadio();
        using Observation observation = ObservationKt.OpenBox("Oreo");
        Observation.Alive alive = Assert.IsType<Observation.Alive>(observation);

        Assert.Equal("alive:Oreo", radio.Watch(alive).Value);
    }

    [Fact]
    public void Watch_WithASiblingSealedArm_TakesTheNamespaceLevelType()
    {
        // A sibling arm is declared at namespace level in C# (`TestLibrary.Issue54.Label`), not
        // nested under its base, so the parameter has to be spelled the same way the return and
        // property routes already spell it. A fix that hardcoded the nested spelling fails here.
        using var radio = new ObservationRadio();
        using var factory = new FlatShapeFactory();
        using Label sill = factory.Label("windowsill");

        Assert.Equal("label:windowsill", radio.Watch(sill).Value);
    }

    [Fact]
    public void Watch_WithTheSealedBase_Discriminates()
    {
        // The sealed base at a parameter position. C# only ever writes `_handle`, so the ADR-009
        // discriminator is irrelevant here and Kotlin is the side that picks the arm. All three
        // arms, because the `object` arm crosses differently from the two payload arms.
        using var radio = new ObservationRadio();
        using Observation alive = ObservationKt.OpenBox("Oreo");
        using Observation dead = ObservationKt.OpenBox("Rex");
        using Observation unknown = ObservationKt.PeekBox();

        Assert.Equal("base:alive:Oreo", radio.Watch(alive).Value);
        Assert.Equal("base:dead:The cat was not Rex", radio.Watch(dead).Value);
        Assert.Equal("base:superposition", radio.Watch(unknown).Value);
    }

    [Fact]
    public void Watch_WithAnOrdinaryClass_TakesTheWrapper()
    {
        // Nothing about this defect is sealed-specific: an ordinary exported class is the same
        // `ObjectHandle`, and it was `IntPtr` too. Constructed directly, so the fact does not
        // depend on any sealed machinery at all.
        using var radio = new ObservationRadio();
        using var mylo = new Cat("Mylo", 9);

        Assert.Equal("cat:Mylo", radio.Watch(mylo).Value);
    }

    [Fact]
    public async Task LogAsync_WithASealedArm_ReturnsTheArmsPayload()
    {
        // The `_async` builder is the second hand-written copy of the same mistake, so it needs
        // its own fact rather than being inferred from the flow route.
        using var radio = new ObservationRadio();
        using Observation observation = ObservationKt.OpenBox("Oreo");
        Observation.Alive alive = Assert.IsType<Observation.Alive>(observation);

        Assert.Equal("logged:Oreo", await radio.LogAsync(alive));
    }

    [Fact]
    public void Tally_WithACollectionAndAHandle_PassesBoth()
    {
        // One member carrying both parameter shapes: a collection that is built and disposed
        // around the call, and a handle that is borrowed and needs no conversion at the seam.
        // The result separates the two contributions (ten per kind, one per letter), so a member
        // that dropped either parameter cannot produce 24.
        using var radio = new ObservationRadio();
        using Observation observation = ObservationKt.OpenBox("Oreo");
        Observation.Alive alive = Assert.IsType<Observation.Alive>(observation);

        Assert.Equal(24, radio.Tally(["biscuit", "milo"], alive).Value);
    }

    [Fact]
    public async Task Watch_ArgumentDisposedMidCollection_KeepsCollecting()
    {
        // The ownership claim, from the consumer's side. The Kotlin export dereferences the
        // handle into a local *before* `scope.launch`, so the coroutine holds a strong Kotlin
        // reference and disposing the C# wrapper mid-flow cannot invalidate what it is still
        // reading. The fixture's later emissions read the observation again, 20ms apart, after
        // the export has returned; without the eager dereference there is nothing left to read.
        using var radio = new ObservationRadio();
        Observation observation = ObservationKt.OpenBox("Oreo");
        Observation.Alive alive = Assert.IsType<Observation.Alive>(observation);

        var seen = new List<string>();
        var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        await foreach (string reading in radio.Watch(alive).WithCancellation(cts.Token))
        {
            seen.Add(reading);
            if (seen.Count == 1)
            {
                // The argument the flow is still reading goes away here, not the flow itself.
                observation.Dispose();
                continue;
            }

            cts.Cancel();
        }

        Assert.Equal("alive:Oreo", seen[0]);
        // Conflation is allowed, so only the fact that a post-dispose reading arrived is asserted,
        // not which tick it was.
        Assert.StartsWith("alive:Oreo:", seen[^1]);
    }
}
