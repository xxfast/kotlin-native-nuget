using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class MutableMapTests
{
    [Fact]
    public void Cat_Schedule_Count()
    {
        using var cat = new Cat("Oreo", 9);
        IDictionary<string, string> schedule = cat.Schedule;
        Assert.Equal(2, schedule.Count);
    }

    [Fact]
    public void Cat_Schedule_GetByKey()
    {
        using var cat = new Cat("Oreo", 9);
        IDictionary<string, string> schedule = cat.Schedule;
        Assert.Equal("Nap", schedule["morning"]);
        Assert.Equal("Play", schedule["evening"]);
    }

    [Fact]
    public void Cat_Schedule_IsMutable()
    {
        using var cat = new Cat("Oreo", 9);
        IDictionary<string, string> schedule = cat.Schedule;
        schedule["night"] = "Sleep";
        Assert.Equal(3, schedule.Count);
        Assert.Equal("Sleep", schedule["night"]);
    }

    [Fact]
    public void Cat_Schedule_RemoveByKey()
    {
        using var cat = new Cat("Oreo", 9);
        IDictionary<string, string> schedule = cat.Schedule;
        schedule.Remove("morning");
        Assert.Equal(1, schedule.Count);
        Assert.Equal("Play", schedule["evening"]);
    }

    [Fact]
    public void Cat_Schedule_Enumeration()
    {
        using var cat = new Cat("Oreo", 9);
        IDictionary<string, string> schedule = cat.Schedule;
        var keys = new List<string>();
        foreach (var kvp in schedule)
        {
            keys.Add(kvp.Key);
        }
        Assert.Equal(2, keys.Count);
        Assert.Contains("morning", keys);
        Assert.Contains("evening", keys);
    }
}
