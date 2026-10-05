using TestLibrary;
using TestLibrary.Models;

namespace IntegrationTests;

/// <summary>
/// Issue <a href="https://github.com/xxfast/kotlin-native-nuget/issues/464">#464</a>: a property
/// and a function sharing a Kotlin name in <c>:test-models</c>, the dependency module that does not
/// apply the main plugin. Without <c>@CSharpName</c> on the function, <c>packNuget</c> fails with
/// <c>ERROR_CSHARP_NAME_COLLISION</c>; without the annotations-only plugin, <c>:test-models</c>
/// cannot resolve the annotation. Oreo files every story from the sunniest windowsill.
/// </summary>
public class DependencyCSharpNameTests
{
    [Fact]
    public void Dateline_RenamedFunction_CallsTheKotlinFunction()
    {
        using var newsroom = new Newsroom();
        using IDateline dateline = newsroom.Dateline();

        Assert.Equal("Windowsill, edition 3", dateline.CityFor(3));
    }

    [Fact]
    public void Dateline_Property_KeepsItsName()
    {
        using var newsroom = new Newsroom();
        using IDateline dateline = newsroom.Dateline();

        Assert.Equal("Windowsill", dateline.City);
    }
}
