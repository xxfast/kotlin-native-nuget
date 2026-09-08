using System.Reflection;
using TestLibrary.Issue113;

namespace IntegrationTests;

/// <summary>
/// Issue <a href="https://github.com/xxfast/kotlin-native-nuget/issues/113">#113</a> / ADR-115: a
/// Kotlin declaration carrying a <c>@RequiresOptIn</c>-meta-annotated marker is not part of the
/// forward-exported C# surface, at any <c>RequiresOptIn.Level</c>.
///
/// <para>
/// The Kotlin fixture is <c>test-library/.../issue113/Issue113Sample.kt</c>, plus
/// <c>CatteryInternalApi</c> one Gradle module away in <c>:test-models</c>. Read its KDoc for the
/// cell table and for what the Kotlin compiler will and will not let a marker sit on.
/// </para>
///
/// <para>
/// <b>The absence trap.</b> Every assertion here is that a member is <em>absent</em>, and this
/// pipeline already drops members for a dozen unrelated reasons. So every marked declaration in the
/// fixture carries <c>String</c>, which nothing else can drop, and every marked member has an
/// unmarked sibling of the same type in the same class. Those controls are the only thing that can
/// catch a skip that is too <em>wide</em>, which absence assertions structurally cannot.
/// </para>
///
/// <para>
/// <b>Not observable from here:</b> the <c>[nuget:SKIPPED_OPT_IN_MARKER]</c> diagnostic itself. Its
/// kind, its <c>WARNING</c> severity, the marker FQN in its text, cell 6's requirement that the
/// message blame the <em>type</em> rather than the member, and cell 2's requirement that a
/// default-target marker warn <em>once</em> rather than once per synthesized <c>copy</c>/
/// <c>&lt;init&gt;</c> parameter, are all processor-level and belong in Tier 1. From compiled C#
/// only the shape of the surface is visible.
/// </para>
///
/// <para>
/// Oreo (black, white in the middle) and Mylo (brown and creamy) run the cattery. The paperwork is
/// internal; the cats are public.
/// </para>
/// </summary>
public class Issue113Tests
{
    private const BindingFlags PublicInstance = BindingFlags.Public | BindingFlags.Instance;

    // -----------------------------------------------------------------------------------------
    // Controls first. An absence-only suite cannot notice a skip that swept up half the fixture,
    // so these run before anything asserts a member is gone.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void C1_UnmarkedSiblings_OfTheMarkedProperties_AreStillExported()
    {
        using Litter litter = Issue113Sample.Adopt();

        // Same class, same `String` type, same positions (constructor val, var, val-with-getter)
        // as the three marked properties. If the skip is too wide, it lands here first.
        Assert.Equal("Oreo", litter.Name);
        Assert.Equal("loaf", litter.MutablePlain);
        Assert.Equal("plain:Oreo", litter.Plain);

        litter.MutablePlain = "croissant";
        Assert.Equal("croissant", litter.MutablePlain);
    }

    [Fact]
    public void C1_DataClassMembers_SurviveTheDroppedProperties()
    {
        // Dropping three of a `data class`'s properties must not take its generated members with
        // it. `Copy` is deliberately not asserted: ADR-115 decides the property skip and says
        // nothing about whether a constructor parameter whose property is marked keeps its slot,
        // so its arity is still open.
        using Litter oreos = Issue113Sample.Adopt();
        using Litter mylos = Issue113Sample.Adopt();

        Assert.True(oreos.Equals(mylos));
        Assert.Equal(oreos.GetHashCode(), mylos.GetHashCode());
        Assert.StartsWith("Litter(name=Oreo", oreos.ToString());
    }

    [Fact]
    public void C1_UnmarkedSiblings_OfTheMarkedFunctions_AreStillExported()
    {
        using var cattery = new Cattery();
        using var shelter = new Shelter();

        Assert.Equal("Oreo & Mylo", cattery.PlainName());
        Assert.Equal("12 Sunbeam Lane", shelter.Address());
    }

