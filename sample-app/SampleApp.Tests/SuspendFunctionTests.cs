using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class SuspendFunctionTests
{
    [Fact]
    public async Task FetchGreeting_ReturnsExpectedString()
    {
        string result = await AsyncFunctions.FetchGreetingAsync("Oreo");
        Assert.Equal("Hello, Oreo!", result);
    }

    [Fact]
    public async Task SaveGreeting_CompletesWithoutError()
    {
        await AsyncFunctions.SaveGreetingAsync("Meow from Mylo");
    }

    [Fact]
    public async Task AsyncCatService_Fetch_ReturnsString()
    {
        using var service = new AsyncCatService("catnip");
        string result = await service.FetchAsync();
        Assert.Equal("catnip result", result);
    }

    [Fact]
    public async Task AsyncCatService_FetchCat_ReturnsCatObject()
    {
        using var service = new AsyncCatService("toys");
        using var cat = await service.FetchCatAsync("Oreo");
        Assert.Equal("Oreo", cat.Name);
    }
}
