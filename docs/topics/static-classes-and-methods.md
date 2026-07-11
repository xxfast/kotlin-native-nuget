# Static classes and methods

A C# `static class` becomes a Kotlin `object` with the same name; its static methods become
functions and its properties become Kotlin properties on that object. On a non-static C# class,
static members instead land in the generated Kotlin `companion object`.

| C# | Kotlin |
|---|---|
| `public static class MimeUtility` | `internal object MimeUtility` |
| `public static string GetMimeMapping(string file)` | `fun getMimeMapping(file: String): String` |
| `public static string DefaultName { get; set; }` | `var defaultName: String` |
| `public static int RenderCount { get; }` | `val renderCount: Int` |

The generated `object` is `internal`, not `public`: it's visible anywhere else in the same Gradle
module (including hand-written sources like `sample-library`'s own Kotlin files) but invisible to
the forward-direction KSP exporter's public-API scan, so a reverse-bound type never accidentally gets
re-exported into the packed nupkg's own `Interop.cs`.

## The `MimeMapping` round trip

`sample-library/build.gradle.kts` binds the `MimeMapping` package:

```kotlin
nuget {
  dependencies {
    dependency("MimeMapping", version = "4.0.0") {
      bind {
        packageName = "mimemapping"
        include("MimeMapping")
      }
    }
  }
}
```

The bound C# API is a single-overload static method (see
[The bridgeable subset](bridgeable-subset.md) for why that matters):

```C#
namespace MimeMapping {
  public static class MimeUtility {
    public static string GetMimeMapping(string file);
  }
}
```

`nugetGenerateBindings` renders this straight into an `object`
(`sample-library/build/nuget-interop/kotlin/nativeMain/mimemapping/MimeUtility.kt`, real generated
output):

```kotlin
internal object MimeUtility {

  fun getMimeMapping(file: String): String {
    val fn = requireNotNull(getMimeMappingFn) {
      "MimeUtility bindings are not registered. " +
        "Ensure the generated C# shims for MimeMapping are referenced " +
        "in the consuming application before making Kotlin → C# bridge calls."
    }
    val resultPtr = memScoped { fn.invoke(file.cstr.ptr) }
      ?: error("MimeUtility.GetMimeMapping returned null - expected a non-null string pointer")
    val result = resultPtr.reinterpret<ByteVar>().toKString()
    freeManagedString(resultPtr)
    return result
  }
}
```

`sample-library` calls it from ordinary Kotlin
(`sample-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/sample/mime/MimeSample.kt`):

```kotlin
package io.github.xxfast.kotlin.native.nuget.sample.mime

import mimemapping.MimeUtility

fun mimeTypeFor(fileName: String): String = MimeUtility.getMimeMapping(fileName)
```

And the C# test exercises the whole path end to end
(`sample-app/SampleApp.Tests/MimeRoundTripTests.cs`):

```C#
[Fact]
public void MimeTypeFor_JsonFile_ReturnsApplicationJson()
{
    string result = MimeSample.mimeTypeFor("data.json");
    Assert.Equal("application/json", result);
}
```

That's the full trip: C# test code calls the forward-bridged `MimeSample.mimeTypeFor`, which is
Kotlin code, which calls the reverse-bridged `MimeUtility.getMimeMapping`, which is itself a thunk
call back into the *real* `MimeMapping` NuGet package.

## Static properties

The bound `Sample.Text.Template` fixture has both property forms:

```C#
// sample-dependency/Template.cs (real source)
public static string DefaultName { get; set; } = "Oreo";

public static int RenderCount { get; private set; }
```

Because `Template` is not a C# static class, its static properties are generated in its companion
object:

```kotlin
// build/nuget-interop/kotlin/nativeMain/sample/text/Template.kt (real generated output)
companion object {
  var defaultName: String
    get() { /* calls defaultNameGetterFn */ }
    set(value) { /* calls defaultNameSetterFn */ }

  val renderCount: Int
    get() { /* calls renderCountGetterFn */ }
}
```

Each accessor is a call through its registered function pointer. The generated C# thunks access
the underlying static property directly:

```C#
// build/nuget-interop/csharp/TemplateRegistration.cs (real generated output)
private static IntPtr DefaultName_Get_Thunk()
{
    string result = Template.DefaultName;
    return Marshal.StringToCoTaskMemUTF8(result);
}

private static void DefaultName_Set_Thunk(IntPtr valuePtr)
{
    Template.DefaultName = Marshal.PtrToStringUTF8(valuePtr)!;
}
```

Hand-written Kotlin uses the companion members through `Template` itself:

```kotlin
// sample-library/src/nativeMain/kotlin/.../sample/Greetings.kt (real source)
fun setDefaultTemplateCatName(name: String): String {
  Template.defaultName = name
  return Template.defaultName
}

fun templateRenderCount(): Int = Template.renderCount
```

The consumer-side round trip calls these Kotlin functions through the forward bridge:

```C#
// sample-app/SampleApp.Tests/TemplateRoundTripTests.cs (real source)
[Fact]
public void StaticProperties_MyloNameAndRenderCount_RoundTripThroughKotlin()
{
    string name = Greetings.setDefaultTemplateCatName("Mylo");
    int renderCount = Greetings.templateRenderCount();

    Assert.Equal("Mylo", name);
    Assert.True(renderCount >= 0);
}
```

## Naming and registration

The registration export name is derived from the C# namespace and type name:
`MimeMapping.MimeUtility` → `nuget_mimemapping_mime_utility_register`. One
`[UnmanagedCallersOnly]` thunk is registered per bridgeable static method; see
[Consuming C# in Kotlin](reverse-overview.md) for the full registration handshake.

## Why `Newtonsoft.Json` doesn't work here

The ROADMAP originally named `Newtonsoft.Json` as the worked example for this feature. It's
structurally impossible under the bridgeable subset: every static method on `JsonConvert`
(`SerializeObject`, `DeserializeObject`, `ToString`, ...) is an overload set, and overload sets are
skipped wholesale (see [The bridgeable subset](bridgeable-subset.md)). `MimeMapping` was chosen
instead specifically because `GetMimeMapping(string): string` is a real, published, single-overload
method that survives the filter unforced.

## Limitations

- Static properties support the current bridgeable primitive and `string` vocabulary. A handle-typed
  property with a setter remains read-only as `val Foo?` until reverse nullability support lets a
  Kotlin `var` accept the same nullable type for both accessors.
- Overload sets are skipped entirely, not partially. See
  [The bridgeable subset](bridgeable-subset.md).

<seealso>
    <category ref="related">
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
        <a href="declaring-dependencies.md">Declaring dependencies</a>
        <a href="objects-and-handles.md">Objects and handles</a>
        <a href="bridgeable-subset.md">The bridgeable subset</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/048-kotlin-stub-generation-from-reverse-ir.md">ADR-048: Kotlin stub generation from reverse IR</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/049-csharp-registration-shim-generation.md">ADR-049: C# registration shim generation</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/050-end-to-end-packaging-integration.md">ADR-050: End-to-end packaging integration</a>
    </category>
</seealso>
