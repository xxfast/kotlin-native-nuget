using TestLibrary.Issue42;

namespace IntegrationTests;

/// <summary>
/// Issue #42: an exported class whose supertype is declared outside the export set. The
/// reachability closure (ADR-066) admits returns, parameters, property types, type arguments,
/// sealed subclasses and primary-ctor params, but never supertypes, while
/// <c>CirClassTranslator</c> derives its <c>interfaces</c> list straight off <c>superTypes</c>
/// with an <c>I</c> prefix and no membership check. So <see cref="Issue42Api"/> renders
/// <c>: IIssue42Component</c> for an interface nothing ever generates, and the whole
/// <c>Interop.cs</c> dies on CS0246 -- the reporter's real case was Koin's <c>KoinComponent</c>.
///
/// After the fix the dangling supertype is simply gone (it carries no member C# could call), the
/// class's own surface is untouched, and a <c>SKIPPED_UNEXPORTED_SUPERTYPE</c> diagnostic names
/// it. Oreo mans port 8080; Mylo naps on the router.
/// </summary>
public class Issue42Tests
{
    [Fact]
    public void Port_PrimaryConstructorProperty_SurvivesTheDroppedSupertype()
    {
        using var api = new Issue42Api(8080);

        Assert.Equal(8080, api.Port);
    }

    [Fact]
    public void Describe_DeclaredMethod_SurvivesTheDroppedSupertype()
    {
        // The half of the contract that "just don't export the class" would also satisfy, and
        // must not: dropping the supertype may not cost the class its own members.
        using var api = new Issue42Api(8080);

        Assert.Equal("api:8080", api.Describe());
    }

    [Fact]
    public void Issue42Api_DoesNotImplementAnInterfaceNamedForTheUnexportedSupertype()
    {
        // Name-specific, not "implements nothing": the generated class still implements
        // IDisposable, which is correct and unrelated.
        var dangling = typeof(Issue42Api)
            .GetInterfaces()
            .Where(i => i.Name == "IIssue42Component")
            .ToList();

        Assert.Empty(dangling);
    }

    [Fact]
    public void NoMarkerInterface_IsEmittedForTheUnexportedSupertype()
    {
        // The other admissible fix -- emitting an empty `public interface IIssue42Component` --
        // is explicitly not what we chose: an unexported supertype has no members the C# side can
        // call, so the marker would be pure noise on the public surface. Interop.cs compiles into
        // IntegrationTests.dll, so that assembly is the right haystack.
        var emitted = typeof(Issue42Api).Assembly
            .GetTypes()
            .Where(t => t.Name == "IIssue42Component")
            .ToList();

        Assert.Empty(emitted);
    }
}
