# Objects and handles

A bound C# class (not `static`) becomes a Kotlin class that wraps an opaque handle to the real C#
object. Every crossing of the bridge allocates a fresh handle and a fresh Kotlin wrapper; there is no
identity caching, and a single public instance constructor maps to a Kotlin secondary constructor.
These decisions are [ADR-051](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/051-csharp-objects-as-opaque-handles.md)
and [ADR-052](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/052-csharp-instance-constructors-in-kotlin.md).

## The handle mechanism

The C# side allocates a `GCHandle.Normal` for the object and hands its `IntPtr` value across the ABI
as a blittable pointer. The Kotlin wrapper stores that pointer, unopened, in an internal holder and
never interprets it; every method call passes it straight back through a thunk. This is the mirror
image of the forward direction's `StableRef`-backed opaque pointers, with the roles of the two
runtimes swapped: `GCHandle.Alloc` ↔ `StableRef.create`, `GCHandle.ToIntPtr` ↔ `.asCPointer()`,
`GCHandle.FromIntPtr(ptr).Target` ↔ `ptr.asStableRef<T>().get()`, `handle.Free()` ↔
`stableRef.dispose()`.

Freeing a handle from Kotlin needs a registered thunk, because Kotlin has no way to call directly
into managed code (see [Consuming C# in Kotlin](reverse-overview.md)). A single shared export,
`nuget_runtime_register`, is emitted once and reused by every bound class:

```C#
// build/nuget-interop/csharp/NugetRuntimeRegistration.cs (real generated output)
internal static class NugetRuntimeRegistration
{
    [DllImport("sample", CallingConvention = CallingConvention.Cdecl,
        EntryPoint = "nuget_runtime_register")]
    private static extern void nuget_runtime_register(IntPtr freeGcHandlePtr);

    [ModuleInitializer]
    internal static unsafe void Initialize() =>
        nuget_runtime_register((IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, void>)(&FreeGcHandle_Thunk));

    [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
    private static void FreeGcHandle_Thunk(IntPtr handle) => GCHandle.FromIntPtr(handle).Free();
}
```

## Lifetime: `Cleaner`-primary, `close()` as the escape hatch

The generated wrapper implements `kotlin.AutoCloseable` and releases the handle automatically once
the wrapper becomes unreachable, via `kotlin.native.ref.Cleaner`. `close()` releases it
deterministically and is idempotent; calling it twice, or having the cleaner run after an explicit
`close()`, is a safe no-op guarded by an atomic flag. Calling any member on an already-closed wrapper
throws `IllegalStateException`.

```kotlin
// build/nuget-interop/kotlin/nativeMain/sample/text/Template.kt (real generated output)
internal class Template internal constructor(handle: COpaquePointer) : AutoCloseable {
  internal val handle: NugetObjectHandle = NugetObjectHandle(handle)

  @Suppress("unused")
  private val cleaner = createCleaner(this.handle) { it.free() }

  override fun close(): Unit = handle.free()

  constructor(source: String) : this(construct(source))

  // ...instance methods, properties, companion object...
}
```

`use { }` therefore works exactly as it would on any other `AutoCloseable`:

```kotlin
// sample-library/src/nativeMain/kotlin/.../sample/Greetings.kt (real source)
fun greet(name: String): String {
  val template: Template = requireNotNull(Template.parse("Hello, {name}")) {
    "Template.parse returned null - expected a non-null Template handle"
  }
  return template.use { Template.render(it, name) }
}
```

`close()` is optional. Forgetting it just means the C# object is released whenever Kotlin's GC
happens to collect the wrapper, the way a Java or Objective-C object crossing into Kotlin behaves
today, not a leak.

## Constructors

Exactly one public instance constructor per bound class maps to a Kotlin secondary constructor.
Kotlin's `constructor(...) : this(...)` delegation can only be an expression, not a statement block,
so the generated constructor delegates through a file-private helper that runs the bridge call and
`requireNotNull`s the returned handle (a C# constructor never legitimately returns null, unlike a
factory method):

```kotlin
// build/nuget-interop/kotlin/nativeMain/sample/text/Template.kt (real generated output)
constructor(source: String) : this(construct(source))

// ...

private fun construct(source: String): COpaquePointer {
  val fn = requireNotNull(ctorFn) { /* ... */ }
  val ptr: COpaquePointer? = memScoped { fn.invoke(source.cstr.ptr) }
  return requireNotNull(ptr) {
    "Template constructor returned a null handle - a C# constructor never returns null."
  }
}
```

```kotlin
// sample-library/src/nativeMain/kotlin/.../sample/Greetings.kt (real source)
fun greetViaConstructor(name: String): String {
  val template: Template = Template("Hello, {name}")
  return template.use { Template.render(it, name) }
}
```

Because a constructor's return is unconditionally the class's own type (never `null`), it is a
distinct RIR node (`RirConstructor`) rather than a `RirMethod` with a mandatory return type.

## Object parameters and returns: `null` maps to a null pointer

- **Object returns are nullable** (`Foo?`). `IntPtr.Zero` from C# is a legitimate `null` (the usual
  shape of a `Try`-style factory), and it maps to Kotlin `null` with no wrapper allocated.
- **Object parameters are non-null** (`Foo`). Passing Kotlin `null` for an object parameter isn't
  supported yet; every object parameter must be a live wrapper.

```kotlin
// companion object { ... } inside Template.kt (real generated output)
fun parse(source: String): Template? {
  val fn = requireNotNull(parseFn) { /* ... */ }
  val ptr: COpaquePointer? = memScoped { fn.invoke(source.cstr.ptr) }
  return ptr?.let { Template(it) }
}

fun render(template: Template, name: String): String {
  val fn = requireNotNull(renderFn) { /* ... */ }
  val resultPtr = memScoped { fn.invoke(template.handle.require("Template"), name.cstr.ptr) }
    ?: error("Template.Render returned null - expected a non-null string pointer")
  // ...
}
```

`template.handle.require("Template")` is how a wrapper's raw pointer gets unwrapped for a call: it
also guards against use-after-close, throwing `IllegalStateException` if the handle was already
freed.

## New wrapper per crossing: no identity, no caching

Every time a C# object crosses into Kotlin, a **new** `GCHandle` and a **new** Kotlin wrapper are
created, even if the same underlying C# object crossed a moment ago. Two wrappers around the same C#
object are entirely independent: each holds its own handle, each is freed independently, and neither
one's `close()` can invalidate the other. `equals`, `hashCode`, and `toString` are left as Kotlin's
defaults (reference identity on the *wrapper*, not the underlying C# object), so:

```kotlin
val a = Template.parse("x")
val b = Template.parse("x")
a == b   // false - different wrappers, even though both wrap equivalent C# state
```

This is a deliberate mirror of the forward direction's own choice
([ADR-005](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/005-object-return-semantics.md)):
simple, stateless, no cross-runtime cache invalidation to get wrong.

## Contrast with the forward direction

The forward direction (Kotlin objects surfacing in C#) chose `IDisposable`-first, because C#
consumers expect deterministic disposal as the default and treat automatic cleanup as a safety net.
The reverse direction inverts that choice deliberately: Kotlin consumers of a foreign object expect
the experience they already have with Java or Objective-C interop, where objects clean themselves up
and disposal is an opt-in for cases that need it.
[ADR-051](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/051-csharp-objects-as-opaque-handles.md)
walks through why this inversion is correct for each direction rather than a stylistic accident.

## Limitations

- No identity caching: two wrappers of the same C# object are unequal and independently closeable.
  Tracked as a possible future optimization only if profiling shows it's needed.
- `equals`/`hashCode`/`toString` are never delegated to the C# object's own `Equals`/`GetHashCode`/
  `ToString`.
- Object parameters cannot be `null` yet; that's blocked on mapping `NullableAttribute` metadata.
- Multiple public constructors on one type are an overload set and are skipped + diagnosed, not
  disambiguated (tracked in
  [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md) Phase 9).
- A throwing C# constructor or factory still fast-fails the host process; see
  [The bridgeable subset](bridgeable-subset.md) for the exception policy.

<seealso>
    <category ref="related">
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
        <a href="instance-members.md">Instance members</a>
        <a href="bridgeable-subset.md">The bridgeable subset</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/005-object-return-semantics.md">ADR-005: Object return semantics</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/051-csharp-objects-as-opaque-handles.md">ADR-051: C# objects as opaque handles</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/052-csharp-instance-constructors-in-kotlin.md">ADR-052: C# instance constructors in Kotlin</a>
    </category>
</seealso>
