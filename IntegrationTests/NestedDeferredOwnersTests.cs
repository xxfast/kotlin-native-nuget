using System;
using System.Linq;
using System.Reflection;
using TestLibrary.Nested;

namespace IntegrationTests;

/// <summary>
/// ADR-134: the owner kinds ADR-133 deferred and this ADR admits. A public nested type is declared
/// as a real C# nested type under (1) an <c>interface</c> owner, (2) a sealed base owner including
/// an ADR-112 eligible <c>sealed interface</c>, (3) a sealed <em>arm</em> owner, and (4) a nested
/// <c>value class</c> is declared as a nested <c>readonly record struct</c>.
///
/// <para>Before this ADR every cell here was absent twice over:
/// <c>SKIPPED_NESTED_DECLARATION</c> at the declaration (naming the owner kind) and
/// <c>UNDECLARED_CLASS</c> at every member typed with one, so the owner did not even carry
/// <c>BarAt</c>/<c>DetailOf</c>/<c>TraceOf</c>/<c>WeightOf</c>.</para>
///
/// <para>Each cell asserts the declaration <em>and</em> a round trip, because the two fail
/// independently: a declaration-only implementation satisfies every <c>GetNestedType</c> below and
/// still cannot marshal a handle minted at <c>purr_on_trace_create</c>, and a positions-only one
/// binds members typed with a type C# cannot name (CS0426).</para>
///
/// <para>The spelling cells are the ADR's one reversal of ADR-133: <c>I</c> attaches to
/// <em>every</em> interface segment of a chain (<c>ICage.Bar</c>), except an eligible sealed
/// interface segment, which renders as the abstract class and so drops it (<c>Beam.Lens</c>, with
/// no <c>IBeam</c> anywhere in the assembly). That last one is the gate-order cell: the owner walk
/// tests <c>INTERFACE</c> before sealed, and if the interface arm claims <c>Beam</c> its child is
/// partitioned into a slot the sealed renderer never reads and vanishes with no diagnostic.</para>
///
/// <para>Oreo purrs at two levels of nesting. Mylo weighs exactly one hamper.</para>
/// </summary>
public class NestedDeferredOwnersTests
{
    // --- Cell 1: interface owner -> ICage.Bar ---

    [Fact]
    public void NestedClass_UnderAnInterfaceOwner_IsDeclaredInsideTheInterface()
    {
        Type? bar = typeof(ICage).GetNestedType("Bar");

        Assert.NotNull(bar);
        Assert.True(bar!.IsNested, "expected Bar nested in ICage, not flattened to namespace root");
        Assert.Same(typeof(ICage), bar.DeclaringType);
    }

    [Fact]
    public void NestedClass_UnderAnInterfaceOwner_IsSpelledWithTheIOnEveryInterfaceSegment()
    {
        // ADR-133 put the `I` on the last segment only; ADR-134 puts it on every interface segment.
        // The chain here is one interface deep, so the full name has to read `...ICage+Bar`.
        Type bar = typeof(ICage).GetNestedType("Bar")!;

        Assert.Equal("ICage+Bar", bar.FullName!.Split('.').Last());
        Assert.DoesNotContain(
            typeof(ICage).Assembly.GetTypes(),
            type => type.Name == "IBar" || (type.Name == "Bar" && !type.IsNested));
    }

    [Fact]
    public void NestedClass_UnderAnInterfaceOwner_RoundTripsThroughTheImplementation()
    {
        // An interface has no constructor, so the handle comes from an exported implementing class;
        // `cage_bar_create` mints it inside Kotlin and C# reads the property back out.
        using var cage = new WireCage(4);
        using ICage.Bar bar = cage.BarAt();

        Assert.Equal(4, bar.N);
        Assert.Equal("bar#4", bar.Describe());
        Assert.Equal("wire/4", cage.Label());
    }

    [Fact]
    public void NestedClass_UnderAnInterfaceOwner_RoundTripsThroughTheInterfaceItself()
    {
        // Dispatch through the interface, not the class: the nested type is declared on `ICage`,
        // so `ICage.BarAt()` has to be part of the interface surface, not only the wrapper's.
        ICage cage = new WireCage(7);
        using ICage.Bar bar = cage.BarAt();

        Assert.Equal(7, bar.N);
        ((IDisposable)cage).Dispose();
    }

    // --- Cell 2: sealed base owner -> Purr.Detail ---

    [Fact]
    public void NestedClass_UnderASealedBaseOwner_IsDeclaredBesideTheArms()
    {
        Type? detail = typeof(Purr).GetNestedType("Detail");

        Assert.NotNull(detail);
        Assert.Same(typeof(Purr), detail!.DeclaringType);
        // ADR-009's block already holds the arms; the nested declaration joins them, and neither
        // displaces the other.
        Assert.NotNull(typeof(Purr).GetNestedType("On"));
        Assert.NotNull(typeof(Purr).GetNestedType("Off"));
    }

