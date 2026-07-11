# Instance members

Instance methods and instance properties on a bound C# class become member functions and properties
on the generated Kotlin wrapper introduced in [Objects and handles](objects-and-handles.md). There is
no dedicated ADR for this feature; it's a confirmed extension of the insight
[ADR-051](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/051-csharp-objects-as-opaque-handles.md)
already states: an instance thunk is a static thunk whose first parameter is the receiver's handle.

## The `Template` fixture

`sample-dependency`'s `Sample.Text.Template` is the canonical fixture for this feature: a
constructor, two static methods, a read-only property, a settable property, an instance method with
a string in and out, an instance method that returns a fresh handle of the same type, plus read-only
and settable static properties.

```C#
// sample-dependency/Template.cs (real source)
public class Template
{
    public static string DefaultName { get; set; } = "Oreo";
    public static int RenderCount { get; private set; }

    public Template(string source) => _source = source;

    public string Source => _source;                    // read-only instance property

    public string Name { get; set; } = "world";          // settable instance property

    public string Apply(string name) =>                  // instance method, string in/out
        _source.Replace("{name}", name);

    public Template Clone() => new(_source) { Name = Name }; // instance method returning a handle

    public static Template Parse(string source) => new(source);
    public static string Render(Template template, string name) =>
        template._source.Replace("{name}", name);
}
```

Generated Kotlin (`build/nuget-interop/kotlin/nativeMain/sample/text/Template.kt`, real output):

```kotlin
internal class Template internal constructor(handle: COpaquePointer) : AutoCloseable {
  // ...handle, cleaner, close(), secondary constructor...

  fun apply(name: String): String {
    val fn = requireNotNull(applyFn) { /* ... */ }
    val resultPtr = memScoped { fn.invoke(handle.require("Template"), name.cstr.ptr) }
      ?: error("Template.Apply returned null - expected a non-null string pointer")
    val result = resultPtr.reinterpret<ByteVar>().toKString()
    freeManagedString(resultPtr)
    return result
  }

  fun clone(): Template? {
    val fn = requireNotNull(cloneFn) { /* ... */ }
    val ptr: COpaquePointer? = fn.invoke(handle.require("Template"))
    return ptr?.let { Template(it) }
  }

  val source: String
    get() {
      val fn = requireNotNull(sourceGetterFn) { /* ... */ }
      val resultPtr = fn.invoke(handle.require("Template"))
        ?: error("Template.Source returned null - expected a non-null string pointer")
      val result = resultPtr.reinterpret<ByteVar>().toKString()
      freeManagedString(resultPtr)
      return result
    }

  var name: String
    get() { /* same shape as source's getter, backed by nameGetterFn */ }
    set(value) {
      val fn = requireNotNull(nameSetterFn) { /* ... */ }
      memScoped { fn.invoke(handle.require("Template"), value.cstr.ptr) }
    }

  companion object { /* parse, render - see Objects and handles */ }
}
```

Every instance member call prepends `handle.require("Template")` as the receiver argument, exactly
mirroring what a handle-typed *parameter* already does elsewhere: it's the same
`handle.require(...)` mechanism used for object parameters in
[Objects and handles](objects-and-handles.md), applied to `this` instead of an explicit argument.

The generated C# thunk shows the same shape from the other side (`selfHandle` first):

```C#
// build/nuget-interop/csharp/TemplateRegistration.cs (real generated output)
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static IntPtr Apply_Thunk(IntPtr selfHandle, IntPtr namePtr)
{
    Template receiver = (Template)GCHandle.FromIntPtr(selfHandle).Target!;
    string result = receiver.Apply(Marshal.PtrToStringUTF8(namePtr)!);
    return Marshal.StringToCoTaskMemUTF8(result);
}
```

`sample-library` puts all of this together:

