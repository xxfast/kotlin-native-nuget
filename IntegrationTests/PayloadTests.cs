using System.Reflection;
using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// Issue <a href="https://github.com/xxfast/kotlin-native-nuget/issues/248">#248</a> / ADR-151:
/// <c>kotlin.ByteArray</c> maps to <c>byte[]</c> and <c>ByteArray?</c> to <c>byte[]?</c> at every
/// ordinary position: property (val and var), primary-constructor parameter, method parameter and
/// return. The wire is the collection row (one <c>StableRef</c> handle, a count plus a
/// <c>memcpy</c>, the null pointer for <c>null</c>), so a <c>byte[]</c> is a fresh copy on every
/// crossing and never a view into the other side's memory.
/// <para>
/// Today <c>ByteArray</c> is an unmapped stdlib type, so <see cref="Payload"/> has no
/// <c>Data</c>, no usable constructor and none of the methods, and <c>PayloadKt</c> does not
/// exist at all. This file cannot compile until the feature ships.
/// </para>
/// <para>
/// Oreo's collar transmitter sends three bytes, Mylo's sends nothing at all.
/// </para>
/// </summary>
public class PayloadTests
{
    // Oreo's three bytes. Small, distinct, and asymmetric, so a reversal is visible.
    private static byte[] Oreo() => new byte[] { 1, 2, 3 };

    // --- {1,2,3} through the constructor and the property getter ---

    [Fact]
    public void DataClass_ByteArrayProperty_RoundTripsThroughCtor()
    {
        using var payload = new Payload(7, Oreo());

        Assert.Equal(new byte[] { 1, 2, 3 }, payload.Data);
    }

    [Fact]
    public void DataClass_PrimitiveComponent_SurvivesBesideTheByteArray()
    {
        using var payload = new Payload(7, Oreo());

        Assert.Equal(7, payload.Code);
    }

    [Fact]
    public void Data_PropertyGetter_TypeIsByteArray()
    {
        using var payload = new Payload(7, Oreo());

        // Compile-time contract: byte[], not List<sbyte> and not IntPtr.
        byte[] data = payload.Data;

        Assert.Equal(3, data.Length);
    }

    // --- The var property's setter ---

    [Fact]
    public void Data_PropertySetter_ReplacesTheKotlinArray()
    {
        using var payload = new Payload(1, new byte[] { 1 });

        payload.Data = new byte[] { 4, 5 };

        Assert.Equal(new byte[] { 4, 5 }, payload.Data);
    }

    [Fact]
    public void Data_PropertySetter_AcceptsAnEmptyArray()
    {
        using var payload = new Payload(1, Oreo());

        payload.Data = Array.Empty<byte>();

        Assert.Empty(payload.Data);
    }

    // --- Top-level function: parameter and return ---

    [Fact]
    public void Reverse_TopLevelFunction_ReturnsTheReversedBytes()
    {
        Assert.Equal(new byte[] { 3, 2, 1 }, PayloadKt.Reverse(Oreo()));
    }

    [Fact]
    public void Reverse_TopLevelFunction_LeavesTheCallersArrayUntouched()
    {
        byte[] input = Oreo();

        PayloadKt.Reverse(input);

        Assert.Equal(new byte[] { 1, 2, 3 }, input);
    }

    // --- The empty array is empty, never null ---

    [Fact]
    public void Empty_TopLevelReturn_IsAnEmptyArrayNotNull()
    {
        byte[] result = PayloadKt.Empty();

        Assert.NotNull(result);
        Assert.Empty(result);
    }

    [Fact]
    public void Reverse_OfAnEmptyArray_IsEmptyNotNull()
    {
        byte[] result = PayloadKt.Reverse(Array.Empty<byte>());

        Assert.NotNull(result);
        Assert.Empty(result);
    }

    [Fact]
    public void Ctor_WithAnEmptyArray_ReadsBackEmptyNotNull()
    {
        // Mylo's collar sends nothing. Zero bytes is a value, not an absence.
        using var payload = new Payload(0, Array.Empty<byte>());

        Assert.NotNull(payload.Data);
        Assert.Empty(payload.Data);
    }

