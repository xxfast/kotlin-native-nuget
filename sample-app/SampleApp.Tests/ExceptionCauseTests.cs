using SampleLibrary;
using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class ExceptionCauseTests
{
    // --- Single-level cause (sync) ---

    [Fact]
    public void Oreo_AllergyReaction_InnerException_IsNotNull()
    {
        var ex = Assert.Throws<KotlinException>(
            () => CauseExceptions.feedCatWithAllergy("Oreo"));
        Assert.NotNull(ex.InnerException);
    }

    [Fact]
    public void Oreo_AllergyReaction_InnerException_IsKotlinException()
    {
        var ex = Assert.Throws<KotlinException>(
            () => CauseExceptions.feedCatWithAllergy("Oreo"));
        Assert.IsType<KotlinException>(ex.InnerException);
    }

    [Fact]
    public void Oreo_AllergyReaction_OuterMessage_IsCorrect()
    {
        var ex = Assert.Throws<KotlinException>(
            () => CauseExceptions.feedCatWithAllergy("Oreo"));
        Assert.Equal("Oreo had a reaction", ex.Message);
    }

    [Fact]
    public void Oreo_AllergyReaction_InnerMessage_IsCorrect()
    {
        var ex = Assert.Throws<KotlinException>(
            () => CauseExceptions.feedCatWithAllergy("Oreo"));
        var inner = (KotlinException)ex.InnerException!;
        Assert.Equal("Oreo is allergic to this treat", inner.Message);
    }

    [Fact]
    public void Oreo_AllergyReaction_InnerKotlinType_ContainsRuntimeException()
    {
        var ex = Assert.Throws<KotlinException>(
            () => CauseExceptions.feedCatWithAllergy("Oreo"));
        var inner = (KotlinException)ex.InnerException!;
        Assert.Contains("RuntimeException", inner.KotlinType);
    }

    [Fact]
    public void Oreo_AllergyReaction_InnerException_HasNoFurtherCause()
    {
        var ex = Assert.Throws<KotlinException>(
            () => CauseExceptions.feedCatWithAllergy("Oreo"));
        Assert.Null(ex.InnerException!.InnerException);
    }

    [Fact]
    public void Oreo_AllergyReaction_InnerException_HasNonEmptyKotlinStackTrace()
    {
        var ex = Assert.Throws<KotlinException>(
            () => CauseExceptions.feedCatWithAllergy("Oreo"));
        var inner = (KotlinException)ex.InnerException!;
        Assert.NotNull(inner.KotlinStackTrace);
        Assert.NotEmpty(inner.KotlinStackTrace);
    }

    [Fact]
    public void Oreo_AllergyReaction_ToString_ContainsInnerExceptionMarker()
    {
        var ex = Assert.Throws<KotlinException>(
            () => CauseExceptions.feedCatWithAllergy("Oreo"));
        Assert.Contains("--->", ex.ToString());
    }

    // --- Happy path (no cause) ---

    [Fact]
    public void Mylo_AllergyFree_Succeeds()
    {
        string result = CauseExceptions.feedCatWithAllergy("Mylo");
        Assert.Equal("Mylo ate happily", result);
    }

    // --- Two-level deep chain (sync) ---

    [Fact]
    public void Oreo_GroomingFailed_DeepChain_OuterMessage_IsCorrect()
    {
        var ex = Assert.Throws<KotlinException>(
            () => CauseExceptions.groomCat("Oreo"));
        Assert.Equal("Oreo's grooming failed", ex.Message);
    }

    [Fact]
    public void Oreo_GroomingFailed_DeepChain_MidCause_IsKotlinException()
    {
        var ex = Assert.Throws<KotlinException>(
            () => CauseExceptions.groomCat("Oreo"));
        Assert.IsType<KotlinException>(ex.InnerException);
    }

    [Fact]
    public void Oreo_GroomingFailed_DeepChain_MidCause_KotlinType_ContainsIllegalStateException()
    {
        var ex = Assert.Throws<KotlinException>(
            () => CauseExceptions.groomCat("Oreo"));
        var mid = (KotlinException)ex.InnerException!;
        Assert.Contains("IllegalStateException", mid.KotlinType);
    }

    [Fact]
    public void Oreo_GroomingFailed_DeepChain_RootCause_IsKotlinException()
    {
        var ex = Assert.Throws<KotlinException>(
            () => CauseExceptions.groomCat("Oreo"));
        var mid = (KotlinException)ex.InnerException!;
        Assert.IsType<KotlinException>(mid.InnerException);
    }

    [Fact]
    public void Oreo_GroomingFailed_DeepChain_RootCause_Message_IsCorrect()
    {
        var ex = Assert.Throws<KotlinException>(
            () => CauseExceptions.groomCat("Oreo"));
        var mid = (KotlinException)ex.InnerException!;
        var root = (KotlinException)mid.InnerException!;
        Assert.Equal("clippers jammed", root.Message);
    }

    [Fact]
    public void Oreo_GroomingFailed_DeepChain_RootCause_HasNoFurtherCause()
    {
        var ex = Assert.Throws<KotlinException>(
            () => CauseExceptions.groomCat("Oreo"));
        var mid = (KotlinException)ex.InnerException!;
        var root = (KotlinException)mid.InnerException!;
        Assert.Null(root.InnerException);
    }

    [Fact]
    public void Mylo_Grooming_Succeeds()
    {
        string result = CauseExceptions.groomCat("Mylo");
        Assert.Equal("Mylo is fluffy", result);
    }

    // --- Suspend function with single-level cause (async) ---

    [Fact]
    public async Task Oreo_VetReport_Async_ThrowsKotlinException()
    {
        await Assert.ThrowsAsync<KotlinException>(
            () => CauseExceptions.FetchVetReportAsync("Oreo"));
    }

    [Fact]
    public async Task Oreo_VetReport_Async_InnerException_IsNotNull()
    {
        var ex = await Assert.ThrowsAsync<KotlinException>(
            () => CauseExceptions.FetchVetReportAsync("Oreo"));
        Assert.NotNull(ex.InnerException);
    }

    [Fact]
    public async Task Oreo_VetReport_Async_InnerMessage_IsCorrect()
    {
        var ex = await Assert.ThrowsAsync<KotlinException>(
            () => CauseExceptions.FetchVetReportAsync("Oreo"));
        var inner = (KotlinException)ex.InnerException!;
        Assert.Equal("clinic offline", inner.Message);
    }

    [Fact]
    public async Task Mylo_VetReport_Async_Succeeds()
    {
        string result = await CauseExceptions.FetchVetReportAsync("Mylo");
        Assert.Equal("Mylo is healthy", result);
    }

    // --- Regression: existing no-cause exception still has null InnerException ---

    [Fact]
    public void Regression_NoCause_InnerException_IsNull()
    {
        // feedCatTreat throws IllegalArgumentException with no cause — InnerException must stay null
        var ex = Assert.Throws<KotlinException>(
            () => SyncExceptions.feedCatTreat("Oreo"));
        Assert.Null(ex.InnerException);
    }
}
