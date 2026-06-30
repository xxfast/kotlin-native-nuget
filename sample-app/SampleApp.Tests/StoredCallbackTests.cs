using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class StoredCallbackTests
{
    [Fact]
    public void Cat_AddMoodListener_CallbackFiresOnTrigger()
    {
        using var cat = new Cat("Oreo", 9);
        var recorded = new List<string>();
        using IDisposable sub = cat.AddMoodListener(mood => recorded.Add(mood.ToString()));

        cat.TriggerMoodChange(Mood.Happy);

        Assert.Equal(new[] { "Happy" }, recorded);
    }

    [Fact]
    public void Cat_AddMoodListener_NoCallbackAfterDispose()
    {
        using var cat = new Cat("Mylo", 9);
        var recorded = new List<string>();
        IDisposable sub = cat.AddMoodListener(mood => recorded.Add(mood.ToString()));

        sub.Dispose();
        cat.TriggerMoodChange(Mood.Grumpy);

        Assert.Empty(recorded);
    }

    [Fact]
    public void Cat_AddMoodListener_MultipleSubscriptions_BothFire()
    {
        using var cat = new Cat("Oreo", 9);
        var oreoMoods = new List<string>();
        var myloMoods = new List<string>();
        using IDisposable sub1 = cat.AddMoodListener(mood => oreoMoods.Add(mood.ToString()));
        using IDisposable sub2 = cat.AddMoodListener(mood => myloMoods.Add(mood.ToString()));

        cat.TriggerMoodChange(Mood.Happy);

        Assert.Equal(new[] { "Happy" }, oreoMoods);
        Assert.Equal(new[] { "Happy" }, myloMoods);
    }

    [Fact]
    public void Cat_AddMoodListener_CapturingLambda_CapturedStateUpdatesAcrossTriggers()
    {
        using var cat = new Cat("Mylo", 9);
        int callCount = 0;
        using IDisposable sub = cat.AddMoodListener(_ => callCount++);

        cat.TriggerMoodChange(Mood.Sleepy);
        cat.TriggerMoodChange(Mood.Happy);

        Assert.Equal(2, callCount);
    }

    [Fact]
    public void Cat_AddMoodListener_UsingBlockScope_ListenerActiveOnlyInsideBlock()
    {
        using var cat = new Cat("Oreo", 9);
        var recorded = new List<string>();

        using (cat.AddMoodListener(mood => recorded.Add(mood.ToString())))
        {
            cat.TriggerMoodChange(Mood.Sleepy);
        }

        cat.TriggerMoodChange(Mood.Happy);

        Assert.Equal(new[] { "Sleepy" }, recorded);
    }
}
