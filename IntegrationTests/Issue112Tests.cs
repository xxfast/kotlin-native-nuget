using System.Reflection;
using TestLibrary.Issue112;

namespace IntegrationTests;

/// <summary>
/// Issue <a href="https://github.com/xxfast/kotlin-native-nuget/issues/112">#112</a> / ADR-113: the
/// generated <c>IFoo</c> is projected from Kotlin simple names while every implementation of it is
/// projected from the forward plan, so the two disagree and nothing can implement the interface.
/// <c>packNuget</c> is green; the failure lands when the consumer compiles <c>Interop.cs</c>.
/// <para>
/// The red for this issue is therefore "everything red": <c>Interop.cs</c> does not compile
/// (CS0102 for a property and a method both named <c>CollarTag</c>, CS0738 because the class's
/// wrapper type is not <c>IntPtr</c>, CS0535 for the two members the class route skipped), so these
/// tests cannot even be built against it.
/// </para>
/// <para>
/// The load-bearing assertions here compare the interface member's type against the
/// <em>implementing class's</em> member type via reflection, never against a hardcoded literal.
/// ADR-113 Decision A flags as <em>inferred, not verified</em> that <c>plan.type.csharpType()</c>
/// and the class route's property projection render character-identical strings for the same Kotlin
/// member. If that is wrong the symptom is CS0738 again, which is the error being fixed, so a test
/// written against a literal would pass while the two sides drift.
/// </para>
/// <para>
/// Oreo (black, white in the middle) and Mylo (brown and creamy) are both broadcasting from their
/// collars.
/// </para>
/// </summary>
public class Issue112Tests
{
    // ---------------------------------------------------------------------------------------
    // Cell 1: a reference-typed interface member must project the same type the class projects.
    // ---------------------------------------------------------------------------------------

    [Fact]
    public void InterfaceMember_ReferenceType_MatchesTheImplementingClassProjection()
    {
        PropertyInfo? onInterface = typeof(IAdvertisement).GetProperty(nameof(IAdvertisement.CollarTag));
        PropertyInfo? onClass = typeof(BleAdvertisement).GetProperty(nameof(BleAdvertisement.CollarTag));

        Assert.NotNull(onInterface);
        Assert.NotNull(onClass);
        Assert.NotEqual(typeof(IntPtr), onInterface!.PropertyType);
        // ADR-113's inferred claim, asserted rather than argued: same Kotlin member, same C# type
        // on both sides. A literal here would pass while the two projections drifted.
        Assert.Equal(onClass!.PropertyType, onInterface.PropertyType);
    }

    [Fact]
    public void InterfaceMembers_NeverProjectToRawIntPtr()
    {
        Assert.Empty(RawPointerMembers(typeof(IAdvertisement)));
        Assert.Empty(RawPointerMembers(typeof(IMicrochipped)));
        Assert.Empty(RawPointerMembers(typeof(IProwling)));
    }

    // ---------------------------------------------------------------------------------------
    // Cell 2: a member the class route skipped is absent from the interface, silently.
    // ---------------------------------------------------------------------------------------

    [Fact]
    public void InterfaceProperty_SkippedByThePlan_IsAbsent()
    {
        // `Collection<String>` is not one of the six collection kinds the forward classifier knows,
        // so the class route skipped it with SKIPPED_UNSUPPORTED_PROPERTY.
        Assert.Null(typeof(IAdvertisement).GetProperty("Codes"));
        Assert.Null(typeof(IProwling).GetProperty("Codes"));
    }

    [Fact]
    public void InterfaceMethod_SkippedByThePlan_IsAbsent()
    {
        // `fun collarTag(code: Int): ByteArray?` is unbridgeable, so it is dropped. That drop is
        // also what keeps ADR-113's fatal CS0102 guard from firing on this hierarchy: the guard is
        // post-filter, and only one `CollarTag` member survives the plan.
        Assert.DoesNotContain(
            typeof(IAdvertisement).GetMethods(),
            method => method.Name == nameof(IAdvertisement.CollarTag));
    }

    [Fact]
    public void Interface_DeclaresExactlyTheBridgeableMembers()
    {
        Assert.Equal(
            new[] { "CollarTag", "Identifier" },
            typeof(IAdvertisement).GetProperties().Select(p => p.Name).OrderBy(n => n));
        Assert.Equal(
            new[] { "Describe" },
            typeof(IAdvertisement).GetMethods().Where(m => !m.IsSpecialName).Select(m => m.Name).OrderBy(n => n));
    }

    // ---------------------------------------------------------------------------------------
    // Cell 3 (the compiling half): the exported class actually satisfies the interface.
    // ---------------------------------------------------------------------------------------