    // --- ByteArray? at every nullable position ---

    [Fact]
    public void Checksum_NullableProperty_IsNullWhenThePayloadIsEmpty()
    {
        using var payload = new Payload(0, Array.Empty<byte>());

        // Compile-time contract: byte[]?, not byte[].
        byte[]? checksum = payload.Checksum;

        Assert.Null(checksum);
    }

    [Fact]
    public void Checksum_NullableProperty_CarriesTheValueWhenPresent()
    {
        using var payload = new Payload(7, Oreo());

        Assert.Equal(new byte[] { 6 }, payload.Checksum);
    }

    [Fact]
    public void Maybe_InstanceMethod_RoundTripsNull()
    {
        using var payload = new Payload(7, Oreo());

        Assert.Null(payload.Maybe(null));
    }

    [Fact]
    public void Maybe_InstanceMethod_RoundTripsAValue()
    {
        using var payload = new Payload(7, Oreo());

        Assert.Equal(new byte[] { 9 }, payload.Maybe(new byte[] { 9 }));
    }

    [Fact]
    public void Maybe_InstanceMethod_RoundTripsAnEmptyArrayAsEmptyNotNull()
    {
        using var payload = new Payload(7, Oreo());

        byte[]? echoed = payload.Maybe(Array.Empty<byte>());

        Assert.NotNull(echoed);
        Assert.Empty(echoed!);
    }

    [Fact]
    public void Maybe_TopLevelFunction_RoundTripsNull()
    {
        Assert.Null(PayloadKt.Maybe(null));
    }

    [Fact]
    public void Maybe_TopLevelFunction_RoundTripsAValue()
    {
        Assert.Equal(new byte[] { 9 }, PayloadKt.Maybe(new byte[] { 9 }));
    }

    // --- Sign: 0xFF and 0x80 are values, not negatives ---

    [Fact]
    public void HighBytes_SurviveTheConstructorAndGetterUnchanged()
    {
        // Irrelevant to a memcpy, load-bearing the day a cell swaps to an sbyte element loop.
        using var payload = new Payload(1, new byte[] { 0xFF, 0x80, 0x00, 0x7F });

        Assert.Equal(new byte[] { 0xFF, 0x80, 0x00, 0x7F }, payload.Data);
    }

    [Fact]
    public void HighBytes_SurviveTheTopLevelRoundTripUnchanged()
    {
        Assert.Equal(new byte[] { 0x7F, 0x00, 0x80, 0xFF }, PayloadKt.Reverse(new byte[] { 0xFF, 0x80, 0x00, 0x7F }));
    }

    // --- A megabyte, both ways ---

    [Fact]
    public void OneMebibyte_RoundTripsThroughReverseByteForByte()
    {
        byte[] input = new byte[1024 * 1024];
        for (int i = 0; i < input.Length; i++) input[i] = (byte)(i % 251);

        byte[] reversed = PayloadKt.Reverse(input);

        Assert.Equal(input.Length, reversed.Length);
        Assert.Equal(input.Reverse().ToArray(), reversed);
    }

    [Fact]
    public void OneMebibyte_RoundTripsThroughTheProperty()
    {
        byte[] input = new byte[1024 * 1024];
        for (int i = 0; i < input.Length; i++) input[i] = (byte)(i % 251);

        using var payload = new Payload(1, input);

        Assert.Equal(input, payload.Data);
    }

    // --- The copy contract: a byte[] is a copy on every crossing, never a view ---

    [Fact]
    public void MutatingTheInputAfterTheCtor_DoesNotReachKotlin()
    {
        byte[] input = Oreo();
        using var payload = new Payload(7, input);

        input[0] = 99;

        Assert.Equal(new byte[] { 1, 2, 3 }, payload.Data);
    }

    [Fact]
    public void MutatingTheInputAfterTheSetter_DoesNotReachKotlin()
    {
        using var payload = new Payload(1, new byte[] { 1 });
        byte[] next = new byte[] { 4, 5 };

        payload.Data = next;
        next[0] = 0;

        Assert.Equal(new byte[] { 4, 5 }, payload.Data);
    }

