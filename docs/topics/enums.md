# Enums

A Kotlin `enum class` becomes a plain C# `enum` with matching ordinal values. Any members declared on the enum class (properties, methods) become C# extension methods, since a C# `enum` can't carry behaviour itself.

| Kotlin | C# | Notes |
|---|---|---|
| `enum class` | `enum` | with extension methods |
| `Mood?` (bare nullable enum, not wrapped in a value class) | `Mood?` (`Nullable<Mood>`) | at property, constructor/method/extension/top-level parameter, method return, and top-level return; see [Nullable](#nullable) below |
| bare `Mood` as a `List`/`Map`/`Set` component (element, key, or value, nullable included) | element/key/value of the matching `IReadOnlyList<T>`/`IReadOnlyDictionary<K,V>`/`IReadOnlySet<T>` etc. | rides the `int` ordinal, projected per element; see [As a collection component](#as-a-collection-component) below |

## Kotlin

From `test-library/src/nativeMain/kotlin/.../cat/Mood.kt`:

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

From `IntegrationTests/EnumTests.cs`:

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

## Returned from a method

An enum-typed method return crosses as an ordinal `int` and is cast back to the C# enum, the same
way an enum property getter does. From `test-library/.../clinic/ClinicSample.kt`:

```kotlin
enum class Mood { CALM, ANXIOUS, PLAYFUL }

class Patient(val name: String) {
  fun mood(): Mood = Mood.CALM
  fun describeMood(mood: Mood): Int = mood.ordinal
}
```

```C#
public global::TestLibrary.Clinic.Mood Mood()
{
    int result = Native_Mood(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return (global::TestLibrary.Clinic.Mood)result;
}
```

From `IntegrationTests/ReturnAndPropertyMarshallingTests.cs`:

```C#
[Fact]
public void Patient_Mood_ReturnsCalm()
{
    using var patient = new Patient("Oreo");
    Assert.Equal(Mood.Calm, patient.Mood());
}
```

## Nullable

A bare `Mood?` (a nullable enum, **not** wrapped in a [value class](value-classes.md)) binds as C#
`Mood?` (`Nullable<Mood>`) at every ordinary forward position: property (class, companion,
top-level, extension), constructor/method/extension/top-level parameter, method return, and
top-level return. There is no spare pointer on an enum's `int`-ordinal wire to carry `null`, so this
rides the same has-value fan-out as `Nullable(ValueClass)` over an `Enum` underlying (see [Value
classes: Nullable over Primitive and Enum
underlyings](value-classes.md#nullable-over-primitive-and-enum-underlyings)), with the value-class
box/unbox step removed: the wire is already the ordinal.

<note>
    <p>
        A bare <code>Mood?</code> property used to abort <code>packNuget</code> outright, since its
        getter had no route once accepted by the planner; parameters and returns silently skipped
        the whole callable instead of crashing. Both are fixed; see
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/080-bare-nullable-enum.md">ADR-080</a>.
    </p>
</note>

From `test-library/src/nativeMain/kotlin/.../cat/NullableEnumSample.kt`:

```kotlin
class MoodJournal(observed: Mood?) {
  var currentMood: Mood? = null

  fun moodSummary(): String = currentMood?.description ?: "No mood recorded yet."

  fun soothe(mood: Mood?): Mood? = if (mood == Mood.GRUMPY) Mood.SLEEPY else mood
}

fun Cat.matchesMood(expected: Mood?): Boolean = expected == null || mood == expected

fun napMood(hour: Int): Mood? = when {
  hour < 0 -> null
  hour == 0 -> Mood.HAPPY
  else -> Mood.GRUMPY
}
```

Generated C#, from `Interop.cs`. The property is the `LegacyTwoCall` getter + `NullableDispatch`
setter shape:

```C#
public global::TestLibrary.Cat.Mood? CurrentMood
{
    get
    {
    bool hasValue = Native_Get_currentMood(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    if (!hasValue) return null;
    int value = Native_Get_currentMood_value(_handle, out IntPtr error2);
    if (error2 != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error2);
    }
    return (global::TestLibrary.Cat.Mood)value;
    }
    set
    {
    if (value.HasValue)
    {
        Native_Set_currentMood(_handle, (int)value.Value, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
    }
    else
    {
        Native_Set_currentMood_null(_handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
    }
    }
}
```

The parameter + method return, on the ADR-061 single-call `valueOut` shape:

```C#
public global::TestLibrary.Cat.Mood? Soothe(global::TestLibrary.Cat.Mood? mood)
{
    bool hasValue = Native_Soothe(_handle, mood.HasValue, (int)mood.GetValueOrDefault(), out int valueOut, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return hasValue ? (global::TestLibrary.Cat.Mood)valueOut : (global::TestLibrary.Cat.Mood?)null;
}
```

The extension parameter:

```C#
public static bool MatchesMood(this Cat receiver, global::TestLibrary.Cat.Mood? expected)
{
    bool nativeResult = Native_MatchesMood(receiver._handle, expected.HasValue, (int)expected.GetValueOrDefault(), out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return nativeResult;
}
```

The top-level function return, on the ADR-002 two-call shape:

```C#
public static global::TestLibrary.Cat.Mood? napMood(int hour)
{
    bool __nuget_hasValue = napMood_has_value(hour, out IntPtr __nuget_hasValueError);
    if (__nuget_hasValueError != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(__nuget_hasValueError);
    }
    if (!__nuget_hasValue) return null;
    int __nuget_value = napMood_value(hour, out IntPtr __nuget_valueError);
    if (__nuget_valueError != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(__nuget_valueError);
    }
    return (global::TestLibrary.Cat.Mood)__nuget_value;
}
```

### Using it from C# {id="using-it-from-c_1"}

From `IntegrationTests/NullableEnumTests.cs`:

```C#
[Fact]
public void MoodJournal_CurrentMood_NonFirstOrdinalRoundTrips()
{
    using var oreo = new MoodJournal(null);

    // Mood.Grumpy is ordinal 2: a getter that always reads slot 0 fails here.
    oreo.CurrentMood = Mood.Grumpy;

    Assert.Equal(Mood.Grumpy, oreo.CurrentMood);
    Assert.Equal("The cat is grumpy and doesn't want to be disturbed.", oreo.MoodSummary());
}

[Fact]
public void MoodJournal_Soothe_GrumpySettlesToSleepy()
{
    using var oreo = new MoodJournal(null);

    // Ordinal 2 in, ordinal 1 out: neither slot can pass by echoing a zero.
    Mood? soothed = oreo.Soothe(Mood.Grumpy);

    Assert.NotNull(soothed);
    Assert.Equal(Mood.Sleepy, soothed);
}

[Fact]
public void NullableEnumSample_NapMood_ZeroHourReturnsOrdinalZeroAsNonNull()
{
    // Mood.Happy is a legitimate entry, not the in-band sentinel this two-call shape exists to
    // avoid confusing with null.
    Mood? mood = NullableEnumSample.napMood(0);

    Assert.NotNull(mood);
    Assert.Equal(Mood.Happy, mood);
}
```

<note>
    <p>
        <code>Mood.Happy</code> is ordinal 0, the mandatory sentinel-catching cell: it must survive
        as non-null and distinguishable from <code>null</code>, or a has-value/value mix-up would
        pass for the wrong reason. <code>Mood.Grumpy</code> is ordinal 2, the non-first-ordinal
        cell, so a getter that always reads slot 0 cannot pass either.
    </p>
</note>

## As a collection component

A bare enum (not wrapped in a [value class](value-classes.md)) crosses a `List`/`MutableList`/
`Map`/`MutableMap`/`Set`/`MutableSet` component slot (element, map key, or map value, nullable
included) as its `Int` ordinal, at input positions, collection property setters, method returns, and
property getters
([ADR-097](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/097-enum-collection-components.md)).
This generalizes the value-class-over-enum wire that [Value classes: As a collection
component](value-classes.md#as-a-collection-component) already ships one level down, minus the
wrapper: C# projects each element to its ordinal at the call site before boxing (`Select(x =>
(int)x)`), and Kotlin re-wraps per element on the way in and casts back on the way out
(`Mood.entries[it as kotlin.Int]`).

<note>
    <p>Before this, a bare enum collection component was bind-then-throw in <b>both</b> directions:
    the write side's <code>Wrap&lt;T&gt;</code> had no branch for an enum, and the read side's
    <code>NugetMarshal.FromHandle&lt;Mood&gt;</code> fell through to <code>Materialize</code> with no
    <code>Factories</code> entry. Both threw <code>NotSupportedException</code> at the first call;
    both are fixed here, not merely narrowed.</p>
</note>

From `test-library/src/nativeMain/kotlin/.../clinic/EnumComponentCollectionsSample.kt`:

```kotlin
class MoodLedger {
  fun logMoods(moods: List<Mood>): String = moods.joinToString(",")

  fun tallyMoods(moods: Set<Mood>): String = moods.map { it.name }.sorted().joinToString(",")

  fun chartMoods(byPatient: Map<String, Mood>): String =
    byPatient.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

  fun moodRoster(byMood: Map<Mood, String>): String =
    byMood.entries.sortedBy { it.key.ordinal }.joinToString(";") { "${it.key}=${it.value}" }

  fun logMoodTrail(moods: List<Mood?>): String = moods.joinToString(",") { it?.name ?: "null" }

  fun moodsOnFile(): List<Mood> = listOf(Mood.CALM, Mood.PLAYFUL)

  fun moodChart(): Map<String, Mood> = mapOf("oreo" to Mood.CALM, "mylo" to Mood.ANXIOUS)
}
```

Generated C#, from `Interop.cs`. The element slot (`LogMoods`), the map value slot (`ChartMoods`) and
the map key slot (`MoodRoster`) each project independently:

```C#
public string LogMoods(IReadOnlyList<global::TestLibrary.Clinic.Mood> moods)
{
    IntPtr moodsHandle = IntPtr.Zero;
    try
    {
        moodsHandle = NugetMarshal.CreateList(global::System.Linq.Enumerable.Select(moods, x => (int)x));
        IntPtr nativeResult = Native_LogMoods(_handle, moodsHandle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return Marshal.PtrToStringUTF8(nativeResult)!;
    }
    finally
    {
        if (moodsHandle != IntPtr.Zero) { NugetListNative.Dispose(moodsHandle); }
    }
}

public string ChartMoods(IReadOnlyDictionary<string, global::TestLibrary.Clinic.Mood> byPatient)
{
    IntPtr byPatientHandle = IntPtr.Zero;
    try
    {
        byPatientHandle = NugetMarshal.CreateMap(global::System.Linq.Enumerable.Select(byPatient, x => new KeyValuePair<string, int>(x.Key, (int)x.Value)));
        // ...
    }
    finally { if (byPatientHandle != IntPtr.Zero) { NugetMapNative.Dispose(byPatientHandle); } }
}

public string MoodRoster(IReadOnlyDictionary<global::TestLibrary.Clinic.Mood, string> byMood)
{
    IntPtr byMoodHandle = IntPtr.Zero;
    try
    {
        byMoodHandle = NugetMarshal.CreateMap(global::System.Linq.Enumerable.Select(byMood, x => new KeyValuePair<int, string>((int)x.Key, x.Value)));
        // ...
    }
    finally { if (byMoodHandle != IntPtr.Zero) { NugetMapNative.Dispose(byMoodHandle); } }
}
```

A nullable enum element (`List<Mood?>`) is admitted automatically the moment a bare enum is allowed,
by `isWrappableComponent()`'s existing `Nullable` delegation, and carries its own write projection:

```C#
public string LogMoodTrail(IReadOnlyList<global::TestLibrary.Clinic.Mood?> moods)
{
    IntPtr moodsHandle = IntPtr.Zero;
    try
    {
        moodsHandle = NugetMarshal.CreateList(global::System.Linq.Enumerable.Select(moods, x => x == null ? (int?)null : (int)x.Value));
        // ...
    }
    finally { if (moodsHandle != IntPtr.Zero) { NugetListNative.Dispose(moodsHandle); } }
}
```

The read side, method return and `Map` value, closes the matching bind-then-throw bug: each element
is read as `int` and cast back, never as `Mood` directly:

```C#
public IReadOnlyList<global::TestLibrary.Clinic.Mood> MoodsOnFile()
{
    IntPtr listHandle = Native_MoodsOnFile(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    int count = NugetListNative.Count(listHandle);
    var result = new List<global::TestLibrary.Clinic.Mood>(count);
    for (int i = 0; i < count; i++)
    {
        result.Add((global::TestLibrary.Clinic.Mood)NugetMarshal.FromHandle<int>(NugetListNative.Get(listHandle, i)));
    }
    NugetListNative.Dispose(listHandle);
    return result.AsReadOnly();
}
```

From `IntegrationTests/EnumComponentCollectionTests.cs`:

```C#
[Fact]
public void MoodLedger_LogMoods_ListOfEnumParameter_RoundTripsEveryElement()
{
    using var ledger = new MoodLedger();

    // The restatement's exact shape. Calm repeats at a non-adjacent index, so a projection
    // that de-duplicated or reordered would not produce this string.
    string logged = ledger.LogMoods(new[] { Mood.Calm, Mood.Playful, Mood.Calm });

    Assert.Equal("CALM,PLAYFUL,CALM", logged);
}

[Fact]
public void MoodLedger_MoodsOnFile_ListOfEnumReturn_MaterializesEveryElement()
{
    using var ledger = new MoodLedger();

    // Read side. Binds today and used to throw NotSupportedException out of FromHandle<Mood> ->
    // Materialize at the first read -- a shipped bug, not a missing capability.
    IReadOnlyList<Mood> moods = ledger.MoodsOnFile();

    Assert.Equal(new[] { Mood.Calm, Mood.Playful }, moods);
}

[Fact]
public void MoodLedger_LogMoodTrail_ListOfNullableEnumParameter_RoundTripsNullAndRealElement()
{
    using var ledger = new MoodLedger();

    string trail = ledger.LogMoodTrail(new Mood?[] { Mood.Calm, null, Mood.Playful });

    Assert.Equal("CALM,null,PLAYFUL", trail);
}
```

This same eligibility change flips a bare-enum-element collection **property** from get-only to
settable, since a `var` collection property's setter uses the same `isWrappableComponent()` predicate
these input positions do; see [Collections: Mutable collection
properties](collections.md#mutable-collection-properties).

<note>
    <p>The gate that admits a bare enum also narrows <code>List</code>'s callable-input gate itself,
    from <code>isBridgeableComponent()</code> to the same <code>isWrappableComponent()</code>
    predicate <code>Map</code>/<code>Set</code> parameters already used. This is a <b>deliberate
    breaking change</b>: a <code>List&lt;Short&gt;</code>-, <code>List&lt;Char&gt;</code>-, or
    <code>List&lt;List&lt;String&gt;&gt;</code>-shaped parameter (a narrow primitive, <code>Char</code>,
    or a nested collection) loses its generated C# member. No shape that <b>worked</b> is removed:
    every one of them threw <code>NotSupportedException</code> at the first call or crashed
    <code>packNuget</code> before this change. A consumer that merely compiled against such a member,
    without ever calling it successfully, is the one affected.</p>
</note>

## Limitations

- `Char` and the narrow primitive kinds (`Byte`/`UByte`/`Short`/`UShort`/`UInt`/`ULong`) still cannot
  be boxed at a collection component position; a `Set<Char>` or `List<Byte>` skips with
  `SKIPPED_UNSUPPORTED_INPUT`. This is unrelated to enums; see [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).
- A nested-collection component (`List<List<Mood>>`) has no representation on the write side and is
  skipped; see [Collections](collections.md).

<seealso>
    <category ref="related">
        <a href="classes-and-objects.md">Classes and objects</a>
        <a href="value-classes.md">Value classes</a>
        <a href="collections.md">Collections</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/006-enum-mapping.md">ADR-006: Enum mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md">ADR-062: Forward callable plan</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/079-nullable-primitive-enum-underlying-value-classes.md">ADR-079: Nullable(ValueClass) over Primitive/Enum-underlying value classes</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/080-bare-nullable-enum.md">ADR-080: Bare nullable enums</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/081-value-class-collection-components.md">ADR-081: Value-class collection components</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/097-enum-collection-components.md">ADR-097: Enum collection components</a>
    </category>
</seealso>
