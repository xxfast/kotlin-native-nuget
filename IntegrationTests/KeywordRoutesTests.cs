using TestLibrary.Routes;

namespace IntegrationTests;

/// <summary>
/// Issues <a href="https://github.com/xxfast/kotlin-native-nuget/issues/65">#65</a> and
/// <a href="https://github.com/xxfast/kotlin-native-nuget/issues/66">#66</a>, on the forward routes
/// that predate the ordinary callable plan. The render-time rules that escape a C# keyword
/// (<c>ref</c> becomes <c>@ref</c>, <c>params</c> becomes <c>@params</c>) and rename a parameter
/// that collides with the ADR-024 exception slot (<c>error</c> becomes <c>error_</c>) live in
/// <c>csharpParameterName()</c>, which only the ordinary plan's parameter render sites reach. The
/// specialized legacy protocols print their own declarations and wrapper bodies as raw renderer
/// text, so a keyword parameter arrives in <c>Interop.cs</c> bare: <c>int ref</c>, <c>string
/// params</c> (CS1001 / CS1041), and a second <c>error</c> beside the exception slot (CS0100 /
/// CS0136).
/// <para>
/// The red for this is therefore "everything red": the generated file does not compile, so these
/// tests cannot even be built against it. Expected once fixed: every named argument below binds,
/// with exactly the spelling the ordinary plan already produces.
/// </para>
/// <para>
/// One fact per legacy route, each a distinct render site: top-level suspend, class suspend method,
/// <c>Flow</c>, <c>MutableStateFlow</c> (the write lambda that declares its own
/// <c>out IntPtr error</c>), lambda parameters in both spellings, generic top-level function,
/// the interface declaration, and the specialized sealed return. The abstract-class route is
/// absent on purpose: an <c>abstract fun</c> that no interface declares is dropped from the
/// generated base entirely today, so it never reaches the keyword bug at all.
/// </para>
/// <para>
/// The cats run dispatch. Oreo takes every call, Mylo is the reason there is an error slot.
/// </para>
/// </summary>
public class KeywordRoutesTests
{
    [Fact]
    public async Task Fetch_TopLevelSuspendRoute_BindsTheKeywordParameter()
    {
        // Oreo counts one biscuit, then asks for the next one.
        Assert.Equal(2, await KeywordRoutesSample.FetchAsync(@ref: 1));
        Assert.Equal(10, await KeywordRoutesSample.FetchAsync(9));
    }

    [Fact]
    public async Task Load_ClassSuspendMethodRoute_BindsTheKeywordParameter()
    {
        using var routes = new KeywordRoutes();

        Assert.Equal("Oreo", await routes.LoadAsync(@params: "Oreo"));
        Assert.Equal("Mylo", await routes.LoadAsync("Mylo"));
    }

    [Fact]
    public async Task Watch_FlowRoute_CapturesTheKeywordParameterIntoTheStream()
    {
        using var routes = new KeywordRoutes();
        var seen = new List<int>();

        // "Oreo" is four letters, so a dropped or defaulted argument comes back 1, 2, 3.
        await foreach (int tick in routes.Watch(@params: "Oreo"))
            seen.Add(tick);

        Assert.Equal(new List<int> { 5, 6, 7 }, seen);
    }

    [Fact]
    public void State_MutableStateFlowRoute_RenamesTheParameterOffTheErrorSlot()
    {
        using var routes = new KeywordRoutes();

        // The generated write lambda declares its own `out IntPtr error`, so the user parameter has
        // to move aside. Mylo has knocked exactly two things off the counter so far today.
        using var state = routes.State(error_: 2);

        Assert.Equal(2, state.Value);

        // Round trip. Reads and writes both re-invoke `state(error_)` on the Kotlin side, so this
        // only holds because the fixture memoises the flow per key.
        state.Value = 5;
        Assert.Equal(5, state.Value);
    }

    [Fact]
    public void OnEvent_LambdaParameterRoute_BindsTheKeywordNamedCallback()
    {
        using var routes = new KeywordRoutes();
        var seen = new List<int>();

        routes.OnEvent(@ref: tick =>
        {
            using var t = tick;
            seen.Add(t.N);
        });

        Assert.Equal(new List<int> { 7 }, seen);
    }

    [Fact]
    public void OnFail_LambdaParameterRoute_RenamesTheCallbackOffTheErrorSlot()
    {
        using var routes = new KeywordRoutes();
        var seen = new List<int>();

        // 9, not 7: this must not be able to pass by reading OnEvent's value.
        routes.OnFail(error_: tick =>
        {
            using var t = tick;
            seen.Add(t.N);
        });

        Assert.Equal(new List<int> { 9 }, seen);
    }

    [Fact]
    public void Put_GenericTopLevelRoute_BindsTheKeywordParameterOnEveryInstantiation()
    {
        Assert.Equal(3, KeywordRoutesSample.Put<int>(@ref: 3));
        Assert.Equal("Oreo", KeywordRoutesSample.Put<string>(@ref: "Oreo"));
    }

