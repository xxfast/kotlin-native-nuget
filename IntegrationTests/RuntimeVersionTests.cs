using System.Reflection;
using TestLibrary;

namespace IntegrationTests;

/// <summary>
/// ADR-129: the generated <c>Interop.cs</c> always emits an <c>internal static class NugetRuntime</c>
/// in the consumer namespace. It P/Invokes the 67th runtime export, <c>nuget_runtime_version</c>, and
/// its <c>[ModuleInitializer] TraceLoaded()</c> prints one line under <c>NUGET_INTEROP_TRACE</c>.
///
/// Think of it as the collar tag on the runtime: Oreo and Mylo look alike in the dark, and so do two
/// builds of the same <c>.dll</c>. This is how a process says which one it is actually wearing.
/// </summary>
[Collection("EnvVars")]
public class RuntimeVersionTests
{
    private const string TraceVariable = "NUGET_INTEROP_TRACE";
    private const string TraceFileVariable = "NUGET_INTEROP_TRACEFILE";

    /// <summary>The fixture's DllImport library name, per the generated <c>[DllImport("test", ...)]</c>.</summary>
    private const string LibraryName = "test";

    [Fact]
    public void Version_MatchesThePackedRuntimeVersion()
    {
        AssemblyMetadataAttribute? expected = typeof(RuntimeVersionTests).Assembly
            .GetCustomAttributes<AssemblyMetadataAttribute>()
            .SingleOrDefault(attribute => attribute.Key == "NugetRuntimeVersion");

        Assert.NotNull(expected);
        // A missing <NugetRuntimeVersion> in build/FixtureVersions.props must fail here, loudly,
        // rather than quietly comparing "" to "".
        Assert.False(string.IsNullOrWhiteSpace(expected.Value));

        Assert.Equal(expected.Value, NugetRuntime.Version);
    }

    [Fact]
    public void TraceLoaded_WhenTraceIsOff_WritesNothing()
    {
        string? previousTrace = Environment.GetEnvironmentVariable(TraceVariable);
        string? previousTraceFile = Environment.GetEnvironmentVariable(TraceFileVariable);
        string traceFile = FreshTraceFile("oreo");
        try
        {
            Environment.SetEnvironmentVariable(TraceVariable, null);
            Environment.SetEnvironmentVariable(TraceFileVariable, traceFile);

            NugetRuntime.TraceLoaded();

            Assert.True(
                !File.Exists(traceFile) || new FileInfo(traceFile).Length == 0,
                $"Trace is off, so {traceFile} must stay absent or empty.");
        }
        finally
        {
            Environment.SetEnvironmentVariable(TraceVariable, previousTrace);
            Environment.SetEnvironmentVariable(TraceFileVariable, previousTraceFile);
            if (File.Exists(traceFile)) File.Delete(traceFile);
        }
    }

    [Fact]
    public void TraceLoaded_WhenTraceIsOn_AppendsTheRuntimeVersionLine()
    {
        string? previousTrace = Environment.GetEnvironmentVariable(TraceVariable);
        string? previousTraceFile = Environment.GetEnvironmentVariable(TraceFileVariable);
        string traceFile = FreshTraceFile("mylo");
        try
        {
            Environment.SetEnvironmentVariable(TraceVariable, "1");
            Environment.SetEnvironmentVariable(TraceFileVariable, traceFile);

            NugetRuntime.TraceLoaded();

            Assert.True(File.Exists(traceFile), $"Trace is on, so {traceFile} must have been written.");
            string line = Assert.Single(File.ReadAllLines(traceFile));
            Assert.Equal($"[nuget:interop] runtime {NugetRuntime.Version} loaded from {LibraryName}", line);
        }
        finally
        {
            Environment.SetEnvironmentVariable(TraceVariable, previousTrace);
            Environment.SetEnvironmentVariable(TraceFileVariable, previousTraceFile);
            if (File.Exists(traceFile)) File.Delete(traceFile);
        }
    }

    /// <summary>A path that does not exist yet, so "absent" and "empty" are both observable.</summary>
    private static string FreshTraceFile(string cat) =>
        Path.Combine(Path.GetTempPath(), $"nuget-interop-{cat}-{Guid.NewGuid():N}.log");
}

/// <summary>
/// Both trace tests mutate process environment variables, so they must never run beside each other.
/// </summary>
[CollectionDefinition("EnvVars", DisableParallelization = true)]
public class EnvVarsCollection
{
}
