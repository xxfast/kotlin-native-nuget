using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class MutableListTests
{
    [Fact]
    public void Cat_FavoriteFoods_Count()
    {
        using var cat = new Cat("Oreo", 9);
        IList<string> foods = cat.FavoriteFoods;
        Assert.Equal(2, foods.Count);
    }

    [Fact]
    public void Cat_FavoriteFoods_GetByIndex()
    {
        using var cat = new Cat("Oreo", 9);
        IList<string> foods = cat.FavoriteFoods;
        Assert.Equal("Tuna", foods[0]);
        Assert.Equal("Salmon", foods[1]);
    }

    [Fact]
    public void Cat_FavoriteFoods_IsMutable()
    {
        using var cat = new Cat("Oreo", 9);
        IList<string> foods = cat.FavoriteFoods;
        foods.Add("Chicken");
        Assert.Equal(3, foods.Count);
        Assert.Equal("Chicken", foods[2]);
    }

    [Fact]
    public void Cat_FavoriteFoods_SetByIndex()
    {
        using var cat = new Cat("Oreo", 9);
        IList<string> foods = cat.FavoriteFoods;
        foods[0] = "Chicken";
        Assert.Equal("Chicken", foods[0]);
    }

    [Fact]
    public void Cat_FavoriteFoods_RemoveAt()
    {
        using var cat = new Cat("Oreo", 9);
        IList<string> foods = cat.FavoriteFoods;
        foods.RemoveAt(0);
        Assert.Equal(1, foods.Count);
        Assert.Equal("Salmon", foods[0]);
    }

    [Fact]
    public void Cat_FavoriteFoods_Enumeration()
    {
        using var cat = new Cat("Oreo", 9);
        IList<string> foods = cat.FavoriteFoods;
        var list = new List<string>();
        foreach (var food in foods)
        {
            list.Add(food);
        }
        Assert.Equal(new List<string> { "Tuna", "Salmon" }, list);
    }
}
