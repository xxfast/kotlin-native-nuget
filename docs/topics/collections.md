# Collections

Kotlin's collection types cross the bridge as an opaque handle plus a small accessor surface (`Count`/`Get`/`ContainsKey`/...), then get eagerly copied into a real .NET collection on the C# side. There's no lazy bridging or lasting connection between the returned C# collection and the Kotlin one after the copy. A `var` collection property can still be **reassigned**, though: the whole collection is replaced on the Kotlin side, see [Mutable collection properties](#mutable-collection-properties) below.

| Kotlin | C# | Notes |
|---|---|---|
| `List<T>` | `IReadOnlyList<T>` | eager copy via opaque handle |
| `MutableList<T>` | `IList<T>` | eager copy |
| `Map<K,V>` | `IReadOnlyDictionary<K,V>` | eager copy |
| `MutableMap<K,V>` | `IDictionary<K,V>` | eager copy |
| `Set<T>` | `IReadOnlySet<T>` | eager copy |
| `MutableSet<T>` | `ISet<T>` | eager copy |
| `T?` (nullable collection reference) | `T?` | `null` ⇄ `IntPtr.Zero`, both directions; independent of element/component nullability |

## Kotlin

From `test-library/src/nativeMain/kotlin/.../cat/Cat.kt`:

```kotlin
val nicknames: List<String> = listOf("${name}y", "Little $name")
val toys: List<Toy> = listOf(Toy("Mouse", "Gray"), Toy("Ball", "Red"))
val favoriteFoods: MutableList<String> = mutableListOf("Tuna", "Salmon")
val accessories: Map<String, Toy> = mapOf(
  "collar" to Toy("Bell Collar", "Gold"),
  "tag" to Toy("Name Tag", "Silver"),
)
val traits: Set<String> = setOf("Playful", "Curious", "Fluffy")
val vaccinations: MutableSet<String> = mutableSetOf("Rabies", "FVRCP")
val schedule: MutableMap<String, String> = mutableMapOf(
  "morning" to "Nap",
  "evening" to "Play",
)
```

## Generated C#

Every collection-typed property follows the same shape: fetch an opaque `listHandle`/`mapHandle`/`setHandle` from Kotlin, walk it with `Count`/`Get`/`KeyAt`/`ValueAt`/`ElementAt`, copy into a real .NET collection, then dispose the Kotlin-side handle.

```C#
public IReadOnlyList<string> Nicknames
{
    get
    {
        IntPtr listHandle = Native_Get_nicknames(_handle, out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        int count = NugetListNative.Count(listHandle);
        var result = new List<string>(count);
        for (int i = 0; i < count; i++)
        {
            result.Add(NugetMarshal.FromHandle<string>(NugetListNative.Get(listHandle, i)));
        }
        NugetListNative.Dispose(listHandle);
        return result.AsReadOnly();
    }
}

public IList<string> FavoriteFoods
{
    get
    {
        /* same walk as Nicknames, but no .AsReadOnly(), returns the mutable List<string> directly */
    }
}

public IReadOnlyDictionary<string, Toy> Accessories
{
    get
    {
        IntPtr mapHandle = Native_Get_accessories(_handle, out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        int count = NugetMapNative.Count(mapHandle);
        var result = new Dictionary<string, Toy>(count);
        for (int i = 0; i < count; i++)
        {
            var key = NugetMarshal.FromHandle<string>(NugetMapNative.KeyAt(mapHandle, i));
            var value = NugetMarshal.FromHandle<Toy>(NugetMapNative.ValueAt(mapHandle, i));
            result[key] = value;
        }
        NugetMapNative.Dispose(mapHandle);
        return result;
    }
}

public IReadOnlySet<string> Traits
{
    get
    {
        IntPtr setHandle = Native_Get_traits(_handle, out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        int count = NugetSetNative.Count(setHandle);
        var result = new HashSet<string>(count);
        for (int i = 0; i < count; i++)
        {
            result.Add(NugetMarshal.FromHandle<string>(NugetSetNative.ElementAt(setHandle, i)));
        }
        NugetSetNative.Dispose(setHandle);
        return result;
    }
}
```

`MutableMap<K,V>` (`Schedule`) and `MutableSet<T>` (`Vaccinations`) follow the same walk, exposed as `IDictionary<K,V>`/`ISet<T>` instead of the read-only interfaces.

