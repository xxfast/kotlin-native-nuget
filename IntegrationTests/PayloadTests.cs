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
}
