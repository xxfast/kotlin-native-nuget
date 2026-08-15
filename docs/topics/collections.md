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

## Exception safety on collection parameters

A collection parameter's temporary native handle is released on every exit path, not only the
successful one. If the Kotlin callee throws, the generated C# still disposes the handle it built:
a parameter list with at least one collection argument pre-declares the handle as `IntPtr.Zero`,
builds it inside a `try`, and disposes it (zero-guarded) in a matching `finally`, whether the call
returns or throws.

From `test-library/.../cat/CollectionExceptions.kt`:

```kotlin
class Auditor {
  fun audit(entries: List<String>): Int =
    throw IllegalStateException("audit failed: ${entries.size} entries do not balance")
}
```

Generated C#, from `Interop.cs`:

```C#
public int Audit(IReadOnlyList<string> entries)
{
    IntPtr entriesHandle = IntPtr.Zero;
    try
    {
        entriesHandle = NugetMarshal.CreateList(entries);
        int nativeResult = Native_Audit(_handle, entriesHandle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return nativeResult;
    }
    finally
    {
        if (entriesHandle != IntPtr.Zero) { NugetListNative.Dispose(entriesHandle); }
    }
}
```

The same guarded `finally` also covers a constructor's `Nullable(Collection)` prelude (`Visit`'s
`notesHandle`, see [Nullable collection references](#nullable-collection-references) below) and an
interface transfer handle: whatever temporary handle a call site builds, it is released whether the
callee returns or throws.

`NugetMarshal.CreateList`/`CreateMap`/`CreateSet` are exception-safe on the way in too. If
enumerating the C# collection argument throws partway through, a custom `IEnumerable`
implementation that fails mid-`MoveNext`, the partially-built native handle is disposed and the
original C# exception surfaces unmasked, not wrapped and not replaced by a Kotlin-side error:

```C#
public static IntPtr CreateList<T>(IEnumerable<T> values)
{
    IntPtr listHandle = NugetListNative.Create();
    try
    {
        foreach (T value in values) NugetListNative.Add(listHandle, Wrap(value));
    }
    catch
    {
        NugetListNative.Dispose(listHandle);
        throw;
    }
    return listHandle;
}
```

From `IntegrationTests/CollectionParameterCleanupTests.cs`:

```C#
[Fact]
public void Audit_CollectionThrowsMidEnumeration_SurfacesOriginalException()
{
    using var auditor = new Auditor();
    var ex = Assert.Throws<GrumpyCatException>(
        () => auditor.Audit(new GrumpyList("Oreo: 3 treats")));
    Assert.Equal("Oreo swatted the ledger off the table", ex.Message);
    Assert.IsNotAssignableFrom<IKotlinException>(ex);
}
```

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
(`String`, `Int`, `Long`, `Float`, `Double`, `Boolean`, `Byte`/`UByte`/`Short`/`UShort`/`UInt`/`ULong`,
`Char`, an object handle, or a bare enum via its `int` ordinal, see
[Enum collection components](enums.md#as-a-collection-component)), applied to `List` too. When a
component fails that check (today, only a nested collection), the property still generates, get-only,
with a `SKIPPED_UNSUPPORTED_INPUT` diagnostic naming it.

From `test-library/.../clinic/ChartSample.kt`:

```kotlin
class Chart(val patientName: String) {
  /** Eligible: `CreateList` + `Wrap<T>` string. */
  var tags: List<String> = emptyList()

  /** Eligible: `CreateMap`, wrappable key (String) AND value (Int). */
  var counts: Map<String, Int> = emptyMap()

  /** Eligible: ObjectHandle element ([Nurse]) + the mutable-list lowering. Reading this back is a
   *  detached copy per ADR-011: mutating the returned list does not reach Kotlin, only assigning a
   *  fresh list does (read-modify-write). */
  var seen: MutableList<Nurse> = mutableListOf()

  /** Eligible: the SET/MUTABLE_SET shared lowering. */
  var codes: Set<String> = emptySet()

  /** Eligible, and nullable: the sharp case ADR-075 exists to fix. Round-trips both ways: assign
   *  a list and read it back, assign `null` and read back `null`. */
  var notes: List<String>? = null

  /** Eligible as of [ADR-097](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/097-enum-collection-components.md):
   *  a bare enum element is no longer what makes a collection property setter ineligible. Reading
   *  it was broken too (`FromHandle<Mood>` -> `Materialize` -> `NotSupportedException`, no
   *  `Factories` entry for an enum); the same int-ordinal projection fixes both halves. */
  var moods: List<Mood> = emptyList()

  /** The Kotlin-side observation for [moods]'s setter. */
  fun moodsSummary(): String = moods.joinToString(",")

  /** Eligible as of [ADR-083](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/083-nullable-collection-components.md):
   *  the *element* is nullable (`String?`, not `String`), and a null component now rides the null
   *  pointer in its own slot. */
  var aliases: List<String?> = emptyList()
}
```

Generated C#, from `Interop.cs`. `Tags` shows an eligible setter (built via the same
`NugetMarshal.CreateList` a `List` **parameter** uses); `Notes` shows the nullable-reference variant,
`value != null ? CreateList(value) : IntPtr.Zero`; `Moods` shows the enum-component projection, the
same `Select(x => (int)x)` write and `(Mood)FromHandle<int>(...)` read a `Map`/`Set`/`List`
**parameter** or **return** use:

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
    {            IntPtr valueHandle = NugetMarshal.CreateList(value);
    Native_Set_tags(_handle, valueHandle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    NugetListNative.Dispose(valueHandle);
    }
}

public IReadOnlyList<string>? Notes
{
    get { /* same walk as Tags, plus: if (nativeResult == IntPtr.Zero) return null; */ }
    set
    {            IntPtr valueHandle = value != null ? NugetMarshal.CreateList(value) : IntPtr.Zero;
    Native_Set_notes(_handle, valueHandle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    if (valueHandle != IntPtr.Zero) { NugetListNative.Dispose(valueHandle); }
    }
}

public IReadOnlyList<global::TestLibrary.Clinic.Mood> Moods
{
    get
    {            IntPtr nativeResult = Native_Get_moods(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    int count = NugetListNative.Count(nativeResult);
    var result = new List<global::TestLibrary.Clinic.Mood>(count);
    for (int i = 0; i < count; i++)
    {
        result.Add((global::TestLibrary.Clinic.Mood)NugetMarshal.FromHandle<int>(NugetListNative.Get(nativeResult, i)));
    }
    NugetListNative.Dispose(nativeResult);
    return result.AsReadOnly();
    }
    set
    {            IntPtr valueHandle = IntPtr.Zero;
    try
    {
        valueHandle = NugetMarshal.CreateList(global::System.Linq.Enumerable.Select(value, x => (int)x));
        Native_Set_moods(_handle, valueHandle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
    }
    finally
    {
        if (valueHandle != IntPtr.Zero) { NugetListNative.Dispose(valueHandle); }
    }
    }
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
public void Chart_Moods_EnumElement_RoundTripsThroughItsSetter()
{
    using var chart = new Chart("Oreo");

    chart.Moods = new[] { Mood.Anxious, Mood.Calm };

    Assert.Equal("ANXIOUS,CALM", chart.MoodsSummary());
    Assert.Equal(new[] { Mood.Anxious, Mood.Calm }, chart.Moods);
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
{            IntPtr valueHandle = NugetMarshal.CreateList(value);
    Native_ChartidSetSymptomTags(receiver.Value, valueHandle, out IntPtr error);
    ...
    NugetListNative.Dispose(valueHandle);
}
```

An ineligible setter emits a warning naming the property and the offending component, and states
that the C# property stays read-only rather than that the property was dropped. A nested collection
is the one component kind still excluded after the narrow-primitive and `Char` widening
([ADR-097](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/097-enum-collection-components.md)
moved this mechanism's fixture off `List<Mood>`, and
[ADR-098](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/098-narrow-primitive-and-char-collection-components.md)
off `List<Char>`; both are eligible now):

```kotlin
// nuget-processor Tier1 fixture, not test-library: a nested collection has no per-element wire at
// all, so the getter still binds but the setter must be absent.
class Box {
  var moods: List<List<String>> = emptyList()
}
```

```
[nuget:SKIPPED_UNSUPPORTED_INPUT] Skipping Box.moods: its setter is not generated because the
    element type List<String> cannot be written into a Kotlin collection. the C# property Moods is
    read-only
```

## Nullable collection components

A `null` list element, set element, or map value now crosses the bridge: it rides the same
pointer every non-null component already crosses as, `IntPtr.Zero` on the way in, a null-checked
read on the way out, for every wrappable component kind including a value class
([ADR-083](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/083-nullable-collection-components.md)).
No has-value pair is needed: the component slot is already pointer-shaped.

From `test-library/.../clinic/NullableComponentCollectionsSample.kt`:

```kotlin
fun tagRoll(tags: List<String?>): String = tags.joinToString(",") { it ?: "null" }

fun scoreBoard(scores: Map<String, Int?>): String =
  scores.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value ?: "null"}" }

fun fileCharts(charts: List<ChartId?>): String =
  charts.joinToString(",") { it?.value ?: "null" }
```

Generated C#, from `Interop.cs`. `TagRoll` boxes each `string?` element as-is; `FileCharts`
`?.`-projects each nullable `ChartId` element to its `string?` underlying before boxing, composing
[ADR-081](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/081-value-class-collection-components.md)'s
per-element projection with the null-pointer slot:

```C#
public string TagRoll(IReadOnlyList<string?> tags)
{
    IntPtr tagsHandle = NugetMarshal.CreateList(tags);
    IntPtr nativeResult = Native_TagRoll(_handle, tagsHandle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    NugetListNative.Dispose(tagsHandle);
    return Marshal.PtrToStringUTF8(nativeResult)!;
}

public string FileCharts(IReadOnlyList<ChartId?> charts)
{
    IntPtr chartsHandle = NugetMarshal.CreateList(global::System.Linq.Enumerable.Select(charts, x => x?.Value));
    IntPtr nativeResult = Native_FileCharts(_handle, chartsHandle, out IntPtr error);
    ...
}
```

The read side closes the same way: a returned collection containing a null element no longer NPEs
on first read (previously a bind-then-break, since `isBridgeableComponent()`'s `Nullable` branch
already admitted these shapes at return positions before the read export itself could handle them).
From `IntegrationTests/NullableComponentCollectionTests.cs`:

```C#
[Fact]
public void ChartLedger_MissingTags_MethodReturn_NullAtNonFirstIndexDoesNotThrow()
{
    using var ledger = new ChartLedger();

    IReadOnlyList<string?> tags = ledger.MissingTags();

    Assert.Equal(3, tags.Count);
    Assert.Equal("OREO", tags[0]);
    Assert.Null(tags[1]);
    Assert.Equal("MYLO", tags[2]);
}
```

A nullable map **key** is not part of this: `Map<String?, Int>` still skips named at a parameter
position, since a C# `Dictionary` can't hold a null key.

## Narrow primitives and Char as collection components

The six narrow primitives (`Byte`/`UByte`/`Short`/`UShort`/`UInt`/`ULong`) and `Char` now bind as a
`List`/`Map`/`Set` element, map key, or map value, at input positions, collection property setters,
method returns, and property getters
([ADR-098](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/098-narrow-primitive-and-char-collection-components.md)).
Before this, none of the seven had a `nuget_wrap_*` export, so `isWrappableComponent()` correctly
excluded them and every such shape was a named `SKIPPED_UNSUPPORTED_INPUT` skip. Their nullable
spellings (`List<Short?>`) come along for free through the same `Nullable` delegation
[ADR-083](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/083-nullable-collection-components.md)
already ships.

From `test-library/.../clinic/NarrowComponentCollectionsSample.kt`:

```kotlin
class Readings {
  fun chart(samples: List<Short>): String = samples.joinToString(",")

  fun weigh(grams: Set<ULong>): String = grams.sorted().joinToString(",")

  fun census(byGrade: Map<UByte, Short>): String =
    byGrade.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

  fun trail(samples: List<Short?>): String = samples.joinToString(",") { it?.toString() ?: "null" }

  fun initials(marks: List<Char>): String = marks.joinToString("")

  fun marks(): List<Char> = listOf('é', '日')
}
```

### Generated C# {id="narrow-component-generated-c"}

From `Interop.cs`. `Chart` and `Trail` are `List` positions (the second nullable), `Weigh` is a `Set`
position, `Census` is a `Map` with a narrow type in both the key and value slots, `Initials` is the
`Char` write side, and `Marks` is the `Char` read side, the one hole `FromHandle<T>` had before this:

```C#
public string Chart(IReadOnlyList<short> samples)
{
    IntPtr samplesHandle = IntPtr.Zero;
    try
    {
        samplesHandle = NugetMarshal.CreateList(samples);
        IntPtr nativeResult = Native_Chart(_handle, samplesHandle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return Marshal.PtrToStringUTF8(nativeResult)!;
    }
    finally
    {
        if (samplesHandle != IntPtr.Zero) { NugetListNative.Dispose(samplesHandle); }
    }
}

public string Weigh(IReadOnlySet<ulong> grams) { /* same shape, via NugetMarshal.CreateSet */ }

public string Census(IReadOnlyDictionary<byte, short> byGrade) { /* same shape, via NugetMarshal.CreateMap */ }

public string Trail(IReadOnlyList<short?> samples) { /* same shape; a null element rides the ADR-083 null pointer */ }

public string Initials(IReadOnlyList<char> marks) { /* same shape, via NugetMarshal.CreateList */ }

public IReadOnlyList<char> Marks()
{
    IntPtr listHandle = Native_Marks(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    int count = NugetListNative.Count(listHandle);
    var result = new List<char>(count);
    for (int i = 0; i < count; i++)
    {
        result.Add(NugetMarshal.FromHandle<char>(NugetListNative.Get(listHandle, i)));
    }
    NugetListNative.Dispose(listHandle);
    return result.AsReadOnly();
}
```

### Using it from C# {id="narrow-component-using-it-from-c"}

From `IntegrationTests/NarrowComponentCollectionTests.cs`:

```C#
[Fact]
public void Readings_Chart_ListOfShortParameter_RoundTripsEveryElement()
{
    using var readings = new Readings();

    string chart = readings.Chart(new short[] { 1, -2, short.MaxValue });

    Assert.Equal("1,-2,32767", chart);
}

[Fact]
public void Readings_Census_MapWithNarrowKeyAndNarrowValue_RoundTripsEveryEntry()
{
    using var readings = new Readings();

    string census = readings.Census(new Dictionary<byte, short>
    {
        [200] = 32767,
        [9] = -40,
    });

    Assert.Equal("9=-40,200=32767", census);
}

[Fact]
public void Readings_Initials_ListOfCharParameter_RoundTripsNonAsciiElements()
{
    using var readings = new Readings();

    string initials = readings.Initials(new[] { 'é', '日', 'A' });

    Assert.Equal("é日A", initials);
}

[Fact]
public void Readings_Marks_ListOfCharReturn_MaterializesNonAsciiElements()
{
    using var readings = new Readings();

    IReadOnlyList<char> marks = readings.Marks();

    Assert.Equal(new[] { 'é', '日' }, marks);
}
```

The mutable collection property setter above (`ChartBook.PendingCharts`-style) admits these seven
component kinds through the same `isWrappableComponent()` predicate; see
[Mutable collection properties](#mutable-collection-properties).

<note>
    <p>The <code>Char</code> half of this ADR is also a correctness fix at <b>every</b> ordinary
    <code>Char</code> position, not only collections: see
    <a href="primitives-and-strings.md#char">Primitives and strings: Char</a>.</p>
</note>

## Limitations

- `Sequence<T>` is not bridgeable. `Cat.unsupported: Sequence<String>` in the sample library is deliberately left out of the generated `Interop.cs` (no eager-copy story for a lazy sequence).
- Only a strict subset of key/value/element types binds at a `Map`/`Set`/`List` parameter position: `String`, `Int`, `Long`, `Float`, `Double`, `Boolean`, the six narrow primitives (`Byte`/`UByte`/`Short`/`UShort`/`UInt`/`ULong`) and `Char` (see [Primitives and strings](primitives-and-strings.md#char), [ADR-098](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/098-narrow-primitive-and-char-collection-components.md)), object handles (a class instance, extracted via the internal `INugetHandle` interface every handle-carrying wrapper implements, see [ADR-094](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/094-reflection-free-generic-dispatch.md)), a bare enum via its `int` ordinal (see [Enums](enums.md#as-a-collection-component), [ADR-097](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/097-enum-collection-components.md)), and a value class over any of the first four underlyings, including an enum-underlying value class via its own `int` ordinal (see [Value classes](value-classes.md#as-a-collection-component), [ADR-081](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/081-value-class-collection-components.md)). A `Nullable` spelling of any of those (`Map<String, Int?>`, `Set<String?>`, `List<ChartId?>`, `List<Mood?>`, `List<Short?>`) binds too: a null element, set member, or map value rides a null pointer in the component slot on both the write and read side, no has-value pair needed (see [ADR-083](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/083-nullable-collection-components.md)). Anything else, a nested collection (`Set<List<String>>`) or an interface, is skipped with `SKIPPED_UNSUPPORTED_INPUT` rather than crashing or binding incorrectly. A nullable map **key** (`Map<String?, Int>`) is still a named skip at the parameter position: a C# `Dictionary` can't hold a null key. `List`/`MutableList` parameters now use the same `isWrappableComponent()` gate `Map`/`Set` parameters do, narrowed from a wider gate that used to let the excluded shapes above bind and then throw or crash `packNuget`, per [ADR-097](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/097-enum-collection-components.md) and [ADR-098](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/098-narrow-primitive-and-char-collection-components.md). No shape that **worked** before that narrowing is gone; see [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md) for the remaining nested-collection gap.
- `MutableMap`/`MutableSet` parameters do not write back, matching `MutableList`. Contents are copied into Kotlin; changes Kotlin makes are not reflected back in the collection you passed.
- A collection property **setter** uses the same wrappable-component predicate as a `Map`/`Set`/`List` parameter above. A nested-collection element skips the setter with `SKIPPED_UNSUPPORTED_INPUT` and falls back to a get-only property; a value-class element (see [Value classes](value-classes.md#as-a-collection-component), [ADR-081](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/081-value-class-collection-components.md)), a bare enum element (see [Enums](enums.md#as-a-collection-component), [ADR-097](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/097-enum-collection-components.md)), a narrow-primitive or `Char` element ([ADR-098](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/098-narrow-primitive-and-char-collection-components.md)), and a nullable element ([ADR-083](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/083-nullable-collection-components.md)) all bind; the getter itself has no such restriction. See [ADR-075](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/075-collection-property-getter-setter-independence.md) and [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md) for the remaining write-side gaps.
- A nested-collection component (`List<List<ChartId>>`) still has no representation on the write side; see [Value classes](value-classes.md#as-a-collection-component) and [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).

<seealso>
    <category ref="related">
        <a href="generics.md">Generics</a>
        <a href="classes-and-objects.md">Classes and objects</a>
        <a href="value-classes.md">Value classes</a>
        <a href="enums.md">Enums</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/011-collection-type-mapping.md">ADR-011: Collection type mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/061-method-return-marshalling.md">ADR-061: Method return marshalling</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md">ADR-062: Forward callable plan</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md">ADR-064: Forward unsupported-declaration diagnostics</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/073-map-and-set-parameters.md">ADR-073: Map/Set parameters</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/075-collection-property-getter-setter-independence.md">ADR-075: Collection property getter/setter independence</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/081-value-class-collection-components.md">ADR-081: Value-class collection components</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/083-nullable-collection-components.md">ADR-083: Nullable collection components</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/097-enum-collection-components.md">ADR-097: Enum collection components</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/098-narrow-primitive-and-char-collection-components.md">ADR-098: Narrow-primitive and Char collection components</a>
    </category>
</seealso>