## Using it from C#

`List<T>`, from `IntegrationTests/ListTests.cs`:

```C#
[Fact]
public void Cat_Nicknames_ListEquality()
{
    using var cat = new Cat("Oreo", 9);
    IReadOnlyList<string> nicknames = cat.Nicknames;
    Assert.Equal(new List<string> { "Oreoy", "Little Oreo" }, nicknames);
}
```

`MutableList<T>`, from `IntegrationTests/MutableListTests.cs`:

```C#
[Fact]
public void Cat_FavoriteFoods_IsMutable()
{
    using var cat = new Cat("Oreo", 9);
    IList<string> foods = cat.FavoriteFoods;
    foods.Add("Chicken");
    Assert.Equal(3, foods.Count);
    Assert.Equal("Chicken", foods[2]);
}
```

Mutating the returned `IList<string>` only changes the C#-side copy. It does not write back to the Kotlin `Cat` instance, since the collection was eagerly copied at the moment of the property access.

`Map<K,V>`, from `IntegrationTests/MapTests.cs`:

```C#
[Fact]
public void Cat_Accessories_GetByKey()
{
    using var cat = new Cat("Oreo", 9);
    IReadOnlyDictionary<string, Toy> accessories = cat.Accessories;
    using var collar = accessories["collar"];
    Assert.Equal("Bell Collar", collar.Name);
    Assert.Equal("Gold", collar.Color);
}
```

`Set<T>`, from `IntegrationTests/SetTests.cs`:

```C#
[Fact]
public void Cat_Traits_SetEquality()
{
    using var cat = new Cat("Oreo", 9);
    IReadOnlySet<string> traits = cat.Traits;
    var expected = new HashSet<string> { "Fluffy", "Playful", "Curious" };
    Assert.True(traits.SetEquals(expected));
}
```

## Returned from a method or extension function

`List<T>`, `Map<K,V>`, `Set<T>`, and their mutable variants also marshal as class-method or
extension-function returns, not only as properties. The collection is boxed as the same object
carrier an object return uses, and the shared `NugetListNative` / `NugetMapNative` / `NugetSetNative`
helpers materialize it exactly as they do for a property (see [Method returns](classes-and-objects.md),
[ADR-061](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/061-method-return-marshalling.md),
and [ADR-062](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md)):

```kotlin
// test-library/.../cat/Cat.kt
fun tags(): List<String> = listOf("$name-tag", "$name-chip")
fun scores(): List<Int> = listOf(lives, lives * 2)

// test-library/.../clinic/ClinicSample.kt
fun scores(): Map<String, Int> = mapOf("weight" to (weight ?: 0))
fun labels(): Set<String> = setOf(name)
```

```C#
public IReadOnlyList<string> Tags()
{
        IntPtr listHandle = Native_Tags(_handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        int count = NugetListNative.Count(listHandle);
        var result = new List<string>(count);
        for (int i = 0; i < count; i++)
        {
            result.Add(NugetMarshal.FromHandle<string>(NugetListNative.Get(listHandle, i)));
        }
        NugetListNative.Dispose(listHandle);
        return result.AsReadOnly();
}

public IReadOnlyDictionary<string, int> Scores()
{
    IntPtr mapHandle = Native_Scores(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    int count = NugetMapNative.Count(mapHandle);
    var result = new Dictionary<string, int>(count);
    for (int i = 0; i < count; i++)
    {
        var key = NugetMarshal.FromHandle<string>(NugetMapNative.KeyAt(mapHandle, i));
        var value = NugetMarshal.FromHandle<int>(NugetMapNative.ValueAt(mapHandle, i));
        result[key] = value;
    }
    NugetMapNative.Dispose(mapHandle);
    return result;
}

public IReadOnlySet<string> Labels()
{
    IntPtr setHandle = Native_Labels(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    int count = NugetSetNative.Count(setHandle);
    var result = new HashSet<string>(count);
    for (int i = 0; i < count; i++)
    {
        result.Add(NugetMarshal.FromHandle<string>(NugetSetNative.ElementAt(setHandle, i)));
    }
    NugetSetNative.Dispose(setHandle);
    return result;
}
```

From `IntegrationTests/ReturnAndPropertyMarshallingTests.cs`:

