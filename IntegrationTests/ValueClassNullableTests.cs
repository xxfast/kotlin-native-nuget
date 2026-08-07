using TestLibrary.Clinic;

namespace IntegrationTests;

/// <summary>
/// ADR-077 sub-item 3: <c>Nullable(ValueClass(String))</c> at property, parameter, and return
/// positions. The wire is the same null pointer already used by nullable String/ObjectHandle
/// shapes -- no has-value pair, because a value class's underlying <c>String</c> is non-nullable
/// by construction. Today <c>ForwardPropertyPlanner.isPlannable</c> guards nullable value-class
/// properties out entirely, and <c>inputSkipReason</c>'s <c>Nullable</c> branch doesn't admit a
/// <c>ValueClass</c> inner, so none of the members below exist in the generated C# yet: these
/// tests are expected to fail on the missing member (CS1061 / CS1729 / CS0117), not on an
/// assertion.
///
/// Expected generated surface per the ADR: the property getter/return produce
/// <c>nativeResult == IntPtr.Zero ? null : new ChartId(Marshal.PtrToStringUTF8(nativeResult)!)</c>
/// (a genuine <c>ChartId?</c> = <c>Nullable&lt;ChartId&gt;</c>, never a reference nullable); the
/// parameter passes <c>to?.Value</c> over the string wire and Kotlin re-wraps with
/// <c>to?.let { ChartId(it) }</c>.
///
/// Oreo never remembers to file a backup chart; Mylo always has one ready.
/// </summary>
public class ValueClassNullableTests
{
    [Fact]
    public void Patient_BackupChart_NullableProperty_StartsNull()
    {
        using var oreo = new Patient("Oreo");

        Assert.Null(oreo.BackupChart);
        Assert.False(oreo.HasBackup());
    }

    [Fact]
    public void Patient_BackupChart_NullableProperty_RoundTripsAValueThenBackToNull()
    {
        using var mylo = new Patient("Mylo");

        mylo.BackupChart = new ChartId("CH-3");

        Assert.Equal(new ChartId("CH-3"), mylo.BackupChart);
        Assert.True(mylo.HasBackup());

        mylo.BackupChart = null;

        Assert.Null(mylo.BackupChart);
        Assert.False(mylo.HasBackup());
    }

    [Fact]
    public void Patient_TransferTo_NullableParameter_ValueCrossesAsARewrappedChartId()
    {
        using var oreo = new Patient("Oreo");

        Assert.Equal("Oreo transferred to CH-7", oreo.TransferTo(new ChartId("CH-7")));
    }

    [Fact]
    public void Patient_TransferTo_NullableParameter_NullCrossesAsNullNotAnEmptyChartId()
    {
        using var oreo = new Patient("Oreo");

        // Proves Kotlin sees a genuine null, not a ChartId wrapping an empty string: only the
        // null branch of transferTo is reachable this way.
        Assert.Equal("Oreo has no transfer", oreo.TransferTo(null));
    }

    [Fact]
    public void Patient_PreviousChart_NullableReturn_IsNullBeforeAnyBackupIsSet()
    {
        using var oreo = new Patient("Oreo");

        Assert.Null(oreo.PreviousChart());
    }

    [Fact]
    public void Patient_PreviousChart_NullableReturn_ReflectsTheBackupOnceSet()
    {
        using var mylo = new Patient("Mylo");

        mylo.BackupChart = new ChartId("CH-9");

        Assert.Equal(new ChartId("CH-9"), mylo.PreviousChart());
    }
}
