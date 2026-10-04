using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// Issue <a href="https://github.com/xxfast/kotlin-native-nuget/issues/56">#56</a> part 1 /
/// ADR-108: a <c>Result&lt;T&gt;</c> at an ordinary return position binds as <c>T</c>, with a
/// <c>Result.failure(e)</c> thrown on the exception channel that already ships. Beside it sits a
/// non-throwing <c>bool TryX(..., out T value, out Exception? failure)</c> twin over the same
/// export, which returns <c>false</c> for a modelled <c>Result.failure</c> and still throws for an
/// exception the Kotlin body threw. No generated result struct.
/// <para>
/// Two payloads for the throwing member, because they take two different Kotlin body shapes:
/// <c>Result&lt;Unit&gt;</c> has no wire at all and must become <c>void</c>;
/// <c>Result&lt;String&gt;</c> has a pointer wire and exercises both the success and the failure
/// half. The Try twin adds a payload that needs no conversion, a nullable one and a handle.
/// </para>
/// </summary>
public class ResultReturnTests
{
    // --- Result<Unit> -> void Run() : the issue's exact repro ---

    [Fact]
    public void Run_ResultOfUnit_BindsAsVoidAndSucceeds()
    {
        using var service = ResultSample.Service();

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
        using var service = ResultSample.Service();

        // Compile-time contract: the return type is string, not Result<string>.
        string treat = service.Feed("Mylo");

        Assert.Equal("Mylo got a treat", treat);
    }

    // --- Result<String> failure half: ADR-029 mapping, identical to a thrown exception ---

    [Fact]
    public void Feed_Oreo_ResultFailure_ThrowsArgumentException()
    {
        using var service = ResultSample.Service();

        Assert.ThrowsAny<ArgumentException>(() => service.Feed("Oreo"));
    }

    [Fact]
    public void Feed_Oreo_ResultFailure_IsExactType_KotlinArgumentException()
    {
        using var service = ResultSample.Service();

        var ex = Assert.ThrowsAny<ArgumentException>(() => service.Feed("Oreo"));

        Assert.IsType<KotlinArgumentException>(ex);
    }

    [Fact]
    public void Feed_Oreo_ResultFailure_KotlinType_IsIllegalArgumentException()
    {
        using var service = ResultSample.Service();

        var ex = Assert.ThrowsAny<ArgumentException>(() => service.Feed("Oreo"));
        var ke = (IKotlinException)ex;

        Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
    }

    [Fact]
    public void Feed_Oreo_ResultFailure_CarriesTheKotlinMessage()
    {
        using var service = ResultSample.Service();

        var ex = Assert.ThrowsAny<ArgumentException>(() => service.Feed("Oreo"));

        Assert.Equal("Oreo is on a diet!", ex.Message);
    }

    [Fact]
    public void Feed_Oreo_ResultFailure_CarriesAKotlinStackTrace()
    {
        // A Result.failure is constructed, never thrown, on the Kotlin side. The trace is captured
        // at construction, so it must still be present after getOrThrow() re-raises it.
        using var service = ResultSample.Service();

        var ex = Assert.ThrowsAny<ArgumentException>(() => service.Feed("Oreo"));
        var ke = (IKotlinException)ex;

        Assert.NotNull(ke.KotlinStackTrace);
        Assert.NotEmpty(ke.KotlinStackTrace);
    }

    [Fact]
    public void Feed_Oreo_ResultFailure_HasNoInnerException()
    {
        // The failure was built with no cause; the envelope must not invent one.
        using var service = ResultSample.Service();

        var ex = Assert.ThrowsAny<ArgumentException>(() => service.Feed("Oreo"));

        Assert.Null(ex.InnerException);
    }

    [Fact]
    public void Feed_FailureThenSuccess_LeavesTheInstanceUsable()
    {
        // The errorOut path must not poison the handle: Oreo's refusal cannot cost Mylo his treat.
        using var service = ResultSample.Service();

        Assert.ThrowsAny<ArgumentException>(() => service.Feed("Oreo"));

        Assert.Equal("Mylo got a treat", service.Feed("Mylo"));
        Assert.Null(Record.Exception(() => service.Run()));
    }

    // --- The non-throwing Try twin ---

