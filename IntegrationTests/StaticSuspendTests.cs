using System.Reflection;
using TestLibrary;
using TestLibrary.Catnap;

namespace IntegrationTests;

/// <summary>
/// ADR-229: a <c>suspend</c> member of an owner with no C# instance scope binds on the static
/// suspend route, one ad-hoc root scope per call. An <c>object</c>'s and a companion's members are
/// static <c>Task&lt;T&gt; XAsync(..., CancellationToken cancellationToken = default)</c> methods,
/// a suspend extension is a C# <c>this</c> extension, an enum body member and an enum companion
/// member land on <c>NapSpotExtensions</c>, and a value-class member is an instance method on the
/// record struct. Each owner crosses a result, a generic <c>T</c>, a fault and a cancellation.
/// <para>
/// Red before ADR-229: every one of these members is a named skip
/// (<c>SKIPPED_UNSUPPORTED_COMBINATION</c> or the extension's named drop), so none of the
/// <c>XAsync</c> members below exists (CS0117 / CS1061).
/// </para>
/// Oreo (black with the white middle) and Mylo (brown and creamy) nap wherever the sun is.
/// </summary>
public class StaticSuspendTests
{
    // (1) An `object`.

    [Fact]
    public async Task Siesta_MinutesForAsync_CompletesOnTheStaticClass()
    {
        Assert.Equal(40, await Siesta.MinutesForAsync("Oreo"));
        Assert.Equal(40, await Siesta.MinutesForAsync("Mylo"));
    }

    [Fact]
    public async Task Siesta_EchoAsync_RoundTripsTheTypeParameter()
    {
        Assert.Equal(4, await Siesta.EchoAsync(4));
        Assert.Equal("Mylo", await Siesta.EchoAsync("Mylo"));
        await using var oreo = new Swing("Oreo");
        await using Swing same = await Siesta.EchoAsync(oreo);
        Assert.Equal("Oreo", same.Sleeper);
    }

    [Fact]
    public async Task Siesta_WakeAsync_ReadsTheClassArgumentAfterTheSuspension()
    {
        await using var mylo = new Swing("Mylo");

        Assert.Equal("Mylo is up", await Siesta.WakeAsync(mylo));
    }

    [Fact]
    public async Task Siesta_SettleAsync_UnitReturnIsAPlainTask()
    {
        Task settling = Siesta.SettleAsync();

        await settling;
        Assert.Equal(typeof(Task), typeof(Siesta).GetMethod("SettleAsync")!.ReturnType);
    }

    [Fact]
    public async Task Siesta_NowAsync_NeverSuspends()
    {
        Assert.Equal(1, await Siesta.NowAsync());
    }

    [Fact]
    public async Task Siesta_AlarmAsync_FaultsWithTheKotlinException()
    {
        var ex = await Assert.ThrowsAnyAsync<InvalidOperationException>(() => Siesta.AlarmAsync());

        Assert.Equal("Oreo sat on the alarm clock", ex.Message);
        Assert.Equal("kotlin.IllegalStateException", ((IKotlinException)ex).KotlinType);
    }

