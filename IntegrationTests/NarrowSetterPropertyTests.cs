using System.Reflection;
using TestLibrary.Scratchpost;

namespace IntegrationTests;

/// <summary>
/// ADR-075 (amended): a Kotlin <c>var</c> whose setter is narrower than public (<c>private set</c>,
/// <c>protected set</c>, <c>internal set</c>) binds as a get-only C# property. The value still
/// moves, through Kotlin's own mutators, and C# reads it back; C# just cannot assign it.
///
/// The private-set class shape is <c>Issue297Tests.Button_Clicks_*</c>. These cover the other
/// two narrowings plus the widened override: Oreo's post is protected, Mylo's tower re-widens it
/// in Kotlin (which C# cannot follow, CS0546), and the feeding bowl is internal.
/// </summary>
public class NarrowSetterPropertyTests
{
    private const BindingFlags Declared =
        BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly;

    // ---- ClawPost: open var, protected set ----

    [Fact]
    public void ClawPost_Scratches_MovesThroughScratch()
    {
        using var post = new ClawPost("Oreo");

        Assert.Equal(0, post.Scratches);
        Assert.Equal(1, post.Scratch());
        Assert.Equal(2, post.Scratch());
        Assert.Equal(2, post.Scratches);
    }

    [Fact]
    public void ClawPost_Scratches_HasNoSetter()
    {
        PropertyInfo? scratches = typeof(ClawPost).GetProperty("Scratches", Declared);

        Assert.NotNull(scratches);
        Assert.NotNull(scratches!.GetMethod);
        Assert.Null(scratches.SetMethod);
    }

    // ---- ClawTower: override var widened to public set in Kotlin ----

    [Fact]
    public void ClawTower_Scratches_DispatchesToTheOverride()
    {
        // Mylo's tower arrives at 10; the base's scratch() bumps the override's field.
        using var tower = new ClawTower("Mylo");
        ClawPost asBase = tower;

        Assert.Equal(10, tower.Scratches);
        Assert.Equal(11, tower.Scratch());
        Assert.Equal(11, asBase.Scratches);
    }

    [Fact]
    public void ClawTower_Scratches_OverrideHasNoSetterEither()
    {
        // Kotlin lets the override widen `protected set` to `public set`; C# cannot add a set
        // accessor to an override of a get-only base property, so the override stays get-only.
        PropertyInfo? onTower = typeof(ClawTower).GetProperty("Scratches", Declared);

        Assert.NotNull(onTower);
        Assert.NotNull(onTower!.GetMethod);
        Assert.Null(onTower.SetMethod);
        Assert.Equal(typeof(ClawPost), onTower.GetMethod!.GetBaseDefinition().DeclaringType);
    }

    // ---- FeedingBowl: nullable primitive, internal set ----

    [Fact]
    public void FeedingBowl_LastMealGrams_IsNullUntilServed()
    {
        using var bowl = new FeedingBowl("Mylo");

        Assert.Null(bowl.LastMealGrams);
        Assert.Equal("Mylo ate 80g", bowl.Serve(80));
        Assert.Equal(80, bowl.LastMealGrams);
    }

    [Fact]
    public void FeedingBowl_LastMealGrams_HasNoSetter()
    {
        // `internal set` is not public API: C# is outside the Kotlin module, so it gets no setter.
        PropertyInfo? lastMeal = typeof(FeedingBowl).GetProperty("LastMealGrams", Declared);

        Assert.NotNull(lastMeal);
        Assert.Equal(typeof(int?), lastMeal!.PropertyType);
        Assert.NotNull(lastMeal.GetMethod);
        Assert.Null(lastMeal.SetMethod);
    }
}
