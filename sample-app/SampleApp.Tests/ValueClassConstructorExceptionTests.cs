using SampleLibrary;
using SampleLibrary.Cat;
using Xunit.Abstractions;

namespace SampleApp.Tests;

// Exercises ADR-033 + ADR-035: constructor exception propagation for value
// classes. Both the primary constructor `(id)` and the secondary `(name, number)`
// route through Kotlin, so the `init` block runs and an over-long id throws. The
// native call + error check live in a private CreateChecked* helper invoked from
// the constructor body, keeping the idiomatic `new CatId(...)` surface.
public class ValueClassConstructorExceptionTests
{
    private readonly ITestOutputHelper _testOutputHelper;

    public ValueClassConstructorExceptionTests(ITestOutputHelper testOutputHelper)
    {
        _testOutputHelper = testOutputHelper;
    }

    [Fact]
    public void CatId_PrimaryConstructor_TooLong_ThrowsArgumentException()
    {
        // 21 characters — exceeds the init block's `id.length <= 20` requirement.
        Assert.ThrowsAny<ArgumentException>(
            () => new CatId("supercalifragilisticx"));
    }

    [Fact]
    public void CatId_PrimaryConstructor_TooLong_IsExactType_KotlinArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => new CatId("supercalifragilisticx"));
        Assert.IsType<KotlinArgumentException>(ex);
    }

    [Fact]
    public void CatId_PrimaryConstructor_TooLong_Message()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => new CatId("supercalifragilisticx"));
        Assert.Equal("Cat ID too long: supercalifragilisticx", ex.Message);
    }

    [Fact]
    public void CatId_PrimaryConstructor_ValidLength_Succeeds()
    {
        var id = new CatId("oreo");
        Assert.Equal("oreo", id.Id);
    }

    [Fact]
    public void CatId_SecondaryConstructor_TooLong_ThrowsArgumentException()
    {
        // "supercalifragilistic" is 20 chars; "-999999" pushes it past the limit.
        Assert.ThrowsAny<ArgumentException>(
            () => new CatId("supercalifragilistic", 999999));
    }

    [Fact]
    public void CatId_SecondaryConstructor_TooLong_IsExactType_KotlinArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => new CatId("supercalifragilistic", 999999));
        Assert.IsType<KotlinArgumentException>(ex);
    }

    [Fact]
    public void CatId_SecondaryConstructor_TooLong_KotlinType_IsIllegalArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => new CatId("supercalifragilistic", 999999));
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
    }

    [Fact]
    public void CatId_SecondaryConstructor_TooLong_Message()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => new CatId("supercalifragilistic", 999999));
        Assert.Equal("Cat ID too long: supercalifragilistic-999999", ex.Message);
    }

    [Fact]
    public void CatId_SecondaryConstructor_TooLong_KotlinStackTrace_NonEmpty()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => new CatId("supercalifragilistic", 999999));
        var ke = (IKotlinException)ex;
        _testOutputHelper.WriteLine(ke.KotlinStackTrace);
        Assert.NotEmpty(ke.KotlinStackTrace);
    }

    [Fact]
    public void CatId_SecondaryConstructor_ValidLength_Succeeds()
    {
        var id = new CatId("oreo", 7);
        Assert.Equal("oreo-7", id.Id);
    }
}
