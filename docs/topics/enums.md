# Enums

A Kotlin `enum class` becomes a plain C# `enum` with matching ordinal values. Any members declared on the enum class (properties, methods) become C# extension methods, since a C# `enum` can't carry behaviour itself.

| Kotlin | C# | Notes |
|---|---|---|
| `enum class` | `enum` | with extension methods |

## Kotlin

From `sample-library/src/nativeMain/kotlin/.../cat/Mood.kt`:

```kotlin
enum class Mood {
  HAPPY,
  SLEEPY,
  GRUMPY;

  val description: String
    get() = when (this) {
      HAPPY -> "The cat is happy and content."
      SLEEPY -> "The cat is sleepy and ready for a nap."
      GRUMPY -> "The cat is grumpy and doesn't want to be disturbed."
    }
}
```

## Generated C#

From `Interop.cs`:

```C#
public enum Mood
{
    Happy = 0,
    Sleepy = 1,
    Grumpy = 2,
}

public static class MoodExtensions
{
    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "mood_get_description")]
    private static extern IntPtr Native_GetDescription(int ordinal);

    public static string Description(this Mood mood)
        => Marshal.PtrToStringUTF8(Native_GetDescription((int)mood))!;
}
```

Enum entries are renamed from Kotlin's `SCREAMING_SNAKE_CASE` (`HAPPY`) to C#'s `PascalCase` (`Happy`), matching each language's own naming convention. The `description` property becomes an extension method `Description()` on `Mood`, computed by passing the ordinal across the bridge.

A `Cat` exposes `Mood` as a settable property (`var mood: Mood`), following the same property pattern as any other enum-typed field:

```kotlin
var mood: Mood = Mood.SLEEPY
```

## Using it from C#

From `sample-app/SampleApp.Tests/EnumTests.cs`:

```C#
[Fact]
public void Mood_HasCorrectValues()
{
    Assert.Equal(0, (int)Mood.Happy);
    Assert.Equal(1, (int)Mood.Sleepy);
    Assert.Equal(2, (int)Mood.Grumpy);
}

[Fact]
public void Cat_Mood_CanBeSet()
{
    using var cat = new Cat("Oreo", 9);
    cat.Mood = Mood.Happy;
    Assert.Equal(Mood.Happy, cat.Mood);
}

[Fact]
public void Mood_Description_ReturnsCorrectString()
{
    Assert.Equal("The cat is happy and content.", Mood.Happy.Description());
    Assert.Equal("The cat is sleepy and ready for a nap.", Mood.Sleepy.Description());
    Assert.Equal("The cat is grumpy and doesn't want to be disturbed.", Mood.Grumpy.Description());
}
```

## See also

- [Classes and objects](classes-and-objects.md)
- [ADR-006: Enum mapping](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/006-enum-mapping.md)