```kotlin
// sample-library/src/nativeMain/kotlin/.../sample/Greetings.kt (real source)
fun greetViaInstanceMembers(name: String): String {
  val template: Template = Template("Hello, {name}")
  template.name = name

  val copy: Template = requireNotNull(template.clone()) {
    "Template.clone returned null - expected a non-null Template handle"
  }
  check(copy.name == template.name) { "Template.clone did not carry over the Name property" }
  check(copy.source == template.source) { "Template.clone did not carry over the source" }

  return template.use { copy.use { c -> c.apply(name) } }
}
```

## Property mapping

| C# property | Kotlin |
|---|---|
| read-only (`{ get; }`, or a getter-only expression body) | `val` |
| settable (`{ get; set; }`) | `var`, with bridge-backed `get()`/`set()` |

Neither is a stored field; both `get()` and `set()` (when present) call through a registered function
pointer on every access, just like a method.

## Registration slot order

The registration export's parameters follow one fixed order, derived from a single shared function
(`bridgeableRegistrables` in `RirBridging.kt`) that both the Kotlin and C# generators consume, so the
two sides can never drift out of alignment:

1. constructor (if any)
2. static methods
3. instance methods
4. one getter, then one setter (if settable), per bridgeable instance property
5. one getter, then one setter (if settable), per bridgeable static property

```C#
// build/nuget-interop/csharp/TemplateRegistration.cs (real generated output, full parameter list)
private static extern void nuget_sample_text_template_register(
    IntPtr ctorPtr, IntPtr parsePtr, IntPtr renderPtr,
    IntPtr applyPtr, IntPtr clonePtr,
    IntPtr sourceGetterPtr, IntPtr nameGetterPtr, IntPtr nameSetterPtr,
    IntPtr defaultNameGetterPtr, IntPtr defaultNameSetterPtr, IntPtr renderCountGetterPtr);
```

## Name collisions with the wrapper itself

The generated wrapper already owns three Kotlin member names: `handle`, `close`, and `cleaner`. A C#
instance method or property whose Kotlin name (camelCase of the C# PascalCase name) would collide
with one of those is skipped entirely and reported as a Gradle build warning:

```
w: [nuget:SomeLib] Skipping SomeType.Close(): member name collision - Kotlin name 'close' would
   shadow the generated wrapper's own 'close' member. Rename or remove this member on the bound C#
   type, or expose it via a differently-named C# adapter method.
```

Statics are unaffected by this rule; they land in the wrapper's `companion object`, a separate name
scope from the wrapper's own instance members.

## Handle-typed properties are always read-only in v1

A C# property whose type is itself a bound class (for example a hypothetical `Template Parent { get;
set; }`) always renders as a read-only `val Foo?`, even if the C# property has a public setter. This
is a Kotlin type-system constraint, not an oversight: a `var`'s getter and setter must share one
type, but object *returns* are nullable (`Foo?`) and object *parameters* are non-null (`Foo`) per
[Objects and handles](objects-and-handles.md), so there's no single type a handle-typed `var` could
declare. The registration filter (`bridgeableRegistrables`) never emits a setter slot for a
handle-typed property, regardless of what the RIR says about its C# mutability.

## Limitations

- A handle-typed settable property is always exposed as read-only (`val Foo?`), never `var`, until
  nullable-reference-type metadata lets object parameters accept `null`.
- Member-name-collision is currently the *only* diagnostic kind surfaced as a build warning; every
  other skip reason (overload sets, unbound type references, and so on) is recorded only in
  `reverse-ir.json`'s `diagnostics` array and never printed during the build. See
  [The bridgeable subset](bridgeable-subset.md).
- Overload sets on instance methods are skipped exactly like static ones: the whole set, not a
  best-effort subset.

<seealso>
    <category ref="related">
        <a href="objects-and-handles.md">Objects and handles</a>
        <a href="static-classes-and-methods.md">Static classes and methods</a>
        <a href="bridgeable-subset.md">The bridgeable subset</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/051-csharp-objects-as-opaque-handles.md">ADR-051: C# objects as opaque handles</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/052-csharp-instance-constructors-in-kotlin.md">ADR-052: C# instance constructors in Kotlin</a>
    </category>
</seealso>