    [Fact]
    public void MutatingTheArrayTheGetterHandedOut_DoesNotReachKotlin()
    {
        using var payload = new Payload(7, Oreo());

        byte[] handedOut = payload.Data;
        handedOut[0] = 99;

        Assert.Equal(new byte[] { 1, 2, 3 }, payload.Data);
    }

    [Fact]
    public void MutatingTheArrayAFunctionReturned_DoesNotReachKotlin()
    {
        byte[] returned = PayloadKt.Reverse(Oreo());
        returned[0] = 99;

        Assert.Equal(new byte[] { 3, 2, 1 }, PayloadKt.Reverse(Oreo()));
    }

    [Fact]
    public void EachGetterRead_HandsOutADistinctArray()
    {
        using var payload = new Payload(7, Oreo());

        Assert.NotSame(payload.Data, payload.Data);
    }

    // --- Primitive parameters in, ByteArray out ---

    [Fact]
    public void Slice_ReturnsTheRequestedRange()
    {
        using var payload = new Payload(7, new byte[] { 1, 2, 3, 4, 5 });

        Assert.Equal(new byte[] { 2, 3, 4 }, payload.Slice(1, 4));
    }

    [Fact]
    public void Slice_OfAnEmptyRange_IsEmptyNotNull()
    {
        using var payload = new Payload(7, Oreo());

        byte[] slice = payload.Slice(1, 1);

        Assert.NotNull(slice);
        Assert.Empty(slice);
    }

    // =============================================================================================
    // ROADMAP Phase 4: `ByteArray` as a COLLECTION COMPONENT, the position ADR-151 deferred.
    //
    // A Kotlin `List<ByteArray>` binds as `IReadOnlyList<byte[]>`, `MutableList<ByteArray>` as
    // `IList<byte[]>`, `Map<String, ByteArray>` as `IReadOnlyDictionary<string, byte[]>` and
    // `MutableMap<String, ByteArray>` as `IDictionary<string, byte[]>` - the same spellings every
    // other component kind gets (docs/topics/collections.md). Each element crosses as its own
    // `StableRef` handle in the ordinary component slot, so an element is a fresh copy per crossing
    // exactly as a standalone `byte[]` is.
    //
    // Measured 2026-09-20 BEFORE the feature shipped, and it was NOT one uniform skip - three
    // separate defects, all fixed in the same change:
    //  - the parameter-side members (SpliceBursts, WeighBursts, CountCollarBytes, WeighLitterGrid)
    //    and every CollarLog member dropped with `[nuget:SKIPPED_UNSUPPORTED_TYPE]` (BYTE_ARRAY),
    //    which is the item proper;
    //  - the collection-RETURN members were still RENDERED into Interop.cs despite their skip
    //    warning, spelled `IReadOnlyList<ByteArray>` / `IReadOnlyList<List>` against types nothing
    //    declares (CS0246), with the `ByteArray` parameter degraded to `IntPtr`. That hole was
    //    general (any refused component, e.g. `List<Instant>`) and is now closed in
    //    `hasLegacyGenericReturnRoute()`: skip means absent;
    //  - `CollarStream.pulses` (`Flow<ByteArray>`) crashed `packNuget` before any of this.
    //
    // Oreo's collar transmits one burst per zoomie; Mylo's transmits a lot of nothing, at length.
    // =============================================================================================

    private const BindingFlags PublicInstance = BindingFlags.Public | BindingFlags.Instance;
    private const BindingFlags PublicStatic = BindingFlags.Public | BindingFlags.Static;

    /// <summary>
    /// Resolved by name, never by <c>typeof</c>, so an absence fact about a member of these owners
    /// reports a result instead of breaking this project's compile.
    /// </summary>
    private static Type? CatType(string simpleName) =>
        typeof(Payload).Assembly.GetType($"TestLibrary.Cat.{simpleName}");

    // --- List<ByteArray> at a return ---

