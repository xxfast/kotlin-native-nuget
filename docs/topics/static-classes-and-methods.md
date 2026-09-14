# Static classes and methods

A C# `static class` becomes a Kotlin `object` with the same name; its static methods become
functions and its properties become Kotlin properties on that object. On a non-static C# class,
static members instead land in the generated Kotlin `companion object` alongside its instance
members (see [Objects and handles](objects-and-handles.md)).

| C# | Kotlin |
|---|---|
| `public static class MimeUtility` | `internal object MimeUtility` |
| `public static string Describe(int value)` / `Describe(bool value)` | `fun describe(value: Int): String` / `describe(value: Boolean)` |
| `public static string DefaultName { get; set; }` | `var defaultName: String` |
| `public static int RenderCount { get; }` | `val renderCount: Int` |

The generated `object` is `internal`: use it from anywhere else in the same Gradle module, but it
never appears in your own library's generated C# API.

## Calling a static method

Given a package bound as shown in [Declaring dependencies](declaring-dependencies.md), a bound
static method reads like any other Kotlin function call:

```C#
namespace MimeMapping {
  public static class MimeUtility {
    public static string GetMimeMapping(string file);
  }
}
```

```kotlin
import mimemapping.MimeUtility

fun mimeTypeFor(fileName: String): String = MimeUtility.getMimeMapping(fileName)
```

## Static properties

A non-static class's static properties land in its `companion object`, addressed through the class
name itself. A property with no public setter becomes a read-only Kotlin `val`; a settable one
becomes a `var`. Reading or writing either calls back into the real C# static member on every
access, so a bound handle- or `string`-typed property follows the same rule as an instance property
(see [Instance members](instance-members.md)), including a nullable C# type staying nullable in
Kotlin.

```C#
public class Template {
  public static string DefaultName { get; set; } = "Oreo";
  public static int RenderCount { get; private set; }
}
```

```kotlin
fun setDefaultTemplateCatName(name: String): String {
  Template.defaultName = name
  return Template.defaultName
}

fun templateRenderCount(): Int = Template.renderCount
```

## Overloads

Bridgeable overloads keep their shared Kotlin name; normal overload resolution picks the right one
by argument type, the same as any other Kotlin overload set. An overload that isn't individually
bridgeable is diagnosed on its own and doesn't hide the overloads that are; a true collision
between two overloads' mapped Kotlin signatures is a generation error instead, see
[The bridgeable subset](bridgeable-subset.md).

```C#
public sealed class OverloadLab {
  public static string Describe(int value) => $"static:int:{value}";
  public static string Describe(bool value) => value ? "static:bool:on" : "static:bool:off";
}
```

```kotlin
OverloadLab.describe(42)    // "static:int:42"
OverloadLab.describe(true)  // "static:bool:on"
```

<seealso>
    <category ref="related">
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
        <a href="declaring-dependencies.md">Declaring dependencies</a>
        <a href="objects-and-handles.md">Objects and handles</a>
        <a href="bridgeable-subset.md">The bridgeable subset</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/053-nullable-reference-types-in-kotlin.md">ADR-053: Nullable reference types in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/057-csharp-overload-sets-in-kotlin.md">ADR-057: C# overload sets in Kotlin</a>
    </category>
</seealso>
