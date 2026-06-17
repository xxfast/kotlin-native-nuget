using SampleLibrary;

namespace SampleApp.Tests;

public class TypeAliasTests
{
    [Fact]
    public void TopScore_ReturnsInt()
    {
        int result = TypeAliases.topScore();
        Assert.Equal(10, result);
    }

    [Fact]
    public void DefaultNames_ReturnsReadOnlyListOfString()
    {
        IReadOnlyList<string> names = TypeAliases.defaultNames();
        Assert.Equal(2, names.Count);
    }

    [Fact]
    public void DefaultNames_ContainsCatNames()
    {
        IReadOnlyList<string> names = TypeAliases.defaultNames();
        Assert.Equal("Oreo", names[0]);
        Assert.Equal("Mylo", names[1]);
    }

    [Fact]
    public void DefaultScores_ReturnsReadOnlyDictionaryOfStringInt()
    {
        IReadOnlyDictionary<string, int> scores = TypeAliases.defaultScores();
        Assert.Equal(2, scores.Count);
    }

    [Fact]
    public void DefaultScores_ContainsCatScores()
    {
        IReadOnlyDictionary<string, int> scores = TypeAliases.defaultScores();
        Assert.Equal(10, scores["Oreo"]);
        Assert.Equal(8, scores["Mylo"]);
    }

    [Fact]
    public void DefaultScores_OreoBeatsMylo()
    {
        IReadOnlyDictionary<string, int> scores = TypeAliases.defaultScores();
        Assert.True(scores["Oreo"] > scores["Mylo"]);
    }
}
