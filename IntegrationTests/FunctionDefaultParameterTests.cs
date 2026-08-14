using System.Runtime.InteropServices;
using TestLibrary.Platform;
using TestLibrary.Whiskers;

namespace IntegrationTests;

/// <summary>
/// ADR-096: Kotlin <em>function</em> default parameters surfaced as C# overloads, the same
/// <c>@JvmOverloads</c> trailing rule ADR-091 shipped for constructors, now on the five function
/// routes: class method, <c>object</c> member, companion member, top-level function and extension
/// function.
///
/// For parameters <c>p1..pn</c>, <c>d</c> is the number of <em>trailing</em> parameters that all
/// have a default; for each <c>k</c> in <c>1..d</c> there is one extra overload taking
/// <c>p1..p(n-k)</c>. The full signature is always kept. A default followed by a required parameter
/// synthesizes nothing, because the generated wrapper is a positional Kotlin call.
///
/// Every test here calls through to Kotlin and asserts the <em>value</em> the omitted argument took,
/// not merely that the overload compiles: KSP only ever exposes the <c>hasDefault</c> bit, so the
/// default is computed by Kotlin at call time and can never be copied into the C# source. Oreo and
/// Mylo have opinions about kibble portions, and neither of them wants to spell out "2 scoops"
/// every single time.
/// </summary>
public class FunctionDefaultParameterTests
{
    private static readonly bool IsMacOs = RuntimeInformation.IsOSPlatform(OSPlatform.OSX);

    // ---- Route 1: class methods (Announcer) ----

    [Fact]
    public void AnnouncerAnnounce_OmittingLoud_UsesKotlinDefaultOfFalse()
    {
        // The ROADMAP item's own shape: `announce(message: String, loud: Boolean = false)`.
        // `message` needs conversion at the seam, `loud` does not, so both halves of the wire are
        // under test in one call.
        using var announcer = new Announcer("Oreo");

        Assert.Equal("Oreo: morning", announcer.Announce("morning"));
    }

    [Fact]
    public void AnnouncerAnnounce_FullSignature_StillWorksUnchanged()
    {
        // The declared entry must render byte-identically, so the synthesized one cannot have
        // displaced it.
        using var announcer = new Announcer("Oreo");

        Assert.Equal("Oreo: MORNING!", announcer.Announce("morning", true));
    }

    [Fact]
    public void AnnouncerTally_OmittingBothTrailingArguments_UsesBothKotlinDefaults()
    {
        // k = 2, the deepest suffix. "cats" and false are declared only in Kotlin.
        using var announcer = new Announcer("Mylo");

        Assert.Equal("Mylo counted 3 cats", announcer.Tally(3));
    }

    [Fact]
    public void AnnouncerTally_OmittingOneTrailingArgument_UsesExcitedDefault()
    {
        // k = 1. Distinct defaults mean a mis-wired suffix shows up as a wrong string rather than
        // a plausible one.
        using var announcer = new Announcer("Mylo");

        Assert.Equal("Mylo counted 3 kittens", announcer.Tally(3, "kittens"));
    }

    [Fact]
    public void AnnouncerTally_FullSignature_UsesEveryGivenArgument()
    {
        using var announcer = new Announcer("Mylo");

        Assert.Equal("Mylo counted 3 kittens!!", announcer.Tally(3, "kittens", true));
    }

    [Fact]
    public void AnnouncerTally_ExposesExactlyThreeArities()
    {
        // d = 2 trailing defaults => d extra entries, linear and not the 2^n combinatorial set.
        Assert.NotNull(typeof(Announcer).GetMethod("Tally", [typeof(int)]));
        Assert.NotNull(typeof(Announcer).GetMethod("Tally", [typeof(int), typeof(string)]));
        Assert.NotNull(typeof(Announcer).GetMethod("Tally", [typeof(int), typeof(string), typeof(bool)]));
        Assert.Equal(3, typeof(Announcer).GetMethods().Count(m => m.Name == "Tally"));
    }

    // ---- Route 2: a synthesized overload next to DECLARED ones (Narrator) ----

    [Fact]
    public void NarratorRate_DeclaredIntOverload_IsUnaffectedByTheSynthesizedEntry()
    {
        // ADR-095 numbers the two declared `rate`s first; synthesized entries are appended after
        // them in the same per-(class, name) counter, so this one must keep dispatching to itself.
        using var narrator = new Narrator("Mylo");

        Assert.Equal("Mylo rates 4 naps", narrator.Rate(4));
    }

    [Fact]
    public void NarratorRate_DeclaredStringOverload_FullSignature_Works()
    {
        using var narrator = new Narrator("Mylo");

        Assert.Equal("Mylo rates smug at 7", narrator.Rate("smug", 3));
    }

    [Fact]
    public void NarratorRate_SynthesizedOverload_UsesBoostDefaultOfOne()
    {
        // "smug".length + 1 = 5. If the synthesized entry were wired to the OTHER declared `rate`
        // (the numbering hazard this cell exists for), this would not compile or would answer with
        // the naps sentence instead.
        using var narrator = new Narrator("Mylo");

        Assert.Equal("Mylo rates smug at 5", narrator.Rate("smug"));
    }

    [Fact]
    public void NarratorRate_ExposesExactlyTheThreeExpectedSignatures()
    {
        // ADR-096's worked example: Rate(int), Rate(string, int), Rate(string). One natural
        // overload set, no visible numbering.
        Assert.NotNull(typeof(Narrator).GetMethod("Rate", [typeof(int)]));
        Assert.NotNull(typeof(Narrator).GetMethod("Rate", [typeof(string), typeof(int)]));
        Assert.NotNull(typeof(Narrator).GetMethod("Rate", [typeof(string)]));
        Assert.Equal(3, typeof(Narrator).GetMethods().Count(m => m.Name == "Rate"));
    }

