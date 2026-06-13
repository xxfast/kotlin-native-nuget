using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class EnumTests
{
    [Fact]
    public void Mood_HasCorrectValues()
    {
        Assert.Equal(0, (int)Mood.Happy);
        Assert.Equal(1, (int)Mood.Sleepy);
        Assert.Equal(2, (int)Mood.Grumpy);
    }

    [Fact]
    public void Cat_Mood_ReturnsDefaultValue()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.Equal(Mood.Sleepy, cat.Mood);
    }

    [Fact]
    public void Cat_Mood_CanBeSet()
    {
        using var cat = new Cat("Oreo", 9);
        cat.Mood = Mood.Happy;
        Assert.Equal(Mood.Happy, cat.Mood);
    }

    [Fact]
    public void Mood_Description_ReturnsCorrectString()
    {
        Assert.Equal("The cat is happy and content.", Mood.Happy.Description());
        Assert.Equal("The cat is sleepy and ready for a nap.", Mood.Sleepy.Description());
        Assert.Equal("The cat is grumpy and doesn't want to be disturbed.", Mood.Grumpy.Description());
    }
}
