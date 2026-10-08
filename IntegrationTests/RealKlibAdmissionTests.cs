using System.Reflection;
using TestLibrary.Co.Touchlab.Kermit;
using TestLibrary.Io.Ktor.Http;
using TestLibrary.Realklib;

namespace IntegrationTests;

/// <summary>
/// ADR-154 against real Maven-published klibs, the shapes the verb was designed around:
/// <c>test-library</c> depends on <c>io.ktor:ktor-http</c> and <c>co.touchlab:kermit</c> and
/// writes <c>admit("io.ktor.http.Url", "co.touchlab.kermit.Severity")</c>. Neither package is in
/// any include list, so both types reach C# through <c>admit</c> alone, under their dependency
/// namespaces. The fixture is <c>test-library/.../test/realklib/Postbox.kt</c>.
///
/// Declarations are read off the compiled types, never off the generated <c>///</c> remarks,
/// which deliberately name the skipped members.
///
/// Oreo posts letters to every cat on the street. Mylo only ever posts complaints.
/// </summary>
public class RealKlibAdmissionTests
{
    private const BindingFlags PublicInstance = BindingFlags.Public | BindingFlags.Instance;

    // ---- kermit Severity: a plain closed enum, by value -----------------------------------------

    [Fact]
    public void KermitSeverity_IsDeclaredAsACSharpEnum_WithItsSixMembers()
    {
        Assert.True(typeof(Severity).IsEnum);
        Assert.Equal("TestLibrary.Co.Touchlab.Kermit", typeof(Severity).Namespace);
        Assert.Equal(
            new[] { "Verbose", "Debug", "Info", "Warn", "Error", "Assert" },
            Enum.GetNames<Severity>());
    }

    [Fact]
    public void KermitSeverity_RoundTripsAtReturnAndParameter()
    {
        using var postbox = new Postbox("Mylo");

        Assert.Equal(Severity.Warn, postbox.Severity());
        Assert.Equal("Error", postbox.NameOf(Severity.Error));
        Assert.Equal("Assert", postbox.NameOf(Severity.Assert));
        Assert.Equal("Verbose", postbox.NameOf(Severity.Verbose));
    }

    // ---- ktor Url: a handle class whose primitive members bind ----------------------------------

    [Fact]
    public void KtorUrl_IsDeclaredAsAHandleClass_WithTypedHostPortAndEncodedPath()
    {
        Type url = typeof(Url);

        Assert.Equal("TestLibrary.Io.Ktor.Http", url.Namespace);
        Assert.True(url.IsClass);
        Assert.True(typeof(IDisposable).IsAssignableFrom(url));
        Assert.Equal(typeof(string), url.GetProperty("Host", PublicInstance)?.PropertyType);
        Assert.Equal(typeof(int), url.GetProperty("Port", PublicInstance)?.PropertyType);
        Assert.Equal(typeof(string), url.GetProperty("EncodedPath", PublicInstance)?.PropertyType);
    }

    /// <summary>
    /// ktor's <c>Url</c> constructor is <c>internal</c>, so C# gets no way to make one: a
    /// <c>Url</c> only ever comes back from Kotlin.
    /// </summary>
    [Fact]
    public void KtorUrl_HasNoPublicConstructor()
    {
        Assert.Empty(typeof(Url).GetConstructors(PublicInstance));
    }

    /// <summary>
    /// The members typed by ktor types nobody admitted (<c>URLProtocol</c>, <c>Parameters</c>)
    /// are absent from the declaration, and so are those types themselves.
    /// </summary>
    [Fact]
    public void KtorUrl_MembersOfUnadmittedKtorTypes_AreNotDeclared()
    {
        Type url = typeof(Url);

        Assert.Null(url.GetProperty("Protocol", PublicInstance));
        Assert.Null(url.GetProperty("ProtocolOrNull", PublicInstance));
        Assert.Null(url.GetProperty("Parameters", PublicInstance));
        Assert.Null(url.Assembly.GetType("TestLibrary.Io.Ktor.Http.URLProtocol"));
        Assert.Null(url.Assembly.GetType("TestLibrary.Io.Ktor.Http.Parameters"));
    }

    [Fact]
    public void KtorUrl_ParsedInKotlin_CarriesHostPortAndPath()
    {
        using var postbox = new Postbox("Oreo");
        using Url url = postbox.Parse("https://example.com:8443/cats/oreo?mood=purr#top");

        Assert.Equal("example.com", url.Host);
        Assert.Equal(8443, url.Port);
        Assert.Equal("/cats/oreo", url.EncodedPath);
    }

    /// <summary>The other direction: a ktor <c>Url</c> handed back to Kotlin as a parameter.</summary>
    [Fact]
    public void KtorUrl_PassedBackToKotlin_IsTheSameUrl()
    {
        using var postbox = new Postbox("Mylo");
        using Url url = postbox.Parse("http://mylo.cat/complaints");

        Assert.Equal("mylo.cat", postbox.HostOf(url));
        Assert.Equal(80, url.Port);
    }
}
