using TestLibrary;
using TestLibrary.Cat;
using Xunit.Abstractions;

namespace IntegrationTests;

// Exercises ADR-032: constructor exception propagation for generic classes.
// Unconstrained (Box<T>) routes the native call through NugetMarshal.CreateBox<T>;
// constrained (PetBox<T : Pet>) routes through PetBoxNative.Create_object.
public class GenericConstructorExceptionTests
{
    private readonly ITestOutputHelper _testOutputHelper;

    public GenericConstructorExceptionTests(ITestOutputHelper testOutputHelper)
    {
        _testOutputHelper = testOutputHelper;
    }

    // --- Unconstrained Box<T>: typed create_* variant error path ---

    [Fact]
    public void Box_String_BlankValue_ConstructorThrowsArgumentException()
    {
        // init { require(value.toString().isNotEmpty()) } → IllegalArgumentException
        Assert.ThrowsAny<ArgumentException>(() => new Box<string>(""));
    }

    [Fact]
    public void Box_String_BlankValue_ConstructorIsExactType_KotlinArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(() => new Box<string>(""));
        Assert.IsType<KotlinArgumentException>(ex);
    }

    [Fact]
    public void Box_String_BlankValue_ConstructorKotlinType_IsIllegalArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(() => new Box<string>(""));
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
    }

    [Fact]
    public void Box_String_BlankValue_ConstructorMessage()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(() => new Box<string>(""));
        Assert.Equal("Box cannot hold a blank value", ex.Message);
    }

    [Fact]
    public void Box_String_BlankValue_ConstructorKotlinStackTrace_NonEmpty()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(() => new Box<string>(""));
        var ke = (IKotlinException)ex;
        _testOutputHelper.WriteLine(ke.KotlinStackTrace);
        Assert.NotEmpty(ke.KotlinStackTrace);
    }

    [Fact]
    public void Box_String_ValidValue_ConstructorSucceeds()
    {
        using var box = new Box<string>("Oreo");
        Assert.Equal("Oreo", box.Value);
    }

    [Fact]
    public void Box_Int_ValidValue_ConstructorSucceeds()
    {
        // Non-string primitive variant still succeeds; init never trips on a number.
        using var box = new Box<int>(42);
        Assert.Equal(42, box.Value);
    }

    // --- Constrained PetBox<T : Pet>: create_object error path ---

    [Fact]
    public void PetBox_BlankNamedPet_ConstructorThrowsArgumentException()
    {
        using var cat = new Cat("", 9);
        Assert.ThrowsAny<ArgumentException>(() => new PetBox<Cat>(cat));
    }

    [Fact]
    public void PetBox_BlankNamedPet_ConstructorIsExactType_KotlinArgumentException()
    {
        using var cat = new Cat("", 9);
        var ex = Assert.ThrowsAny<ArgumentException>(() => new PetBox<Cat>(cat));
        Assert.IsType<KotlinArgumentException>(ex);
    }

    [Fact]
    public void PetBox_BlankNamedPet_ConstructorMessage()
    {
        using var cat = new Cat("", 9);
        var ex = Assert.ThrowsAny<ArgumentException>(() => new PetBox<Cat>(cat));
        Assert.Equal("PetBox needs a named pet", ex.Message);
    }

    [Fact]
    public void PetBox_NamedPet_ConstructorSucceeds()
    {
        using var oreo = new Cat("Oreo", 9);
        using var box = new PetBox<Cat>(oreo);
        using Cat cat = box.Value;
        Assert.Equal("Oreo", cat.Name);
    }
}