```C#
[Fact]
public void Patient_Scores_ReturnsWeightMap()
{
    using var patient = new Patient("Oreo");
    patient.AdjustWeight(7);
    var scores = patient.Scores();
    Assert.Single(scores);
    Assert.Equal(7, scores["weight"]);
}

[Fact]
public void Patient_Labels_ReturnsNameSet()
{
    using var patient = new Patient("Oreo");
    var labels = patient.Labels();
    Assert.Single(labels);
    Assert.Contains("Oreo", labels);
}
```

`List<T>` **inputs** (method parameters) also plan: the C# side builds a temporary handle via
`NugetMarshal.CreateList` and the Kotlin side walks it. `Map`, `MutableMap`, `Set` and `MutableSet`
**inputs** plan the same way, via `NugetMarshal.CreateMap`/`CreateSet`.

## Method parameters

A `Map<K,V>`, `MutableMap<K,V>`, `Set<T>` or `MutableSet<T>` parameter mirrors the return-position
mapping above, at every call position: class method, companion method, top-level function,
extension function, and constructor.

From `test-library/.../clinic/ClinicSample.kt`:

```kotlin
class Patient(val name: String) {
  fun recordScores(scores: Map<String, Int>): Int = scores.values.sum()

  fun tallyScores(scores: MutableMap<String, Int>): Int {
    scores["total"] = scores.values.sum()
    return scores.size
  }

  fun linkWard(ward: Map<String, Patient>): Int = ward.values.count { it.name.isNotBlank() }

  fun addLabels(labels: Set<String>): Int = labels.size

  fun addCodes(codes: MutableSet<Int>): Int {
    codes.add(0)
    return codes.size
  }

  companion object {
    fun batchScore(scores: Map<String, Int>): Int = scores.size
  }
}

fun countLabels(labels: Set<String>): Int = labels.size

fun Patient.mergeScores(extra: Map<String, Int>): Int = extra.size

class Ward(val name: String, val tags: Set<String>)
```

Generated C#, from `Interop.cs`:

```C#
public int RecordScores(IReadOnlyDictionary<string, int> scores)
{
    IntPtr scoresHandle = NugetMarshal.CreateMap(scores);
    int nativeResult = Native_RecordScores(_handle, scoresHandle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    NugetMapNative.Dispose(scoresHandle);
    return nativeResult;
}

public int TallyScores(IDictionary<string, int> scores)
{
    IntPtr scoresHandle = NugetMarshal.CreateMap(scores);
    int nativeResult = Native_TallyScores(_handle, scoresHandle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    NugetMapNative.Dispose(scoresHandle);
    return nativeResult;
}

public int LinkWard(IReadOnlyDictionary<string, Patient> ward)
{
    IntPtr wardHandle = NugetMarshal.CreateMap(ward);
    int nativeResult = Native_LinkWard(_handle, wardHandle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    NugetMapNative.Dispose(wardHandle);
    return nativeResult;
}

public int AddLabels(IReadOnlySet<string> labels)
{
    IntPtr labelsHandle = NugetMarshal.CreateSet(labels);
    int nativeResult = Native_AddLabels(_handle, labelsHandle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    NugetSetNative.Dispose(labelsHandle);
    return nativeResult;
}

public int AddCodes(ISet<int> codes)
{
    IntPtr codesHandle = NugetMarshal.CreateSet(codes);
    int nativeResult = Native_AddCodes(_handle, codesHandle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    NugetSetNative.Dispose(codesHandle);
    return nativeResult;
}

public static int BatchScore(IReadOnlyDictionary<string, int> scores) { /* same shape, static */ }

public static int countLabels(IReadOnlySet<string> labels) { /* top-level function */ }

public static int MergeScores(this Patient receiver, IReadOnlyDictionary<string, int> extra) { /* extension */ }

public Ward(string name, IReadOnlySet<string> tags) { /* constructor */ }
```

<note>
    <p>A <code>MutableMap</code>/<code>MutableSet</code> parameter does not write back. Contents
    are copied into Kotlin. Changes Kotlin makes are not reflected back in the collection you
    passed. This matches <code>MutableList</code>'s existing input behavior.</p>
</note>

From `IntegrationTests/MapSetParameterMarshallingTests.cs`:

