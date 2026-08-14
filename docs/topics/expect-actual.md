# expect/actual declarations

Kotlin Multiplatform's `expect`/`actual` pair is a source-level mechanism, not a runtime type: `expect` declares a header in a shared source set, and each target's `actual` supplies the body. The forward direction exports the `actual` and drops the `expect` entirely, so the generated C# looks exactly like it would for an ordinary, non-`expect` declaration of the same shape. This page covers only what's specific to the `expect`/`actual` mechanism itself; the resulting class, top-level function/property, or object follows its own mapping page ([Classes and objects](classes-and-objects.md), [Objects and companions](objects-and-companions.md), [Top-level declarations](top-level-declarations.md)).

| Kotlin | C# | Notes |
|---|---|---|
| `expect`/`actual class` | ordinary `class` | the `actual` is the export root; an `expect class` with no explicit constructor still gets a usable public constructor, because the `actual` always has one |
| `expect`/`actual fun` / `val` (top-level) | ordinary static class member | the C# static class name comes from the **expect's** file, never the actual's |
| `expect`/`actual object` | ordinary `static class` | |
| `actual typealias Foo = Target` | `Target`'s own C# type | `Foo` never appears in C#; every reference to it, at any position, redirects to `Target` |

## Kotlin

The fixture package `io.github.xxfast.kotlin.native.nuget.test.platform` crosses every shape in one package. The `expect` side, from `test-library/src/nativeMain/kotlin/.../platform/PlatformApi.kt`:

```kotlin
expect class Device(name: String) {
  fun describe(): String
  val id: String
}

expect class Sensor {
  fun reading(): Int
}

expect fun platformName(): String
expect val platformTag: String

expect object PlatformRegistry {
  fun count(): Int
}

expect class Clock {
  fun label(): String
}

class SystemClock {
  fun label(): String = "system-clock"
}

expect fun defaultClock(): Clock

fun labelOf(clock: Clock): String = clock.label()
```

The two per-target actuals, deliberately named differently from `PlatformApi.kt` and from each other. `test-library/src/macosArm64Main/kotlin/.../platform/PlatformApiMacos.kt`:

```kotlin
actual class Device actual constructor(private val name: String) {
  actual fun describe(): String = "$name on macos"
  actual val id: String = "macos-device"
}

actual class Sensor {
  actual fun reading(): Int = 42
}

actual fun platformName(): String = "macos"
actual val platformTag: String = "osx-arm64"

actual object PlatformRegistry {
  actual fun count(): Int = 1
}

actual typealias Clock = SystemClock

actual fun defaultClock(): Clock = SystemClock()
```

`test-library/src/mingwX64Main/kotlin/.../platform/PlatformApiMingw.kt` declares the identical public surface and differs only in the returned values (`"mingw"`, `"win-x64"`, `24`, `"mingw-device"`). That symmetry is deliberate: it's what proves the *actual* body is what runs, not the expect header.

## Generated C#

From `Interop.cs`. `platformName()`, `defaultClock()`, and `labelOf(...)` take their static class name, `PlatformApi`, from the **expect's** file (`PlatformApi.kt`), not `PlatformApiMacos`/`PlatformApiMingw`:

```C#
namespace TestLibrary.Platform
{
    public static partial class PlatformApi
    {
        public static string platformName()
        {
            // ...
        }

        public static SystemClock defaultClock()
        {
            // ...
        }

        public static string labelOf(SystemClock clock)
        {
            // ...
        }

        public static string PlatformTag
        {
            get { /* ... */ }
        }
    }
    public class Device : IDisposable
    {
        public Device(string name) { /* ... */ }
        public string Id { get { /* ... */ } }
        public string Describe() { /* ... */ }
    }
    public class Sensor : IDisposable
    {
        public Sensor() { /* ... */ }
        public int Reading() { /* ... */ }
    }
    public class SystemClock : IDisposable
    {
        public SystemClock() { /* ... */ }
        public string Label() { /* ... */ }
    }
    public static class PlatformRegistry
    {
        public static int Count() { /* ... */ }
    }
}
```

There is no `Clock` type anywhere in `Interop.cs`. `defaultClock()`'s return position and `labelOf`'s parameter position both erase to `SystemClock`, the `actual typealias` target, per the redirect below.

`Sensor` (an `expect class` declaring no constructor) still gets a genuine public `Sensor()` in C#, because KSP reports zero constructors on the `expect` side but a synthetic no-arg one on the `actual`, and the `actual` is what's exported.