    [Fact]
    public void Handle_InterfaceDeclarationRoute_BindsTheKeywordParameterThroughTheInterface()
    {
        using var handler = new KeywordHandlerImpl();

        // The control: a class method, escaped by the ordinary plan today, and it must stay escaped.
        Assert.Equal("OREO", handler.Handle(@params: "Oreo"));

        // The cell: `IKeywordHandler` is printed by the interface renderer, where the name is bare.
        IKeywordHandler dispatch = handler;
        Assert.Equal("MYLO", dispatch.Handle(@params: "Mylo"));
    }

    [Fact]
    public void Make_SpecializedSealedReturnRoute_BindsTheKeywordParameter()
    {
        using KeywordShape shape = KeywordRoutesSample.Make(@ref: 5);

        var round = Assert.IsType<Round>(shape);
        Assert.Equal(5, round.R);
    }

    // Generated-local collisions on the generic top-level route. Each Kotlin parameter is named
    // after a local the generated `F<T>` body declares for itself, so the named argument below is
    // the public label that must survive: the generator renames its own locals, never these.
    // `int` and `string` cross the width dispatch (`width`, `present`); `KeywordTick` takes the
    // object arm (`handle`, `owned`, `result`).

    [Fact]
    public void Keep_GenericTopLevelRoute_BindsAParameterNamedOwned()
    {
        // Oreo keeps three biscuits, his own name, and the tick Mylo left him.
        Assert.Equal(3, KeywordRoutesSample.Keep<int>(owned: 3));
        Assert.Equal("Oreo", KeywordRoutesSample.Keep<string>(owned: "Oreo"));
        using var tick = new KeywordTick(11);
        using var kept = KeywordRoutesSample.Keep<KeywordTick>(owned: tick);
        Assert.Equal(11, kept.N);
    }

    [Fact]
    public void Hold_GenericTopLevelRoute_BindsAParameterNamedHandle()
    {
        Assert.Equal(4, KeywordRoutesSample.Hold<int>(handle: 4));
        Assert.Equal("Mylo", KeywordRoutesSample.Hold<string>(handle: "Mylo"));
        using var tick = new KeywordTick(12);
        using var held = KeywordRoutesSample.Hold<KeywordTick>(handle: tick);
        Assert.Equal(12, held.N);
    }

    [Fact]
    public void Pick_GenericTopLevelRoute_BindsAParameterNamedResult()
    {
        Assert.Equal(5, KeywordRoutesSample.Pick<int>(result: 5));
        Assert.Equal("Oreo", KeywordRoutesSample.Pick<string>(result: "Oreo"));
        using var tick = new KeywordTick(13);
        using var picked = KeywordRoutesSample.Pick<KeywordTick>(result: tick);
        Assert.Equal(13, picked.N);
    }

    [Fact]
    public void Span_GenericTopLevelRoute_BindsAParameterNamedWidth()
    {
        // Mylo, stretched out on the couch, is measured in cushions.
        Assert.Equal(6, KeywordRoutesSample.Span<int>(width: 6));
        Assert.Equal("Mylo", KeywordRoutesSample.Span<string>(width: "Mylo"));
        using var tick = new KeywordTick(14);
        using var spanned = KeywordRoutesSample.Span<KeywordTick>(width: tick);
        Assert.Equal(14, spanned.N);
    }

    [Fact]
    public void Seen_GenericTopLevelRoute_BindsAParameterNamedPresent()
    {
        Assert.Equal(7, KeywordRoutesSample.Seen<int>(present: 7));
        Assert.Equal("Oreo", KeywordRoutesSample.Seen<string>(present: "Oreo"));
        using var tick = new KeywordTick(15);
        using var seen = KeywordRoutesSample.Seen<KeywordTick>(present: tick);
        Assert.Equal(15, seen.N);
    }

    [Fact]
    public void Mark_GenericTopLevelRoute_BindsAParameterNamedErrorOut()
    {
        // Named after the Kotlin export's exception slot, not a C# local: like the plan routes,
        // the label shifts to `errorOut_` so the ABI contract check reads it as user data.
        // Mylo marks his spot.
        Assert.Equal(8, KeywordRoutesSample.Mark<int>(errorOut_: 8));
        Assert.Equal("Mylo", KeywordRoutesSample.Mark<string>(errorOut_: "Mylo"));
        using var tick = new KeywordTick(16);
        using var marked = KeywordRoutesSample.Mark<KeywordTick>(errorOut_: tick);
        Assert.Equal(16, marked.N);
    }

    [Fact]
    public void KeywordCollar_Constructor_ShiftsAParameterNamedHandleOffItsLocal()
    {
        // Oreo's collar tag. Green today: a constructor parameter goes through
        // bridgeParameterName(), so `handle` is already `handle_` and never meets the body's
        // own `IntPtr handle` local. Pinned so the generic-route fix cannot change it.
        using var collar = new KeywordCollar(handle_: 21);
        Assert.Equal(21, collar.Tag);
    }
}
