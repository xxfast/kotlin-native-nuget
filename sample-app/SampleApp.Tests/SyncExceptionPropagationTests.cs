using SampleLibrary;
using SampleLibrary.Cat;
using Xunit.Abstractions;

namespace SampleApp.Tests;

public class SyncExceptionPropagationTests
{
    private readonly ITestOutputHelper _testOutputHelper;

    public SyncExceptionPropagationTests(ITestOutputHelper testOutputHelper)
    {
        _testOutputHelper = testOutputHelper;
    }

    [Fact]
    public void Oreo_OnDiet_ThrowsKotlinException()
    {
        Assert.Throws<KotlinException>(
            () => SyncExceptions.feedCatTreat("Oreo"));
    }

    [Fact]
    public void Oreo_OnDiet_ThrowsKotlinException_WithTypeName()
    {
        var ex = Assert.Throws<KotlinException>(
            () => SyncExceptions.feedCatTreat("Oreo"));
        Assert.Equal("kotlin.IllegalArgumentException", ex.KotlinType);
    }

    [Fact]
    public void Oreo_OnDiet_ThrowsKotlinException_WithMessage()
    {
        var ex = Assert.Throws<KotlinException>(
            () => SyncExceptions.feedCatTreat("Oreo"));
        Assert.Equal("Oreo is on a diet!", ex.Message);
    }

    [Fact]
    public void Mylo_GetsTreat_Succeeds()
    {
        string result = SyncExceptions.feedCatTreat("Mylo");
        Assert.Equal("Mylo got a treat", result);
    }

    [Fact]
    public void Oreo_OnDiet_ThrowsKotlinException_WithKotlinStackTrace_NonEmpty()
    {
        var ex = Assert.Throws<KotlinException>(
            () => SyncExceptions.feedCatTreat("Oreo"));
        Assert.NotNull(ex.KotlinStackTrace);
        Assert.NotEmpty(ex.KotlinStackTrace);
    }

    [Fact]
    public void Oreo_OnDiet_ThrowsKotlinException_WithKotlinStackTrace_ContainsExceptionType()
    {
        var ex = Assert.Throws<KotlinException>(
            () => SyncExceptions.feedCatTreat("Oreo"));
        _testOutputHelper.WriteLine(ex.KotlinStackTrace);
        Assert.Contains("IllegalArgumentException", ex.KotlinStackTrace);
    }

    [Fact]
    public void Oreo_OnDiet_ThrowsKotlinException_WithKotlinStackTrace_ContainsThrowingFunction()
    {
        var ex = Assert.Throws<KotlinException>(
            () => SyncExceptions.feedCatTreat("Oreo"));
        Assert.Contains("feedCatTreat", ex.KotlinStackTrace);
    }

    [Fact]
    public void Oreo_OnDiet_ThrowsKotlinException_ToString_ContainsKotlinStackTraceSection()
    {
        var ex = Assert.Throws<KotlinException>(
            () => SyncExceptions.feedCatTreat("Oreo"));
        Assert.Contains("Kotlin stack trace", ex.ToString());
    }

    [Fact]
    public void Oreo_OnDiet_ThrowsKotlinException_ToString_ContainsKotlinStackTraceContent()
    {
        var ex = Assert.Throws<KotlinException>(
            () => SyncExceptions.feedCatTreat("Oreo"));
        Assert.Contains(ex.KotlinStackTrace, ex.ToString());
    }
}
