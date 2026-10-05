using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-129 amendment: with <c>NUGET_INTEROP_TRACE</c> on, every Kotlin exception that crosses the
/// forward bridge writes one <c>[nuget:interop] error</c> line from
/// <c>NugetErrorNative.BuildException</c> to the same sink as <c>NugetRuntime.TraceLoaded</c>.
/// Same seam as <see cref="RuntimeVersionTests"/>: the variables are read per call, so the test
/// flips them in the non-parallel <c>EnvVars</c> collection.
/// </summary>
[Collection("EnvVars")]
public class ForwardErrorTraceTests
{
    private const string TraceVariable = "NUGET_INTEROP_TRACE";
    private const string TraceFileVariable = "NUGET_INTEROP_TRACEFILE";
    private const string LinePrefix = "[nuget:interop] error Rake: ";

    [Fact]
    public void Oreo_JammedRake_WhenTraceIsOn_AppendsOneErrorLine()
    {
        string[] lines = TraceOf("oreo", "1");

        // Filtered by member: a background Kotlin error from an earlier test may share the window.
        string line = Assert.Single(lines, l => l.StartsWith(LinePrefix, StringComparison.Ordinal));
        // The concrete Kotlin class, the .NET type it became, and the row that decided it.
        Assert.Equal(
            LinePrefix + "io.github.xxfast.kotlin.native.nuget.test.cat.LitterBoxJammedException" +
            " -> KotlinIOException (row kotlinx.io.IOException): Oreo buried the rake",
            line);
    }

    [Fact]
    public void Mylo_JammedRake_WhenTraceIsOff_WritesNothing()
    {
        string[] lines = TraceOf("mylo", null);

        Assert.DoesNotContain(lines, l => l.StartsWith(LinePrefix, StringComparison.Ordinal));
    }

    /// <summary>The trace file's lines after one throwing forward call, with the trace set so.</summary>
    private static string[] TraceOf(string cat, string? trace)
    {
        string? previousTrace = Environment.GetEnvironmentVariable(TraceVariable);
        string? previousTraceFile = Environment.GetEnvironmentVariable(TraceFileVariable);
        string traceFile =
            Path.Combine(Path.GetTempPath(), $"nuget-interop-error-{cat}-{Guid.NewGuid():N}.log");
        try
        {
            Environment.SetEnvironmentVariable(TraceVariable, trace);
            Environment.SetEnvironmentVariable(TraceFileVariable, traceFile);

            Assert.ThrowsAny<IOException>(() => LitterBoxErrors.Rake("Oreo"));

            return File.Exists(traceFile) ? File.ReadAllLines(traceFile) : [];
        }
        finally
        {
            Environment.SetEnvironmentVariable(TraceVariable, previousTrace);
            Environment.SetEnvironmentVariable(TraceFileVariable, previousTraceFile);
            if (File.Exists(traceFile)) File.Delete(traceFile);
        }
    }
}
