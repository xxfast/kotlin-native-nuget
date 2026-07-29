# Generic types

A C#-declared generic class reached through at least one closed instantiation (`Box<int>`,
`Pairing<string, int>`) becomes a real Kotlin generic class, not a monomorphized family
(`BoxOfInt`, `BoxOfString`). Every member, including one that never mentions the type parameter,
dispatches through a per-instantiation witness object, because `[UnmanagedCallersOnly]` thunks
cannot be generic (`CS8895`). This is [ADR-072](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/072-closed-constructed-generics-in-kotlin.md).

| C# | Kotlin | Notes |
|---|---|---|
| `class Box<T>` reached through a closed instantiation | `class Box<T>` | one Kotlin type for every instantiation, over an erased handle plus a witness |
| `Box(T value)` (public constructor) | `Box(value)` fake top-level constructor | one overload per unambiguous instantiation; see Limitations |
| `T Value { get; }` | `val value: T` | the marshalling seam; the witness knows the concrete `T` |
| a `T`-free member (`string Describe()`) | `fun describe(): String` | still needs its own per-instantiation thunk, forced by `CS8895` |
| `Box<T> Rewrap()` | `fun rewrap(): Box<T>` | an instantiation reached by substituting into the definition's own members |
| `List<int>`, `Dictionary<string, int>` | not bound | `skipped_unbound_generic_instantiation`; see Limitations |

## Kotlin

The generated class holds an [ADR-051](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/051-csharp-objects-as-opaque-handles.md)
handle and a witness; every member delegates to it:

```kotlin
// build/nuget-interop/kotlin/nativeMain/test/boxes/Box.kt (real generated output)
internal class Box<T> internal constructor(
  handle: NugetObjectHandle,
  private val bridge: BoxBridge<T>,
) : NugetHandleOwner, AutoCloseable {
  override val handle: NugetObjectHandle = handle

  @Suppress("unused")
  private val cleaner = kotlin.native.ref.createCleaner(this.handle) { it.free() }

  override fun close(): Unit = handle.free()

  fun describe(): String = bridge.describe(handle)

  fun rewrap(): Box<T> = bridge.rewrap(handle)

  val value: T get() = bridge.value(handle)
}

internal fun Box(value: Int): Box<Int> {
  val handle = BoxOfIntBridge.construct(value)
  return Box(handle, BoxOfIntBridge)
}
```

`BoxBridge<T>` is the interface every witness implements, one per open definition:

```kotlin
// build/nuget-interop/kotlin/nativeMain/test/boxes/BoxBridge.kt (real generated output)
internal interface BoxBridge<T> {
  fun construct(value: T): NugetObjectHandle
  fun describe(handle: NugetObjectHandle): String
  fun rewrap(handle: NugetObjectHandle): Box<T>
  fun value(handle: NugetObjectHandle): T
}
```

An instantiation that has no fake constructor (`Box<String>`, ambiguous against `Box<String?>`,
see Limitations) is still reachable through any bound member that returns it:

```kotlin
// build/nuget-interop/kotlin/nativeMain/test/boxes/Boxes.kt (real generated output)
fun ofText(value: String): Box<String> {
  val fn = requireNotNull(BoxesBindings.ofText__27c56ad2ed8de7d8e30d35836b9f6c66Fn) { /* ... */ }
  val ptr: COpaquePointer? = memScoped { fn.invoke(value.cstr.ptr) }
  return Box(NugetObjectHandle(requireNotNull(ptr) { /* ... */ }), BoxOfStringBridge)
}
```

Arity 2 carries the parameter names straight from metadata:

```kotlin
// build/nuget-interop/kotlin/nativeMain/test/boxes/Pairing.kt (real generated output)
internal class Pairing<TKey, TValue> internal constructor(
  handle: NugetObjectHandle,
  private val bridge: PairingBridge<TKey, TValue>,
) : NugetHandleOwner, AutoCloseable {
  val key: TKey get() = bridge.key(handle)
  val value: TValue get() = bridge.value(handle)
}
```

`Crate<T> where T : class` reads the `ReferenceTypeConstraint` from metadata but v1 emits no
Kotlin type-parameter constraint (`<T>`, not `<T : Any>`); the class still binds and constructs
normally.

## Generated C#

Every closed instantiation gets its own registration class and export symbol, never the
definition:

```C#
// contentFiles/cs/any/BoxOfIntRegistration.cs (real generated output, package build)
internal static class BoxOfIntRegistration
{
    [DllImport("test", CallingConvention = CallingConvention.Cdecl,
        EntryPoint = "nuget_test_boxes_box_of_int_register")]
    private static extern void nuget_test_boxes_box_of_int_register(int slotCount, long contractHash, IntPtr constructPtr, IntPtr describePtr, IntPtr rewrapPtr, IntPtr valuePtr);

    [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
    private static IntPtr Construct_Thunk(int value) =>
        GCHandle.ToIntPtr(GCHandle.Alloc(new Box<int>(value)));

    [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
    private static IntPtr Describe_Thunk(IntPtr selfHandle)
    {
        Box<int> receiver = (Box<int>)GCHandle.FromIntPtr(selfHandle).Target!;
        return Marshal.StringToCoTaskMemUTF8(receiver.Describe());
    }

    [ModuleInitializer]
    internal static unsafe void Register() =>
        nuget_test_boxes_box_of_int_register(4, -6973804050365710142L, /* ... */);
}
```

