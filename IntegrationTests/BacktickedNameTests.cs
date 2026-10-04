using TestLibrary.Backtickednames;

namespace IntegrationTests;

/// <summary>
/// Kotlin names that need backticks (<c>backtickednames/Lamp.kt</c>). A hard keyword binds under
/// its PascalCase name, its keyword parameter <c>@</c>-escaped, on the plan route and on the
/// legacy <c>suspend</c> route. Each member returns which body ran. A name with a space has no
/// native proof: Kotlin/Native's own C API fails to link a public member named that way.
/// <para>
/// Oreo switches the lamp on; Mylo waits for it to warm up.
/// </para>
/// </summary>
public class BacktickedNameTests
{
    [Fact]
    public void KeywordMember_WithKeywordParameter_ReachesItsBody()
    {
        using var lamp = new Lamp();
        Assert.Equal("in:3", lamp.In(@object: 3));
    }

    [Fact]
    public async Task KeywordSuspendMember_WithKeywordParameter_ReachesItsBody()
    {
        using var lamp = new Lamp();
        Assert.Equal("is:Oreo", await lamp.IsAsync(@fun: "Oreo"));
    }
}
