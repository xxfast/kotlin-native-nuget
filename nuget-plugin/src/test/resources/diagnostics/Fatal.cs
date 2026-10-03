// The reverse diagnostic kinds that fail generation, read by RirDiagnosticCoverageTest through the
// real NugetMetadataReader. Kept apart from EveryKind.cs so that fixture stays generatable.
namespace Diagnostics.Fatal;

// ERROR_KOTLIN_SIGNATURE_COLLISION: both properties render `val name: Int`
public class Ledger
{
    public int Name { get; }

    public int name { get; }
}

// ERROR_GENERIC_ARITY_NAME_COLLISION: Box and Box`1 both strip to the Kotlin name `Box`
public class Box
{
    public int Size { get; }
}

public class Box<T>
{
    public Box(T value) { Value = value; }

    public T Value { get; }
}

public class Shelf
{
    public Box<int>? Numbers() => null;
}
