using System;
using System.Linq;
using System.Reflection;
using TestLibrary;
using TestLibrary.Issue54;
using TestLibrary.Models;

namespace IntegrationTests;

/// <summary>
/// ADR-133 flips this file from absence to presence. It was the nested-declaration <em>skip</em>:
/// a plain <c>class</c> or <c>object</c> nested inside an exported class was never declared, in
/// either of the two routes into the export set. Both routes are still here, because a fix that
/// opens one does not open the other:
/// <list type="bullet">
/// <item>(a) a module-local nested class/object (<c>ProbeOuter.Nested</c>, <c>ProbeOuter.Marker</c>),
/// collected by the owner walk in <c>NugetProcessor.kt</c>, which used to filter
/// <c>parentDeclaration == null</c> in every root bucket;</item>
/// <item>(b) a dependency module's nested class/object (<c>Broadcast.Schedule</c>,
/// <c>Broadcast.Defaults</c>) reached through <c>Newsroom</c>, where the ADR-066 closure used to
/// refuse every nested bucket outright.</item>
/// </list>
///
/// Two things must NOT flip, and they are the reason this file survives instead of being deleted:
/// <list type="bullet">
/// <item>the <c>companion object</c> carve-out: it is nested by every structural test, but it is
/// how <c>ProbeOuter.Make()</c> reaches C# as a static factory, so it must never surface as a
/// <c>Companion</c> nested type;</item>
/// <item>a member <em>returning</em> a nested <c>object</c> (<c>Single()</c>,
/// <c>Newsroom.Defaults()</c>). A Kotlin object renders as a C# static class and a member typed
/// with a static class is CS0722, nested or not. The declaration appears; the member stays absent.
/// </item>
/// </list>
///
/// The cross-the-seam behaviour of a declared nested type lives in <see cref="NestedTypesTests"/>;
/// this file stays focused on which declarations exist and where.
///
/// Oreo probes every nested cardboard box in the house. Mylo waits for the all-clear, then takes it.
/// </summary>
public class NestedClassGateTests
{
    private const BindingFlags PublicInstance = BindingFlags.Public | BindingFlags.Instance;
    private const BindingFlags PublicStatic = BindingFlags.Public | BindingFlags.Static;

    // --- Shape (a): module-local nested class and object ---

    [Fact]
    public void ProbeOuter_StillConstructs_AndItsUnrelatedMemberStillBinds()
    {
        // The control half: an implementation that drops the owning class to make its children fit
        // would also fail here, which is the point.
        using var outer = new ProbeOuter();

        Assert.Equal("outer", outer.Label);
    }

    [Fact]
    public void ProbeOuter_CompanionFactory_IsTheCarveOut_AndStillWorks()
    {
        using ProbeOuter outer = ProbeOuter.Make();

        Assert.Equal("outer", outer.Label);
    }

    [Fact]
    public void ProbeOuter_NonNullNestedClassReturn_IsDeclared_AndRoundTrips()
    {
        // Was: Assert.Null(GetMethod("Make", PublicInstance)). The Kotlin member is now `nest()`,
        // because an instance Make() beside the companion's static Make() is CS0111.
        using var outer = new ProbeOuter();
        using var nested = outer.Nest();

        Assert.Equal("n", nested.X);
    }

    [Fact]
    public void ProbeOuter_NullableNestedClassReturn_IsDeclared_AndReturnsNull()
    {
        // Was: Assert.Null(GetMethod("Maybe", PublicInstance)). The nullable branch of the old skip
        // was a separate code path, so it stays a separate cell after the flip.
        using var outer = new ProbeOuter();

        Assert.NotNull(typeof(ProbeOuter).GetMethod("Maybe", PublicInstance));
        Assert.Null(outer.Maybe());
    }

    [Fact]
    public void ProbeOuter_NestedObjectReturn_StaysSkipped_BecauseOfCS0722()
    {
        // Unchanged by ADR-133 and deliberately so: `single(): Marker` returns an object type,
        // which is a C# static class. `public Marker Single()` is CS0722 no matter where Marker is
        // declared, so the member stays absent while the declaration below appears.
        Assert.Null(typeof(ProbeOuter).GetMethod("Single", PublicInstance));
    }