Note the casing split, inherited unchanged from the rest of the forward direction: `platformName()` and `defaultClock()` keep Kotlin's camelCase (top-level *functions* are never PascalCased), while `PlatformTag` (a top-level *property*) and `PlatformRegistry.Count()` (an `object` member) are PascalCased. See [Top-level declarations](top-level-declarations.md) and [Objects and companions](objects-and-companions.md).

## Using it from C#

From `IntegrationTests/PlatformTests.cs`, selecting the expected value by RID since only one target's `actual` body runs on a given CI box:

```C#
[Fact]
public void PlatformName_ReturnsRunningActualsValue()
{
    // Proves the *actual* body ran, not merely that the symbol resolved: "macos"/"mingw"
    // only exist on the actual side, never on the expect header.
    Assert.Equal(ExpectedPlatformName, PlatformApi.platformName());
}

[Fact]
public void Sensor_ImplicitConstructor_ProducesUsableInstance()
{
    using var sensor = new Sensor();
    Assert.Equal(ExpectedSensorReading, sensor.Reading());
}
```

The alias redirect, typed as `SystemClock` rather than `var`, so a wrong redirect would fail to *compile*, not just fail an assertion:

```C#
[Fact]
public void DefaultClock_ReturnsSystemClockAndReportsSystemClockLabel()
{
    using SystemClock clock = PlatformApi.defaultClock();
    Assert.Equal("system-clock", clock.Label());
}
```

The negative half of the file-naming rule:

```C#
[Fact]
public void PlatformApi_TargetSpecificClassNamesDoNotExist()
{
    Assert.Null(Type.GetType("TestLibrary.Platform.PlatformApiMacos, TestLibrary"));
    Assert.Null(Type.GetType("TestLibrary.Platform.PlatformApiMingw, TestLibrary"));
}
```

## The `actual typealias` redirect

`actual typealias Clock = SystemClock` means the `expect class Clock` and `SystemClock` are one type in C# but two separate declarations to KSP, and a type reference to `Clock` resolves to the `expect` class declaration from every source set, never to the alias. So the redirect is name-based: every position typed `Clock` (a parameter, a return, a property) is classified as `SystemClock` instead, and `Clock` never enters the generated C# at all. This mirrors how an ordinary `typealias` already erases to its underlying type (see [Generics](generics.md)).

When the alias target isn't itself exportable, a member that mentions it is skipped rather than emitting a `Clock` that was never generated:

```
[nuget:SKIPPED_ACTUAL_TYPEALIAS_TARGET] Skipping failureLabel: its ACTUAL_TYPEALIAS_TARGET type
    combination is not supported. the `actual typealias` for `Failure` resolves to
    `IllegalStateException`, which the forward direction does not export; wrap it in a class you
    declare and expose that instead
```

This fires for `actual typealias Failure = kotlin.IllegalStateException`, a stdlib type the forward direction can never bring into scope. There's no such member in `test-library`; this shape is pinned as a permanent Tier 1 regression cell instead, since a `SKIPPED_*` warning must not sit in a real package's build log forever.

## Constructor default parameters on an `expect` class

Kotlin forbids an `actual` from restating a default value, so on an `expect`/`actual class` pair
every parameter of the exported (`actual`) declaration reports `hasDefault = false`; the value
lives only on the `expect`. [Constructor default parameters](classes-and-objects.md#constructor-default-parameters)
would silently and "correctly" conclude that no `expect` class has any defaults unless something
consults the `expect` side directly, so the planner looks the owning class up in the same
`expectsByName` index this page's export-root rule already relies on, and reads the default off
the **expect's primary constructor**.

`Beacon`, from `test-library/src/nativeMain/kotlin/.../platform/PlatformApi.kt`:

```kotlin
expect class Beacon(name: String, interval: Int = 5) {
  fun describe(): String
}
```

`interval`'s default (`5`) is declared once, on the `expect`, and never repeated on either actual.
The generated C# still gets the omitting overload:

```C#
public class Beacon : IDisposable
{
    public Beacon(string name, int interval) { /* ... */ } // full signature

    public Beacon(string name) { /* ... */ } // interval omitted; Kotlin supplies 5
}
```

From `IntegrationTests/ConstructorDefaultParameterTests.cs`:

```C#
[Fact]
public void Beacon_OmittingInterval_UsesTheExpectDeclaredDefault()
{
    // The trap: without the expectsByName lookup the planner concludes "no defaults" and this
    // line is CS7036. The value 5 exists only on the expect side.
    using var beacon = new Beacon("Oreo's collar");

    string expected = IsMacOs
        ? "Oreo's collar every 5s on macos"
        : "Oreo's collar every 5s on mingw";
    Assert.Equal(expected, beacon.Describe());
}
```

This lookup only covers an `expect` class's **primary** constructor; a secondary constructor on an
`expect`/`actual` class gets no synthesized overloads in v1, since matching an actual secondary
constructor to its expect counterpart would need a signature-matching rule across two declarations
that has not been verified. See [Classes and objects](classes-and-objects.md#constructor-default-parameters)
for the general mechanism.

## Function default parameters on a top-level `expect` function

The same restatement rule applies to an ordinary function: an `actual fun` cannot restate a
default, so every parameter of the exported declaration reports `hasDefault = false`. Of the five
function routes [ADR-096](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/096-function-default-parameters.md)
covers, **only the top-level-function route** consults the `expect` side for this bit; a class
method, `object` member, companion member, or extension declared on an `expect` class gets no
synthesized overload, even where the equivalent constructor case above does.

`beaconLabel`, from `test-library/src/nativeMain/kotlin/.../platform/PlatformApi.kt`:

```kotlin
expect fun beaconLabel(prefix: String, level: Int = 7): String
```

`level`'s default (`7`) is declared once, on the `expect`, and never repeated on either actual:

```kotlin
actual fun beaconLabel(prefix: String, level: Int): String = "$prefix at level $level on macos"
```

The generated C# still gets the omitting overload:

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "beaconLabel")]
private static extern IntPtr Native_beaconLabel(string prefix, int level, out IntPtr error);

