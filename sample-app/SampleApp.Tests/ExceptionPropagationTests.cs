using SampleLibrary;
using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class ExceptionPropagationTests
{
    [Fact]
    public async Task OreoOnDiet_ThrowsKotlinException_WithTypeName()
    {
        // Oreo can't have treats — he's on a diet!
        var ex = await Assert.ThrowsAsync<KotlinException>(
            () => AsyncExceptions.FetchCatTreatAsync("Oreo"));
        Assert.Equal("kotlin.IllegalArgumentException", ex.KotlinType);
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
    public async Task KotlinException_HasKotlinTypeProperty()
    {
        // Verify the KotlinType property is set correctly
        var ex = await Assert.ThrowsAsync<KotlinException>(
            () => AsyncExceptions.FetchCatTreatAsync("Oreo"));
        Assert.Contains("IllegalArgumentException", ex.KotlinType);
    }

    [Fact]
    public async Task OreoOnDiet_ThrowsKotlinException_WithKotlinStackTrace_NonEmpty()
    {
        // Oreo can't have treats — he's on a diet, and the trace proves it!
        var ex = await Assert.ThrowsAsync<KotlinException>(
            () => AsyncExceptions.FetchCatTreatAsync("Oreo"));
        Assert.NotNull(ex.KotlinStackTrace);
        Assert.NotEmpty(ex.KotlinStackTrace);
    }

    [Fact]
    public async Task OreoOnDiet_ThrowsKotlinException_WithKotlinStackTrace_ContainsExceptionType()
    {
        var ex = await Assert.ThrowsAsync<KotlinException>(
            () => AsyncExceptions.FetchCatTreatAsync("Oreo"));
        Assert.Contains("IllegalArgumentException", ex.KotlinStackTrace);
    }

    [Fact]
    public async Task OreoOnDiet_ThrowsKotlinException_WithKotlinStackTrace_ContainsThrowingFunction()
    {
        var ex = await Assert.ThrowsAsync<KotlinException>(
            () => AsyncExceptions.FetchCatTreatAsync("Oreo"));
        Assert.Contains("fetchCatTreat", ex.KotlinStackTrace);
    }

    [Fact]
    public async Task OreoOnDiet_ThrowsKotlinException_ToString_ContainsKotlinStackTraceSection()
    {
        var ex = await Assert.ThrowsAsync<KotlinException>(
            () => AsyncExceptions.FetchCatTreatAsync("Oreo"));
        Assert.Contains("Kotlin stack trace", ex.ToString());
    }

    [Fact]
    public async Task OreoOnDiet_ThrowsKotlinException_ToString_ContainsKotlinStackTraceContent()
    {
        var ex = await Assert.ThrowsAsync<KotlinException>(
            () => AsyncExceptions.FetchCatTreatAsync("Oreo"));
        Assert.Contains(ex.KotlinStackTrace, ex.ToString());
    }
}
