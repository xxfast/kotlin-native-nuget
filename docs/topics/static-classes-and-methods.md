# Static classes and methods

A C# `static class` becomes a Kotlin `object` with the same name; its static methods become
functions and its properties become Kotlin properties on that object. On a non-static C# class,
static members instead land in the generated Kotlin `companion object`.

| C# | Kotlin |
|---|---|
| `public static class MimeUtility` | `internal object MimeUtility` |
| `public static string Describe(int value)` / `Describe(bool value)` | `fun describe(value: Int): String` / `describe(value: Boolean)` |
| `public static string DefaultName { get; set; }` | `var defaultName: String` |
| `public static int RenderCount { get; }` | `val renderCount: Int` |

The generated `object` is `internal`, not `public`: it's visible anywhere else in the same Gradle
module (including hand-written sources like `test-library`'s own Kotlin files) but invisible to
the forward-direction KSP exporter's public-API scan, so a reverse-bound type never accidentally gets
re-exported into the packed nupkg's own `Interop.cs`.

## The `MimeMapping` round trip

`test-library/build.gradle.kts` binds the `MimeMapping` package:

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

The bound C# API is a static method:

```C#
namespace MimeMapping {
  public static class MimeUtility {
    public static string GetMimeMapping(string file);
  }
}
```

`nugetGenerateBindings` renders this straight into an `object`
(`test-library/build/nuget-interop/kotlin/nativeMain/mimemapping/MimeUtility.kt`, real generated
output):

```kotlin
internal object MimeUtility {

  fun getMimeMapping(file: String): String {
    val fn = requireNotNull(MimeUtilityBindings.getMimeMapping__cb4c202351abfeec4d85fccb5ca462a0Fn) {
      NugetRegistry.notRegistered("MimeMapping.MimeUtility", "MimeMapping")
    }
    val resultPtr = memScoped { fn.invoke(file.cstr.ptr) }
      ?: error("MimeUtility.GetMimeMapping returned null, expected a non-null string pointer")
    val result = resultPtr.reinterpret<ByteVar>().toKString()
    freeManagedString(resultPtr)
    return result
  }
}
```

`test-library` calls it from ordinary Kotlin
(`test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/sample/mime/MimeSample.kt`):

```kotlin
package io.github.xxfast.kotlin.native.nuget.test.mime

import mimemapping.MimeUtility

fun mimeTypeFor(fileName: String): String = MimeUtility.getMimeMapping(fileName)
```

And the C# test exercises the whole path end to end
(`IntegrationTests/MimeRoundTripTests.cs`):

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

The bound `Test.Text.Template` fixture has both property forms:

```C#
// TestDependency/Template.cs (real source)
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
// test-library/src/nativeMain/kotlin/.../sample/Greetings.kt (real source)
fun setDefaultTemplateCatName(name: String): String {
  Template.defaultName = name
  return Template.defaultName
}

fun templateRenderCount(): Int = Template.renderCount
```

The consumer-side round trip calls these Kotlin functions through the forward bridge:

```C#
// IntegrationTests/TemplateRoundTripTests.cs (real source)
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

## Static method overloads

The `Test.Overloads.OverloadLab` fixture has two `Describe` methods:

```C#
public static string Describe(int value) => $"static:int:{value}";

public static string Describe(bool value) => value ? "static:bool:on" : "static:bool:off";
```

Generated Kotlin keeps the shared name and lets normal overload resolution select by parameter
type:

```kotlin
fun describe(value: Boolean): String {
  val fn = requireNotNull(OverloadLabBindings.describe__09da8e80bd8920c59e5252f3d665716aFn) {
    NugetRegistry.notRegistered("Test.Overloads.OverloadLab", "TestDependency")
  }
  val resultPtr = fn.invoke(value)
    ?: error("OverloadLab.Describe returned null, expected a non-null string pointer")
  val result = resultPtr.reinterpret<ByteVar>().toKString()
  freeManagedString(resultPtr)
  return result
}

fun describe(value: Int): String {
  val fn = requireNotNull(OverloadLabBindings.describe__a8ac9b64f5e802cd2f6fdcaa8b7dc202Fn) {
    NugetRegistry.notRegistered("Test.Overloads.OverloadLab", "TestDependency")
  }
  val resultPtr = fn.invoke(value)
    ?: error("OverloadLab.Describe returned null, expected a non-null string pointer")
  val result = resultPtr.reinterpret<ByteVar>().toKString()
  freeManagedString(resultPtr)
  return result
}
```

## Limitations

- Static properties support the current bridgeable primitive, `string`, and handle vocabulary. A
  handle-typed static property with a setter now renders as a Kotlin `var` too, its type driven by the
  property's own `NullableAttribute` (see [Instance members](instance-members.md) and
  [ADR-053](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/053-nullable-reference-types-in-kotlin.md)).
- Unsupported overloads are diagnosed independently. A true mapped Kotlin-signature collision is
  a generation error; see [The bridgeable subset](bridgeable-subset.md).

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
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/053-nullable-reference-types-in-kotlin.md">ADR-053: Nullable reference types in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/057-csharp-overload-sets-in-kotlin.md">ADR-057: C# overload sets in Kotlin</a>
    </category>
</seealso>
