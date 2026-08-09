using System.Runtime.InteropServices;
using TestLibrary.Cat;
using TestLibrary.Platform;

namespace IntegrationTests;

/// <summary>
/// ADR-091: Kotlin constructor default parameters surfaced as C# constructor overloads, using the
/// <c>@JvmOverloads</c> rule. For each maximal TRAILING run of defaulted parameters, one omitting
/// overload per suffix length is synthesized; Kotlin computes the default at call time (KSP only
/// ever exposes the <c>hasDefault</c> bit, never the value). A default with a required parameter
/// after it produces nothing.
///
/// Oreo travels a lot. His carrier, his kennel booking and his scratch post all have sensible
/// defaults that a human should never have to spell out, and neither should a C# caller.
/// </summary>
public class ConstructorDefaultParameterTests
{
    private static readonly bool IsMacOs = RuntimeInformation.IsOSPlatform(OSPlatform.OSX);

    // --- One trailing default: Cat(name: String, lives: Int = 9) ---

    [Fact]
    public void Cat_OmittingLives_UsesKotlinDefaultOfNine()
    {
        // The ROADMAP papercut this ADR exists to fix: `new Cat("Mouse")` is CS7036 today.
        // Nine lives is Kotlin's number, evaluated by Kotlin, never copied into the C# source.
        using var mouse = new Cat("Mouse");

        Assert.Equal(9, mouse.Lives);
    }

    [Fact]
    public void Cat_FullSignature_StillWorksUnchanged()
    {
        // Mylo is a house cat, he has spent a few. The full-signature export must render
        // byte-identically to today, so the omitting overload cannot have displaced it.
        using var mylo = new Cat("Momo", 7);

        Assert.Equal(7, mylo.Lives);
    }

    [Fact]
    public void Cat_BothArities_ReachTheSameKotlinConstructor()
    {
        // Same class, two exports (cat_create and a synthesized cat_create_N). If the synthesized
        // one were wired to a different Kotlin target, `Meow()` would not agree.
        using var defaulted = new Cat("Oreo");
        using var explicitLives = new Cat("Oreo", 9);

        Assert.Equal("Meow! My name is Oreo", defaulted.Meow());
        Assert.Equal(explicitLives.Meow(), defaulted.Meow());
        Assert.Equal(explicitLives.Lives, defaulted.Lives);
    }

    // --- Two trailing defaults: Carrier(label, size: Int = 3, padded: Boolean = true) ---

    [Fact]
    public void Carrier_FullSignature_UsesEveryGivenArgument()
    {
        using var carrier = new Carrier("Oreo's crate", 5, false);

        Assert.Equal("Oreo's crate size 5 bare", carrier.Describe());
    }

    [Fact]
    public void Carrier_OmittingOneTrailingArgument_UsesPaddedDefault()
    {
        // k = 1: `padded` omitted, `size` supplied. Distinct default values (3 / true) mean a
        // mis-wired suffix shows up as a wrong string, not a plausible one.
        using var carrier = new Carrier("Mylo's crate", 5);

        Assert.Equal("Mylo's crate size 5 padded", carrier.Describe());
    }

    [Fact]
    public void Carrier_OmittingBothTrailingArguments_UsesBothDefaults()
    {
        // k = 2: the deepest suffix. Both defaults come from Kotlin.
        using var carrier = new Carrier("Mylo's crate");

        Assert.Equal("Mylo's crate size 3 padded", carrier.Describe());
    }

    [Fact]
    public void Carrier_ExposesExactlyThreePublicConstructors()
    {
        // d = 2 trailing defaults => d extra exports, linear and not the 2^n combinatorial set.
        // `internal Carrier(IntPtr handle)` is excluded: GetConstructors() is public-only.
        Assert.Equal(3, typeof(Carrier).GetConstructors().Length);
    }

