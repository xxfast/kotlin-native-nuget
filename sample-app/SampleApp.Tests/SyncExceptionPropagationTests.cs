using SampleLibrary;
using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class SyncExceptionPropagationTests
{
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
}
