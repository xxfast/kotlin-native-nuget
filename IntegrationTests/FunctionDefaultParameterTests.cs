using System.Runtime.InteropServices;
using TestLibrary.Platform;
using TestLibrary.Whiskers;

namespace IntegrationTests;

/// <summary>
/// ADR-164 (superseding ADR-096's omitting overloads): Kotlin <em>function</em> default parameters
/// on the five function routes (class method, <c>object</c> member, companion member, top-level
/// function and extension function) surface as ONE C# method whose defaulted parameters take the
/// nullable form of their type. <c>null</c> means unset. The trailing all-defaulted run is
/// optional (<c>= null</c>); a default followed by a required parameter is required-but-nullable.
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
        // A positional value still binds to the widened `bool?`.
        using var announcer = new Announcer("Oreo");

        Assert.Equal("Oreo: MORNING!", announcer.Announce("morning", true));
    }

    [Fact]
    public void AnnouncerTally_OmittingBothTrailingArguments_UsesBothKotlinDefaults()
    {
        // Both unset. "cats" and false are declared only in Kotlin.
        using var announcer = new Announcer("Mylo");

        Assert.Equal("Mylo counted 3 cats", announcer.Tally(3));
    }

    [Fact]
    public void AnnouncerTally_OmittingOneTrailingArgument_UsesExcitedDefault()
    {
        // Only `excited` unset. Distinct defaults mean a mis-wired mask arm shows up as a wrong
        // string rather than a plausible one.
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
    public void AnnouncerTally_SettingOnlyTheLastDefault_ByName_UsesTheEarlierDefault()
    {
        // Issue #297: skip `label`, set `excited`. Impossible under the omitting overloads.
        using var announcer = new Announcer("Mylo");

        Assert.Equal("Mylo counted 3 cats!!", announcer.Tally(3, excited: true));
    }

    [Fact]
    public void AnnouncerTally_ExposesExactlyOneSignature_WithTwoOptionalNullableParameters()
    {
        var tally = Assert.Single(typeof(Announcer).GetMethods(), m => m.Name == "Tally");
        var parameters = tally.GetParameters();

        Assert.Equal([typeof(int), typeof(string), typeof(bool?)], parameters.Select(p => p.ParameterType));
        Assert.False(parameters[0].IsOptional);
        Assert.True(parameters[1].IsOptional);
        Assert.True(parameters[2].IsOptional);
    }

    // ---- Route 2: a widened overload next to a DECLARED one (Narrator) ----

    [Fact]
    public void NarratorRate_DeclaredIntOverload_IsUnaffectedByTheWidenedSibling()
    {
        // ADR-095 numbers the two declared `rate`s; widening `boost` must not steal `Rate(4)`.
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
    public void NarratorRate_OmittingBoost_UsesBoostDefaultOfOne()
    {
        // "smug".length + 1 = 5. If the unset arm were wired to the OTHER declared `rate` (the
        // numbering hazard this cell exists for), this would answer with the naps sentence.
        using var narrator = new Narrator("Mylo");

        Assert.Equal("Mylo rates smug at 5", narrator.Rate("smug"));
    }

    [Fact]
    public void NarratorRate_ExposesExactlyTheTwoDeclaredSignatures()
    {
        // Rate(int) and Rate(string, int? boost = null). One natural overload set, no visible
        // numbering, no synthesized third entry.
        Assert.NotNull(typeof(Narrator).GetMethod("Rate", [typeof(int)]));
        var widened = typeof(Narrator).GetMethod("Rate", [typeof(string), typeof(int?)]);
        Assert.NotNull(widened);
        Assert.True(widened.GetParameters()[1].IsOptional);
        Assert.Equal(2, typeof(Narrator).GetMethods().Count(m => m.Name == "Rate"));
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
        // Top-level route: the widened entry is PascalCase (ADR-110) and there is only one.
        Assert.Equal("hi Oreo", WhiskersSample.Hail("Oreo"));
    }

    [Fact]
    public void Hail_FullSignature_StillWorks()
    {
        Assert.Equal("HI OREO", WhiskersSample.Hail("Oreo", true));
    }

    // ---- Route 6: extension functions (PawExtensions), receiver survives truncation ----

    [Fact]
    public void PawKnead_OmittingEveryParameter_KeepsTheReceiverAndUsesBothDefaults()
    {
        // All parameters are defaulted, so the empty mask arm carries ZERO arguments. The receiver
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
    public void PawKnead_SettingOnlySurface_ByName_UsesTimesDefault()
    {
        using var paw = new Paw("Oreo");

        Assert.Equal("Oreo kneads the couch 2 times", paw.Knead(surface: "couch"));
    }

    [Fact]
    public void PawKnead_ExposesOneSignatureWithTheReceiverFirst()
    {
        var knead = Assert.Single(typeof(PawExtensions).GetMethods(), m => m.Name == "Knead");
        var parameters = knead.GetParameters();

        Assert.Equal([typeof(Paw), typeof(int?), typeof(string)], parameters.Select(p => p.ParameterType));
        Assert.False(parameters[0].IsOptional);
        Assert.True(parameters[1].IsOptional);
        Assert.True(parameters[2].IsOptional);
    }

    // ---- The middle default: required-but-nullable ----

    [Fact]
    public void Book_FullSignature_Works()
    {
        Assert.Equal("Paws booked 12 spots in Colombo", WhiskersSample.Book("Paws", 12, "Colombo"));
    }

    [Fact]
    public void Book_NullCapacity_UsesKotlinDefault()
    {
        Assert.Equal("Paws booked 3 spots in Colombo", WhiskersSample.Book("Paws", null, "Colombo"));
    }

    [Fact]
    public void Book_CapacityIsRequiredButNullable()
    {
        // `capacity` has a required parameter after it, so C# cannot make it optional. One
        // signature, `capacity` typed `int?`, nothing optional.
        var book = Assert.Single(typeof(WhiskersSample).GetMethods(), m => m.Name == "Book");
        var parameters = book.GetParameters();

        Assert.Equal([typeof(string), typeof(int?), typeof(string)], parameters.Select(p => p.ParameterType));
        Assert.All(parameters, p => Assert.False(p.IsOptional));
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

        Assert.Equal(expected, PlatformApi.BeaconLabel("Oreo's collar"));
    }

    [Fact]
    public void BeaconLabel_FullSignature_StillWorks()
    {
        string expected = IsMacOs
            ? "Mylo's collar at level 30 on macos"
            : "Mylo's collar at level 30 on mingw";

        Assert.Equal(expected, PlatformApi.BeaconLabel("Mylo's collar", 30));
    }

    // ---- expect/actual, overloaded: each `actual` must consult ITS OWN expect overload ----

    [Fact]
    public void Nuzzle_OmittingLoud_UsesTheExpectDeclaredDefaultOfFalse()
    {
        // Two `expect fun nuzzle(...)` namesakes, each with its own trailing default. A name-keyed
        // expect index collapses them and reports "no defaults" for both, so this line is CS7036.
        // Oreo gets the quiet nuzzle; he is asleep on the keyboard.
        string expected = IsMacOs ? "Oreo on macos" : "Oreo on mingw";

        Assert.Equal(expected, PlatformApi.Nuzzle("Oreo"));
    }

    [Fact]
    public void Nuzzle_OmittingPrefix_UsesTheOtherOverloadsExpectDeclaredDefault()
    {
        // The Int overload's own default is "n", declared only on its own expect. Resolving it off
        // the String overload's expect would yield the wrong value rather than no overload at all.
        string expected = IsMacOs ? "n3 on macos" : "n3 on mingw";

        Assert.Equal(expected, PlatformApi.Nuzzle(3));
    }

    [Fact]
    public void Nuzzle_FullSignatures_StillWork()
    {
        string loud = IsMacOs ? "Mylo! on macos" : "Mylo! on mingw";
        string counted = IsMacOs ? "cats:2 on macos" : "cats:2 on mingw";

        Assert.Equal(loud, PlatformApi.Nuzzle("Mylo", true));
        Assert.Equal(counted, PlatformApi.Nuzzle(2, "cats:"));
    }

    [Fact]
    public void Nuzzle_ExposesBothDeclaredOverloads_EachWidened()
    {
        // Two entries, one per declared namesake, each with its own widened default. Stated by
        // signature so a collapse of the two namesakes into one shows up as a missing overload.
        Assert.NotNull(typeof(PlatformApi).GetMethod("Nuzzle", [typeof(string), typeof(bool?)]));
        Assert.NotNull(typeof(PlatformApi).GetMethod("Nuzzle", [typeof(int), typeof(string)]));
        Assert.Equal(2, typeof(PlatformApi).GetMethods().Count(m => m.Name == "Nuzzle"));
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