    [Fact]
    public void BurstChunks_ListOfByteArrayReturn_IsByteExactPerChunk()
    {
        IReadOnlyList<byte[]> chunks = PayloadKt.BurstChunks(new byte[] { 1, 2, 3, 4, 5 }, 2);

        Assert.Equal(3, chunks.Count);
        Assert.Equal(new byte[] { 1, 2 }, chunks[0]);
        Assert.Equal(new byte[] { 3, 4 }, chunks[1]);
        Assert.Equal(new byte[] { 5 }, chunks[2]);
    }

    [Fact]
    public void BurstChunks_HighAndZeroBytes_SurviveTheElementCrossing()
    {
        // Kotlin's Byte is signed and C#'s byte is not: 0xFF must arrive as 255, not as -1 or 0.
        IReadOnlyList<byte[]> chunks = PayloadKt.BurstChunks(new byte[] { 0x00, 0xFF, 0x7F, 0x80 }, 2);

        Assert.Equal(new byte[] { 0x00, 0xFF }, chunks[0]);
        Assert.Equal(new byte[] { 0x7F, 0x80 }, chunks[1]);
    }

    [Fact]
    public void BurstChunks_EmptyInput_IsAnEmptyListNotAListHoldingAnEmptyArray()
    {
        IReadOnlyList<byte[]> chunks = PayloadKt.BurstChunks(Array.Empty<byte>(), 2);

        Assert.Empty(chunks);
    }

    [Fact]
    public void BurstChunks_EachElement_IsADetachedCopy()
    {
        IReadOnlyList<byte[]> chunks = PayloadKt.BurstChunks(new byte[] { 1, 2, 3, 4 }, 2);

        chunks[0][0] = 99;

        Assert.Equal(new byte[] { 1, 2 }, PayloadKt.BurstChunks(new byte[] { 1, 2, 3, 4 }, 2)[0]);
    }

    // --- List<ByteArray> at a parameter ---

    [Fact]
    public void SpliceBursts_ListOfByteArrayParameter_ConcatenatesEveryElementInOrder()
    {
        byte[] spliced = PayloadKt.SpliceBursts(new[] { new byte[] { 1, 2 }, new byte[] { 3 } });

        Assert.Equal(new byte[] { 1, 2, 3 }, spliced);
    }

    [Fact]
    public void SpliceBursts_WithAZeroLengthElementInTheMiddle_KeepsTheSurroundingBytes()
    {
        // A zero-length element is a value, not an absence: it must not collapse into null and it
        // must not take the `addressOf(0)` throw with it.
        byte[] spliced = PayloadKt.SpliceBursts(
            new[] { new byte[] { 1 }, Array.Empty<byte>(), new byte[] { 0x00, 0xFF } });

        Assert.Equal(new byte[] { 1, 0x00, 0xFF }, spliced);
    }

    [Fact]
    public void SpliceBursts_OfAnEmptyList_IsAnEmptyArray()
    {
        byte[] spliced = PayloadKt.SpliceBursts(Array.Empty<byte[]>());

        Assert.NotNull(spliced);
        Assert.Empty(spliced);
    }

    [Fact]
    public void SpliceBursts_With64KiBInOneElement_KeepsEveryByte()
    {
        byte[] big = new byte[64 * 1024];
        for (int i = 0; i < big.Length; i++) big[i] = (byte)(i % 251);

        byte[] spliced = PayloadKt.SpliceBursts(new[] { new byte[] { 7 }, big });

        Assert.Equal(1 + big.Length, spliced.Length);
        Assert.Equal(big, spliced.Skip(1).ToArray());
    }

    [Fact]
    public void SpliceBursts_LeavesTheCallersElementArraysUntouched()
    {
        byte[] first = { 1, 2 };

        PayloadKt.SpliceBursts(new[] { first, new byte[] { 3 } });

        Assert.Equal(new byte[] { 1, 2 }, first);
    }

    // --- MutableList<ByteArray>: the IList<byte[]> spelling of the same wire ---

    [Fact]
    public void WeighBursts_MutableListOfByteArrayParameter_SumsTheElementLengths()
    {
        IList<byte[]> parts = new List<byte[]> { new byte[] { 1, 2 }, Array.Empty<byte>(), new byte[] { 3 } };

        Assert.Equal(3, PayloadKt.WeighBursts(parts));
    }

