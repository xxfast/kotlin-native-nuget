using System.Reflection;
using TestLibrary.Issue131;

namespace IntegrationTests;

/// <summary>
/// Issue #131, the mapping half: a nullable exported class handle at a parameter position, on the
/// two routes that had no coverage. <c>Patient.Attach</c> already pinned the class-method route;
/// these pin the constructor and the top-level factory, where the same lowering is reached through
/// a different emitter path (<c>logger?._handle ?? IntPtr.Zero</c> out,
/// <c>logger?.asStableRef&lt;Logger&gt;()?.get()</c> in).
/// <para>
/// <see cref="Logger"/> needs that conversion; <c>note</c> is a <c>string?</c> and needs none, so
/// both null-carrying spellings cross on each route.
/// </para>
/// <para>
/// The diagnostic half of the issue is <c>HubWithEvents</c>, absent by design and asserted by
/// reflection: the KSP-side wording is pinned by
/// <c>Tier1NullableParameterDiagnosticTest</c>.
/// </para>
/// <para>
/// Oreo logs everything. Mylo prefers to run without a logger.
/// </para>
/// </summary>
public class Issue131Tests
{
    // ---- Top-level factory route. ----

    [Fact]
    public void TopLevelFactory_WithANullLogger_PassesNullThrough()
    {
        using var settings = new Settings(3);

        using Hub hub = HubSample.Hub(settings, null, null);

        Assert.Equal("3/none/-", hub.Describe());
        Assert.Null(hub.Logger);
    }

    [Fact]
    public void TopLevelFactory_WithARealLogger_PassesTheHandleThrough()
    {
        using var settings = new Settings(7);
        using var logger = new Logger("Oreo");

        using Hub hub = HubSample.Hub(settings, logger, "n");

        Assert.Equal("7/Oreo/n", hub.Describe());
        using Logger? carried = hub.Logger;
        Assert.Equal("Oreo", carried!.Tag);
    }

    /// <summary>
    /// ADR-096's omitting overloads are minted over the nullable handle parameter too, so the
    /// defaults-only call the issue asked for exists.
    /// </summary>
    [Fact]
    public void TopLevelFactory_OmittingEveryDefault_UsesTheKotlinDefaults()
    {
        using Hub hub = HubSample.Hub();

        Assert.Equal("0/none/-", hub.Describe());
    }

    // ---- Constructor route. ----

    [Fact]
    public void Constructor_WithANullLogger_PassesNullThrough()
    {
        using var settings = new Settings(1);

        using var hub = new Hub(settings, null, null);

        Assert.Equal("1/none/-", hub.Describe());
        Assert.Null(hub.Logger);
    }

    [Fact]
    public void Constructor_WithARealLogger_PassesTheHandleThrough()
    {
        using var settings = new Settings(2);
        using var logger = new Logger("Mylo");

        using var hub = new Hub(settings, logger, "purring");

        Assert.Equal("2/Mylo/purring", hub.Describe());
        Assert.Equal("[Mylo] hi", logger.Log("hi"));
    }

    /// <summary>
    /// The borrowed handle is not consumed by the call: the same <see cref="Logger"/> can back
    /// several hubs and still be usable afterwards.
    /// </summary>
    [Fact]
    public void ANullableHandleArgument_IsBorrowed_NotConsumed()
    {
        using var settings = new Settings(4);
        using var logger = new Logger("Oreo");

        using var first = new Hub(settings, logger, null);
        using Hub second = HubSample.Hub(settings, logger, null);

        Assert.Equal("4/Oreo/-", first.Describe());
        Assert.Equal("4/Oreo/-", second.Describe());
        Assert.Equal("[Oreo] still here", logger.Log("still here"));
    }

    // ---- The refusal arm: absent, never present-and-broken. ----

    [Fact]
    public void AFlowParameter_LeavesTheWholeFunctionAbsent()
    {
        MethodInfo[] methods = typeof(HubSample).GetMethods();

        Assert.DoesNotContain(methods, method => method.Name == "HubWithEvents");
    }
}