    [Fact]
    public void C2_OptInConsumer_IsNotAMarkerMember_AndStaysExported()
    {
        // `kotlin.OptIn` carries no `RequiresOptIn` meta-annotation (ADR-115 Finding 7), so the
        // two-hop test answers false for it. A declaration that opts *in* to a marker is a
        // consumer, not a member, and must survive. It really does call the marked
        // `Cattery.markedName()`, so the value proves the call still happens natively even though
        // `MarkedName` itself is no longer exported.
        using var cattery = new Cattery();

        Assert.Equal("opted-in:ledger:oreo", cattery.ConsumesMarked());
    }

    [Fact]
    public void TheOwningTypes_SurviveTheSkip()
    {
        // The whole-class version of the same control: dropping members must not drop their
        // owners. `HouseRules` is deliberately not in this list; see cell 5.
        Type[] declared = typeof(Litter).Assembly
            .GetTypes()
            .Where(type => type.Namespace == "TestLibrary.Issue113")
            .ToArray();

        Assert.Contains(declared, type => type.Name == nameof(Litter));
        Assert.Contains(declared, type => type.Name == nameof(Shelter));
        Assert.Contains(declared, type => type.Name == nameof(Cattery));
        Assert.Contains(declared, type => type.Name == nameof(Issue113Sample));
    }

    // -----------------------------------------------------------------------------------------
    // Cells 1-3: the three marker positions on properties of a `data class`.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void Cell1_PropertyTargetedMarker_OnAConstructorVal_IsAbsent()
    {
        // The issue's literal reported shape: `@property:InternalApi val extra: String`. Pre-fix
        // this is `public string Extra { get; }` backed by the `litter_get_extra` export.
        Assert.Null(typeof(Litter).GetProperty("Extra", PublicInstance));
    }

    [Fact]
    public void Cell2_DefaultTargetMarker_OnAConstructorVal_IsAbsent()
    {
        Assert.Null(typeof(Litter).GetProperty("Other", PublicInstance));
    }

    [Fact]
    public void Cell3_AccessorTargetedMarker_IsAbsent()
    {
        // `@set:InternalApi var viaSetter`. The marker is NOT on the `KSPropertyDeclaration`, only
        // on `setter.annotations`, so an implementation that reads only `declaration.annotations`
        // leaves this property fully exported and nothing else in the suite notices.
        //
        // ADR-115's read table skips the whole property when an accessor carries a marker, which
        // is what this pins. The alternative it never separately considered, exporting the
        // property get-only because only *writes* require the opt-in, would fail here. That is a
        // decision to confirm, not a bug in this assertion.
        Assert.Null(typeof(Litter).GetProperty("ViaSetter", PublicInstance));
    }

    [Fact]
    public void Cells1To3_LeaveNoStrayAccessorMembers()
    {
        // A partial fix could drop the C# property while leaving the generated `Native_Get_extra`
        // shim, or re-surface the member as a method. Nothing public may carry these names.
        string[] gone = ["Extra", "Other", "ViaSetter"];

        var strays = typeof(Litter)
            .GetMembers(PublicInstance)
            .Where(member => gone.Contains(member.Name))
            .Select(member => $"{member.MemberType} {member.Name}")
            .OrderBy(name => name)
            .ToArray();

        Assert.Empty(strays);
    }

    // -----------------------------------------------------------------------------------------
    // Cell 4: the member-function skip path.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void Cell4_MarkedMemberFunction_IsAbsent()
    {
        Assert.Null(typeof(Cattery).GetMethod("MarkedName", PublicInstance));
    }

    // -----------------------------------------------------------------------------------------
    // Cell 5: a marked class is never declared. Structurally different from a member skip: it is
    // refused at declaration/reachability, so there is no C# type at all rather than an empty one.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void Cell5_MarkedClass_IsNeverDeclaredAnywhereInTheAssembly()
    {
        // Interop.cs compiles into IntegrationTests.dll, so that assembly is the right haystack
        // (precedent: NestedClassGateTests, Issue42Tests). Pre-fix this type exists with a
        // constructor, `Motto` and `Describe()`, all backed by `houserules_*` exports.
        var emitted = typeof(Litter).Assembly
            .GetTypes()
            .Where(type => type.Name == "HouseRules")
            .Select(type => type.FullName)
            .ToArray();

        Assert.Empty(emitted);
    }