    // --- Map<String, ByteArray> value, in and out ---

    [Fact]
    public void RewindCollars_MapValueParameterAndReturn_ReversesEachValue()
    {
        IReadOnlyDictionary<string, byte[]> rewound = PayloadKt.RewindCollars(
            new Dictionary<string, byte[]>
            {
                ["oreo"] = new byte[] { 1, 2, 3 },
                ["mylo"] = Array.Empty<byte>(),
            });

        Assert.Equal(2, rewound.Count);
        Assert.Equal(new byte[] { 3, 2, 1 }, rewound["oreo"]);
        Assert.Empty(rewound["mylo"]);
    }

    [Fact]
    public void RewindCollars_HighBytesInAMapValue_SurviveUnchanged()
    {
        IReadOnlyDictionary<string, byte[]> rewound = PayloadKt.RewindCollars(
            new Dictionary<string, byte[]> { ["oreo"] = new byte[] { 0x00, 0x80, 0xFF } });

        Assert.Equal(new byte[] { 0xFF, 0x80, 0x00 }, rewound["oreo"]);
    }

    [Fact]
    public void CountCollarBytes_MutableMapValueParameter_SumsEveryValuesLength()
    {
        IDictionary<string, byte[]> chips = new Dictionary<string, byte[]>
        {
            ["oreo"] = new byte[] { 1, 2, 3 },
            ["mylo"] = new byte[] { 4 },
        };

        Assert.Equal(4, PayloadKt.CountCollarBytes(chips));
    }

    // --- Nested: List<List<ByteArray>>, which the component recursion is meant to give for free ---

    [Fact]
    public void LitterGrid_NestedListOfListOfByteArray_ReturnsEveryInnerElement()
    {
        IReadOnlyList<IReadOnlyList<byte[]>> grid = PayloadKt.LitterGrid();

        Assert.Equal(2, grid.Count);
        Assert.Equal(new byte[] { 1, 2 }, grid[0][0]);
        Assert.Empty(grid[0][1]);
        Assert.Equal(new byte[] { 0x00, 0xFF, 0x7F }, grid[1][0]);
    }

    [Fact]
    public void WeighLitterGrid_NestedListOfListOfByteArrayParameter_SumsEveryInnerLength()
    {
        int weight = PayloadKt.WeighLitterGrid(
            new[]
            {
                new[] { new byte[] { 1, 2 }, Array.Empty<byte>() },
                new[] { new byte[] { 0xFF } },
            });

        Assert.Equal(3, weight);
    }

    // --- Nullable component, read side ---

    [Fact]
    public void PatchySignals_NullableElementReturn_CarriesTheNullInTheMiddle()
    {
        IReadOnlyList<byte[]?> signals = PayloadKt.PatchySignals();

        Assert.Equal(4, signals.Count);
        Assert.Equal(new byte[] { 1, 2 }, signals[0]);
        Assert.Null(signals[1]);
        Assert.NotNull(signals[2]);
        Assert.Empty(signals[2]!);
        Assert.Equal(new byte[] { 0x00, 0xFF }, signals[3]);
    }

    // --- Nullable component, WRITE side: the load-bearing trap ---

    [Fact]
    public void MissingSignals_NullElementsInbound_ArriveAsNullsAtTheRightIndices()
    {
        // Projecting a null element as IntPtr.Zero that the fill loop then disposes takes the host
        // down, so this is a real round trip: Kotlin reports WHICH indices arrived null. A shim
        // that drops nulls (or shifts the remaining elements up) reports the wrong indices rather
        // than passing by accident.
        IReadOnlyList<int> missing = PayloadKt.MissingSignals(
            new List<byte[]?> { new byte[] { 1 }, null, Array.Empty<byte>(), null });

        Assert.Equal(new[] { 1, 3 }, missing);
    }

    [Fact]
    public void MissingSignals_AllNulls_ReportsEveryIndex()
    {
        IReadOnlyList<int> missing = PayloadKt.MissingSignals(new List<byte[]?> { null, null });

        Assert.Equal(new[] { 0, 1 }, missing);
    }

