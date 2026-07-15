using TestLibrary.Cat;

namespace IntegrationTests;

public class ReverseLambdaTests
{
    [Fact]
    public void Cat_DescribeWith_InvokesCSharpLambda()
    {
        using var cat = new Cat("Oreo", 9);
        string result = cat.DescribeWith(name => $"This cat is called {name}");
        Assert.Equal("This cat is called Oreo", result);
    }

    [Fact]
    public void Cat_NicknamesMatching_PredicateFiltersOnKotlinSide()
    {
        using var cat = new Cat("Oreo", 9);
        IReadOnlyList<string> matching = cat.NicknamesMatching(n => n.StartsWith("Little"));
        Assert.Equal(new List<string> { "Little Oreo" }, matching);
    }

    [Fact]
    public void Cat_NicknamesMatching_CapturingLambda()
    {
        using var cat = new Cat("Oreo", 9);
        int minLength = 6;
        IReadOnlyList<string> matching = cat.NicknamesMatching(n => n.Length >= minLength);
        Assert.Equal(new List<string> { "Little Oreo" }, matching);
    }

    [Fact]
    public void Cat_GreetUsing_ArityZeroLambda()
    {
        using var cat = new Cat("Oreo", 9);
        string result = cat.GreetUsing(() => "Hello");
        Assert.Equal("Hello, says Oreo", result);
    }

    [Fact]
    public void Cat_ForEachToy_UnitReturningAction()
    {
        using var cat = new Cat("Oreo", 9);
        var toyNames = new List<string>();
        cat.ForEachToy(toy =>
        {
            using var t = toy;
            toyNames.Add(t.Name);
        });
        Assert.Equal(new List<string> { "Mouse", "Ball" }, toyNames);
    }

    [Fact]
    public void Cat_CombineNicknames_Arity2LambdaParameter()
    {
        using var cat = new Cat("Oreo", 9);
        string result = cat.CombineNicknames((a, b) => $"{a} & {b}");
        Assert.Equal("Oreoy & Little Oreo", result);
    }

    [Fact]
    public void Cat_NicknamesMatchingName_Arity2Predicate()
    {
        using var cat = new Cat("Oreo", 9);
        IReadOnlyList<string> matching = cat.NicknamesMatchingName((nick, name) => nick.Contains(name));
        Assert.Equal(new List<string> { "Oreoy", "Little Oreo" }, matching);
    }
}
