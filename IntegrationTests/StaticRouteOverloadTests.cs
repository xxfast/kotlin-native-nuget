using TestLibrary.Grooming;

namespace IntegrationTests;

/// <summary>
/// ADR-095: same-name overloads on the four <em>static</em> export routes. Kotlin's
/// <c>GroomingSample.kt</c> declares an overload pair (or ladder) on each of them:
///
/// <list type="bullet">
///   <item>object member — <c>Parlour.describe</c> / <c>Parlour.rate</c></item>
///   <item>companion member — <c>Groomer.Companion.of</c></item>
///   <item>top-level function — <c>bookGrooming</c>, and <c>waitTime</c> on the two-call
///   nullable-primitive shape</item>
///   <item>extension function — <c>Mitten.pat</c> / <c>Mitten.brush</c>, plus <c>Tomcat.pat</c>,
///   the cross-receiver namesake that shares one package-scoped symbol with <c>Mitten.pat</c></item>
/// </list>
///
/// The bridge numbers the native exports (<c>parlour_describe</c>, <c>_2</c>, ...), while the C#
/// surface stays one natural overload set per container with no visible numbering. Every Kotlin
/// body returns a distinct string, so a mis-numbered export dispatches to the wrong declaration
/// visibly instead of returning something the right shape by accident.
/// </summary>
public class StaticRouteOverloadTests
{
    // ---- Route 1: object members (Parlour) ----

    [Fact]
    public void ParlourDescribe_NoArguments_DispatchesToFirstOverload()
    {
        Assert.Equal("the parlour is open", Parlour.Describe());
    }

    [Fact]
    public void ParlourDescribe_WithCat_DispatchesToMarshalledOverload()
    {
        Assert.Equal("Oreo is booked in", Parlour.Describe("Oreo"));
    }

    [Fact]
    public void ParlourRate_IntAndCoat_ShareOneWireShapeAndStayDistinct()
    {
        // Int and Coat both cross as int, so these two are the object-route pair that collides on
        // the private extern name if only the DllImport EntryPoint carries the number.
        Assert.Equal("the parlour is rated 10", Parlour.Rate(10));
        Assert.Equal("the parlour grooms tuxedo coats", Parlour.Rate(Coat.Tuxedo));
        Assert.Equal("the parlour grooms tabby coats", Parlour.Rate(Coat.Tabby));
    }

    // ---- Route 2: companion members (Groomer.Companion) ----

    [Fact]
    public void GroomerOf_WithName_DispatchesToMarshalledOverload()
    {
        using var groomer = Groomer.Of("Oreo");

        Assert.Equal("Oreo", groomer.Name);
    }

    [Fact]
    public void GroomerOf_WithChairs_DispatchesToIntOverload()
    {
        using var groomer = Groomer.Of(3);

        Assert.Equal("groomer of 3 chairs", groomer.Name);
    }

    [Fact]
    public void GroomerOf_WithCoat_DispatchesToEnumOverload()
    {
        // Same wire shape as Of(int): the companion route's Native_Companion_* extern pair.
        using var groomer = Groomer.Of(Coat.Tabby);

        Assert.Equal("tabby groomer", groomer.Name);
    }

    // ---- Route 3: top-level functions (GroomingSample) ----

    [Fact]
    public void BookGrooming_NoArguments_DispatchesToFirstOverload()
    {
        Assert.Equal("the next slot is free", GroomingSample.bookGrooming());
    }

    [Fact]
    public void BookGrooming_WithCat_DispatchesToMarshalledOverload()
    {
        Assert.Equal("Mylo is groomed at noon", GroomingSample.bookGrooming("Mylo"));
    }

    [Fact]
    public void WaitTime_NoArguments_DispatchesToFirstTwoCallOverload()
    {
        // Nullable primitive return: the ADR-002 two-call shape (waitTime_has_value +
        // waitTime_value), a different planner entry point from every other cell here.
        Assert.Equal(15, GroomingSample.waitTime());
    }

    [Fact]
    public void WaitTime_WithCat_DispatchesToSecondTwoCallOverload()
    {
        Assert.Equal(4, GroomingSample.waitTime("Mylo"));
    }

