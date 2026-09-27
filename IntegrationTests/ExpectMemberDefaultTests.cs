using TestLibrary.Platform;

namespace IntegrationTests;

/// <summary>
/// ADR-074 amendment (2026-09-27): a default declared on the <c>expect</c> half of a
/// <em>member</em> binds as a C# optional parameter on every member route, and omitting it runs the
/// Kotlin default, exactly as a top-level <c>expect fun</c> already did (ADR-096). Fixture:
/// <c>PlatformDefaults.kt</c> plus the two per-target actual files.
/// <para>
/// Kotlin forbids an <c>actual</c> from restating a default, so without the member lookup each
/// omitting call below is CS7036 at build time. Every default exists only on the <c>expect</c>, and
/// the <c>actual</c> bodies turn it into a distinct number, so a C# filler would read back wrong.
/// </para>
/// <para>
/// Oreo's bowl already holds 40 grams; Mylo waits for the cupboard to be counted.
/// </para>
/// </summary>
public class ExpectMemberDefaultTests
{
    // ---- the ordinary class plan route ----

    [Fact]
    public void Fill_OmittingScoops_RunsTheExpectDeclaredDefault()
    {
        using var bowl = new Bowl(40);

        // scoops = 3, declared only on the expect: 40 + 3 * 10.
        Assert.Equal(70, bowl.Fill());
    }

    [Fact]
    public void Fill_ZeroScoopsSupplied_IsAValueNotUnset()
    {
        using var bowl = new Bowl(40);

        Assert.Equal(40, bowl.Fill(0));
    }

    // ---- the legacy suspend member route ----

    [Fact]
    public async Task RefillAsync_OmittingScoops_RunsTheExpectDeclaredDefault()
    {
        using var bowl = new Bowl(40);

        // scoops = 2: 40 + 2 * 100.
        Assert.Equal(240, await bowl.RefillAsync());
        Assert.Equal(140, await bowl.RefillAsync(1));
    }

    // ---- the legacy Flow member route ----

    [Fact]
    public async Task Trickle_OmittingDrops_RunsTheExpectDeclaredDefault()
    {
        using var bowl = new Bowl(40);
        var drops = new List<int>();

        // drops = 2: emits 41, 42.
        await foreach (int drop in bowl.Trickle())
            drops.Add(drop);

        Assert.Equal(new List<int> { 41, 42 }, drops);
    }

    // ---- the sealed base and its arms (declared on the actual side) ----

    [Fact]
    public void Portion_OnTheBase_OmittingExtra_DispatchesToTheArmWithTheExpectDefault()
    {
        using Meal meal = PlatformDefaults.DryMeal(40);

        // extra = 4, declared only on the expect base member: 40 + 4.
        Assert.Equal(44, meal.Portion());
    }

    [Fact]
    public void Portion_OnTheArm_OmittingExtra_RunsTheExpectDefault()
    {
        using Meal meal = PlatformDefaults.DryMeal(40);
        var dry = Assert.IsType<Meal.Dry>(meal);

        Assert.Equal(44, dry.Portion());
        Assert.Equal(41, dry.Portion(1));
    }

    [Fact]
    public void Portion_OnAStatelessArm_OmittingExtra_RunsTheExpectDefault()
    {
        using Meal meal = PlatformDefaults.WetMeal();

        Assert.IsType<Meal.Wet>(meal);
        Assert.Equal(104, meal.Portion());
    }

    // ---- the expect object ----

    [Fact]
    public void Stock_OmittingTins_RunsTheExpectDeclaredDefault()
    {
        // tins = 6: 6 * 2.
        Assert.Equal(12, Cupboard.Stock());
        Assert.Equal(2, Cupboard.Stock(1));
    }

    // ---- the companion object ----

    [Fact]
    public void Of_OmittingGrams_RunsTheExpectDeclaredDefault()
    {
        // grams = 25, declared only on the expect companion: 25 + 0 * 10.
        using var bowl = Bowl.Of();

        Assert.Equal(25, bowl.Fill(0));
    }

    // ---- the extension ----

    [Fact]
    public void TopUp_OmittingExtra_RunsTheExpectDeclaredDefault()
    {
        using var bowl = new Bowl(40);

        // extra = 15, declared only on the expect extension: 40 + 15.
        Assert.Equal(55, bowl.TopUp());
        Assert.Equal(41, bowl.TopUp(1));
    }
}
