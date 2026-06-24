using SampleLibrary;
using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class ExceptionCauseTests
{
    // --- Single-level cause (sync) ---
    //
    // feedCatWithAllergy("Oreo") throws:
    //   IllegalArgumentException("Oreo had a reaction",
    //     cause: RuntimeException("Oreo is allergic to this treat"))
    //
    // Under ADR-029:
    //   outer  → KotlinArgumentException : ArgumentException  (IllegalArgumentException is mapped)
    //   inner  → KotlinException                              (RuntimeException is NOT mapped — fallback)

    [Fact]
    public void Oreo_AllergyReaction_InnerException_IsNotNull()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => CauseExceptions.feedCatWithAllergy("Oreo"));
        Assert.NotNull(ex.InnerException);
    }

    [Fact]
    public void Oreo_AllergyReaction_OuterType_IsKotlinArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => CauseExceptions.feedCatWithAllergy("Oreo"));
        Assert.IsType<KotlinArgumentException>(ex);
    }

    [Fact]
    public void Oreo_AllergyReaction_InnerException_IsBaseKotlinException()
    {
        // RuntimeException is unmapped — falls back to base KotlinException
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => CauseExceptions.feedCatWithAllergy("Oreo"));
        Assert.IsType<KotlinException>(ex.InnerException);
    }

    [Fact]
    public void Oreo_AllergyReaction_OuterMessage_IsCorrect()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => CauseExceptions.feedCatWithAllergy("Oreo"));
        Assert.Equal("Oreo had a reaction", ex.Message);
    }

    [Fact]
    public void Oreo_AllergyReaction_InnerMessage_IsCorrect()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => CauseExceptions.feedCatWithAllergy("Oreo"));
        var inner = (KotlinException)ex.InnerException!;
        Assert.Equal("Oreo is allergic to this treat", inner.Message);
    }

    [Fact]
    public void Oreo_AllergyReaction_InnerKotlinType_ContainsRuntimeException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => CauseExceptions.feedCatWithAllergy("Oreo"));
        var inner = (KotlinException)ex.InnerException!;
        Assert.Contains("RuntimeException", inner.KotlinType);
    }

    [Fact]
    public void Oreo_AllergyReaction_InnerException_HasNoFurtherCause()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => CauseExceptions.feedCatWithAllergy("Oreo"));
        Assert.Null(ex.InnerException!.InnerException);
    }

    [Fact]
    public void Oreo_AllergyReaction_InnerException_HasNonEmptyKotlinStackTrace()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => CauseExceptions.feedCatWithAllergy("Oreo"));
        var inner = (KotlinException)ex.InnerException!;
        Assert.NotNull(inner.KotlinStackTrace);
        Assert.NotEmpty(inner.KotlinStackTrace);
    }

    [Fact]
    public void Oreo_AllergyReaction_ToString_ContainsInnerExceptionMarker()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
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
    //
    // groomCat("Oreo") throws:
    //   IllegalArgumentException("Oreo's grooming failed",
    //     cause: IllegalStateException("grooming aborted",
    //       cause: RuntimeException("clippers jammed")))
    //
    // Under ADR-029:
    //   outer  → KotlinArgumentException : ArgumentException         (IllegalArgumentException is mapped)
    //   mid    → KotlinInvalidOperationException : InvalidOperationException  (IllegalStateException is mapped)
    //   root   → KotlinException                                      (RuntimeException is NOT mapped)

    [Fact]
    public void Oreo_GroomingFailed_DeepChain_OuterMessage_IsCorrect()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => CauseExceptions.groomCat("Oreo"));
        Assert.Equal("Oreo's grooming failed", ex.Message);
    }

    [Fact]
    public void Oreo_GroomingFailed_DeepChain_OuterType_IsKotlinArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => CauseExceptions.groomCat("Oreo"));
        Assert.IsType<KotlinArgumentException>(ex);
    }

    [Fact]
    public void Oreo_GroomingFailed_DeepChain_MidCause_IsKotlinInvalidOperationException()
    {
        // IllegalStateException is mapped to KotlinInvalidOperationException : InvalidOperationException
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => CauseExceptions.groomCat("Oreo"));
        Assert.IsType<KotlinInvalidOperationException>(ex.InnerException);
    }

    [Fact]
    public void Oreo_GroomingFailed_DeepChain_MidCause_IsCatchableAs_InvalidOperationException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => CauseExceptions.groomCat("Oreo"));
        Assert.IsAssignableFrom<InvalidOperationException>(ex.InnerException);
    }

    [Fact]
    public void Oreo_GroomingFailed_DeepChain_MidCause_KotlinType_ContainsIllegalStateException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => CauseExceptions.groomCat("Oreo"));
        var mid = (IKotlinException)ex.InnerException!;
        Assert.Contains("IllegalStateException", mid.KotlinType);
    }

    [Fact]
    public void Oreo_GroomingFailed_DeepChain_RootCause_IsBaseKotlinException()
    {
        // RuntimeException is NOT mapped — stays base KotlinException
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => CauseExceptions.groomCat("Oreo"));
        var mid = (InvalidOperationException)ex.InnerException!;
        Assert.IsType<KotlinException>(mid.InnerException);
    }

    [Fact]
    public void Oreo_GroomingFailed_DeepChain_RootCause_Message_IsCorrect()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => CauseExceptions.groomCat("Oreo"));
        var mid = (InvalidOperationException)ex.InnerException!;
        var root = (KotlinException)mid.InnerException!;
        Assert.Equal("clippers jammed", root.Message);
    }

    [Fact]
    public void Oreo_GroomingFailed_DeepChain_RootCause_HasNoFurtherCause()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => CauseExceptions.groomCat("Oreo"));
        var mid = (InvalidOperationException)ex.InnerException!;
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
    //
    // fetchVetReport("Oreo") throws:
    //   IllegalArgumentException("vet report failed",
    //     cause: IllegalStateException("clinic offline"))
    //
    // Under ADR-029:
    //   outer  → KotlinArgumentException : ArgumentException
    //   inner  → KotlinInvalidOperationException : InvalidOperationException

    [Fact]
    public async Task Oreo_VetReport_Async_ThrowsArgumentException()
    {
        await Assert.ThrowsAnyAsync<ArgumentException>(
            () => CauseExceptions.FetchVetReportAsync("Oreo"));
    }

    [Fact]
    public async Task Oreo_VetReport_Async_IsExactType_KotlinArgumentException()
    {
        var ex = await Assert.ThrowsAnyAsync<ArgumentException>(
            () => CauseExceptions.FetchVetReportAsync("Oreo"));
        Assert.IsType<KotlinArgumentException>(ex);
    }

    [Fact]
    public async Task Oreo_VetReport_Async_InnerException_IsNotNull()
    {
        var ex = await Assert.ThrowsAnyAsync<ArgumentException>(
            () => CauseExceptions.FetchVetReportAsync("Oreo"));
        Assert.NotNull(ex.InnerException);
    }

    [Fact]
    public async Task Oreo_VetReport_Async_InnerException_IsKotlinInvalidOperationException()
    {
        // IllegalStateException cause is mapped to KotlinInvalidOperationException
        var ex = await Assert.ThrowsAnyAsync<ArgumentException>(
            () => CauseExceptions.FetchVetReportAsync("Oreo"));
        Assert.IsType<KotlinInvalidOperationException>(ex.InnerException);
    }

    [Fact]
    public async Task Oreo_VetReport_Async_InnerMessage_IsCorrect()
    {
        var ex = await Assert.ThrowsAnyAsync<ArgumentException>(
            () => CauseExceptions.FetchVetReportAsync("Oreo"));
        Assert.Equal("clinic offline", ex.InnerException!.Message);
    }

    [Fact]
    public async Task Oreo_VetReport_Async_InnerKotlinType_IsIllegalStateException()
    {
        var ex = await Assert.ThrowsAnyAsync<ArgumentException>(
            () => CauseExceptions.FetchVetReportAsync("Oreo"));
        var inner = (IKotlinException)ex.InnerException!;
        Assert.Equal("kotlin.IllegalStateException", inner.KotlinType);
    }

    [Fact]
    public async Task Mylo_VetReport_Async_Succeeds()
    {
        string result = await CauseExceptions.FetchVetReportAsync("Mylo");
        Assert.Equal("Mylo is healthy", result);
    }

    // --- Regression: no-cause exception still has null InnerException ---

    [Fact]
    public void Regression_NoCause_InnerException_IsNull()
    {
        // feedCatTreat throws IllegalArgumentException with no cause
        // Under ADR-029 it becomes KotlinArgumentException, but InnerException must still be null
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => SyncExceptions.feedCatTreat("Oreo"));
        Assert.IsType<KotlinArgumentException>(ex);
        Assert.Null(ex.InnerException);
    }
}
