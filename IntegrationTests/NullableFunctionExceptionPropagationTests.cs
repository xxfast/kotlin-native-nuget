using TestLibrary;
using Xunit.Abstractions;

namespace IntegrationTests;

// Regression coverage: a nullable-returning top-level function (ADR-002's `_has_value` /
// `_value` two-call pattern) previously crashed the host process (SIGBUS) the instant it threw,
// because the generated DllImports never declared the synchronous error out-param the native
// side always writes (ADR-024). See ADR-002 and ADR-024.
public class NullableFunctionExceptionPropagationTests
{
    private readonly ITestOutputHelper _testOutputHelper;

    public NullableFunctionExceptionPropagationTests(ITestOutputHelper testOutputHelper)
    {
        _testOutputHelper = testOutputHelper;
    }

    // --- Int? ---

    [Fact]
    public void NullableIntOrThrow_NegativeInput_ThrowsArgumentException()
    {
        // Under ADR-029, IllegalArgumentException maps to KotlinArgumentException : ArgumentException
        Assert.ThrowsAny<ArgumentException>(
            () => Mappings.nullableIntOrThrow(-1));
    }

    [Fact]
    public void NullableIntOrThrow_NegativeInput_IsExactType_KotlinArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => Mappings.nullableIntOrThrow(-1));
        Assert.IsType<KotlinArgumentException>(ex);
    }

    [Fact]
    public void NullableIntOrThrow_NegativeInput_KotlinType_IsIllegalArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => Mappings.nullableIntOrThrow(-1));
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
    }

    [Fact]
    public void NullableIntOrThrow_NegativeInput_WithMessage()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => Mappings.nullableIntOrThrow(-1));
        Assert.Equal("input must not be negative", ex.Message);
    }

    [Fact]
    public void NullableIntOrThrow_NegativeInput_KotlinStackTrace_NonEmpty()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => Mappings.nullableIntOrThrow(-1));
        var ke = (IKotlinException)ex;
        _testOutputHelper.WriteLine(ke.KotlinStackTrace);
        Assert.NotNull(ke.KotlinStackTrace);
        Assert.NotEmpty(ke.KotlinStackTrace);
    }

    [Fact]
    public void NullableIntOrThrow_ZeroInput_ReturnsNull()
    {
        Assert.Null(Mappings.nullableIntOrThrow(0));
    }

    [Fact]
    public void NullableIntOrThrow_PositiveInput_ReturnsValue()
    {
        Assert.Equal(5, Mappings.nullableIntOrThrow(5));
    }

    // --- String? ---

    [Fact]
    public void NullableStringOrThrow_NegativeInput_ThrowsArgumentException()
    {
        Assert.ThrowsAny<ArgumentException>(
            () => Mappings.nullableStringOrThrow(-1));
    }

    [Fact]
    public void NullableStringOrThrow_NegativeInput_IsExactType_KotlinArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => Mappings.nullableStringOrThrow(-1));
        Assert.IsType<KotlinArgumentException>(ex);
    }

    [Fact]
    public void NullableStringOrThrow_NegativeInput_KotlinType_IsIllegalArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => Mappings.nullableStringOrThrow(-1));
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
    }

    [Fact]
    public void NullableStringOrThrow_NegativeInput_WithMessage()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => Mappings.nullableStringOrThrow(-1));
        Assert.Equal("input must not be negative", ex.Message);
    }

    [Fact]
    public void NullableStringOrThrow_NegativeInput_KotlinStackTrace_NonEmpty()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => Mappings.nullableStringOrThrow(-1));
        var ke = (IKotlinException)ex;
        _testOutputHelper.WriteLine(ke.KotlinStackTrace);
        Assert.NotNull(ke.KotlinStackTrace);
        Assert.NotEmpty(ke.KotlinStackTrace);
    }

    [Fact]
    public void NullableStringOrThrow_ZeroInput_ReturnsNull()
    {
        Assert.Null(Mappings.nullableStringOrThrow(0));
    }

    [Fact]
    public void NullableStringOrThrow_PositiveInput_ReturnsValue()
    {
        Assert.Equal("value-5", Mappings.nullableStringOrThrow(5));
    }
}
