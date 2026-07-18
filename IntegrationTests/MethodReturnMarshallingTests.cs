using TestLibrary.Cat;

namespace IntegrationTests;

// ADR-061: the full method-return matrix (object, nullable object, collection, nullable string,
// nullable primitive) at the two positions the property getter's marshalling cascade never
// covered — a class instance method and an extension function. Before the fix, both positions
// declare a return type the method body cannot satisfy (an unmarshalled `List<T>`, a non-null
// object/String/primitive for a nullable body) and fail to compile as generated Kotlin, so this
// whole file is expected RED until the processor grows the new export/marshalling branches.
//
// Oreo and Mylo do the heavy lifting again: Oreo starts every case with no owner, no alias, and
// no age (so every nullable case has a genuine null branch to assert), then gets assigned one so
// the non-null branch is assertable in the same breath.
public class MethodReturnMarshallingTests
{
    // ---- Class-method position (Cat) ----

    [Fact]
    public void Cat_FindOwner_DefaultsToSelf()
    {
        using var oreo = new Cat("Oreo", 9);
        using Cat owner = oreo.FindOwner();
        Assert.Equal("Oreo", owner.Name);
    }

    [Fact]
    public void Cat_FindOwner_ReturnsBrotherWhenSet()
    {
        using var oreo = new Cat("Oreo", 9);
        using var mylo = new Cat("Mylo", 8);
        oreo.Brother = mylo;

        using Cat owner = oreo.FindOwner();
        Assert.Equal("Mylo", owner.Name);
    }

    [Fact]
    public void Cat_MaybeOwner_NullWhenNoBrother()
    {
        using var oreo = new Cat("Oreo", 9);
        Cat? owner = oreo.MaybeOwner();
        Assert.Null(owner);
    }

    [Fact]
    public void Cat_MaybeOwner_NonNullWhenBrotherSet()
    {
        using var oreo = new Cat("Oreo", 9);
        using var mylo = new Cat("Mylo", 8);
        oreo.Brother = mylo;

        using Cat? owner = oreo.MaybeOwner();
        Assert.NotNull(owner);
        Assert.Equal("Mylo", owner!.Name);
    }

    [Fact]
    public void Cat_Tags_ReturnsMarshalledStringElements()
    {
        using var oreo = new Cat("Oreo", 9);
        IReadOnlyList<string> tags = oreo.Tags();
        Assert.Equal(new List<string> { "Oreo-tag", "Oreo-chip" }, tags);
    }

    [Fact]
    public void Cat_Scores_ReturnsBlittableIntElements()
    {
        using var oreo = new Cat("Oreo", 9);
        IReadOnlyList<int> scores = oreo.Scores();
        Assert.Equal(new List<int> { 9, 18 }, scores);
    }

    [Fact]
    public void Cat_Alias_NullWhenNoOwner()
    {
        using var oreo = new Cat("Oreo", 9);
        Assert.Null(oreo.Alias());
    }

    [Fact]
    public void Cat_Alias_NonNullWhenOwnerSet()
    {
        using var oreo = new Cat("Oreo", 9);
        oreo.Owner = "Isuru";
        Assert.Equal("Oreo (owned by Isuru)", oreo.Alias());
    }

    [Fact]
    public void Cat_AgeInMonths_NullWhenAgeUnset()
    {
        using var oreo = new Cat("Oreo", 9);
        Assert.Null(oreo.AgeInMonths());
    }

    [Fact]
    public void Cat_AgeInMonths_NonNullWhenAgeSet()
    {
        using var oreo = new Cat("Oreo", 9);
        oreo.Age = 3;
        Assert.Equal(36, oreo.AgeInMonths());
    }

    [Fact]
    public void Cat_TakeAgeInMonths_InvokesMethodExactlyOnce()
    {
        using var oreo = new Cat("Oreo", 9);
        oreo.Age = 3;

        Assert.Equal(36, oreo.TakeAgeInMonths());
        Assert.Null(oreo.Age);
    }

    [Fact]
    public void Cat_TakeAgeInMonths_NullUsesTheValueOutAbsentBranch()
    {
        using var oreo = new Cat("Oreo", 9);

        Assert.Null(oreo.TakeAgeInMonths());
    }

    [Fact]
    public void Cat_TakeExtensionAgeInMonths_InvokesExtensionExactlyOnce()
    {
        using var oreo = new Cat("Oreo", 9);
        oreo.Age = 3;

        Assert.Equal(36, oreo.TakeExtensionAgeInMonths());
        Assert.Null(oreo.Age);
    }

    [Fact]
    public void Cat_TakeExtensionAgeInMonths_NullUsesTheValueOutAbsentBranch()
    {
        using var oreo = new Cat("Oreo", 9);

        Assert.Null(oreo.TakeExtensionAgeInMonths());
    }

    // ---- Extension-function position (Toy) ----
    // A toy's "owner" is the cat it belongs to. Every null/non-null branch is driven by the
    // toy's own (immutable) fields, since Toy has nothing to mutate.

    [Fact]
    public void Toy_FindOwner_ReturnsMarshalledObject()
    {
        var mouse = new Toy("Mouse", "Gray");
        using Cat owner = mouse.FindOwner();
        Assert.Equal("Mouse", owner.Name);
    }

    [Fact]
    public void Toy_MaybeOwner_NullForNonGrayToy()
    {
        var ball = new Toy("Ball", "Red");
        Cat? owner = ball.MaybeOwner();
        Assert.Null(owner);
    }

    [Fact]
    public void Toy_MaybeOwner_NonNullForGrayToy()
    {
        var mouse = new Toy("Mouse", "Gray");
        using Cat? owner = mouse.MaybeOwner();
        Assert.NotNull(owner);
        Assert.Equal("Mouse", owner!.Name);
    }

    [Fact]
    public void Toy_Tags_ReturnsMarshalledStringElements()
    {
        var mouse = new Toy("Mouse", "Gray");
        IReadOnlyList<string> tags = mouse.Tags();
        Assert.Equal(new List<string> { "Mouse-tag", "Gray-tag" }, tags);
    }

    [Fact]
    public void Toy_Scores_ReturnsBlittableIntElements()
    {
        var mouse = new Toy("Mouse", "Gray");
        IReadOnlyList<int> scores = mouse.Scores();
        Assert.Equal(new List<int> { 5, 4 }, scores);
    }

    [Fact]
    public void Toy_Alias_NullForNonGrayToy()
    {
        var ball = new Toy("Ball", "Red");
        Assert.Null(ball.Alias());
    }

    [Fact]
    public void Toy_Alias_NonNullForGrayToy()
    {
        var mouse = new Toy("Mouse", "Gray");
        Assert.Equal("Mouse (aka Grey Ghost)", mouse.Alias());
    }

    [Fact]
    public void Toy_AgeInMonths_NullForNonGrayToy()
    {
        var ball = new Toy("Ball", "Red");
        Assert.Null(ball.AgeInMonths());
    }

    [Fact]
    public void Toy_AgeInMonths_NonNullForGrayToy()
    {
        var mouse = new Toy("Mouse", "Gray");
        Assert.Equal(60, mouse.AgeInMonths());
    }
}