```C#
[Fact]
public void Patient_RecordScores_SumsTheValues()
{
    using var oreo = new Patient("Oreo");

    int total = oreo.RecordScores(new Dictionary<string, int> { ["agility"] = 3, ["cuddles"] = 4 });

    Assert.Equal(7, total);
}

// ADR-073 no-write-back regression: the Kotlin body puts "total" into its copy, but Oreo's own
// Dictionary must come back untouched.
[Fact]
public void Patient_TallyScores_MutatesKotlinsCopyOnly_CallersDictionaryIsUnchanged()
{
    using var oreo = new Patient("Oreo");
    var scores = new Dictionary<string, int> { ["agility"] = 3 };

    int tally = oreo.TallyScores(scores);

    Assert.Equal(2, tally);
    Assert.Single(scores);
    Assert.False(scores.ContainsKey("total"));
    Assert.Equal(3, scores["agility"]);
}

[Fact]
public void Ward_Constructor_MarshalsSetParameter()
{
    using var ward = new Ward("East Wing", new HashSet<string> { "quiet", "sunny" });

    Assert.Equal("East Wing", ward.Name);
    Assert.Equal(2, ward.Tags.Count);
    Assert.Contains("quiet", ward.Tags);
}
```

A plain `Dictionary`/`HashSet` is directly assignable at every one of the four parameter kinds; a
bare `IDictionary<K,V>` cannot be passed to a `Map<K,V>` parameter (it needs `IReadOnlyDictionary`),
and a bare `IReadOnlyDictionary<K,V>` cannot be passed to a `MutableMap<K,V>` parameter (it needs
`IDictionary`). Same asymmetry for `Set`/`ISet`/`IReadOnlySet`.

## Nullable collection references

A `List`/`Map`/`Set` property (`val` or `var`) can itself be nullable
(`List<String>?`), independent of whether its elements are. A `null` Kotlin value crosses as
`IntPtr.Zero` and materializes as C# `null`, not an empty collection or a stray handle
([ADR-075](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/075-collection-property-getter-setter-independence.md)).
The same nullable shape also plans as an ordinary callable **parameter**: a constructor, method, or
generated `copy()` can take a `List<String>?`.

From `test-library/.../clinic/ClinicSample.kt`:

```kotlin
data class Visit(
  val patient: String,
  val symptoms: List<String>,
  val notes: List<String>? = null,
)

class Roster(nurse: Nurse?) {
  val staff: List<Nurse>? = nurse?.let { listOf(it) }
}
```

Generated C#, from `Interop.cs`. The constructor builds `notesHandle` only when `notes` isn't `null`;
the getter checks for a null handle before walking it:

```C#
public Visit(string patient, IReadOnlyList<string> symptoms, IReadOnlyList<string>? notes)
{
    IntPtr symptomsHandle = NugetMarshal.CreateList(symptoms);
    IntPtr notesHandle = notes != null ? NugetMarshal.CreateList(notes) : IntPtr.Zero;
    IntPtr handle = Native_Create(patient, symptomsHandle, notesHandle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    NugetListNative.Dispose(symptomsHandle);
    if (notesHandle != IntPtr.Zero) { NugetListNative.Dispose(notesHandle); }
    _handle = handle;
}

public IReadOnlyList<string>? Notes
{
    get
    {            IntPtr nativeResult = Native_Get_notes(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    if (nativeResult == IntPtr.Zero) return null;
    int count = NugetListNative.Count(nativeResult);
    var result = new List<string>(count);
    for (int i = 0; i < count; i++)
    {
        result.Add(NugetMarshal.FromHandle<string>(NugetListNative.Get(nativeResult, i)));
    }
    NugetListNative.Dispose(nativeResult);
    return result.AsReadOnly();
    }
}
```

`Roster.Staff: IReadOnlyList<Nurse>?` follows the same shape for an `ObjectHandle` element, which
still needs `FromHandle<Nurse>` per entry once the null-handle check passes.

From `IntegrationTests/CollectionPropertyIndependenceTests.cs`:

```C#
[Fact]
public void Visit_Notes_WhenNull_IsNullNotAStrayHandle()
{
    using var visit = new Visit("Mylo", new List<string> { "sneezing" }, null);

    // This is the actual reported bug: a null Kotlin handle must come back as C# `null`,
    // not a boxed IntPtr or an empty list.
    Assert.Null(visit.Notes);
}

[Fact]
public void Roster_Staff_WithNoAttendingNurse_IsNull()
{
    using var roster = new Roster(null);

    Assert.Null(roster.Staff);
}
```

