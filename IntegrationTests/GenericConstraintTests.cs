using System.Reflection;
using TestLibrary.Cat;

namespace IntegrationTests;

public class GenericConstraintTests
{
    [Fact]
    public void PetBox_Oreo_ConstructorAndGetter()
    {
        using var oreo = new Cat("Oreo", 9);
        using var box = new PetBox<Cat>(oreo);
        using Cat cat = box.Value;
        Assert.Equal("Oreo", cat.Name);
    }

    [Fact]
    public void PetBox_Mylo_ConstructorAndGetter()
    {
        using var mylo = new Cat("Mylo", 4);
        using var box = new PetBox<Cat>(mylo);
        using Cat cat = box.Value;
        Assert.Equal("Mylo", cat.Name);
    }

    [Fact]
    public void PetBox_TypeParameter_HasIPetConstraint()
    {
        Type[] constraints = typeof(PetBox<>).GetGenericArguments()[0].GetGenericParameterConstraints();
        Assert.Contains(typeof(IPet), constraints);
    }

    [Fact]
    public void AdoptPet_Oreo_ReturnsSameCat()
    {
        using var oreo = new Cat("Oreo", 9);
        using Cat adopted = Helpers.adoptPet<Cat>(oreo);
        Assert.Equal("Oreo", adopted.Name);
    }

    [Fact]
    public void AdoptPet_Mylo_ReturnsSameCat()
    {
        using var mylo = new Cat("Mylo", 4);
        using Cat adopted = Helpers.adoptPet<Cat>(mylo);
        Assert.Equal("Mylo", adopted.Name);
    }

    [Fact]
    public void AdoptPet_TypeParameter_HasIPetConstraint()
    {
        MethodInfo method = typeof(Helpers)
            .GetMethod("adoptPet")!
            .MakeGenericMethod(typeof(Cat))
            .GetGenericMethodDefinition();

        Type[] constraints = method.GetGenericArguments()[0].GetGenericParameterConstraints();
        Assert.Contains(typeof(IPet), constraints);
    }

    [Fact]
    public void GroomPet_ReturnsGroomedCat()
    {
        using var oreo = new Cat("Oreo", 9);
        using Cat groomed = Helpers.groomPet<Cat>(oreo);
        Assert.Equal("Oreo", groomed.Name);
    }
}