    [Fact]
    public void MissingSignals_NoNulls_ReportsNothing()
    {
        IReadOnlyList<int> missing = PayloadKt.MissingSignals(
            new List<byte[]?> { new byte[] { 1 }, Array.Empty<byte>() });

        Assert.Empty(missing);
    }

    // --- Class positions: constructor parameter, property getter and setter, method ---

    [Fact]
    public void CollarLog_Ctor_TakesAListOfByteArrayAndAMapOfByteArrayValues()
    {
        using var log = new CollarLog(
            "Oreo",
            new[] { new byte[] { 1, 2 }, Array.Empty<byte>() },
            new Dictionary<string, byte[]> { ["chip"] = new byte[] { 0xFF } });

        Assert.Equal("Oreo", log.CatName);
        Assert.Equal(2, log.Bursts.Count);
        Assert.Equal(new byte[] { 1, 2 }, log.Bursts[0]);
        Assert.Equal(new byte[] { 0xFF }, log.Chips["chip"]);
    }

    [Fact]
    public void CollarLog_ListPropertySetter_ReplacesTheKotlinList()
    {
        using var log = new CollarLog("Oreo", new[] { new byte[] { 1 } }, new Dictionary<string, byte[]>());

        log.Bursts = new[] { new byte[] { 4, 5 }, new byte[] { 0x00, 0xFF } };

        Assert.Equal(2, log.Bursts.Count);
        Assert.Equal(new byte[] { 4, 5 }, log.Bursts[0]);
        Assert.Equal(new byte[] { 0x00, 0xFF }, log.Bursts[1]);
    }

    [Fact]
    public void CollarLog_MapPropertySetter_ReplacesTheKotlinMap()
    {
        using var log = new CollarLog("Mylo", Array.Empty<byte[]>(), new Dictionary<string, byte[]>());

        log.Chips = new Dictionary<string, byte[]> { ["collar"] = new byte[] { 7, 8 } };

        Assert.Equal(new byte[] { 7, 8 }, log.Chips["collar"]);
    }

    [Fact]
    public void CollarLog_Rewound_MethodReturnsAListOfByteArray()
    {
        using var log = new CollarLog(
            "Oreo",
            new[] { new byte[] { 1, 2, 3 }, new byte[] { 0x00, 0xFF } },
            new Dictionary<string, byte[]>());

        IReadOnlyList<byte[]> rewound = log.Rewound();

        Assert.Equal(new byte[] { 3, 2, 1 }, rewound[0]);
        Assert.Equal(new byte[] { 0xFF, 0x00 }, rewound[1]);
    }

    [Fact]
    public void CollarLog_Append_MethodTakesAListOfByteArray()
    {
        using var log = new CollarLog("Oreo", new[] { new byte[] { 1 } }, new Dictionary<string, byte[]>());

        int total = log.Append(new[] { new byte[] { 2, 3 }, Array.Empty<byte>() });

        Assert.Equal(3, total);
        Assert.Equal(3, log.Bursts.Count);
    }

    // --- Legacy async routes: the same component gates ---

    [Fact]
    public async Task BurstsAsync_SuspendReturnOfListOfByteArray_RoundTrips()
    {
        using var stream = new CollarStream();

        IReadOnlyList<byte[]> bursts = await stream.BurstsAsync();

        Assert.Equal(3, bursts.Count);
        Assert.Equal(new byte[] { 1, 2 }, bursts[0]);
        Assert.Empty(bursts[1]);
        Assert.Equal(new byte[] { 0x00, 0xFF }, bursts[2]);
    }

    [Fact]
    public async Task Ticks_FlowOfListOfByteArray_MaterialisesEachEmission()
    {
        using var stream = new CollarStream();
        var seen = new List<byte[][]>();

        await foreach (IReadOnlyList<byte[]> page in stream.Ticks) seen.Add(page.ToArray());

        Assert.Equal(2, seen.Count);
        Assert.Equal(new byte[] { 1 }, seen[0][0]);
        Assert.Equal(new byte[] { 2, 3 }, seen[1][0]);
        Assert.Empty(seen[1][1]);
    }