    [Fact]
    public void Carrier_DefaultedProperties_ReadBackTheKotlinDefaults()
    {
        // Reads the state rather than the formatted string, so the assertion does not depend on
        // Describe()'s wording.
        using var carrier = new Carrier("Oreo's crate");

        Assert.Equal(3, carrier.Size);
        Assert.True(carrier.Padded);
    }

    // --- Middle default: Kennel(name, capacity: Int = 10, city: String) ---

    [Fact]
    public void Kennel_FullSignature_Works()
    {
        using var kennel = new Kennel("Paws", 12, "Colombo");

        Assert.Equal("Paws holds 12 in Colombo", kennel.Describe());
    }

    [Fact]
    public void Kennel_ExposesExactlyOnePublicConstructor()
    {
        // `capacity` has a required parameter after it, so a positional Kotlin call can never skip
        // it and the JvmOverloads rule synthesizes nothing. This is documented behaviour, not a
        // gap: assert it structurally so a future "helpful" combinatorial expansion trips here.
        Assert.Single(typeof(Kennel).GetConstructors());
    }

    [Fact]
    public void Kennel_HasNoTwoArgumentConstructor()
    {
        // The negative half, stated by type rather than by count: no `Kennel(string, int)` and no
        // `Kennel(string)` may exist.
        Assert.Null(typeof(Kennel).GetConstructor([typeof(string), typeof(int)]));
        Assert.Null(typeof(Kennel).GetConstructor([typeof(string)]));
    }

    // --- Secondary constructor with a trailing default: ScratchPost ---

    [Fact]
    public void ScratchPost_SecondaryConstructor_FullSignature_Works()
    {
        using var post = new ScratchPost("tower", 60, false);

        Assert.Equal("scratch post tower/60cm/wobbly", post.Describe());
    }

    [Fact]
    public void ScratchPost_SecondaryConstructor_OmittingTrailingDefault_UsesSturdy()
    {
        // The synthesized overload belongs to the SECONDARY constructor (ADR-034 numbering
        // continues into the synthesized entries), so it must route to the secondary's body and
        // pick up `sturdy = true`, not fall back to the primary.
        using var post = new ScratchPost("tower", 60);

        Assert.Equal("scratch post tower/60cm/sturdy", post.Describe());
    }

    [Fact]
    public void ScratchPost_PrimaryConstructor_GetsNoSynthesizedOverload()
    {
        // Primary is `(String)` with no defaults, so the public set is exactly:
        // (string), (string, int, bool) and the synthesized (string, int).
        using var post = new ScratchPost("plain");

        Assert.Equal("scratch post plain", post.Describe());
        Assert.Equal(3, typeof(ScratchPost).GetConstructors().Length);
    }

    // --- expect/actual: the default lives on the expect, never on the exported actual ---

    [Fact]
    public void Beacon_OmittingInterval_UsesTheExpectDeclaredDefault()
    {
        // The trap named on the ROADMAP: Kotlin forbids an `actual` from restating a default, so
        // every parameter of the EXPORTED declaration reports hasDefault = false. Without the
        // expectsByName lookup the planner concludes "no defaults" and this line is CS7036.
        // The value 5 exists only on the expect side.
        using var beacon = new Beacon("Oreo's collar");

        string expected = IsMacOs
            ? "Oreo's collar every 5s on macos"
            : "Oreo's collar every 5s on mingw";
        Assert.Equal(expected, beacon.Describe());
    }

    [Fact]
    public void Beacon_FullSignature_StillWorks()
    {
        using var beacon = new Beacon("Mylo's collar", 30);

        string expected = IsMacOs
            ? "Mylo's collar every 30s on macos"
            : "Mylo's collar every 30s on mingw";
        Assert.Equal(expected, beacon.Describe());
    }

    [Fact]
    public void Beacon_ExposesExactlyTwoPublicConstructors()
    {
        Assert.Equal(2, typeof(Beacon).GetConstructors().Length);
    }
}
