using TestLibrary;
using TestLibrary.Mishaps;

namespace IntegrationTests;

/// <summary>
/// The ADR-201 amendment: <c>Throwable</c> at the positions ADR-201 deferred. Out of Kotlin each
/// value is the ADR-107 envelope, an unthrown <c>System.Exception</c>; into Kotlin any
/// <c>System.Exception</c> arrives as a <c>NugetManagedException</c> carrying its type name and
/// message.
///
/// Mochi coughs up a hairball at 3am; Oreo reports it, in bulk.
/// </summary>
public class ThrowableRemainingPositionsTests
{
    // --- Group A: a List element and a Map value at an input ---

    [Fact]
    public void ReportAll_ListInput_ArrivesAsManagedExceptions()
    {
        using var stream = new MishapStream();

        string seen = stream.ReportAll(new Exception[]
        {
            new InvalidOperationException("the bowl is empty"),
            new ArgumentException("vet says: not again"),
        });

        Assert.Equal(
            "NugetManagedException: System.InvalidOperationException: the bowl is empty | " +
                "NugetManagedException: System.ArgumentException: vet says: not again",
            seen);
    }

    [Fact]
    public void ReportSome_NullableElements_KeepTheirNulls()
    {
        using var stream = new MishapStream();

        Assert.Equal(1, stream.ReportSome(new Exception?[] { null, new Exception("fever"), null }));
        Assert.Equal(0, stream.ReportSome(Array.Empty<Exception?>()));
    }

    [Fact]
    public void ReportByCat_MapValueInput_ArrivesPerEntry()
    {
        using var stream = new MishapStream();

        string seen = stream.ReportByCat(new Dictionary<string, Exception>
        {
            ["Oreo"] = new InvalidOperationException("ate the plant"),
            ["Mochi"] = new TimeoutException("3am"),
        });

        Assert.Equal(
            "Mochi=System.TimeoutException: 3am | Oreo=System.InvalidOperationException: ate the plant",
            seen);
    }

    [Fact]
    public void Pending_MutableListSetter_WritesAndReadsBack()
    {
        using var stream = new MishapStream();

        stream.Pending = new List<Exception> { new Exception("fever"), new Exception("fleas") };

        Assert.Equal(2, stream.PendingCount());
        Assert.Equal("System.Exception: fleas", stream.Pending[1].Message);
    }

    // --- Group B: a suspend or Flow parameter ---

    [Fact]
    public async Task ReportLater_SuspendParameter_ArrivesAsAManagedException()
    {
        using var stream = new MishapStream();

        string seen = await stream.ReportLaterAsync(new TimeoutException("Mylo is late for dinner"));

        Assert.Equal("NugetManagedException: System.TimeoutException: Mylo is late for dinner", seen);
    }

    [Fact]
    public async Task MaybeLater_NullableSuspendParameter_TakesNullAndAValue()
    {
        using var stream = new MishapStream();

        Assert.False(await stream.MaybeLaterAsync(null));
        Assert.True(await stream.MaybeLaterAsync(new Exception("fever")));
    }

    [Fact]
    public async Task Watch_FlowParameter_ArrivesBeforeTheFirstItem()
    {
        using var stream = new MishapStream();
        var seen = new List<string>();

        await foreach (string item in stream.Watch(new InvalidOperationException("bowl"))) seen.Add(item);

        Assert.Equal(new[] { "System.InvalidOperationException: bowl", "again" }, seen);
    }

    // --- Group C: a bare suspend result, Flow element and StateFlow element ---

    [Fact]
    public async Task WorstLater_SuspendResult_IsTheMappedExceptionWithItsCause()
    {
        using var stream = new MishapStream();

        Exception worst = await stream.WorstLaterAsync();

        Assert.IsType<KotlinArgumentException>(worst);
        Assert.Equal("Oreo ate the plant", worst.Message);
        Assert.Equal("the door was open", worst.InnerException!.Message);
    }

    [Fact]
    public async Task LatestLater_NullableSuspendResult_ReadsNullThenAValue()
    {
        using var stream = new MishapStream();

        Assert.Null(await stream.LatestLaterAsync());
        stream.Record(new TimeoutException("Mylo is late"));
        Exception? latest = await stream.LatestLaterAsync();

        Assert.Equal("System.TimeoutException: Mylo is late", latest!.Message);
    }

