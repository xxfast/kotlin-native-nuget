using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class StructuredConcurrencyTests
{
    [Fact]
    public async Task Dispose_WhileCoroutineInFlight_CancelsTask()
    {
        Task<string> task;
        using (var service = new CatNapService())
        {
            task = service.LongNapAsync();
            await Task.Delay(50);
        }
        await Assert.ThrowsAsync<TaskCanceledException>(() => task);
    }

    [Fact]
    public async Task QuickNap_CompletesBeforeDispose_ReturnsResult()
    {
        using var service = new CatNapService();
        string result = await service.QuickNapAsync();
        Assert.Equal("quick nap done", result);
    }

    [Fact]
    public async Task AsyncMethod_AfterDispose_ThrowsObjectDisposedException()
    {
        var service = new CatNapService();
        service.Dispose();
        await Assert.ThrowsAsync<ObjectDisposedException>(
            () => service.LongNapAsync());
    }

    [Fact]
    public async Task MultipleConcurrentCalls_AllCancelOnDispose()
    {
        Task<string> t1, t2;
        using (var service = new CatNapService())
        {
            t1 = service.LongNapAsync();
            t2 = service.LongNapAsync();
            await Task.Delay(50);
        }
        await Assert.ThrowsAsync<TaskCanceledException>(() => t1);
        await Assert.ThrowsAsync<TaskCanceledException>(() => t2);
    }

    [Fact]
    public async Task SilentNap_CancelsOnDispose()
    {
        Task task;
        using (var service = new CatNapService())
        {
            task = service.SilentNapAsync();
            await Task.Delay(50);
        }
        await Assert.ThrowsAsync<TaskCanceledException>(() => task);
    }
}