`(Box<int>)` throws `InvalidCastException` on a mismatched handle; Kotlin's static types make that
unreachable through the generated API. A cross-package type argument (a bound handle in another
namespace) needs its own `using`, exactly like an enum argument would:

```C#
// contentFiles/cs/any/BoxOfFerretRegistration.cs (real generated output)
namespace Test.Boxes
{
    using Test.Menagerie;

    internal static class BoxOfFerretRegistration
    {
        [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
        private static IntPtr Construct_Thunk(IntPtr valueHandle) =>
            GCHandle.ToIntPtr(GCHandle.Alloc(new Box<Ferret>((Ferret)GCHandle.FromIntPtr(valueHandle).Target!)));
    }
}
```

## Using it from Kotlin

```kotlin
// test-library/src/nativeMain/kotlin/.../test/boxes/BoxesSample.kt (real source)

// construction: fake constructor, type argument inferred
fun boxOfIntValue(value: Int): Int = Box(value).value

// T-free member still needs its own per-instantiation thunk (CS8895)
fun boxOfIntDescribe(value: Int): String = Box(value).describe()

// obtained from a bound static member, the only route for Box<String>
fun boxOfTextUppercased(value: String): String = Boxes.ofText(value).value.uppercase()

// Box<String?> genuinely round-trips null
fun boxOfMaybeTextIsNull(): Boolean = Boxes.ofMaybeText(null).value == null

// enum type argument, cross-namespace, no cast at the call site
fun boxOfMoodIsSleepy(): Boolean = Boxes.ofMood(CatMood.SLEEPY).value == CatMood.SLEEPY

// instantiation at a PARAMETER position
fun unwrapBoxOfInt(value: Int): Int = Boxes.unwrap(Box(value))

// a member of the generic type returning its own instantiation
fun rewrapBoxOfInt(value: Int): Int = Box(value).rewrap().value

// polymorphism over the generic itself, the whole point of not monomorphizing
internal fun <T> describeAll(boxes: List<Box<T>>): List<String> = boxes.map { it.describe() }
```

`describeAll` is `internal` because `Box` itself is `internal`: the generated class visibility
follows the same rule as every other reverse-bound type in this project's `test-library` fixture.

## Limitations

- Only generic **classes** bind. Generic interfaces stay excluded (`skipped_generic_interface`,
  see [The bridgeable subset](bridgeable-subset.md)), and generic **methods** (`T Identity<T>(T)`)
  stay `skipped_open_generic` permanently, unless a caller can pin the type argument.
- A type argument must be a primitive, `string` (nullable or not), a bound enum, a bound class
  handle, or a bound interface. Anything else, another generic instantiation, a struct, an array,
  a `ref struct`, disqualifies the whole instantiation with `skipped_generic_type_argument`.
- A BCL generic (`List<int>`, `Dictionary<string, int>`) is diagnosed
  (`skipped_unbound_generic_instantiation`), not bound: its definition lives outside the bound
  assemblies, so it has no extracted members to bind. Mapping it to a Kotlin collection idiom is
  tracked separately in [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md) Phase 10.
- A bare type parameter annotated nullable (`T? Peek()`) is not representable per instantiation:
  `skipped_nullable_type_parameter`.
- No Kotlin type-parameter constraints are emitted (`where T : class` does not become `<T : Any>`).
  A consumer can *write* `Box<Double>` even though no such instantiation is bound; they can never
  obtain a value of it, since there is no witness or factory for it, so the failure is at the first
  attempt to get one, at compile time.
- If two instantiations of one definition erase their fake constructor to the same non-null Kotlin
  parameter list (`Box<String>` and `Box<String?>` both erase to `(String)`), **both** lose their
  fake constructor (`skipped_ambiguous_generic_constructor`, a Gradle build warning, not a
  `reverse-ir.json` diagnostic: computing the erasure needs the Kotlin-side parameter list). Such
  an instantiation is still reachable through any bound member returning it.
- A generic definition with zero discovered instantiations emits nothing at all: no Kotlin type,
  no C# class, no registration export, just an `info_uninstantiated_generic_type` note.

## See also

<seealso>
    <category ref="related">
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
        <a href="objects-and-handles.md">Objects and handles</a>
        <a href="bridgeable-subset.md">The bridgeable subset</a>
        <a href="registration-diagnostics.md">Registration diagnostics</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/072-closed-constructed-generics-in-kotlin.md">ADR-072: Closed constructed generics from C# in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/043-bridgeable-subset-boundary.md">ADR-043: Bridgeable subset boundary</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/010-generics-mapping.md">ADR-010: Generics mapping (forward)</a>
    </category>
</seealso>