    [Fact]
    public async Task WorstOrThrowLater_ThrowPath_FaultsTheTask()
    {
        using var stream = new MishapStream();

        var thrown = await Assert.ThrowsAsync<KotlinInvalidOperationException>(
            () => stream.WorstOrThrowLaterAsync(true));

        Assert.Equal("the vet is closed", thrown.Message);
    }

    [Fact]
    public async Task Each_FlowElement_StreamsEveryExceptionAsAValue()
    {
        using var stream = new MishapStream();
        var seen = new List<Exception>();

        await foreach (Exception mishap in stream.Each()) seen.Add(mishap);

        Assert.Equal(2, seen.Count);
        Assert.IsType<KotlinInvalidOperationException>(seen[0]);
        Assert.EndsWith("HairballError", ((IKotlinException)seen[1]).KotlinType);
    }

    [Fact]
    public async Task MaybeEach_NullableFlowElement_KeepsTheNull()
    {
        using var stream = new MishapStream();
        var seen = new List<Exception?>();

        await foreach (Exception? mishap in stream.MaybeEach()) seen.Add(mishap);

        Assert.Null(seen[0]);
        Assert.Equal("x", seen[1]!.Message);
    }

    [Fact]
    public void Current_StateFlowElement_ReadsNullThenTheRecordedException()
    {
        using var stream = new MishapStream();

        Assert.Null(stream.Current.Value);
        stream.Record(new ArgumentException("the plant"));

        Assert.Equal("System.ArgumentException: the plant", stream.Current.Value!.Message);
    }

    [Fact]
    public async Task StreamLater_AcquiredFlow_StreamsTheException()
    {
        using var stream = new MishapStream();
        using KotlinFlow<Exception> later = await stream.StreamLaterAsync();
        var seen = new List<Exception>();

        await foreach (Exception mishap in later) seen.Add(mishap);

        Assert.Equal("again", Assert.Single(seen).Message);
    }

    // --- Group D: a lambda payload and result, and a listener parameter ---

    [Fact]
    public void OnMishap_LambdaPayload_IsAnUnthrownKotlinException()
    {
        using var stream = new MishapStream();
        Exception? got = null;

        stream.OnMishap(mishap => got = mishap);

        Assert.IsType<KotlinInvalidOperationException>(got);
        Assert.EndsWith("HairballError", ((IKotlinException)got!).KotlinType);
    }

    [Fact]
    public void Recover_LambdaResult_ArrivesAsAManagedException()
    {
        using var stream = new MishapStream();

        string seen = stream.Recover(() => new ArgumentException("the plant was poisonous"));

        Assert.Equal(
            "NugetManagedException: System.ArgumentException: the plant was poisonous", seen);
    }

    private sealed class Listener : IMishapListener
    {
        public List<Exception> Heard { get; } = new();
        public void OnMishap(Exception mishap) => Heard.Add(mishap);
        public void Dispose() { }
    }

    [Fact]
    public void Announce_ListenerParameter_IsAnUnthrownKotlinException()
    {
        using var stream = new MishapStream();
        var listener = new Listener();
        using (IDisposable sub = stream.AddMishapListener(listener))
        {
            Assert.Equal(1, stream.Announce());
        }

        Assert.Equal(0, stream.Announce());
        Assert.IsType<KotlinArgumentException>(Assert.Single(listener.Heard));
        Assert.Equal("Oreo ate the plant", listener.Heard[0].Message);
    }

    // --- Group E: a C#-implemented interface slot ---

    private sealed class Sink(Exception? last) : IMishapSink
    {
        public Exception? Seen { get; private set; }
        public void Accept(Exception mishap) => Seen = mishap;
        public Exception? Last() => last;
        public void Dispose() { }
    }

    [Fact]
    public void DrainTo_SlotParameter_ReceivesAnUnthrownKotlinException()
    {
        using var stream = new MishapStream();
        var sink = new Sink(null);

        Assert.Null(stream.DrainTo(sink));

        Assert.IsType<KotlinInvalidOperationException>(sink.Seen);
        Assert.Equal("Mochi, 3am", sink.Seen!.Message);
        Assert.EndsWith("HairballError", ((IKotlinException)sink.Seen).KotlinType);
    }

    [Fact]
    public void DrainTo_SlotResult_ArrivesAsAManagedException()
    {
        using var stream = new MishapStream();
        var sink = new Sink(new InvalidOperationException("the bowl is empty"));

        Assert.Equal("System.InvalidOperationException: the bowl is empty", stream.DrainTo(sink));
    }
}