public static string beaconLabel(string prefix, int level) { /* ... */ } // full signature

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "beaconLabel_2")]
private static extern IntPtr Native_beaconLabel_2(string prefix, out IntPtr error);

public static string beaconLabel(string prefix) { /* ... */ } // level omitted; Kotlin supplies 7
```

From `IntegrationTests/FunctionDefaultParameterTests.cs`:

```C#
[Fact]
public void BeaconLabel_OmittingLevel_UsesTheExpectDeclaredDefault()
{
    // Kotlin forbids an `actual` from restating a default, so every parameter of the EXPORTED
    // declaration reports hasDefault = false. Without the expectsByName lookup the planner
    // concludes "no defaults" and this line is CS7036. The value 7 exists only on the expect.
    string expected = IsMacOs
        ? "Oreo's collar at level 7 on macos"
        : "Oreo's collar at level 7 on mingw";

    Assert.Equal(expected, PlatformApi.beaconLabel("Oreo's collar"));
}
```

See [Function default parameters](top-level-declarations.md#function-default-parameters) for the
general mechanism.

## Limitations

- `expect sealed class`: not exercised. `getSealedSubclasses()` against an actualized sealed class hasn't been spiked.
- `actual typealias` to a generic or parameterized target (`actual typealias Bag = List<String>`): the redirect substitutes a `KSClassDeclaration` and loses type arguments, so this routes through `SKIPPED_ACTUAL_TYPEALIAS_TARGET` rather than binding.
- Cross-module (klib) `expect`/`actual`: guarded defensively, but the underlying resolution behaviour hasn't been spiked.
- KDoc and annotations declared on the `expect` are invisible after the filter (nothing in the generator consumes either today).
- Two packaged targets can legitimately generate different C# APIs when their `actual`s diverge beyond the `expect`'s contract, and only one target's `Interop.cs` ships (`packNuget` packages exactly one target's output while shipping every target's binary). Nothing currently diffs the two; see the open cross-target-divergence item in [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).
- A secondary constructor on an `expect`/`actual class` gets no synthesized default-parameter overloads; see the "Constructor default parameters on an `expect` class" section above.
- Function default parameters on an `expect` declaration are only surfaced on the top-level-function route; a class method, `object` member, companion member, or extension declared on an `expect` class gets no synthesized overload. See "Function default parameters on a top-level `expect` function" above.

<seealso>
    <category ref="related">
        <a href="forward-overview.md">Publishing Kotlin to C#</a>
        <a href="classes-and-objects.md">Classes and objects</a>
        <a href="objects-and-companions.md">Objects and companions</a>
        <a href="top-level-declarations.md">Top-level declarations</a>
        <a href="generics.md">Generics</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/074-expect-actual-declarations.md">ADR-074: Forward expect/actual declarations</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/007-top-level-function-class-naming.md">ADR-007: Top-level function class naming</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/018-type-alias-mapping.md">ADR-018: Type alias mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/091-constructor-default-parameters.md">ADR-091: Constructor default parameters</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/096-function-default-parameters.md">ADR-096: Function default parameters</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md">ADR-064: Forward unsupported-declaration diagnostics</a>
    </category>
</seealso>
