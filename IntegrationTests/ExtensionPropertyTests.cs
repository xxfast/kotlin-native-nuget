using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

public class ExtensionPropertyTests
{
    [Fact]
    public void Cat_GetIsKitten_ReturnsTrueForNewCatWithNineLives()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.True(cat.GetIsKitten());
    }

    [Fact]
    public void Cat_GetIsKitten_ReturnsFalseForCatWithFewLivesLeft()
    {
        using var cat = new Cat("Mylo", 3);
        Assert.False(cat.GetIsKitten());
    }

    [Fact]
    public void Cat_GetIsKitten_ReturnsFalseAtExactlySevenLives()
    {
        using var cat = new Cat("Oreo", 7);
        Assert.False(cat.GetIsKitten());
    }

    [Fact]
    public void Cat_GetLabel_ReturnsNameWithMood()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.Equal("Oreo (sleepy)", cat.GetLabel());
    }

    [Fact]
    public void Cat_GetLabel_ReturnsMyloWithMood()
    {
        using var cat = new Cat("Mylo", 9);
        Assert.Equal("Mylo (sleepy)", cat.GetLabel());
    }

    [Fact]
    public void String_GetWordCount_ReturnsTwoForTwoWords()
    {
        Assert.Equal(2, "hello world".GetWordCount());
    }

    [Fact]
    public void String_GetWordCount_ReturnsOneForSingleWord()
    {
        Assert.Equal(1, "Oreo".GetWordCount());
    }

    [Fact]
    public void String_GetWordCount_IgnoresLeadingAndTrailingSpaces()
    {
        Assert.Equal(2, "  Oreo Mylo  ".GetWordCount());
    }

    [Fact]
    public void String_GetWordCount_CountsCatNames()
    {
        Assert.Equal(3, "Oreo and Mylo".GetWordCount());
    }
}
