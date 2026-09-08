using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

public class LambdaTests
{
    /// <summary>
    /// Issue #114. A <c>() -> Unit</c> property rendered <c>KotlinFunc&lt;void&gt;</c>, which is
    /// CS1547 and does not compile at all, so this test failing to build is itself the report.
    /// It asserts the invocation had its effect rather than only that it returned, because a
    /// KotlinAction that silently did nothing would compile just as well.
    /// </summary>
    [Fact]
    public void Cat_OnNap_InvokeHasEffect()
    {
        using var cat = new Cat("Oreo", 9);
        using var onNap = cat.OnNap;
        Assert.Equal(0, cat.Naps);
        onNap.Invoke();
        Assert.Equal(1, cat.Naps);
        onNap.Invoke();
        Assert.Equal(2, cat.Naps);
    }

    /// <summary>
    /// The arity-N facet of issue #114: <c>(Int) -> Unit</c> rendered
    /// <c>KotlinFunc&lt;int, void&gt;</c>. The Unit return is dropped and the parameter kept, so
    /// this binds as <c>KotlinAction&lt;int&gt;</c>.
    /// </summary>
    [Fact]
    public void Cat_OnNapFor_InvokeWithArgument()
    {
        using var cat = new Cat("Oreo", 9);
        using var onNapFor = cat.OnNapFor;
        onNapFor.Invoke(3);
        Assert.Equal(3, cat.Naps);
    }

    [Fact]
    public void Cat_OnMeow_Invoke()
    {
        using var cat = new Cat("Oreo", 9);
        using var onMeow = cat.OnMeow;
        string result = onMeow.Invoke();
        Assert.Equal("Meow! My name is Oreo", result);
    }

    [Fact]
    public void Cat_OnPet_Invoke()
    {
        using var cat = new Cat("Oreo", 9);
        using var onPet = cat.OnPet;
        string result = onPet.Invoke("purrs");
        Assert.Equal("Oreo purrs contentedly", result);
    }

    [Fact]
    public void Cat_OnMeow_MultipleInvocations()
    {
        using var cat = new Cat("Oreo", 9);
        using var onMeow = cat.OnMeow;
        Assert.Equal("Meow! My name is Oreo", onMeow.Invoke());
        Assert.Equal("Meow! My name is Oreo", onMeow.Invoke());
    }

    [Fact]
    public void Cat_CountLives_ReturnsInt()
    {
        using var cat = new Cat("Oreo", 9);
        using var countLives = cat.CountLives;
        int result = countLives.Invoke();
        Assert.Equal(9, result);
    }

    [Fact]
    public void Cat_IsAlive_ReturnsBool()
    {
        using var cat = new Cat("Oreo", 9);
        using var isAlive = cat.IsAlive;
        bool result = isAlive.Invoke();
        Assert.True(result);
    }

    [Fact]
    public void Cat_FavoriteToy_ReturnsObject()
    {
        using var cat = new Cat("Oreo", 9);
        using var favoriteToy = cat.FavoriteToy;
        using var toy = favoriteToy.Invoke();
        Assert.Equal("Mouse", toy.Name);
        Assert.Equal("Gray", toy.Color);
    }

    [Fact]
    public void Greeter_ReturnsInvocableLambda()
    {
        using var greet = Mappings.Greeter("Hello");
        string result = greet.Invoke("World");
        Assert.Equal("Hello, World!", result);
    }

    [Fact]
    public void Greeter_DifferentGreetings()
    {
        using var hi = Mappings.Greeter("Hi");
        using var hey = Mappings.Greeter("Hey");
        Assert.Equal("Hi, Alice!", hi.Invoke("Alice"));
        Assert.Equal("Hey, Bob!", hey.Invoke("Bob"));
    }
}
