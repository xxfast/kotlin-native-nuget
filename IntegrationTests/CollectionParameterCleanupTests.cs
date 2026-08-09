using System.Collections;
using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// The generated shims build temporary native handles for collection arguments and
/// release them after the call. When the Kotlin side throws, the release must still
/// happen. The leak is invisible from C# (there is no live-handle export), so these
/// tests are the behavioural net: the mapped exception must surface cleanly, and
/// hammering the throwing path must not destabilise the process.
///
/// Oreo and Mylo's treat ledger never balances, which is convenient.
/// </summary>
public class CollectionParameterCleanupTests
{
    private static readonly string[] Entries = ["Oreo: 3 treats", "Mylo: 5 treats"];
    private static readonly HashSet<string> Labels = ["biscuit", "milo"];

    [Fact]
    public void Audit_ThrowsMappedKotlinException()
    {
        using var auditor = new Auditor();
        var ex = Assert.ThrowsAny<InvalidOperationException>(() => auditor.Audit(Entries));
        Assert.IsType<KotlinInvalidOperationException>(ex);
        Assert.Equal("audit failed: 2 entries do not balance", ex.Message);
        Assert.Equal("kotlin.IllegalStateException", ((IKotlinException)ex).KotlinType);
    }

    [Fact]
    public void Audit_RepeatedThrows_StaySane()
    {
        using var auditor = new Auditor();
        for (int i = 0; i < 50; i++)
        {
            var ex = Assert.ThrowsAny<InvalidOperationException>(() => auditor.Audit(Entries));
            Assert.Equal("audit failed: 2 entries do not balance", ex.Message);
        }
    }

    [Fact]
    public void Audit_EmptyList_ThrowsMappedKotlinException()
    {
        using var auditor = new Auditor();
        var ex = Assert.ThrowsAny<InvalidOperationException>(
            () => auditor.Audit(Array.Empty<string>()));
        Assert.Equal("audit failed: 0 entries do not balance", ex.Message);
    }

    [Fact]
    public void CrossCheck_TwoCollectionKinds_ThrowsMappedKotlinException()
    {
        using var auditor = new Auditor();
        var ex = Assert.ThrowsAny<InvalidOperationException>(
            () => auditor.CrossCheck(Entries, Labels));
        Assert.IsType<KotlinInvalidOperationException>(ex);
        Assert.Equal("cross-check failed: 2 entries, 2 labels", ex.Message);
    }

    [Fact]
    public void CrossCheck_RepeatedThrows_StaySane()
    {
        using var auditor = new Auditor();
        for (int i = 0; i < 50; i++)
        {
            var ex = Assert.ThrowsAny<InvalidOperationException>(
                () => auditor.CrossCheck(Entries, Labels));
            Assert.Equal("cross-check failed: 2 entries, 2 labels", ex.Message);
        }
    }

    [Fact]
    public void Ledger_EmptyEntries_ConstructorThrowsMappedKotlinException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => new Ledger(Array.Empty<string>()));
        Assert.IsType<KotlinArgumentException>(ex);
        Assert.Equal("ledger needs entries", ex.Message);
    }

    [Fact]
    public void Ledger_RepeatedConstructorThrows_StaySane()
    {
        for (int i = 0; i < 50; i++)
        {
            Assert.ThrowsAny<ArgumentException>(() => new Ledger(Array.Empty<string>()));
        }
    }

    [Fact]
    public void Ledger_ValidEntries_StillConstructs()
    {
        using var ledger = new Ledger(Entries);
        Assert.Equal(2, ledger.Count);
    }

    [Fact]
    public void Ledger_ConstructsAfterFailedConstructions()
    {
        for (int i = 0; i < 50; i++)
        {
            Assert.ThrowsAny<ArgumentException>(() => new Ledger(Array.Empty<string>()));
        }

        using var ledger = new Ledger(Entries);
        Assert.Equal(2, ledger.Count);
    }

    /// <summary>
    /// CreateList enumerates the argument while filling the native list. A collection
    /// that throws mid-enumeration must surface its own C# exception unmasked, not a
    /// KotlinException, and not a wrapper.
    /// </summary>
    [Fact]
    public void Audit_CollectionThrowsMidEnumeration_SurfacesOriginalException()
    {
        using var auditor = new Auditor();
        var ex = Assert.Throws<GrumpyCatException>(
            () => auditor.Audit(new GrumpyList("Oreo: 3 treats")));
        Assert.Equal("Oreo swatted the ledger off the table", ex.Message);
        Assert.IsNotAssignableFrom<IKotlinException>(ex);
    }

    [Fact]
    public void CrossCheck_SecondCollectionThrowsMidEnumeration_SurfacesOriginalException()
    {
        using var auditor = new Auditor();
        var ex = Assert.Throws<GrumpyCatException>(
            () => auditor.CrossCheck(Entries, new GrumpySet("biscuit")));
        Assert.Equal("Oreo swatted the ledger off the table", ex.Message);
    }

    [Fact]
    public void Ledger_CollectionThrowsMidEnumeration_SurfacesOriginalException()
    {
        Assert.Throws<GrumpyCatException>(
            () => new Ledger(new GrumpyList("Oreo: 3 treats")));
    }

    [Fact]
    public void Audit_RepeatedMidEnumerationThrows_StaySane()
    {
        using var auditor = new Auditor();
        for (int i = 0; i < 50; i++)
        {
            Assert.Throws<GrumpyCatException>(
                () => auditor.Audit(new GrumpyList("Oreo: 3 treats")));
        }

        var mapped = Assert.ThrowsAny<InvalidOperationException>(() => auditor.Audit(Entries));
        Assert.Equal("audit failed: 2 entries do not balance", mapped.Message);
    }

    public sealed class GrumpyCatException(string message) : Exception(message);

    /// <summary>
    /// Yields one element, then throws on the second MoveNext, i.e. after the shim has
    /// already created the native list handle and added an element to it.
    /// </summary>
    private sealed class GrumpyList(string first) : IReadOnlyList<string>
    {
        public int Count => 2;

        public string this[int index] => first;

        public IEnumerator<string> GetEnumerator()
        {
            yield return first;
            throw new GrumpyCatException("Oreo swatted the ledger off the table");
        }

        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    }

    private sealed class GrumpySet(string first) : IReadOnlySet<string>
    {
        private readonly HashSet<string> _backing = [first, "milo"];

        public int Count => _backing.Count;

        public IEnumerator<string> GetEnumerator()
        {
            yield return first;
            throw new GrumpyCatException("Oreo swatted the ledger off the table");
        }

        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

        public bool Contains(string item) => _backing.Contains(item);

        public bool IsProperSubsetOf(IEnumerable<string> other) => _backing.IsProperSubsetOf(other);

        public bool IsProperSupersetOf(IEnumerable<string> other) => _backing.IsProperSupersetOf(other);

        public bool IsSubsetOf(IEnumerable<string> other) => _backing.IsSubsetOf(other);

        public bool IsSupersetOf(IEnumerable<string> other) => _backing.IsSupersetOf(other);

        public bool Overlaps(IEnumerable<string> other) => _backing.Overlaps(other);

        public bool SetEquals(IEnumerable<string> other) => _backing.SetEquals(other);
    }
}