    [Fact]
    public void Interface_IsImplementedByTheExportedClass()
    {
        // The compile itself is the real test (CS0535/CS0738 are compile errors, not runtime ones),
        // so this pins the relationship the compile proves.
        Assert.True(typeof(IAdvertisement).IsAssignableFrom(typeof(BleAdvertisement)));
    }

    [Fact]
    public void InterfaceMembers_RoundTripThroughTheImplementation()
    {
        using var tag = new CollarTag("Oreo");
        using var advertisement = new BleAdvertisement("hallway-01", tag);

        IAdvertisement ad = advertisement;

        Assert.Equal("hallway-01", ad.Identifier);
        Assert.Equal("Oreo", ad.CollarTag!.Label);
        Assert.Equal("beacon:hallway-01", ad.Describe("beacon:"));
    }

    [Fact]
    public void NullableReferenceInterfaceMember_ReadsBackNull()
    {
        using var advertisement = new BleAdvertisement("hallway-02", null);

        IAdvertisement ad = advertisement;

        Assert.Null(ad.CollarTag);
    }

    // ---------------------------------------------------------------------------------------
    // Cell 4: the ADR-040 regression guard. `Microchipped` is only implemented, never returned, so
    // it has no interface plan entries today. `IMicrochipped` must not lose a single member.
    // ---------------------------------------------------------------------------------------

    [Fact]
    public void NonReachableInterface_KeepsEveryBridgeableMember()
    {
        Assert.Equal(
            new[] { "Label", "Lives", "Nickname" },
            typeof(IMicrochipped).GetProperties().Select(p => p.Name).OrderBy(n => n));
        Assert.Equal(
            new[] { "Describe", "Nap" },
            typeof(IMicrochipped).GetMethods().Where(m => !m.IsSpecialName).Select(m => m.Name).OrderBy(n => n));
    }

    [Fact]
    public void NonReachableInterface_MemberTypesMatchTheImplementingClass()
    {
        foreach (PropertyInfo onInterface in typeof(IMicrochipped).GetProperties())
        {
            PropertyInfo? onClass = typeof(MicrochippedCat).GetProperty(onInterface.Name);
            Assert.NotNull(onClass);
            Assert.Equal(onClass!.PropertyType, onInterface.PropertyType);
        }
    }

    [Fact]
    public void NonReachableInterface_RoundTrips()
    {
        using var cat = new MicrochippedCat("Mylo", 9, "milk-drinker");

        IMicrochipped chipped = cat;

        Assert.Equal("Mylo", chipped.Label);
        Assert.Equal(9, chipped.Lives);
        Assert.Equal("milk-drinker", chipped.Nickname);
        Assert.Equal("chip:Mylo", chipped.Describe("chip:"));
        chipped.Nap();
    }

    // ---------------------------------------------------------------------------------------
    // The reachable half of the same route: ADR-040 writes the implementation, not the user, and
    // `IProwling` has to agree with that generated backing class instead.
    // ---------------------------------------------------------------------------------------

    [Fact]
    public void ReachableInterface_MemberTypesMatchTheGeneratedBackingClass()
    {
        PropertyInfo? onInterface = typeof(IProwling).GetProperty(nameof(IProwling.CollarTag));
        PropertyInfo? onBacking = typeof(Prowling).GetProperty(nameof(IProwling.CollarTag));

        Assert.NotNull(onInterface);
        Assert.NotNull(onBacking);
        Assert.Equal(onBacking!.PropertyType, onInterface!.PropertyType);
    }

    [Fact]
    public void ReachableInterface_RoundTripsThroughDispatchExports()
    {
        using IProwling prowling = Issue112Sample.Prowl();

        Assert.Equal("Mylo", prowling.CollarTag!.Label);
        Assert.Equal("Mylo roams the hallway", prowling.Describe("Mylo"));
    }

    private static IEnumerable<string> RawPointerMembers(Type type)
    {
        foreach (PropertyInfo property in type.GetProperties())
        {
            if (IsRawPointer(property.PropertyType)) yield return property.Name;
        }

        foreach (MethodInfo method in type.GetMethods())
        {
            if (IsRawPointer(method.ReturnType)) yield return method.Name;
            foreach (ParameterInfo parameter in method.GetParameters())
            {
                if (IsRawPointer(parameter.ParameterType)) yield return $"{method.Name}.{parameter.Name}";
            }
        }
    }

    private static bool IsRawPointer(Type type)
        => type == typeof(IntPtr) || type == typeof(IntPtr?);
}
