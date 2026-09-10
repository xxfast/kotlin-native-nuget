using System.Reflection;
using TestLibrary.Issue113;

namespace IntegrationTests;

/// <summary>
/// ADR-115's amendment: <c>nuget { publish { exportMarkers("...") } }</c> names a
/// <c>@RequiresOptIn</c> marker whose declarations keep exporting, the inverse of
/// <c>binary-compatibility-validator</c>'s <c>nonPublicMarkers</c>.
///
/// <para>
/// The Kotlin fixture is <c>test-library/.../issue113/ExportMarkersSample.kt</c>, and
/// <c>test-library/build.gradle.kts</c> waives exactly one marker,
/// <c>...issue113.ExperimentalDiet</c>. It shares a package with <see cref="Issue113Tests"/>'s
/// fixture on purpose: <c>LedgerApi</c> next door stays unlisted, so one build shows both halves.
/// </para>
///
/// <para>
/// <b>The presence-and-absence pairing.</b> <see cref="Issue113Tests"/> is an absence-only suite,
/// and this one is its mirror: every waived member asserted present has an unlisted-marker member
/// beside it asserted absent. A waiver that degenerates into "export everything marked" passes the
/// presence half and fails <see cref="UnlistedMarker_OnASiblingMember_IsStillSkipped"/>; a waiver
/// that never fires fails the presence half alone.
/// </para>
///
/// <para>
/// <b>Not observable from here:</b> that the generated <c>CNameExports.kt</c> carries
/// <c>@OptIn(ExperimentalDiet::class)</c>. <c>ExperimentalDiet</c> is <c>ERROR</c> level, so a
/// missing opt-in fails the Kotlin compile and this assembly never gets built at all, which is
/// issue #113's original failure re-entered from the other side. Whether the emission is present
/// is a Tier 1 question; from compiled C# only its consequence is visible.
/// </para>
///
/// <para>
/// Mylo (brown and creamy) is on a diet plan he did not consent to. Oreo (black, white in the
/// middle) keeps his ledger private.
/// </para>
/// </summary>
public class ExportMarkersTests
{
    private const BindingFlags PublicInstance = BindingFlags.Public | BindingFlags.Instance;

    [Fact]
    public void WaivedMarker_OnAWholeClass_ExportsTheTypeAndItsMembers()
    {
        // `@ExperimentalDiet class Nutritionist`. Without the waiver this type is refused at
        // declaration (Issue113Tests cell 5), so its very existence is the assertion; `Name` and
        // `Plan()` then prove the waiver reaches members read through a waived owner too.
        using var nutritionist = new Nutritionist("Mylo");

        Assert.Equal("Mylo", nutritionist.Name);
        Assert.Equal("kibble", nutritionist.Plan());
    }

    [Fact]
    public void WaivedMarker_OnASingleMember_ExportsThatMember()
    {
        // Member-level waiver on an unmarked owner: the other half of the read table. `PlainName`
        // is the unmarked control, so a skip that swept up the class lands there first.
        using var planner = new DietPlanner();

        Assert.Equal("Mylo", planner.PlainName());
        Assert.Equal("kibble", planner.DietName());
    }

    [Fact]
    public void UnlistedMarker_OnASiblingMember_IsStillSkipped()
    {
        // `@LedgerApi fun ledgerName()`, same class, same `String` return, same build. This is the
        // only assertion that can catch a waiver implemented as "stop skipping marked things".
        Assert.Null(typeof(DietPlanner).GetMethod("LedgerName", PublicInstance));
    }

    [Fact]
    public void TheWaivedMarker_IsNotItselfExported()
    {
        // Waiving a marker exports its *users*, not the annotation class. ADR-115 alternative 4
        // (declare the marker so the generated file can opt in) was rejected, and it would surface
        // `ExperimentalDiet` here.
        var emitted = typeof(DietPlanner).Assembly
            .GetTypes()
            .Where(type => type.Name is "ExperimentalDiet" or "ExperimentalDietAttribute")
            .Select(type => type.FullName)
            .ToArray();

        Assert.Empty(emitted);
    }
}