## Mutable collection properties

A `var`-declared collection property plans its getter and setter independently
([ADR-075](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/075-collection-property-getter-setter-independence.md)).
The getter is unconditional and has no element-type restriction: every element type that
materializes for a `val` also materializes for a `var`. The setter is narrower: it binds only when
every component, the element for `List`/`Set`, the key **and** value for `Map`, satisfies the same
`isWrappableComponent()` predicate the `Map`/`Set` **parameter** side above already uses
(`String`, `Int`, `Long`, `Float`, `Double`, `Boolean`, or an object handle), applied to `List` too.
When a component fails that check, the property still generates, get-only, with a
`SKIPPED_UNSUPPORTED_INPUT` diagnostic naming it.

From `test-library/.../clinic/ChartSample.kt`:

```kotlin
class Chart(val patientName: String) {
  /** Eligible: `CreateList` + `Wrap<T>` string. */
  var tags: List<String> = emptyList()

  /** Eligible: `CreateMap`, wrappable key (String) AND value (Int). */
  var counts: Map<String, Int> = emptyMap()

  /** Eligible: ObjectHandle element ([Nurse]) + the mutable-list lowering. */
  var seen: MutableList<Nurse> = mutableListOf()

  /** Eligible: the SET/MUTABLE_SET shared lowering. */
  var codes: Set<String> = emptySet()

  /** Eligible, and nullable. Round-trips both ways: assign a list and read it back, assign `null`
   *  and read back `null`. */
  var notes: List<String>? = null

  /** Ineligible: `Mood` is an enum element, not `isWrappableComponent()`. Must become a get-only
   *  C# property (`{ get; }`, no `set`) plus a `SKIPPED_UNSUPPORTED_INPUT` diagnostic. */
  var moods: List<Mood> = emptyList()

  /** Ineligible for a different reason: the *element* is nullable (`String?`, not `String`), not
   *  the collection reference. */
  var aliases: List<String?> = emptyList()
}
```

Generated C#, from `Interop.cs`. `Tags` shows an eligible setter (built via the same
`NugetMarshal.CreateList` a `List` **parameter** uses); `Notes` shows the nullable-reference variant,
`value != null ? CreateList(value) : IntPtr.Zero`; `Moods` shows the ineligible fallback, get-only,
no setter emitted at all:

```C#
public IReadOnlyList<string> Tags
{
    get
    {            IntPtr nativeResult = Native_Get_tags(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    int count = NugetListNative.Count(nativeResult);
    var result = new List<string>(count);
    for (int i = 0; i < count; i++)
    {
        result.Add(NugetMarshal.FromHandle<string>(NugetListNative.Get(nativeResult, i)));
    }
    NugetListNative.Dispose(nativeResult);
    return result.AsReadOnly();
    }
    set
    {            Native_Set_tags(_handle, NugetMarshal.CreateList(value), out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    }
}

public IReadOnlyList<string>? Notes
{
    get { /* same walk as Tags, plus: if (nativeResult == IntPtr.Zero) return null; */ }
    set
    {            Native_Set_notes(_handle, value != null ? NugetMarshal.CreateList(value) : IntPtr.Zero, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    }
}

public IReadOnlyList<global::TestLibrary.Clinic.Mood> Moods
{
    get { /* same walk as Tags: the getter is unrestricted */ }
    // no setter: Mood fails isWrappableComponent(), so this stays get-only.
}
```

From `IntegrationTests/CollectionPropertyIndependenceTests.cs`:

```C#
[Fact]
public void Chart_Tags_ListOfString_RoundTrips()
{
    using var chart = new Chart("Oreo");

    chart.Tags = new List<string> { "black", "white", "biscuit" };

    Assert.Equal(new[] { "black", "white", "biscuit" }, chart.Tags);
}

[Fact]
public void Chart_Notes_AssignedNull_RoundTrips()
{
    using var chart = new Chart("Mylo");
    chart.Notes = new List<string> { "temporary" };
    Assert.NotNull(chart.Notes);

    chart.Notes = null;

    Assert.Null(chart.Notes);
}

[Fact]
public void Chart_Moods_EnumElement_HasNoPublicSetter()
{
    var property = typeof(Chart).GetProperty(nameof(Chart.Moods));

    Assert.NotNull(property);
    Assert.NotNull(property!.GetGetMethod());
    Assert.Null(property.GetSetMethod());
}
```