    [Fact]
    public void TryFeed_Mylo_ResultSuccess_ReturnsTrueWithThePayload()
    {
        using var service = ResultSample.Service();

        Assert.True(service.TryFeed("Mylo", out string? treat, out Exception? failure));

        Assert.Equal("Mylo got a treat", treat);
        Assert.Null(failure);
    }

    [Fact]
    public void TryFeed_Oreo_ResultFailure_ReturnsFalseWithTheMappedException()
    {
        using var service = ResultSample.Service();

        Assert.False(service.TryFeed("Oreo", out string? treat, out Exception? failure));

        Assert.Null(treat);
        var mapped = Assert.IsType<KotlinArgumentException>(failure);
        Assert.Equal("Oreo is on a diet!", mapped.Message);
        Assert.Equal("kotlin.IllegalArgumentException", mapped.KotlinType);
    }

    [Fact]
    public void TryWeigh_ResultOfInt_NeedsNoConversion()
    {
        using var service = ResultSample.Service();

        Assert.True(service.TryWeigh("Mylo", out int weight, out Exception? none));
        Assert.Equal(4, weight);
        Assert.Null(none);

        Assert.False(service.TryWeigh("Oreo", out int refused, out Exception? failure));
        Assert.Equal(0, refused);
        Assert.Equal(
            "Oreo will not get on the scale",
            Assert.IsType<KotlinArgumentException>(failure).Message);
    }

    [Fact]
    public void TryWeigh_Ghost_ThrownException_StillThrows()
    {
        // The test that proves the feature: a Try that swallowed every error would pass all the
        // others. Ghost's body throws instead of returning a failure, and the export zeroes the
        // failure flag before the call, so the Try must rethrow rather than return false.
        using var service = ResultSample.Service();

        var thrown = Assert.Throws<KotlinInvalidOperationException>(
            () => service.TryWeigh("Ghost", out _, out _));

        Assert.Equal("No such cat: Ghost", thrown.Message);
        Assert.Equal("kotlin.IllegalStateException", thrown.KotlinType);
    }

    [Fact]
    public void TryWeigh_ThrowThenFailureThenSuccess_ReadsEachCallsOwnFlag()
    {
        // A flag left set by one call must not leak into the next: the thrown call between two
        // modelled failures still throws, and the success after it still reports true.
        using var service = ResultSample.Service();

        Assert.False(service.TryWeigh("Oreo", out _, out _));
        Assert.Throws<KotlinInvalidOperationException>(
            () => service.TryWeigh("Ghost", out _, out _));
        Assert.False(service.TryWeigh("Oreo", out _, out _));
        Assert.True(service.TryWeigh("Mylo", out int weight, out _));
        Assert.Equal(4, weight);
    }

    [Fact]
    public void TryRun_And_TryScold_ResultOfUnit_HaveNoValueParameter()
    {
        using var service = ResultSample.Service();

        Assert.True(service.TryRun(out Exception? none));
        Assert.Null(none);
        Assert.True(service.TryScold("Mylo", out _));

        Assert.False(service.TryScold("Oreo", out Exception? failure));
        Assert.Equal("Oreo does not care", Assert.IsType<KotlinArgumentException>(failure).Message);
    }

    [Fact]
    public void TryLastVetVisitYear_SuccessfulNull_IsTrueWithNull()
    {
        using var service = ResultSample.Service();

        Assert.True(service.TryLastVetVisitYear("Mylo", out int? year, out Exception? none));
        Assert.Null(year);
        Assert.Null(none);

        Assert.False(service.TryLastVetVisitYear("Oreo", out int? hidden, out Exception? failure));
        Assert.Null(hidden);
        Assert.Equal(
            "Oreo hid under the bed",
            Assert.IsType<KotlinInvalidOperationException>(failure).Message);
    }

    [Fact]
    public void TryAdopt_HandlePayload_ReturnsAnOwnedCat()
    {
        using var service = ResultSample.Service();

        Assert.True(service.TryAdopt("Mylo", out Cat? cat, out _));
        using (cat)
        {
            Assert.Equal("Mylo", cat.Name);
        }

        Assert.False(service.TryAdopt("Oreo", out Cat? none, out Exception? failure));
        Assert.Null(none);
        Assert.IsType<KotlinArgumentException>(failure);
    }
}