    [Fact]
    public void ProbeOuter_NestedDeclarations_AreDeclaredAsNestedTypes()
    {
        // Was: Assert.Null(GetNestedType(...)) for both. This is the ADR-133 pin itself.
        Type? nested = typeof(ProbeOuter).GetNestedType("Nested");
        Type? marker = typeof(ProbeOuter).GetNestedType("Marker");

        Assert.NotNull(nested);
        Assert.NotNull(marker);
        Assert.Same(typeof(ProbeOuter), nested!.DeclaringType);
        Assert.Same(typeof(ProbeOuter), marker!.DeclaringType);
        Assert.True(marker.IsAbstract && marker.IsSealed, "expected the nested object to be a static class");
    }

    // --- Shape (b): admitted dependency class, nested class and object ---

    [Fact]
    public void Newsroom_NestedDependencyClassReturn_IsDeclared_AndRoundTrips()
    {
        // Was: Assert.Null(GetMethod("Schedule", PublicInstance)). The closure now admits a nested
        // dependency declaration when its whole enclosing chain is admitted, and the owner walk is
        // the sole declarer, so exactly one Broadcast.Schedule exists.
        using var newsroom = new Newsroom();
        using var schedule = newsroom.Schedule();

        Assert.Equal(7, schedule.Slot);
        Assert.NotNull(typeof(Broadcast).GetNestedType("Schedule"));
    }

    [Fact]
    public void Newsroom_NestedDependencyObjectReturn_StaysSkipped_ButTheTypeIsDeclared()
    {
        // Same CS0722 rule as Single(): the member cannot return a static class, but
        // Broadcast.Defaults itself must be declared, nested, under its dependency owner.
        Assert.Null(typeof(Newsroom).GetMethod("Defaults", PublicInstance));
        Assert.NotNull(typeof(Broadcast).GetNestedType("Defaults"));
    }

    [Fact]
    public void Newsroom_SurvivesTheFlip()
    {
        // Same guarantee Sponsor() pins for an unadmitted class: nothing here may take the rest of
        // the facade with it, and Broadcast itself must keep binding.
        Assert.NotNull(typeof(Newsroom).GetMethod("Latest", PublicInstance));
        Assert.NotNull(typeof(Newsroom).GetMethod("Broadcast", PublicInstance));

        using var newsroom = new Newsroom();
        using var broadcast = newsroom.Broadcast();

        Assert.Equal("Radio Mylo 101.1", broadcast.Station);
    }

    // --- Assembly-wide: nested exactly once, never flattened, never duplicated ---

    [Fact]
    public void EveryNestedDeclaration_ExistsExactlyOnce_AndIsNested()
    {
        // Was: Assert.Empty(strays) over the same four names, then Assert.Single by bare name. The
        // bare-name form cannot tell a flattened twin from two different owners' same-named nested
        // types, and ADR-133's own fixture has exactly that (`Aviary.Defaults` beside
        // `Broadcast.Defaults`), so the pin is per (name, owner): no namespace-root twin (the
        // pre-2026-09-07 flattening that failed CS0426) and no second declaration from the
        // dependency merge (CS0101, issue #54/#110).
        var expected = new (string Name, Type Owner)[]
        {
            ("Nested", typeof(ProbeOuter)),
            ("Marker", typeof(ProbeOuter)),
            ("Schedule", typeof(Broadcast)),
            ("Defaults", typeof(Broadcast)),
            ("Page", typeof(Almanac)),
        };

        var declarations = typeof(ProbeOuter).Assembly
            .GetTypes()
            .Where(type => expected.Any(cell => cell.Name == type.Name))
            .ToArray();

        Assert.Empty(declarations.Where(type => !type.IsNested).Select(type => type.FullName));
        foreach ((string name, Type owner) in expected)
        {
            Assert.Single(declarations.Where(type => type.Name == name && type.DeclaringType == owner));
        }
    }

    [Fact]
    public void ProbeOuter_CarveOut_DoesNotLeakACompanionType()
    {
        // The companion is bridged as a static member on ProbeOuter, never as its own type. Now
        // that nested types ARE declared, this is the cell that keeps the carve-out honest.
        Assert.Null(typeof(ProbeOuter).GetNestedType("Companion"));
        Assert.NotNull(typeof(ProbeOuter).GetMethod("Make", PublicStatic));
    }
}