<note>
    <p>The setter reassigns the whole collection: <code>chart.Tags = new List&lt;string&gt; { ... }</code>
    replaces the Kotlin-side list. The getter is still a <b>detached copy</b> per
    <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/011-collection-type-mapping.md">ADR-011</a>:
    mutating the <code>IList&lt;Nurse&gt;</code> returned by <code>chart.Seen</code> in place does not
    reach Kotlin. Read, extend on the C# side, and reassign to get the change across (read-modify-write).</p>
</note>

```C#
var attending = new List<Nurse>(chart.Seen) { barton };
chart.Seen = attending;
```

The same eligibility predicate applies to a collection-typed extension property, including one whose
receiver crosses the bridge by value (a `String`- or object-underlying value class) rather than by
object handle. From `test-library/.../clinic/ClinicSample.kt`:

```kotlin
var ChartId.symptomTags: List<String>
  get() = chartIdSymptomTags[this] ?: emptyList()
  set(value) {
    chartIdSymptomTags[this] = value
  }
```

```C#
public static IReadOnlyList<string> GetSymptomTags(this ChartId receiver) { /* same walk */ }

public static void SetSymptomTags(this ChartId receiver, IReadOnlyList<string> value)
{            Native_ChartidSetSymptomTags(receiver.Value, NugetMarshal.CreateList(value), out IntPtr error);
    ...
}
```

An ineligible setter emits a warning naming the property and the offending component, and states
that the C# property stays read-only rather than that the property was dropped:

```
[nuget:SKIPPED_UNSUPPORTED_INPUT] Skipping Chart.moods: its setter is not generated because the
    element type Mood cannot be written into a Kotlin collection. the C# property Moods is
    read-only
    at ChartSample.kt:37
```

## Limitations

- `Sequence<T>` is not bridgeable. `Cat.unsupported: Sequence<String>` in the sample library is deliberately left out of the generated `Interop.cs` (no eager-copy story for a lazy sequence).
- Only a strict subset of key/value/element types binds at a `Map`/`Set` parameter position: `String`, `Int`, `Long`, `Float`, `Double`, `Boolean`, and object handles (a class instance, boxed via reflection over its `_handle` field). Anything else, an enum (`Map<String, Mood>`), `Char` (`Set<Char>`), a nullable component (`Map<String, Int?>`), a nested collection (`Set<List<String>>`), a value class, or an interface, is skipped with `SKIPPED_UNSUPPORTED_INPUT` rather than crashing or binding incorrectly. `List`/`MutableList` parameters accept a wider (and, for some of those same shapes, unsafe) set of component types; see [ADR-073](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/073-map-and-set-parameters.md)'s Deferred section and [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md) for the tracked gap.
- `MutableMap`/`MutableSet` parameters do not write back, matching `MutableList`. Contents are copied into Kotlin; changes Kotlin makes are not reflected back in the collection you passed.
- A collection property **setter** uses the same wrappable-component predicate as a `Map`/`Set` parameter above, but applies it to `List` as well, so it is stricter than a `List` *parameter* (which still binds a wider, less safe set of element types, see above). An enum element (`List<Mood>`), a nullable element (`List<String?>`), a value-class element, or a nested-collection element skips the setter with `SKIPPED_UNSUPPORTED_INPUT` and falls back to a get-only property; the getter itself has no such restriction. See [ADR-075](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/075-collection-property-getter-setter-independence.md) and [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md) for the tracked write-side gap.
- Reading an enum-element collection getter (`List<Mood>`) is untested and known-broken at runtime: `NugetMarshal.FromHandle<T>` has no enum branch, so `chart.Moods` would throw `MissingMethodException`. Pre-existing, not introduced by the setter-independence feature; see [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).

<seealso>
    <category ref="related">
        <a href="generics.md">Generics</a>
        <a href="classes-and-objects.md">Classes and objects</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/011-collection-type-mapping.md">ADR-011: Collection type mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/061-method-return-marshalling.md">ADR-061: Method return marshalling</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md">ADR-062: Forward callable plan</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md">ADR-064: Forward unsupported-declaration diagnostics</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/073-map-and-set-parameters.md">ADR-073: Map/Set parameters</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/075-collection-property-getter-setter-independence.md">ADR-075: Collection property getter/setter independence</a>
    </category>
</seealso>
