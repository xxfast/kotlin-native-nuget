using TestLibrary.Overloads;

namespace IntegrationTests;

/// <summary>
/// ADR-057 outer-loop tests. The assertions sit at the C# consumer boundary and exercise the real
/// route through TestLibrary's forward exports, Kotlin's reverse bindings, and TestDependency.
/// </summary>
public class OverloadRoundTripTests
{
    [Fact]
    public void StaticMethodOverloads_DispatchByParameterType()
    {
        string result = OverloadsSample.DescribeOverloads(23, true);

        Assert.Equal("static:int:23|static:bool:on", result);
    }

    [Fact]
    public void InstanceMethodOverloads_DispatchStringAndInt()
    {
        string result = OverloadsSample.ApplyOverloads("Oreo", 11);

        Assert.Equal("seed:7:text:Oreo|seed:7:int:11", result);
    }

    [Fact]
    public void ClassConstructorOverloads_DispatchIntAndBoolean()
    {
        string result = OverloadsSample.ClassConstructorOverloads();

        Assert.Equal("seed:9:int:2|enabled:off:text:Mylo", result);
    }

    [Fact]
    public void StructConstructorOverloads_DispatchEveryShape()
    {
        string result = OverloadsSample.StructConstructorOverloads();

        Assert.Equal("2,3|4,4|1,1|5,6", result);
    }
}
