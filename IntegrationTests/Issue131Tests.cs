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
/// ADR-149 is the diagnostic half's arity question: a trailing defaulted <c>Flow</c> costs only
/// the arities that still carry it. <c>HubWithEvents()</c> and <c>HubWithEvents(Settings)</c>
/// bind; the events arity stays absent. The KSP-side wording is still pinned by
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

    // ---- ADR-149: supported arities of a partially unsupported signature. ----

    [Fact]
    public void HubWithEvents_OmittingEveryDefault_UsesTheKotlinDefault()
    {
        using Hub hub = HubSample.HubWithEvents();

        Assert.Equal("0/none/-", hub.Describe());
    }

    [Fact]
    public void HubWithEvents_OmittingTheFlow_UsesTheKotlinDefault()
    {
        using var settings = new Settings(3);

        using Hub hub = HubSample.HubWithEvents(settings);

        Assert.Equal("3/none/-", hub.Describe());
    }

    [Fact]
    public void HubWithEvents_LeavesOnlyTheUnsupportedArityAbsent()
    {
        MethodInfo[] methods = typeof(HubSample).GetMethods()
            .Where(method => method.Name == "HubWithEvents")
            .ToArray();

        Assert.Contains(methods, method => method.GetParameters().Length == 0);
        Assert.Contains(methods, method =>
            method.GetParameters() is [{ ParameterType.Name: "Settings" }]);
        Assert.DoesNotContain(methods, method =>
            method.GetParameters().Any(parameter => parameter.Name == "events"));
    }

    [Fact]
    public void HubWithLoggerAndEvents_OmittingEveryDefault_UsesTheKotlinDefaults()
    {
        using Hub hub = HubSample.HubWithLoggerAndEvents();

        Assert.Equal("0/none/feed", hub.Describe());
    }

    [Fact]
    public void HubWithLoggerAndEvents_OmittingLoggerAndEvents_UsesTheKotlinDefaults()
    {
        using var settings = new Settings(3);

        using Hub hub = HubSample.HubWithLoggerAndEvents(settings);

        Assert.Equal("3/none/feed", hub.Describe());
    }

    [Fact]
    public void HubWithLoggerAndEvents_OmittingOnlyEvents_Binds()
    {
        using var settings = new Settings(3);
        using var logger = new Logger("Oreo");

        using Hub hub = HubSample.HubWithLoggerAndEvents(settings, logger);

        Assert.Equal("3/Oreo/feed", hub.Describe());
    }

    [Fact]
    public void HubWithLoggerAndEvents_LeavesOnlyTheUnsupportedArityAbsent()
    {
        MethodInfo[] methods = typeof(HubSample).GetMethods()
            .Where(method => method.Name == "HubWithLoggerAndEvents")
            .ToArray();

        Assert.Contains(methods, method => method.GetParameters().Length == 0);
        Assert.Contains(methods, method =>
            method.GetParameters() is [{ ParameterType.Name: "Settings" }]);
        Assert.Contains(methods, method =>
            method.GetParameters() is
                [{ ParameterType.Name: "Settings" }, { ParameterType.Name: "Logger" }]);
        Assert.DoesNotContain(methods, method =>
            method.GetParameters().Any(parameter => parameter.Name == "events"));
    }

    [Fact]
    public void Sill_OmittingEveryDefault_UsesTheKotlinDefaults()
    {
        using var sill = new Sill();

        Assert.Equal("0/-", sill.Describe());
    }

    [Fact]
    public void Sill_OmittingTheFlow_UsesTheKotlinDefault()
    {
        using var settings = new Settings(3);

        using var sill = new Sill(settings);

        Assert.Equal("3/-", sill.Describe());
    }

    [Fact]
    public void Sill_LeavesOnlyTheUnsupportedArityAbsent()
    {
        ConstructorInfo[] constructors = typeof(Sill).GetConstructors();

        Assert.NotNull(typeof(Sill).GetConstructor(Type.EmptyTypes));
        Assert.NotNull(typeof(Sill).GetConstructor([typeof(Settings)]));
        Assert.DoesNotContain(constructors, constructor =>
            constructor.GetParameters().Any(parameter => parameter.Name == "events"));
    }

    [Fact]
    public void DeskOpen_OmittingEveryDefault_UsesTheKotlinDefaults()
    {
        using var desk = new Desk("Oreo");

        Assert.Equal("Oreo desk 0/-", desk.Open());
    }

    [Fact]
    public void DeskOpen_OmittingTheFlow_UsesTheKotlinDefault()
    {
        using var desk = new Desk("Oreo");
        using var settings = new Settings(3);

        Assert.Equal("Oreo desk 3/-", desk.Open(settings));
    }

    [Fact]
    public void DeskOpen_LeavesOnlyTheUnsupportedArityAbsent()
    {
        Assert.DoesNotContain(
            typeof(Desk).GetMethods(),
            method => method.Name == "Open" &&
                method.GetParameters().Any(parameter => parameter.Name == "events"));
    }

    [Fact]
    public void SwitchboardPatch_OmittingEveryDefault_UsesTheKotlinDefaults()
    {
        Assert.Equal("patch 0/-", Switchboard.Patch());
    }

    [Fact]
    public void SwitchboardPatch_OmittingTheFlow_UsesTheKotlinDefault()
    {
        Assert.Equal("patch 3/-", Switchboard.Patch(3));
    }

    [Fact]
    public void SwitchboardPatch_LeavesOnlyTheUnsupportedArityAbsent()
    {
        Assert.DoesNotContain(
            typeof(Switchboard).GetMethods(),
            method => method.Name == "Patch" &&
                method.GetParameters().Any(parameter => parameter.Name == "events"));
    }

    [Fact]
    public void WindowOf_OmittingEveryDefault_UsesTheKotlinDefaults()
    {
        Assert.Equal("window 0/-", Window.Of());
    }

    [Fact]
    public void WindowOf_OmittingTheFlow_UsesTheKotlinDefault()
    {
        using var settings = new Settings(3);

        Assert.Equal("window 3/-", Window.Of(settings));
    }

    [Fact]
    public void WindowOf_LeavesOnlyTheUnsupportedArityAbsent()
    {
        Assert.DoesNotContain(
            typeof(Window).GetMethods(),
            method => method.Name == "Of" &&
                method.GetParameters().Any(parameter => parameter.Name == "events"));
    }

    [Fact]
    public void LoggerCall_OmittingEveryDefault_UsesTheKotlinDefaults()
    {
        using var logger = new Logger("Mylo");

        Assert.Equal("Mylo calls 0/-", logger.Call());
    }

    [Fact]
    public void LoggerCall_OmittingTheFlow_UsesTheKotlinDefault()
    {
        using var logger = new Logger("Oreo");

        Assert.Equal("Oreo calls 3/-", logger.Call(3));
    }

    [Fact]
    public void LoggerCall_LeavesOnlyTheUnsupportedArityAbsent()
    {
        Assert.DoesNotContain(
            typeof(LoggerExtensions).GetMethods(),
            method => method.Name == "Call" &&
                method.GetParameters().Any(parameter => parameter.Name == "events"));
    }

    [Fact]
    public void ShiftNightWatch_OmittingEveryDefault_UsesTheKotlinDefaults()
    {
        using var night = new Shift.Night(cat: "Oreo");

        Assert.Equal("Oreo watches 0/-", night.Watch());
    }

    [Fact]
    public void ShiftNightWatch_OmittingTheFlow_UsesTheKotlinDefault()
    {
        using var night = new Shift.Night(cat: "Oreo");
        using var settings = new Settings(3);

        Assert.Equal("Oreo watches 3/-", night.Watch(settings));
    }

    [Fact]
    public void ShiftNightWatch_LeavesOnlyTheUnsupportedArityAbsent()
    {
        Assert.DoesNotContain(
            typeof(Shift.Night).GetMethods(),
            method => method.Name == "Watch" &&
                method.GetParameters().Any(parameter => parameter.Name == "events"));
    }
}
