using System.Linq;
using TestLibrary;
using TestLibrary.Issue54;
using TestLibrary.Models;

namespace IntegrationTests;

/// <summary>
/// The undeclared-enum gate: an exported member typed with an <c>enum class</c> that is not in the
/// exported set is classified as a C# enum reference and spelled
/// <c>global::TestLibrary.Issue54.NestedModeOwner.Mode</c> even though the enum is never declared,
/// so <c>Interop.cs</c> fails the consumer compile with CS0246/CS0426.
///
/// Three shapes, because the classifier's enum branch reaches them by three different routes and a
/// gate that closes one does not close the others:
/// <list type="bullet">
/// <item>(a) a module-local enum <em>nested</em> inside an exported class — <c>rootEnums</c> only
/// declares top-level enums, and the closure never admits a module-local declaration;</item>
/// <item>(b) a dependency module's <em>nested</em> enum that the closure <em>does</em> admit (its
/// ENUM admission has no <c>parentDeclaration</c> filter), declared at namespace root under its
/// simple name while every reference spells it <c>Broadcast.AdBand</c>;</item>
/// <item>(c) a top-level enum in the never-admitted <c>dev.other.core</c>, the
/// <c>containingFile == null</c> half of the gate.</item>
/// </list>
///
/// After the fix every enum-typed member skips with a named diagnostic, the owning classes still
/// generate and construct, and their unrelated members still bind. The diagnostics themselves
/// (<c>SKIPPED_UNSUPPORTED_TYPE</c> with the <c>UNDECLARED_ENUM</c> reason,
/// <c>SKIPPED_UNEXPORTED_DEPENDENCY_TYPE</c> for shape (c)) are asserted at Tier 1; from compiled
/// C# only the absence of the members, and the survival of everything around them, is observable.
///
/// Oreo runs on two modes and no more; Mylo only listens to the cat radio station on FM.
/// </summary>
public class NestedEnumGateTests
{
    // --- Shape (a): module-local nested enum ---

    [Fact]
    public void NestedModeOwner_StillConstructs_AndItsUnrelatedMemberStillBinds()
    {
        // The control half: a gate that drops the whole owning class instead of just its
        // enum-typed members would also make this pass-looking test fail, which is the point.
        using var owner = new NestedModeOwner();

        Assert.Equal("owner", owner.Name);
    }

    [Fact]
    public void NestedModeOwner_PropertyPosition_IsSkipped()
    {
        Assert.Null(typeof(NestedModeOwner).GetProperty("Mode"));
    }

    [Fact]
    public void NestedModeOwner_ParameterPosition_IsSkipped()
    {
        Assert.Null(typeof(NestedModeOwner).GetMethod("Set"));
    }

    [Fact]
    public void NestedModeOwner_ReturnPosition_IsSkipped()
    {
        Assert.Null(typeof(NestedModeOwner).GetMethod("Current"));
    }

    [Fact]
    public void NestedModeOwner_NestedEnum_IsNeverDeclaredAsANestedType()
    {
        // A fix that "declares nested enums" instead of gating them would satisfy the member
        // assertions above by emitting NestedModeOwner.Mode; this pins the chosen behaviour.
        Assert.Null(typeof(NestedModeOwner).GetNestedType("Mode"));
    }

    // --- Shape (b): admitted dependency class, nested enum ---

    [Fact]
    public void Broadcast_AdmittedDependencyClass_StillConstructs_AndItsUnrelatedMemberStillBinds()
    {
        using var newsroom = new Newsroom();
        using Broadcast broadcast = newsroom.Broadcast();

        Assert.Equal("Radio Mylo 101.1", broadcast.Station);
    }

    [Fact]
    public void Broadcast_NestedDependencyEnumProperty_IsSkipped()
    {
        Assert.Null(typeof(Broadcast).GetProperty("Band"));
        Assert.Null(typeof(Broadcast).GetNestedType("AdBand"));
    }

    // --- Shape (c): unadmitted top-level dependency enum ---

    [Fact]
    public void Airwave_UnadmittedDependencyEnum_MemberIsSkipped_AndNewsroomSurvives()
    {
        Assert.Null(typeof(Newsroom).GetMethod("Airwave"));

        // Same guarantee Sponsor() pins for an unadmitted class: the skip must not take the rest
        // of the facade with it.
        Assert.NotNull(typeof(Newsroom).GetMethod("Latest"));
        Assert.NotNull(typeof(Newsroom).GetMethod("Broadcast"));
    }

    // --- Assembly-wide: none of the three enums may be declared anywhere ---

    [Fact]
    public void NoneOfTheUndeclarableEnums_ExistAnywhereInTheGeneratedAssembly()
    {
        // Shape (b) fails here today in a way the per-type assertions cannot see: the closure
        // admits Broadcast.AdBand and translateEnum declares it as a namespace-root `public enum
        // AdBand`, so a type named AdBand exists even though no reference resolves against it.
        var strays = typeof(NestedModeOwner).Assembly
            .GetTypes()
            .Where(type => type.Name is "Mode" or "AdBand" or "Airwave")
            .Select(type => type.FullName)
            .OrderBy(name => name)
            .ToArray();

        Assert.Empty(strays);
    }
}