    [Fact]
    public async Task Siesta_LingerAsync_CancelsThroughTheToken()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => Siesta.LingerAsync(cts.Token));
    }

    [Fact]
    public async Task Siesta_LingerWithAsync_PreCancelledToken_CancelsTheTask()
    {
        using var cts = new CancellationTokenSource();
        cts.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => Siesta.LingerWithAsync("Mylo", cts.Token));
    }

    [Fact]
    public void Siesta_MinutesForAsync_SpellsTheStaticSuspendShape()
    {
        MethodInfo minutes = typeof(Siesta).GetMethod("MinutesForAsync")!;

        Assert.True(minutes.IsStatic);
        Assert.Equal(typeof(Task<int>), minutes.ReturnType);
        ParameterInfo token = minutes.GetParameters().Last();
        Assert.Equal(typeof(CancellationToken), token.ParameterType);
        Assert.True(token.IsOptional);
        Assert.True(typeof(Siesta).IsAbstract && typeof(Siesta).IsSealed);
    }

    // (2) A default-named companion, static on the owner beside its instance members.

    [Fact]
    public async Task Swing_HangAsync_IsAStaticFactoryOnTheOwnerClass()
    {
        await using Swing swing = await Swing.HangAsync("Oreo");

        Assert.Equal("Oreo", swing.Sleeper);
    }

    [Fact]
    public async Task Swing_CarryAsync_RoundTripsTheTypeParameter()
    {
        Assert.Equal(3, await Swing.CarryAsync(3));
        Assert.Equal("Oreo", await Swing.CarryAsync("Oreo"));
        await using var mylo = new Swing("Mylo");
        await using Swing same = await Swing.CarryAsync(mylo);
        Assert.Equal("Mylo", same.Sleeper);
    }

    [Fact]
    public async Task Swing_SwayAsync_StaticAndInstanceOverloadsBothBind()
    {
        await using var oreo = new Swing("Oreo");

        Assert.Equal("Oreo sways", await oreo.SwayAsync());
        Assert.Equal("swayed 3 times", await Swing.SwayAsync(3));
        MethodInfo[] sways = typeof(Swing).GetMethods().Where(m => m.Name == "SwayAsync").ToArray();
        Assert.Equal(2, sways.Length);
        Assert.Single(sways, m => m.IsStatic);
    }

    [Fact]
    public async Task Swing_ReadyAsync_NeverSuspends()
    {
        Assert.Equal(2, await Swing.ReadyAsync());
    }

    [Fact]
    public async Task Swing_SnapAsync_FaultsWithTheKotlinException()
    {
        var ex = await Assert.ThrowsAnyAsync<ArgumentException>(() => Swing.SnapAsync());

        Assert.Equal("Mylo is too heavy for the swing", ex.Message);
        Assert.Equal("kotlin.IllegalArgumentException", ((IKotlinException)ex).KotlinType);
    }

    [Fact]
    public async Task Swing_CreakAsync_CancelsThroughTheToken()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => Swing.CreakAsync(cts.Token));
    }

    // (3) A named companion.

    [Fact]
    public async Task Quilt_KnitAsync_NamedCompanionIsStaticOnTheOwner()
    {
        using Quilt quilt = await Quilt.KnitAsync("brown and creamy");

        Assert.Equal("brown and creamy", quilt.Colour);
    }

    // (4) Suspend extensions.

    [Fact]
    public async Task Swing_RockAsync_ClassReceiverExtension()
    {
        await using var mylo = new Swing("Mylo");

        Assert.Equal("Mylo rocked 2 times", await mylo.RockAsync(2));
    }

    [Fact]
    public async Task Swing_TuckAsync_ExtensionOwnTypeParameter()
    {
        await using var oreo = new Swing("Oreo");
        await using var mylo = new Swing("Mylo");

        Assert.Equal(7, await oreo.TuckAsync(7));
        Assert.Equal("quilt", await oreo.TuckAsync("quilt"));
        await using Swing tucked = await oreo.TuckAsync(mylo);
        Assert.Equal("Mylo", tucked.Sleeper);
    }

    [Fact]
    public async Task Swing_OccupantAsync_NullableReceiver_KeepsNull()
    {
        await using var oreo = new Swing("Oreo");
        Swing? none = null;

        Assert.Equal("Oreo", await oreo.OccupantAsync());
        Assert.Equal("empty", await none.OccupantAsync());
    }

    [Fact]
    public async Task String_YawnAsync_StringReceiverExtension()
    {
        Assert.Equal("Mylo yawns", await "Mylo".YawnAsync());
    }

    [Fact]
    public async Task Int_NapMinutesAsync_PrimitiveReceiverExtension()
    {
        Assert.Equal("Oreo naps for 40 minutes", await 40.NapMinutesAsync("Oreo"));
        Assert.Equal("Mylo naps for -1 minutes", await (-1).NapMinutesAsync("Mylo"));
    }

    [Fact]
    public async Task Boolean_StirringAsync_BooleanReceiverCrossesItsOneByteWire()
    {
        Assert.Equal("Mylo is stirring", await true.StirringAsync());
        Assert.Equal("Mylo is out cold", await false.StirringAsync());
    }

    [Fact]
    public async Task Swing_FrayAsync_FaultsWithTheKotlinException()
    {
        await using var oreo = new Swing("Oreo");

        var ex = await Assert.ThrowsAnyAsync<InvalidOperationException>(() => oreo.FrayAsync());

        Assert.Equal("Oreo clawed through the swing", ex.Message);
    }

    [Fact]
    public async Task Swing_DangleAsync_CancelsThroughTheToken()
    {
        await using var mylo = new Swing("Mylo");
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => mylo.DangleAsync(cts.Token));
    }

    // (5) An enum body and an enum companion.

    [Fact]
    public async Task NapSpot_ClaimAsync_EnumReceiverCrossesByOrdinal()
    {
        Assert.Equal("Oreo claims the windowsill", await NapSpot.Windowsill.ClaimAsync("Oreo"));
        Assert.Equal("Mylo claims a cardboard box", await NapSpot.Box.ClaimAsync("Mylo"));
    }

    [Fact]
    public async Task NapSpot_NextAsync_EnumReturn()
    {
        Assert.Equal(NapSpot.Laundry, await NapSpot.Windowsill.NextAsync());
        Assert.Equal(NapSpot.Windowsill, await NapSpot.Box.NextAsync());
    }

    [Fact]
    public async Task NapSpot_StashAsync_MemberOwnTypeParameter()
    {
        await using var mylo = new Swing("Mylo");

        Assert.Equal("treat", await NapSpot.Laundry.StashAsync("treat"));
        await using Swing stashed = await NapSpot.Laundry.StashAsync(mylo);
        Assert.Equal("Mylo", stashed.Sleeper);
    }

    [Fact]
    public async Task NapSpot_EvictAsync_FaultsWithTheKotlinException()
    {
        var ex = await Assert.ThrowsAnyAsync<InvalidOperationException>(() => NapSpot.Laundry.EvictAsync());

        Assert.Equal("Mylo got evicted from the laundry basket", ex.Message);
    }

    [Fact]
    public async Task NapSpot_GuardAsync_CancelsThroughTheToken()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => NapSpot.Box.GuardAsync(cts.Token));
    }

    [Fact]
    public async Task NapSpotExtensions_FavouriteAsync_EnumCompanionMember()
    {
        Assert.Equal(NapSpot.Box, await NapSpotExtensions.FavouriteAsync());
    }

    // (6) Value-class members, over an `Int` and over a class handle.

    [Fact]
    public async Task NapLength_DoubledAsync_ValueClassReturn()
    {
        var nap = new NapLength(20);

        Assert.Equal(new NapLength(40), await nap.DoubledAsync());
    }

    [Fact]
    public async Task NapLength_DescribeAsync_StringArgument()
    {
        Assert.Equal("Oreo naps for 30 minutes", await new NapLength(30).DescribeAsync("Oreo"));
    }

    [Fact]
    public async Task NapLength_LabelAsync_MemberOwnTypeParameter()
    {
        var nap = new NapLength(15);

        Assert.Equal("Mylo: 15", await nap.LabelAsync("Mylo"));
        Assert.Equal("2: 15", await nap.LabelAsync(2));
    }

    [Fact]
    public async Task NapLength_CutAsync_FaultsWithTheKotlinException()
    {
        var ex = await Assert.ThrowsAnyAsync<InvalidOperationException>(() => new NapLength(90).CutAsync());

        Assert.Equal("Oreo never cuts a 90 minute nap short", ex.Message);
    }

    [Fact]
    public async Task NapLength_OversleepAsync_CancelsThroughTheToken()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => new NapLength(60).OversleepAsync(cts.Token));
    }

    [Fact]
    public async Task NapTag_ReadAsync_HandleUnderlyingReadAfterTheSuspension()
    {
        await using var oreo = new Swing("Oreo");

        Assert.Equal("tag: Oreo", await new NapTag(oreo).ReadAsync());
    }
}
