using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

public class ExceptionPropagationTests
{
    [Fact]
    public async Task OreoOnDiet_ThrowsArgumentException_WithTypeName()
    {
        // Oreo can't have treats — he's on a diet!
        // Under ADR-029, IllegalArgumentException maps to KotlinArgumentException : ArgumentException
        var ex = await Assert.ThrowsAnyAsync<ArgumentException>(
            () => AsyncExceptions.FetchCatTreatAsync("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
        Assert.Equal("Oreo is on a diet!", ex.Message);
    }

    [Fact]
    public async Task MyloPetTreat_Succeeds()
    {
        // Mylo is not on a diet — treats for everyone!
        string result = await AsyncExceptions.FetchCatTreatAsync("Mylo");
        Assert.Equal("Mylo got a treat", result);
    }

    [Fact]
    public async Task OreoOnDiet_IsExactType_KotlinArgumentException()
    {
        // Verify the concrete mapped subtype is KotlinArgumentException
        var ex = await Assert.ThrowsAnyAsync<ArgumentException>(
            () => AsyncExceptions.FetchCatTreatAsync("Oreo"));
        Assert.IsType<KotlinArgumentException>(ex);
    }

    [Fact]
    public async Task OreoOnDiet_ViaIKotlinException_KotlinType_ContainsIllegalArgumentException()
    {
        // KotlinType string is preserved on IKotlinException
        var ex = await Assert.ThrowsAnyAsync<ArgumentException>(
            () => AsyncExceptions.FetchCatTreatAsync("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.Contains("IllegalArgumentException", ke.KotlinType);
    }

    [Fact]
    public async Task OreoOnDiet_ViaIKotlinException_KotlinStackTrace_NonEmpty()
    {
        // Oreo can't have treats — he's on a diet, and the trace proves it!
        var ex = await Assert.ThrowsAnyAsync<ArgumentException>(
            () => AsyncExceptions.FetchCatTreatAsync("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.NotNull(ke.KotlinStackTrace);
        Assert.NotEmpty(ke.KotlinStackTrace);
    }

    [Fact]
    public async Task OreoOnDiet_ViaIKotlinException_KotlinStackTrace_ContainsExceptionType()
    {
        var ex = await Assert.ThrowsAnyAsync<ArgumentException>(
            () => AsyncExceptions.FetchCatTreatAsync("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.Contains("IllegalArgumentException", ke.KotlinStackTrace);
    }

    [Fact]
    public async Task OreoOnDiet_ViaIKotlinException_KotlinStackTrace_ContainsThrowingFunction()
    {
        var ex = await Assert.ThrowsAnyAsync<ArgumentException>(
            () => AsyncExceptions.FetchCatTreatAsync("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.Contains("fetchCatTreat", ke.KotlinStackTrace);
    }

    [Fact]
    public async Task OreoOnDiet_ToString_ContainsKotlinStackTraceSection()
    {
        var ex = await Assert.ThrowsAnyAsync<ArgumentException>(
            () => AsyncExceptions.FetchCatTreatAsync("Oreo"));
        Assert.Contains("Kotlin stack trace", ex.ToString());
    }

    [Fact]
    public async Task OreoOnDiet_ToString_ContainsKotlinStackTraceContent()
    {
        var ex = await Assert.ThrowsAnyAsync<ArgumentException>(
            () => AsyncExceptions.FetchCatTreatAsync("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.Contains(ke.KotlinStackTrace, ex.ToString());
    }
}
