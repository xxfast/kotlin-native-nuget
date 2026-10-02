// One shape per RirDiagnosticKind the reverse bridge can emit without failing generation, read by
// RirDiagnosticCoverageTest through the real NugetMetadataReader. The fatal kinds live in
// Fatal.cs. Each shape names the kind it produces; a shape may produce others too.
using System;
using System.Collections.Generic;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Diagnostics.EveryKind;

// SKIPPED_REF_STRUCT (on Lab.UseScratch)
public ref struct Scratch
{
    public int Value;
}

// SKIPPED_UNSUPPORTED_STRUCT_TYPE, and SKIPPED_UNSUPPORTED_STRUCT on Lab.Overstuffed
public readonly struct Overstuffed
{
    private readonly int _hidden;

    public Overstuffed(int visible)
    {
        Visible = visible;
        _hidden = visible;
    }

    public int Visible { get; }
}

// SKIPPED_UNSUPPORTED_ENUM (on Lab.Permissions)
[Flags]
public enum Permissions
{
    Read = 1,
    Write = 2,
}

// INFO_ENUM_ENTRY_KEPT_VERBATIM
public enum Status
{
    HTTPStatus,
    HttpStatus,
}

// SKIPPED_COLLECTION_POSITION: a collection on a struct member
public struct Point
{
    public Point(int x) { X = x; }

    public int X { get; }

    public List<int> Neighbours() => new();
}

// SKIPPED_NESTED_TYPE
public class Outer
{
    public int Value { get; set; }

    public class Inner
    {
    }
}

// SKIPPED_EMPTY_INTERFACE
public interface IMarker
{
}

// SKIPPED_GENERIC_INTERFACE
public interface ISource<T>
{
    T Next();
}

// SKIPPED_DEFAULT_INTERFACE_METHOD, SKIPPED_INTERFACE_STATIC_MEMBER, SKIPPED_KOTLIN_BRIDGE (Sum's
// arity is outside the slot vocabulary) and SKIPPED_COLLECTION_POSITION (Items)
public interface ICalculator
{
    int Add(int a, int b);

    int Sum(int a, int b, int c);

    List<int> Items();

    int Twice(int a) => a * 2;

    static int Zero => 0;
}

// INFO_UNINSTANTIATED_GENERIC_TYPE
public class Lonely<T>
{
    public T? Value { get; set; }
}

// SKIPPED_NULLABLE_TYPE_PARAMETER (Peek)
public class Cell<T> where T : class
{
    public Cell(T value) { Value = value; }

    public T Value { get; }

    public T? Peek() => Value;
}

// SKIPPED_AMBIGUOUS_GENERIC_CONSTRUCTOR: Wrap<int> and Wrap<string> both erase to (Int)
public class Wrap<T>
{
    public Wrap(int seed) { Seed = seed; }

    public int Seed { get; }
}

public delegate void ByRef(ref int value);

public class Lab
{
    // SKIPPED_REF_STRUCT
    public void UseScratch(Scratch scratch) { }

    // SKIPPED_UNSUPPORTED_STRUCT
    public Overstuffed Overstuffed() => new(1);

    // SKIPPED_UNSUPPORTED_ENUM
    public Permissions Permissions() => global::Diagnostics.EveryKind.Permissions.Read;

    public Status Status() => global::Diagnostics.EveryKind.Status.HttpStatus;

    // SKIPPED_OPEN_GENERIC
    public T Echo<T>(T value) => value;

    // SKIPPED_UNBOUND_TYPE_REFERENCE
    public void Append(StringBuilder builder) { }

    // SKIPPED_MEMBER_NAME_COLLISION
    public void Close() { }

    // SKIPPED_ABI_ARITY_LIMIT
    public int Many(
        int a1, int a2, int a3, int a4, int a5, int a6, int a7, int a8, int a9, int a10, int a11,
        int a12, int a13, int a14, int a15, int a16, int a17, int a18, int a19, int a20, int a21,
        int a22, int a23) => a1;

    // INFO_ASYNC_NOT_YET_MAPPED
    public ValueTask Settle() => default;

    // INFO_CANCELLATION_TOKEN_NOT_YET_MAPPED
    public void Block(CancellationToken token) { }

    // INFO_CANCELLATION_OVERLOAD_FOLDED
    public Task<int> FetchAsync() => Task.FromResult(1);

    public Task<int> FetchAsync(CancellationToken token) => Task.FromResult(1);

#nullable disable
    // INFO_OBLIVIOUS_NULLABILITY
    public string Legacy() => "";
#nullable enable

    // SKIPPED_INDEXER
    public int this[int index] => index;

    // SKIPPED_EVENT
    public event EventHandler? Changed;

    // SKIPPED_UNBOUND_GENERIC_INSTANTIATION
    public Queue<int> Pending() => new();

    // SKIPPED_GENERIC_TYPE_ARGUMENT
    public Cell<Cell<string>>? Nested() => null;

    public Cell<string>? Text() => null;

    public Wrap<int>? NumberWrap() => null;

    public Wrap<string>? TextWrap() => null;

    // SKIPPED_COLLECTION_ELEMENT
    public List<int?> Maybe() => new();

    // SKIPPED_DELEGATE_SIGNATURE
    public void Mutate(ByRef action) { }

    // SKIPPED_DELEGATE_POSITION
    public Func<int> Producer() => () => 1;

    // INFO_DELEGATE_OVERLOAD_AMBIGUITY
    public void Run(Action action) { }

    public void Run(Func<int> action) { }

    // SKIPPED_ARRAY
    public int[] Numbers() => Array.Empty<int>();

    // SKIPPED_OVERLOAD_SET
    public void Take(List<string> values) { }

    public void Take(IList<string> values) { }

    public ICalculator? Calculator() => null;

    public Point Origin() => new(0);
}
