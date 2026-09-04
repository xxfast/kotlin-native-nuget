using TestLibrary;
using TestLibrary.Issue50;

namespace IntegrationTests;

/// <summary>
/// Issue <a href="https://github.com/xxfast/kotlin-native-nuget/issues/50">#50</a>: a sealed
/// subclass whose property types live in another exported namespace renders them by bare simple
/// name in the property type, <c>new List&lt;T&gt;(count)</c>, <c>FromHandle&lt;T&gt;</c> and
/// <c>new T(handle)</c> positions, so <c>Interop.cs</c> fails with CS0246. The same positions on a
/// top-level class were fixed by #47.
/// <para>
/// <see cref="Issue50State"/> lives in the root <c>TestLibrary</c> namespace; its payloads
/// <see cref="Issue50Assignment"/> and <see cref="Issue50Position"/> live in
/// <c>TestLibrary.Issue50</c>. These tests cannot compile until the generated file does, which is
/// the red signal.
/// </para>
/// </summary>
public class Issue50Tests
{
    [Fact]
    public void Loading_MaterialisesTheDataObjectArm()
    {
        using Issue50State state = Issue50Feed.issue50Loading();
        Assert.IsType<Issue50State.Loading>(state);
    }

    [Fact]
    public void Success_Crew_ReadsTheCrossNamespaceListAndItsElements()
    {
        using Issue50State state = Issue50Feed.issue50Loaded();
        var success = Assert.IsType<Issue50State.Success>(state);

        IReadOnlyList<Issue50Assignment> crew = success.Crew;

        Assert.Equal(2, crew.Count);
        Assert.Equal("Oreo", crew[0].Name);
        Assert.Equal("Mylo", crew[1].Name);
        Assert.All(crew, assignment => Assert.Equal("Kitchen Station", assignment.Craft));
    }

    [Fact]
    public void Success_Position_ReadsTheCrossNamespaceObjectProperty()
    {
        using Issue50State state = Issue50Feed.issue50Loaded();
        var success = Assert.IsType<Issue50State.Success>(state);

        using Issue50Position position = success.Position;

        Assert.Equal(-37.81, position.Latitude);
        Assert.Equal(144.96, position.Longitude);
    }
}