    // --- The two BARE-ByteArray async shapes the research memo never checked ---
    //
    // Probed by reflection on purpose: if either is a named skip today the member is simply absent,
    // and a direct call would break this project's compile instead of reporting which of the two it
    // is. The type arguments (Task<byte[]>, KotlinFlow<byte[]>) compile either way.

    [Fact]
    public async Task SnapshotAsync_BareByteArrayAtASuspendReturn_BindsAsTaskOfByteArray()
    {
        Type? streamType = CatType("CollarStream");
        Assert.NotNull(streamType);
        MethodInfo? snapshot = streamType!.GetMethod("SnapshotAsync", PublicInstance);
        Assert.NotNull(snapshot);
        Assert.Equal(typeof(Task<byte[]>), snapshot!.ReturnType);

        using var stream = (IDisposable)Activator.CreateInstance(streamType)!;
        object?[] args = snapshot.GetParameters()
            .Select(parameter => parameter.ParameterType == typeof(CancellationToken)
                ? (object?)CancellationToken.None
                : null)
            .ToArray();

        byte[] bytes = await (Task<byte[]>)snapshot.Invoke(stream, args)!;

        Assert.Equal(new byte[] { 0x00, 0xFF }, bytes);
    }

    [Fact]
    public async Task Pulses_BareByteArrayAsAFlowElement_BindsAsKotlinFlowOfByteArray()
    {
        Type? streamType = CatType("CollarStream");
        Assert.NotNull(streamType);
        PropertyInfo? pulses = streamType!.GetProperty("Pulses", PublicInstance);
        Assert.NotNull(pulses);
        Assert.Equal(typeof(KotlinFlow<byte[]>), pulses!.PropertyType);

        using var stream = (IDisposable)Activator.CreateInstance(streamType)!;
        var seen = new List<byte[]>();

        await foreach (byte[] pulse in (KotlinFlow<byte[]>)pulses.GetValue(stream)!) seen.Add(pulse);

        Assert.Equal(2, seen.Count);
        Assert.Equal(new byte[] { 1 }, seen[0]);
        Assert.Equal(new byte[] { 0x7F, 0xFF }, seen[1]);
    }

    // Typed rather than reflected: the nullable twin is a bound member, so it can be enumerated
    // directly. The guard is the runtime one below, not the compile: a dropped `?` declares
    // `KotlinFlow<byte[]>` with an unguarded `NugetMarshal.ReadBytes(IntPtr.Zero)`, and the middle
    // emission faults the enumerator instead of arriving as the null `Assert.Null` expects.
    [Fact]
    public async Task PatchyPulses_NullableByteArrayOnAPlainFlow_YieldsTheNullInTheMiddle()
    {
        using var stream = new CollarStream();
        var seen = new List<byte[]?>();

        await foreach (byte[]? pulse in stream.PatchyPulses) seen.Add(pulse);

        Assert.Equal(3, seen.Count);
        Assert.Equal(new byte[] { 2 }, seen[0]);
        Assert.Null(seen[1]);
        Assert.Equal(new byte[] { 3, 4 }, seen[2]);
    }

    // --- DECLINED shapes: they stay named skips, with no C# member at all ---
    //
    // Arrays compare by identity in both languages and every crossing copies, so a `Set<ByteArray>`
    // membership test or a `Map<ByteArray, V>` lookup could never succeed. Asserted as absence, not
    // on the diagnostic sentence (the hint's wording is the implementer's to choose).

    [Fact]
    public void PayloadKt_KeepsItsControlMember_SoTheAbsencesBelowProveSomething()
    {
        Assert.NotNull(CatType("PayloadKt")?.GetMethod("Reverse", PublicStatic));
    }

    [Fact]
    public void UniquePatches_SetOfByteArray_HasNoCSharpMember()
    {
        Assert.Null(CatType("PayloadKt")?.GetMethod("UniquePatches", PublicStatic));
    }

    [Fact]
    public void ByFingerprint_ByteArrayMapKey_HasNoCSharpMember()
    {
        Assert.Null(CatType("PayloadKt")?.GetMethod("ByFingerprint", PublicStatic));
    }
}