    [Fact]
    public void NestedClass_UnderASealedBaseOwner_RoundTrips()
    {
        // An arm is reached through a factory and a type test, never through `new`: ADR-009 gives
        // it only `internal On(IntPtr handle)`, and `new Purr.On(3)` binds to *that* (IntPtr is
        // nint, which takes an int implicitly) and hands the bridge 3 as a stable-ref address.
        using Purr purr = Deferred.PurringPurr(3);
        Purr.On on = Assert.IsType<Purr.On>(purr);
        using Purr.Detail detail = on.DetailOf();

        Assert.Equal("purr", detail.Text);
        Assert.Equal("detail:purr", detail.Describe());
    }

    [Fact]
    public void NestedClass_UnderASealedBaseOwner_IsReachableFromEveryArm()
    {
        // `detailOf` is a concrete method on the base, so the payload-free arm inherits it: an
        // implementation that hangs the nested declaration off the arm blocks passes the cell
        // above and fails this one.
        using Purr off = Deferred.SleepingPurr();
        using Purr.Detail detail = off.DetailOf();

        Assert.IsType<Purr.Off>(off);
        Assert.Equal("purr", detail.Text);
    }

    // --- Cell 3: sealed arm owner -> Purr.On.Trace ---

    [Fact]
    public void NestedClass_UnderASealedArmOwner_IsDeclaredInsideTheArm()
    {
        Type? trace = typeof(Purr.On).GetNestedType("Trace");

        Assert.NotNull(trace);
        Assert.Same(typeof(Purr.On), trace!.DeclaringType);
        // Depth: the arm is itself nested in the base, so Trace is two levels in.
        Assert.Same(typeof(Purr), typeof(Purr.On).DeclaringType);
    }

    [Fact]
    public void NestedClass_UnderASealedArmOwner_RoundTrips()
    {
        // `purr_on_trace_create`: the prefix chain is base + arm + child, which is the thing a
        // "use the immediate owner's prefix" implementation gets wrong (ADR-117 collision).
        using Purr purr = Deferred.PurringPurr(9);
        Purr.On on = Assert.IsType<Purr.On>(purr);
        using Purr.On.Trace trace = on.TraceOf();

        Assert.Equal(9, trace.At);
        Assert.Equal("trace@9", trace.Describe());
    }

    // --- Cell 4: eligible sealed interface owner -> Beam.Lens, the gate-order cell ---

    [Fact]
    public void NestedClass_UnderAnEligibleSealedInterface_IsDeclaredUnderTheAbstractBase()
    {
        // The silent-loss cell. An eligible sealed interface renders as `public abstract class
        // Beam` (ADR-112), so its child is declared there and spelled with no `I`.
        Type? lens = typeof(Beam).GetNestedType("Lens");

        Assert.NotNull(lens);
        Assert.Same(typeof(Beam), lens!.DeclaringType);
        Assert.True(typeof(Beam).IsAbstract && typeof(Beam).IsClass, "expected Beam to render as the abstract class");
        Assert.DoesNotContain(typeof(Beam).Assembly.GetTypes(), type => type.Name == "IBeam");
    }

    [Fact]
    public void NestedClass_UnderAnEligibleSealedInterface_RoundTrips()
    {
        using Beam beam = Deferred.LitBeam(11);
        Beam.Lit lit = Assert.IsType<Beam.Lit>(beam);
        using Beam.Lens lens = lit.LensOf();

        Assert.Equal(11, lens.Strength);
        Assert.Equal("lens x11", lens.Describe());
    }

    // --- Cell 5: nested value class -> Hamper.Weight, a nested readonly record struct ---

    [Fact]
    public void NestedValueClass_IsDeclaredAsANestedValueType()
    {
        Type? weight = typeof(Hamper).GetNestedType("Weight");

        Assert.NotNull(weight);
        Assert.True(weight!.IsValueType, "expected Weight to be a struct, not a handle class");
        Assert.Same(typeof(Hamper), weight.DeclaringType);
        // A record struct, like every top-level value class (ADR-014), so it is not IDisposable.
        Assert.False(typeof(IDisposable).IsAssignableFrom(weight));
    }

    [Fact]
    public void NestedValueClass_RoundTripsAtAReturnAndAParameterPosition()
    {
        // Crosses as its underlying Int (ADR-077) at both directions; `Hamper.Weight` is only the
        // C# spelling, which is exactly why the declaration and the positions can fail separately.
        using var hamper = new Hamper("oreo");
        Hamper.Weight weight = hamper.WeightOf();

        Assert.Equal(400, weight.Grams);
        Assert.Equal(400, hamper.GramsOf(weight));
        Assert.Equal("hamper oreo", hamper.Label());
    }

    [Fact]
    public void NestedValueClass_CarriesItsOwnMembers_UnderTheChainedPrefix()
    {
        // `hamper_weight_isHeavy` / `hamper_weight_get_kilos`: the value class's own members export
        // under the whole enclosing chain, not the bare simple name.
        var light = new Hamper.Weight(400);
        var heavy = new Hamper.Weight(2400);

        Assert.False(light.IsHeavy());
        Assert.True(heavy.IsHeavy());
        Assert.Equal(2.4, heavy.Kilos, 3);
    }
}