    // ---- Route 3: object members (Kibble) ----

    [Fact]
    public void KibbleScoop_OmittingScoops_UsesKotlinDefaultOfTwo()
    {
        Assert.Equal("2 scoops of tuna", Kibble.Scoop("tuna"));
    }

    [Fact]
    public void KibbleScoop_FullSignature_StillWorks()
    {
        Assert.Equal("5 scoops of tuna", Kibble.Scoop("tuna", 5));
    }

    // ---- Route 4: companion members (Basket.Companion) ----

    [Fact]
    public void BasketOf_OmittingCapacity_UsesKotlinDefaultOfFour()
    {
        using var basket = Basket.Of("Colombo");

        Assert.Equal("Colombo basket for 4", basket.Label);
    }

    [Fact]
    public void BasketOf_FullSignature_StillWorks()
    {
        using var basket = Basket.Of("Colombo", 9);

        Assert.Equal("Colombo basket for 9", basket.Label);
    }

    // ---- Route 5: top-level functions (WhiskersSample) ----

    [Fact]
    public void Hail_OmittingLoud_UsesKotlinDefaultOfFalse()
    {
        // Top-level is one of the two routes where the synthesized entry shares its declaration
        // node with the declared one, so `planFor` has to become plural before this can exist at
        // all. Top-level functions keep their Kotlin camelCase name (ADR-074).
        Assert.Equal("hi Oreo", WhiskersSample.hail("Oreo"));
    }

    [Fact]
    public void Hail_FullSignature_StillWorks()
    {
        Assert.Equal("HI OREO", WhiskersSample.hail("Oreo", true));
    }

    // ---- Route 6: extension functions (PawExtensions), receiver survives truncation ----

    [Fact]
    public void PawKnead_OmittingEveryParameter_KeepsTheReceiverAndUsesBothDefaults()
    {
        // All parameters are defaulted, so at k = 2 the plan carries ZERO parameters. The receiver
        // is a ForwardReceiver.Value rather than a plan parameter, so it must survive: `Knead()`
        // still has to know it is Oreo's paw.
        using var paw = new Paw("Oreo");

        Assert.Equal("Oreo kneads the blanket 2 times", paw.Knead());
    }

    [Fact]
    public void PawKnead_OmittingOnlySurface_UsesBlanketDefault()
    {
        using var paw = new Paw("Mylo");

        Assert.Equal("Mylo kneads the blanket 9 times", paw.Knead(9));
    }

    [Fact]
    public void PawKnead_FullSignature_StillWorks()
    {
        using var paw = new Paw("Mylo");

        Assert.Equal("Mylo kneads the couch 9 times", paw.Knead(9, "couch"));
    }

    [Fact]
    public void PawKnead_ExposesEveryAritySignatureWithTheReceiverFirst()
    {
        Assert.NotNull(typeof(PawExtensions).GetMethod("Knead", [typeof(Paw)]));
        Assert.NotNull(typeof(PawExtensions).GetMethod("Knead", [typeof(Paw), typeof(int)]));
        Assert.NotNull(typeof(PawExtensions).GetMethod("Knead", [typeof(Paw), typeof(int), typeof(string)]));
    }

    // ---- The middle-default negative: absent output, not wrong output ----

    [Fact]
    public void Book_FullSignature_Works()
    {
        Assert.Equal("Paws booked 12 spots in Colombo", WhiskersSample.book("Paws", 12, "Colombo"));
    }

    [Fact]
    public void Book_HasNoOmittingOverload()
    {
        // `capacity` has a required parameter after it, so a positional Kotlin call can never skip
        // it. Stated by signature so a future "helpful" combinatorial expansion trips here.
        Assert.Null(typeof(WhiskersSample).GetMethod("book", [typeof(string), typeof(int)]));
        Assert.Null(typeof(WhiskersSample).GetMethod("book", [typeof(string)]));
        Assert.Equal(1, typeof(WhiskersSample).GetMethods().Count(m => m.Name == "book"));
    }

    // ---- expect/actual: the default lives on the expect, never on the exported actual ----

    [Fact]
    public void BeaconLabel_OmittingLevel_UsesTheExpectDeclaredDefault()
    {
        // Kotlin forbids an `actual` from restating a default, so every parameter of the EXPORTED
        // declaration reports hasDefault = false. Without the expectsByName lookup the planner
        // concludes "no defaults" and this line is CS7036. The value 7 exists only on the expect.
        string expected = IsMacOs
            ? "Oreo's collar at level 7 on macos"
            : "Oreo's collar at level 7 on mingw";

        Assert.Equal(expected, PlatformApi.beaconLabel("Oreo's collar"));
    }

    [Fact]
    public void BeaconLabel_FullSignature_StillWorks()
    {
        string expected = IsMacOs
            ? "Mylo's collar at level 30 on macos"
            : "Mylo's collar at level 30 on mingw";

        Assert.Equal(expected, PlatformApi.beaconLabel("Mylo's collar", 30));
    }

    // ---- The numbering is a native-export detail and must not leak ----

    [Fact]
    public void NoRouteLeaksItsNumberedExportNameIntoThePublicSurface()
    {
        Type[] containers =
        [
            typeof(Announcer), typeof(Narrator), typeof(Kibble),
            typeof(Basket), typeof(WhiskersSample), typeof(PawExtensions),
        ];

        foreach (Type container in containers)
        {
            // Property accessors (get_Label) are special-name; only real methods are checked here.
            Assert.DoesNotContain(container.GetMethods(), m => !m.IsSpecialName && m.Name.Contains('_'));
        }
    }
}