    [Fact]
    public void WaitTime_WithBlankCat_ReturnsNullFromTheNumberedPresenceCall()
    {
        // The presence half of the numbered pair has to belong to *this* overload; a mis-numbered
        // _has_value would answer for waitTime() instead, which is never null.
        Assert.Null(GroomingSample.waitTime("   "));
    }

    // ---- Route 4: extension functions (MittenExtensions / TomcatExtensions) ----

    [Fact]
    public void MittenPat_NoArguments_DispatchesToFirstOverload()
    {
        using var oreo = new Mitten("Oreo");

        Assert.Equal("Oreo purrs", oreo.Pat());
    }

    [Fact]
    public void MittenPat_WithStyle_DispatchesToMarshalledOverload()
    {
        using var oreo = new Mitten("Oreo");

        Assert.Equal("Oreo enjoys a gentle pat", oreo.Pat("gentle"));
    }

    [Fact]
    public void MittenBrush_IntAndCoat_ShareOneWireShapeAndStayDistinct()
    {
        using var mylo = new Mitten("Mylo");

        Assert.Equal("Mylo is brushed 3 times", mylo.Brush(3));
        Assert.Equal("Mylo is brushed for a tabby coat", mylo.Brush(Coat.Tabby));
    }

    [Fact]
    public void TomcatPat_SameNameDifferentReceiver_ResolvesToItsOwnExport()
    {
        // Extension plan symbols are receiver-agnostic ($package.$name), so this declaration
        // shares a symbol with Mitten.pat and crashes generation today. Both must survive, each on
        // its own {Receiver}Extensions class.
        using var oreo = new Mitten("Oreo");
        using var mylo = new Tomcat("Mylo");

        Assert.Equal("Oreo purrs", oreo.Pat());
        Assert.Equal("Mylo tolerates exactly one pat", mylo.Pat());
    }

    // ---- The numbering is a native-export detail and must not leak ----

    [Fact]
    public void EveryStaticRoute_ExposesOverloadsAsOneSet_WithoutNumberedPublicNames()
    {
        Assert.NotNull(typeof(Parlour).GetMethod("Describe", Type.EmptyTypes));
        Assert.NotNull(typeof(Parlour).GetMethod("Describe", new[] { typeof(string) }));
        Assert.NotNull(typeof(Parlour).GetMethod("Rate", new[] { typeof(int) }));
        Assert.NotNull(typeof(Parlour).GetMethod("Rate", new[] { typeof(Coat) }));

        Assert.NotNull(typeof(Groomer).GetMethod("Of", new[] { typeof(string) }));
        Assert.NotNull(typeof(Groomer).GetMethod("Of", new[] { typeof(int) }));
        Assert.NotNull(typeof(Groomer).GetMethod("Of", new[] { typeof(Coat) }));

        Assert.NotNull(typeof(GroomingSample).GetMethod("bookGrooming", Type.EmptyTypes));
        Assert.NotNull(typeof(GroomingSample).GetMethod("bookGrooming", new[] { typeof(string) }));
        Assert.NotNull(typeof(GroomingSample).GetMethod("waitTime", Type.EmptyTypes));
        Assert.NotNull(typeof(GroomingSample).GetMethod("waitTime", new[] { typeof(string) }));

        Assert.NotNull(typeof(MittenExtensions).GetMethod("Pat", new[] { typeof(Mitten) }));
        Assert.NotNull(typeof(MittenExtensions).GetMethod("Pat", new[] { typeof(Mitten), typeof(string) }));
        Assert.NotNull(typeof(MittenExtensions).GetMethod("Brush", new[] { typeof(Mitten), typeof(int) }));
        Assert.NotNull(typeof(MittenExtensions).GetMethod("Brush", new[] { typeof(Mitten), typeof(Coat) }));
        Assert.NotNull(typeof(TomcatExtensions).GetMethod("Pat", new[] { typeof(Tomcat) }));

        Type[] containers =
        [
            typeof(Parlour), typeof(Groomer), typeof(GroomingSample),
            typeof(MittenExtensions), typeof(TomcatExtensions),
        ];
        foreach (Type container in containers)
        {
            // Property accessors (get_Name) are special-name; only real methods are checked here.
            Assert.DoesNotContain(container.GetMethods(), m => !m.IsSpecialName && m.Name.Contains('_'));
        }
    }
}
