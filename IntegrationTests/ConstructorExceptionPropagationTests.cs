using TestLibrary;
using TestLibrary.Cat;
using Xunit.Abstractions;

namespace IntegrationTests;

public class ConstructorExceptionPropagationTests
{
    private readonly ITestOutputHelper _testOutputHelper;

    public ConstructorExceptionPropagationTests(ITestOutputHelper testOutputHelper)
    {
        _testOutputHelper = testOutputHelper;
    }

    // --- Kitten constructor: IllegalArgumentException from require(age >= 0) ---

    [Fact]
    public void Kitten_NegativeAge_ConstructorThrowsArgumentException()
    {
        // Under ADR-029, IllegalArgumentException maps to KotlinArgumentException : ArgumentException
        Assert.ThrowsAny<ArgumentException>(
            () => new Kitten("Oreo", -1));
    }

    [Fact]
    public void Kitten_NegativeAge_ConstructorIsExactType_KotlinArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => new Kitten("Oreo", -1));
        Assert.IsType<KotlinArgumentException>(ex);
    }

    [Fact]
    public void Kitten_NegativeAge_ConstructorKotlinType_IsIllegalArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => new Kitten("Oreo", -1));
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
    }

    [Fact]
    public void Kitten_NegativeAge_ConstructorMessage()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => new Kitten("Oreo", -1));
        Assert.Equal("Kitten age cannot be negative", ex.Message);
    }

    [Fact]
    public void Kitten_NegativeAge_ConstructorKotlinStackTrace_NonEmpty()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => new Kitten("Oreo", -1));
        var ke = (IKotlinException)ex;
        _testOutputHelper.WriteLine(ke.KotlinStackTrace);
        Assert.NotNull(ke.KotlinStackTrace);
        Assert.NotEmpty(ke.KotlinStackTrace);
    }

    [Fact]
    public void Kitten_NegativeAge_ConstructorKotlinStackTrace_ContainsExceptionType()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => new Kitten("Oreo", -1));
        var ke = (IKotlinException)ex;
        Assert.Contains("IllegalArgumentException", ke.KotlinStackTrace);
    }

    [Fact]
    public void Kitten_ValidAge_ConstructorSucceeds()
    {
        using var kitten = new Kitten("Oreo", 2);
        Assert.Equal("Oreo", kitten.Name);
        Assert.Equal(2, kitten.Age);
    }

    [Fact]
    public void Kitten_ZeroAge_ConstructorSucceeds()
    {
        using var kitten = new Kitten("Mylo", 0);
        Assert.Equal("Mylo", kitten.Name);
        Assert.Equal(0, kitten.Age);
    }

    // --- CatRescue constructor: IllegalStateException from check(capacity > 0) ---

    [Fact]
    public void CatRescue_ZeroCapacity_ConstructorThrowsInvalidOperationException()
    {
        // IllegalStateException maps to KotlinInvalidOperationException : InvalidOperationException
        Assert.ThrowsAny<InvalidOperationException>(
            () => new CatRescue("Purr-fect Shelter", 0));
    }

    [Fact]
    public void CatRescue_ZeroCapacity_ConstructorIsExactType_KotlinInvalidOperationException()
    {
        var ex = Assert.ThrowsAny<InvalidOperationException>(
            () => new CatRescue("Purr-fect Shelter", 0));
        Assert.IsType<KotlinInvalidOperationException>(ex);
    }

    [Fact]
    public void CatRescue_ZeroCapacity_ConstructorKotlinType_IsIllegalStateException()
    {
        var ex = Assert.ThrowsAny<InvalidOperationException>(
            () => new CatRescue("Purr-fect Shelter", 0));
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.IllegalStateException", ke.KotlinType);
    }

    [Fact]
    public void CatRescue_ZeroCapacity_ConstructorMessage()
    {
        var ex = Assert.ThrowsAny<InvalidOperationException>(
            () => new CatRescue("Purr-fect Shelter", 0));
        Assert.Equal("Rescue shelter must have positive capacity", ex.Message);
    }

    [Fact]
    public void CatRescue_ValidCapacity_ConstructorSucceeds()
    {
        using var rescue = new CatRescue("Purr-fect Shelter", 10);
        Assert.Equal("Purr-fect Shelter", rescue.Name);
        Assert.Equal(10, rescue.Capacity);
    }

    // --- CatWeightRecord constructor: custom OverfedCatException → base KotlinException ---

    [Fact]
    public void CatWeightRecord_Overweight_ConstructorThrowsBaseKotlinException()
    {
        // OverfedCatException is user-defined — not a stdlib type — so it falls back to base KotlinException
        var ex = Assert.ThrowsAny<KotlinException>(
            () => new CatWeightRecord("Oreo", 25.0));
        Assert.IsType<KotlinException>(ex);
    }

    [Fact]
    public void CatWeightRecord_Overweight_ConstructorKotlinType_IsCustomException()
    {
        var ex = Assert.ThrowsAny<KotlinException>(
            () => new CatWeightRecord("Oreo", 25.0));
        Assert.Equal(
            "io.github.xxfast.kotlin.native.nuget.test.cat.OverfedCatException",
            ex.KotlinType);
    }

    [Fact]
    public void CatWeightRecord_Overweight_ConstructorMessage()
    {
        var ex = Assert.ThrowsAny<KotlinException>(
            () => new CatWeightRecord("Oreo", 25.0));
        Assert.Equal("Oreo is dangerously overweight at 25.0kg", ex.Message);
    }

    [Fact]
    public void CatWeightRecord_HealthyWeight_ConstructorSucceeds()
    {
        using var record = new CatWeightRecord("Mylo", 4.5);
        Assert.Equal("Mylo", record.Name);
        Assert.Equal(4.5, record.WeightKg);
    }

    // --- CatProfile (data class): constructor + copy() with IllegalArgumentException ---

    [Fact]
    public void CatProfile_NegativeTreatBudget_ConstructorThrowsArgumentException()
    {
        Assert.ThrowsAny<ArgumentException>(
            () => new CatProfile("Oreo", -1));
    }

    [Fact]
    public void CatProfile_NegativeTreatBudget_ConstructorIsExactType_KotlinArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => new CatProfile("Oreo", -1));
        Assert.IsType<KotlinArgumentException>(ex);
    }

    [Fact]
    public void CatProfile_NegativeTreatBudget_ConstructorKotlinType_IsIllegalArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => new CatProfile("Oreo", -1));
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
    }

    [Fact]
    public void CatProfile_ValidBudget_ConstructorSucceeds()
    {
        using var profile = new CatProfile("Oreo", 100);
        Assert.Equal("Oreo", profile.Name);
        Assert.Equal(100, profile.TreatBudget);
    }

    [Fact]
    public void CatProfile_ZeroBudget_ConstructorSucceeds()
    {
        using var profile = new CatProfile("Mylo", 0);
        Assert.Equal("Mylo", profile.Name);
        Assert.Equal(0, profile.TreatBudget);
    }

    // --- CatProfile.Copy: re-runs init, so negative treatBudget throws ---

    [Fact]
    public void CatProfile_Copy_NegativeTreatBudget_ThrowsArgumentException()
    {
        using var profile = new CatProfile("Oreo", 100);
        Assert.ThrowsAny<ArgumentException>(
            () => profile.Copy("Oreo", -1));
    }

    [Fact]
    public void CatProfile_Copy_NegativeTreatBudget_IsExactType_KotlinArgumentException()
    {
        using var profile = new CatProfile("Oreo", 100);
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => profile.Copy("Oreo", -1));
        Assert.IsType<KotlinArgumentException>(ex);
    }

    [Fact]
    public void CatProfile_Copy_NegativeTreatBudget_KotlinType_IsIllegalArgumentException()
    {
        using var profile = new CatProfile("Oreo", 100);
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => profile.Copy("Oreo", -1));
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
    }

    [Fact]
    public void CatProfile_Copy_ValidBudget_Succeeds()
    {
        using var profile = new CatProfile("Oreo", 100);
        using var updated = profile.Copy("Oreo", 200);
        Assert.Equal("Oreo", updated.Name);
        Assert.Equal(200, updated.TreatBudget);
    }

    [Fact]
    public void CatProfile_Copy_DifferentName_Succeeds()
    {
        using var profile = new CatProfile("Oreo", 50);
        using var myloProfile = profile.Copy("Mylo", 50);
        Assert.Equal("Mylo", myloProfile.Name);
        Assert.Equal(50, myloProfile.TreatBudget);
        Assert.Equal("Oreo", profile.Name);
    }
}
