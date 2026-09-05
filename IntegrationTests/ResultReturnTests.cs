using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// Issue <a href="https://github.com/xxfast/kotlin-native-nuget/issues/56">#56</a> part 1 /
/// ADR-108: a <c>Result&lt;T&gt;</c> at an ordinary return position binds as <c>T</c>, with a
/// <c>Result.failure(e)</c> thrown on the exception channel that already ships. The human decision
/// is throw-on-failure only: there is no <c>TryRun</c> overload and no generated result struct.
/// <para>
/// Today both members are dropped with <c>SKIPPED_UNSUPPORTED_TYPE ... VALUE_CLASS</c>, so
/// <c>Run</c> and <c>Feed</c> do not exist on <see cref="Service"/> and this file cannot compile.
/// That is the red signal.
/// </para>
/// <para>
/// Two payloads, because they take two different Kotlin body shapes: <c>Result&lt;Unit&gt;</c> has
/// no wire at all and must become <c>void</c>; <c>Result&lt;String&gt;</c> has a pointer wire and
/// exercises both the success and the failure half.
/// </para>
/// </summary>
public class ResultReturnTests
{
    // --- Result<Unit> -> void Run() : the issue's exact repro ---

    [Fact]
    public void Run_ResultOfUnit_BindsAsVoidAndSucceeds()
    {
        using var service = ResultSample.service();

        // Compile-time contract: Run() is void, not Unit-returning and not a bool Try shape.
        Assert.Null(Record.Exception(() => service.Run()));
    }

    [Fact]
    public void Run_ResultOfUnit_IsAlsoReachableFromTheOrdinaryConstructor()
    {
        using var service = new Service();

        Assert.Null(Record.Exception(() => service.Run()));
    }

    // --- Result<String> success half ---

    [Fact]
    public void Feed_Mylo_ResultSuccess_ReturnsThePayloadAsAPlainString()
    {
        using var service = ResultSample.service();

        // Compile-time contract: the return type is string, not Result<string>.
        string treat = service.Feed("Mylo");

        Assert.Equal("Mylo got a treat", treat);
    }

    // --- Result<String> failure half: ADR-029 mapping, identical to a thrown exception ---

    [Fact]
    public void Feed_Oreo_ResultFailure_ThrowsArgumentException()
    {
        using var service = ResultSample.service();

        Assert.ThrowsAny<ArgumentException>(() => service.Feed("Oreo"));
    }

    [Fact]
    public void Feed_Oreo_ResultFailure_IsExactType_KotlinArgumentException()
    {
        using var service = ResultSample.service();

        var ex = Assert.ThrowsAny<ArgumentException>(() => service.Feed("Oreo"));

        Assert.IsType<KotlinArgumentException>(ex);
    }

    [Fact]
    public void Feed_Oreo_ResultFailure_KotlinType_IsIllegalArgumentException()
    {
        using var service = ResultSample.service();

        var ex = Assert.ThrowsAny<ArgumentException>(() => service.Feed("Oreo"));
        var ke = (IKotlinException)ex;

        Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
    }

    [Fact]
    public void Feed_Oreo_ResultFailure_CarriesTheKotlinMessage()
    {
        using var service = ResultSample.service();

        var ex = Assert.ThrowsAny<ArgumentException>(() => service.Feed("Oreo"));

        Assert.Equal("Oreo is on a diet!", ex.Message);
    }

    [Fact]
    public void Feed_Oreo_ResultFailure_CarriesAKotlinStackTrace()
    {
        // A Result.failure is constructed, never thrown, on the Kotlin side. The trace is captured
        // at construction, so it must still be present after getOrThrow() re-raises it.
        using var service = ResultSample.service();

        var ex = Assert.ThrowsAny<ArgumentException>(() => service.Feed("Oreo"));
        var ke = (IKotlinException)ex;

        Assert.NotNull(ke.KotlinStackTrace);
        Assert.NotEmpty(ke.KotlinStackTrace);
    }

    [Fact]
    public void Feed_Oreo_ResultFailure_HasNoInnerException()
    {
        // The failure was built with no cause; the envelope must not invent one.
        using var service = ResultSample.service();

        var ex = Assert.ThrowsAny<ArgumentException>(() => service.Feed("Oreo"));

        Assert.Null(ex.InnerException);
    }

    [Fact]
    public void Feed_FailureThenSuccess_LeavesTheInstanceUsable()
    {
        // The errorOut path must not poison the handle: Oreo's refusal cannot cost Mylo his treat.
        using var service = ResultSample.service();

        Assert.ThrowsAny<ArgumentException>(() => service.Feed("Oreo"));

        Assert.Equal("Mylo got a treat", service.Feed("Mylo"));
        Assert.Null(Record.Exception(() => service.Run()));
    }
}
