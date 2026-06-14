using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class MutableSetTests
{
    [Fact]
    public void Cat_Vaccinations_Count()
    {
        using var cat = new Cat("Oreo", 9);
        ISet<string> vaccinations = cat.Vaccinations;
        Assert.Equal(2, vaccinations.Count);
    }

    [Fact]
    public void Cat_Vaccinations_Contains()
    {
        using var cat = new Cat("Oreo", 9);
        ISet<string> vaccinations = cat.Vaccinations;
        Assert.True(vaccinations.Contains("Rabies"));
        Assert.True(vaccinations.Contains("FVRCP"));
        Assert.False(vaccinations.Contains("FeLV"));
    }

    [Fact]
    public void Cat_Vaccinations_IsMutable()
    {
        using var cat = new Cat("Oreo", 9);
        ISet<string> vaccinations = cat.Vaccinations;
        vaccinations.Add("FeLV");
        Assert.Equal(3, vaccinations.Count);
        Assert.True(vaccinations.Contains("FeLV"));
    }

    [Fact]
    public void Cat_Vaccinations_Remove()
    {
        using var cat = new Cat("Oreo", 9);
        ISet<string> vaccinations = cat.Vaccinations;
        vaccinations.Remove("Rabies");
        Assert.Equal(1, vaccinations.Count);
        Assert.False(vaccinations.Contains("Rabies"));
    }

    [Fact]
    public void Cat_Vaccinations_Enumeration()
    {
        using var cat = new Cat("Oreo", 9);
        ISet<string> vaccinations = cat.Vaccinations;
        var list = new List<string>();
        foreach (var v in vaccinations)
        {
            list.Add(v);
        }
        Assert.Equal(2, list.Count);
        Assert.Contains("Rabies", list);
        Assert.Contains("FVRCP", list);
    }
}
