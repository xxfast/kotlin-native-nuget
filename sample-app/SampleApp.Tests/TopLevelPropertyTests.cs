using SampleLibrary;

namespace SampleApp.Tests;

public class TopLevelPropertyTests
{
    [Fact]
    public void GetStringVal()
    {
        Assert.Equal("Scottish Fold", Properties.CatBreed);
    }

    [Fact]
    public void GetIntVar()
    {
        Assert.Equal(9, Properties.CatLives);
    }

    [Fact]
    public void SetAndGetIntVar()
    {
        Properties.CatLives = 7;
        Assert.Equal(7, Properties.CatLives);
    }

    [Fact]
    public void NullableStringDefaultNull()
    {
        Assert.Null(Properties.CatNickname);
    }

    [Fact]
    public void SetAndGetNullableString()
    {
        Properties.CatNickname = "Whiskers";
        Assert.Equal("Whiskers", Properties.CatNickname);
    }

    [Fact]
    public void SetNullableStringToNull()
    {
        Properties.CatNickname = "Whiskers";
        Properties.CatNickname = null;
        Assert.Null(Properties.CatNickname);
    }

    [Fact]
    public void NullableDoubleDefaultNull()
    {
        Assert.Null(Properties.CatWeight);
    }

    [Fact]
    public void SetAndGetNullableDouble()
    {
        Properties.CatWeight = 4.5;
        Assert.Equal(4.5, Properties.CatWeight);
    }

    [Fact]
    public void SetNullableDoubleToNull()
    {
        Properties.CatWeight = 4.5;
        Properties.CatWeight = null;
        Assert.Null(Properties.CatWeight);
    }
}
