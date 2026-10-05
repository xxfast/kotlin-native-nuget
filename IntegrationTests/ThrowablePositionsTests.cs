using TestLibrary;
using TestLibrary.Mishaps;

namespace IntegrationTests;

/// <summary>
/// ADR-201: <c>Throwable</c> beyond the ADR-107 property getter. Out of Kotlin (a sync return, a
/// <c>List</c> element, a <c>Map</c> value, a module-local unexported subclass) every value is the
/// ADR-107 envelope, read back as a constructed, unthrown <c>System.Exception</c>. Into Kotlin (a
/// parameter declared <c>Throwable</c>/<c>Exception</c>/<c>RuntimeException</c>, a <c>var</c>
/// setter) any <c>System.Exception</c> arrives as the ADR-161 <c>NugetManagedException</c>,
/// carrying the managed type name and message only.
///
/// Oreo ate the plant; Mochi coughs up a hairball; Mylo reports both.
/// </summary>
public class ThrowablePositionsTests
{
    private const string ManagedExceptionType =
        "io.github.xxfast.kotlin.native.nuget.runtime.NugetManagedException";

    // --- Sync returns ---

    [Fact]
    public void Latest_NullableReturn_IsNullBeforeAnyReport()
    {
        using var log = new MishapLog();

        Exception? latest = log.Latest();

        Assert.Null(latest);
    }

    [Fact]
    public void Worst_Return_IsTheMappedExceptionWithItsCause()
    {
        using var log = new MishapLog();

        Exception worst = log.Worst();

        Assert.IsType<KotlinArgumentException>(worst);
        Assert.Equal("Oreo ate the plant", worst.Message);
        Assert.IsType<KotlinException>(worst.InnerException);
        Assert.Equal("the door was left open", worst.InnerException!.Message);
    }

    [Fact]
    public void WorstOrThrow_ThrowPath_ThrowsInsteadOfReturning()
    {
        using var log = new MishapLog();

        var thrown = Assert.Throws<KotlinInvalidOperationException>(() => log.WorstOrThrow(true));

        Assert.Equal("the vet is closed", thrown.Message);
        Assert.IsType<KotlinArgumentException>(log.WorstOrThrow(false));
    }

    // --- A module-local, unexported subclass ---

    [Fact]
    public void Hairball_ModuleLocalSubclass_MapsThroughItsStdlibBase()
    {
        using var log = new MishapLog();

        Exception hairball = log.Hairball();

        Assert.IsType<KotlinInvalidOperationException>(hairball);
        Assert.Equal("Mochi, 3am", hairball.Message);
        Assert.Equal(
            "io.github.xxfast.kotlin.native.nuget.hidden.HairballError",
            ((IKotlinException)hairball).KotlinType);
    }

    [Fact]
    public void Hairball_ModuleLocalSubclass_DeclaresNoCSharpType()
    {
        // The class itself stays undeclared: it binds as a value, never as a type.
        Assert.DoesNotContain(
            typeof(MishapLog).Assembly.GetTypes(),
            type => type.Name == "HairballError");
    }

    // --- Collection components ---

    [Fact]
    public void Timeline_ListReturn_ReadsEachElementIncludingTheNull()
    {
        using var log = new MishapLog();

        IReadOnlyList<Exception?> timeline = log.Timeline();

        Assert.Equal(3, timeline.Count);
        Assert.IsType<KotlinInvalidOperationException>(timeline[0]);
        Assert.Equal("the bowl is empty", timeline[0]!.Message);
        Assert.Null(timeline[1]);
        Assert.Equal("Mochi, 3am", timeline[2]!.Message);
    }

    [Fact]
    public void ByCat_MapValue_ReadsEachException()
    {
        using var log = new MishapLog();

        IReadOnlyDictionary<string, Exception> byCat = log.ByCat();

        Assert.IsType<KotlinArgumentException>(byCat["Oreo"]);
        Assert.IsType<KotlinInvalidOperationException>(byCat["Mochi"]);
    }

    // --- Inputs: a C# exception arrives as NugetManagedException ---

    [Fact]
    public void Report_ThenAll_RoundTripsAManagedException()
    {
        using var log = new MishapLog();

        log.Report(new InvalidOperationException("the bowl is empty"));
        IReadOnlyList<Exception> all = log.All;

        Assert.Single(all);
        Assert.Equal("System.InvalidOperationException: the bowl is empty", all[0].Message);
        Assert.Equal(ManagedExceptionType, ((IKotlinException)all[0]).KotlinType);
    }

    [Fact]
    public void Report_KotlinSeesTheManagedTypeAndMessage()
    {
        using var log = new MishapLog();

        log.Report(new ArgumentException("Oreo ate the plant"));

        Assert.Equal(
            "NugetManagedException: System.ArgumentException: Oreo ate the plant",
            log.DescribeLast());
    }

    [Fact]
    public void Report_AMessageContainingTheSeparator_KeepsItWhole()
    {
        // The wire splits at the FIRST ": ", which a CLR full name never contains.
        using var log = new MishapLog();

        log.Report(new Exception("vet says: not again"));

        Assert.Equal("NugetManagedException: System.Exception: vet says: not again", log.DescribeLast());
    }

    [Fact]
    public void ReportOrSkip_NullableParameter_TakesNullAndAValue()
    {
        using var log = new MishapLog();

        Assert.False(log.ReportOrSkip(null));
        Assert.True(log.ReportOrSkip(new TimeoutException("Mylo is late for dinner")));
        Assert.Equal(
            "System.TimeoutException: Mylo is late for dinner",
            log.Latest()!.Message);
    }

    [Fact]
    public void LastMishap_Setter_WritesAManagedExceptionAndNull()
    {
        using var log = new MishapLog();

        log.LastMishap = new InvalidOperationException("the bowl is empty");
        Exception? read = log.LastMishap;
        log.LastMishap = null;

        Assert.Equal("System.InvalidOperationException: the bowl is empty", read!.Message);
        Assert.Null(log.LastMishap);
        Assert.Single(log.All);
    }

    [Fact]
    public void Strict_NarrowerParameter_IsNotBound()
    {
        // `strict(mishap: IllegalStateException)` cannot hold a NugetManagedException.
        Assert.Null(typeof(MishapLog).GetMethod("Strict"));
    }
}
