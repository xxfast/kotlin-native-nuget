using SampleLibrary;
using SampleLibrary.Cat;
using Xunit.Abstractions;

namespace SampleApp.Tests;

// Exercises ADR-034: secondary constructors are now exported as overloads
// (cat_create / cat_create_2) and propagate exceptions like the primary.
// CatLitter's secondary `(brand, bags, perBag)` delegates to the primary, which
// re-runs the init validation.
public class SecondaryConstructorExceptionTests
{
    private readonly ITestOutputHelper _testOutputHelper;

    public SecondaryConstructorExceptionTests(ITestOutputHelper testOutputHelper)
    {
        _testOutputHelper = testOutputHelper;
    }

    // --- Primary constructor still works ---

    [Fact]
    public void CatLitter_PrimaryConstructor_Succeeds()
    {
        using var litter = new CatLitter("Tidy", 5);
        Assert.Equal("Tidy", litter.Brand);
        Assert.Equal(5, litter.WeightKg);
    }

    [Fact]
    public void CatLitter_PrimaryConstructor_NonPositiveWeight_Throws()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(() => new CatLitter("Tidy", 0));
        Assert.IsType<KotlinArgumentException>(ex);
    }

    // --- Secondary constructor: (brand, bags, perBag) ---

    [Fact]
    public void CatLitter_SecondaryConstructor_Succeeds()
    {
        using var litter = new CatLitter("Tidy", 2, 5);
        Assert.Equal("Tidy", litter.Brand);
        Assert.Equal(10, litter.WeightKg);
    }

    [Fact]
    public void CatLitter_SecondaryConstructor_ZeroBags_ThrowsArgumentException()
    {
        Assert.ThrowsAny<ArgumentException>(() => new CatLitter("Tidy", 0, 5));
    }

    [Fact]
    public void CatLitter_SecondaryConstructor_ZeroBags_IsExactType_KotlinArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(() => new CatLitter("Tidy", 0, 5));
        Assert.IsType<KotlinArgumentException>(ex);
    }

    [Fact]
    public void CatLitter_SecondaryConstructor_ZeroBags_KotlinType_IsIllegalArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(() => new CatLitter("Tidy", 0, 5));
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
    }

    [Fact]
    public void CatLitter_SecondaryConstructor_ZeroBags_Message()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(() => new CatLitter("Tidy", 0, 5));
        Assert.Equal("Litter weight must be positive", ex.Message);
    }

    [Fact]
    public void CatLitter_SecondaryConstructor_ZeroBags_KotlinStackTrace_NonEmpty()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(() => new CatLitter("Tidy", 0, 5));
        var ke = (IKotlinException)ex;
        _testOutputHelper.WriteLine(ke.KotlinStackTrace);
        Assert.NotEmpty(ke.KotlinStackTrace);
    }
}
