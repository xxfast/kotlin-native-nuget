using SampleLibrary.Overloads;

namespace SampleApp.Tests;

/// <summary>
/// ADR-057 outer-loop tests. The assertions sit at the C# consumer boundary and exercise the real
/// route through SampleLibrary's forward exports, Kotlin's reverse bindings, and SampleDependency.
/// </summary>
public class OverloadRoundTripTests
{
    [Fact]
    public void StaticMethodOverloads_DispatchByParameterType()
    {
        string result = OverloadsSample.describeOverloads(23, true);

        Assert.Equal("static:int:23|static:bool:on", result);
    }

    [Fact]
    public void InstanceMethodOverloads_DispatchStringAndInt()
    {
        string result = OverloadsSample.applyOverloads("Oreo", 11);

        Assert.Equal("seed:7:text:Oreo|seed:7:int:11", result);
    }

    [Fact]
    public void ClassConstructorOverloads_DispatchIntAndBoolean()
    {
        string result = OverloadsSample.classConstructorOverloads();

        Assert.Equal("seed:9:int:2|enabled:off:text:Mylo", result);
    }

    [Fact]
    public void StructConstructorOverloads_DispatchEveryShape()
    {
        string result = OverloadsSample.structConstructorOverloads();

        Assert.Equal("2,3|4,4|1,1|5,6", result);
    }
}
