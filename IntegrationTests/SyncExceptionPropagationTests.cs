using TestLibrary;
using TestLibrary.Cat;
using Xunit.Abstractions;

namespace IntegrationTests;

public class SyncExceptionPropagationTests
{
    private readonly ITestOutputHelper _testOutputHelper;

    public SyncExceptionPropagationTests(ITestOutputHelper testOutputHelper)
    {
        _testOutputHelper = testOutputHelper;
    }

    [Fact]
    public void Oreo_OnDiet_ThrowsArgumentException()
    {
        // Under ADR-029, IllegalArgumentException maps to KotlinArgumentException : ArgumentException
        Assert.ThrowsAny<ArgumentException>(
            () => SyncExceptions.feedCatTreat("Oreo"));
    }

    [Fact]
    public void Oreo_OnDiet_IsExactType_KotlinArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => SyncExceptions.feedCatTreat("Oreo"));
        Assert.IsType<KotlinArgumentException>(ex);
    }

    [Fact]
    public void Oreo_OnDiet_ViaIKotlinException_KotlinType_IsIllegalArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => SyncExceptions.feedCatTreat("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
    }

    [Fact]
    public void Oreo_OnDiet_WithMessage()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
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
    public void Oreo_OnDiet_ViaIKotlinException_KotlinStackTrace_NonEmpty()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => SyncExceptions.feedCatTreat("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.NotNull(ke.KotlinStackTrace);
        Assert.NotEmpty(ke.KotlinStackTrace);
    }

    [Fact]
    public void Oreo_OnDiet_ViaIKotlinException_KotlinStackTrace_ContainsExceptionType()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => SyncExceptions.feedCatTreat("Oreo"));
        var ke = (IKotlinException)ex;
        _testOutputHelper.WriteLine(ke.KotlinStackTrace);
        Assert.Contains("IllegalArgumentException", ke.KotlinStackTrace);
    }

    [Fact]
    public void Oreo_OnDiet_ViaIKotlinException_KotlinStackTrace_ContainsThrowingFunction()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => SyncExceptions.feedCatTreat("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.Contains("feedCatTreat", ke.KotlinStackTrace);
    }

    [Fact]
    public void Oreo_OnDiet_ToString_ContainsKotlinStackTraceSection()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => SyncExceptions.feedCatTreat("Oreo"));
        Assert.Contains("Kotlin stack trace", ex.ToString());
    }

    [Fact]
    public void Oreo_OnDiet_ToString_ContainsKotlinStackTraceContent()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => SyncExceptions.feedCatTreat("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.Contains(ke.KotlinStackTrace, ex.ToString());
    }
}