    // -----------------------------------------------------------------------------------------
    // Cell 6: a member whose return type is the marked class. Its own skip reason, because
    // SKIPPED_UNEXPORTED_DEPENDENCY_TYPE's `include(...)` hint cannot repair a marked type.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void Cell6_MemberReturningAMarkedType_IsAbsent()
    {
        Assert.Null(typeof(Shelter).GetMethod("Rules", PublicInstance));
    }

    [Fact]
    public void Cell6_IsNotRepairedByFallingBackToARawHandle()
    {
        // The plausible-but-wrong fix: keep the member and project the undeclared type as `IntPtr`.
        // That compiles, leaks the marked type as an opaque handle, and would pass every other
        // assertion in this class.
        var handleReturns = typeof(Shelter)
            .GetMethods(PublicInstance)
            .Where(method => method.ReturnType == typeof(IntPtr) || method.ReturnType == typeof(IntPtr?))
            .Select(method => method.Name)
            .ToArray();

        Assert.Empty(handleReturns);
    }

    // -----------------------------------------------------------------------------------------
    // Cell 7: level independence. If this is the only cell that fails, the implementation keyed on
    // RequiresOptIn.Level and the experimental-marker leak is still open.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void Cell7_WarningLevelMarker_IsAbsentToo()
    {
        // `@ExperimentalPurr` is `RequiresOptIn.Level.WARNING`. It never breaks the generated
        // `CNameExports.kt` compile, so failure (2), the silent leak, is the *only* symptom this
        // level has. ADR-115 deliberately does not consult the level.
        Assert.Null(typeof(Cattery).GetMethod("ExperimentalName", PublicInstance));
    }

    // -----------------------------------------------------------------------------------------
    // Cell 8: a marker declared in :test-models, applied here. Retires ADR-115's one Inferred
    // claim: the spike proved two-hop marker resolution against a JVM jar, never a native klib.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void Cell8_MarkerFromADependencyModule_IsResolvedAcrossTheKlib()
    {
        // If klib metadata does not carry the marker's own `@RequiresOptIn` meta-annotation
        // through, this is the only assertion in the suite that fails, and the failure is exactly
        // "a dependency-module marker keeps leaking silently".
        Assert.Null(typeof(Cattery).GetMethod("CrossModuleName", PublicInstance));
    }

    // -----------------------------------------------------------------------------------------
    // The whole-surface sweep: no marked name may reappear anywhere under the namespace.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void NoMarkedDeclaration_SurvivesAnywhereUnderTheNamespace()
    {
        string[] marked = ["Extra", "Other", "ViaSetter", "MarkedName", "ExperimentalName", "CrossModuleName", "Rules"];

        var survivors = typeof(Litter).Assembly
            .GetTypes()
            .Where(type => type.Namespace == "TestLibrary.Issue113")
            .SelectMany(type => type.GetMembers(PublicInstance | BindingFlags.Static)
                .Where(member => marked.Contains(member.Name))
                .Select(member => $"{type.Name}.{member.Name}"))
            .OrderBy(name => name)
            .ToArray();

        Assert.Empty(survivors);
    }

    [Fact]
    public void TheMarkerAnnotations_AreNotThemselvesExported()
    {
        // Annotation classes already skip with SKIPPED_ANNOTATION_CLASS, so this holds today. It is
        // here because a fix that "declares the marker so the generated file can opt in"
        // (ADR-115 alternative 4, rejected) would surface them.
        string[] markers = ["InternalApi", "LedgerApi", "ExperimentalPurr", "CatteryInternalApi"];

        var emitted = typeof(Litter).Assembly
            .GetTypes()
            .Where(type => markers.Contains(type.Name) || markers.Contains(type.Name.Replace("Attribute", "")))
            .Select(type => type.FullName)
            .ToArray();

        Assert.Empty(emitted);
    }
}
