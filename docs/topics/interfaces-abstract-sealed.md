# Interfaces, abstract classes, and sealed classes

Kotlin's three flavours of inheritance each get a distinct C# shape: `interface` becomes an `I`-prefixed C# interface with default methods delegating back to Kotlin, `abstract class` becomes a C# `abstract class` whose subclasses share an inherited `_handle`, and `sealed class` becomes an abstract class with its subtypes nested inside it.

| Kotlin | C# | Notes |
|---|---|---|
| `interface` | `interface` (`I`-prefixed) | default methods delegate to Kotlin; every exported interface's own declaration (not just a reachable one's) is now typed from the forward plan, the same source of truth its implementing class uses, see [Declaring every exported interface](#declaring-every-exported-interface), [ADR-113](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/113-interface-declaration-on-the-forward-plan.md) |
| `abstract class` | `abstract class` | `_handle` inherited by subclasses |
| `sealed class` | `abstract class` | a nested subclass stays nested (`Base.Sub`); a **sibling** subclass, declared beside its base rather than inside it, is declared at namespace level (`public sealed class Sub : Base`), for a `data class` or an `object`/`data object` alike, see [A sibling sealed subclass declared beside its base](#a-sibling-sealed-subclass-declared-beside-its-base), [ADR-009](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/009-sealed-class-mapping.md) |
| **eligible** `sealed interface` (no type parameters, every subclass a nested class/object with no other superclass, no sub-interfaces) | `abstract class` | same shape as `sealed class` above; no C# interface is declared for it, see [Sealed interfaces](#sealed-interfaces), [ADR-112](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/112-sealed-interface-mapping.md) |
| **ineligible** `sealed interface` | `interface` (`I`-prefixed) | stays on the ordinary interface route; every member typed with it skips named (`SKIPPED_SEALED_POSITION`), and the declaration itself gets `SKIPPED_INELIGIBLE_SEALED_INTERFACE` naming the disqualifying subclass, see [Sealed interfaces](#sealed-interfaces), [ADR-112](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/112-sealed-interface-mapping.md) |
| interface-typed return (method result or property) | `IFoo` / `IFoo?` | backed by a generated `sealed class Foo : IFoo`, see [ADR-040](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/040-interface-return-type-mapping.md) |
| interface-typed parameter, a C# class implementing `IFoo` | accepted, no `_handle` needed | dispatched through a per-interface bridge factory, see [ADR-084](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/084-csharp-implemented-interfaces.md) |
| a property of a sealed subclass, any shape a class property supports (nullable enum, nullable reference, `Boolean`, collections, `var`, `Duration`/`Uuid`/value classes/interfaces) | the same shape an ordinary class property gets | planned by [ADR-062](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md)'s property plan, same as any class, since [ADR-111](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/111-sealed-subclass-properties-on-the-property-plan.md); see [Every property shape on a sealed subclass](#every-property-shape-on-a-sealed-subclass) |
| a public method a sealed subclass **itself declares** (including its own `override fun`), any shape a class method supports | the same shape an ordinary class method gets, exported `${sealed}_${sub}_${name}[_n]` | planned by [ADR-062](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md)'s callable plan, same as any class, since [ADR-116](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/116-sealed-subclass-methods-on-the-callable-plan.md); declared-only. A declared `suspend fun` also binds, as `Task<T> XxxAsync` off the arm's own export prefix, with overloads numbered `_2` on both the entry point and the private extern, see [ADR-118](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/118-suspend-route-sealed-arm-owners-and-overload-numbering.md). A declared `Flow<T>`/`StateFlow<T>` member (property or method return) also binds now, as `KotlinFlow<T>`/`KotlinStateFlow<T>` off the arm's own export prefix, through the same collect/value thunks the ordinary-class route uses, see [ADR-124](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/124-flow-route-sealed-arm-owners.md); lambda-parameter/generic methods are still a named skip; see [Methods on a sealed subclass](#methods-on-a-sealed-subclass) |
| property whose own type is a sealed class (bare, nullable, or a collection component, read-only or `var`) | the sealed base | materialised through `<Base>.FromHandle(...)`, see [Sealed types as property types](#sealed-types-as-property-types), [ADR-105](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/105-sealed-property-position.md) |
| a class, object, or companion method returning a sealed base, scalar or as a `List`/`Map`/`Set` component | the sealed base (or `IReadOnlyList<Base>`) | reads through the same `FromHandle` discriminator a top-level sealed return already used, see [A class method returning a sealed base](#a-class-method-returning-a-sealed-base), [ADR-009](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/009-sealed-class-mapping.md) |
| a sealed type at a **parameter** position, bare, nullable, or as a collection component, including a constructor parameter | an ordinary handle argument (`shape._handle`, or boxed per element through `NugetMarshal.Wrap<T>` in a collection) | the same `sealedAsHandle()` rewrite the property planner uses applies to every declared parameter, so `Issue54Drawing`'s own four-parameter constructor now binds, see [A sealed type at a parameter position](#a-sealed-type-at-a-parameter-position), [ADR-105](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/105-sealed-property-position.md) |
| a sealed subclass declared nested inside its sealed base, used at a return, property, or parameter position | `Base.Sub` (enclosing scope kept) | see [A nested sealed subclass at a member position](#a-nested-sealed-subclass-at-a-member-position) |
| an `interface` declared nested inside another class, used at a return, property, or parameter position | skipped named (`UNDECLARED_INTERFACE`) | see [Nested interfaces skip named](#nested-interfaces-skip-named) |

## Kotlin

From `test-library/src/nativeMain/kotlin/.../cat/Pet.kt` and `Animal.kt`:

```kotlin
interface Pet {
  val name: String
  fun speak(): String
  fun greet(): String = "Hi, I'm $name"
}

abstract class Animal(override val name: String) : Pet {
  override fun greet(): String = "Hi, I'm $name"

  fun introduce(): String = "My name is $name"
}
```

`Cat` (see [Classes and objects](classes-and-objects.md)) extends `Animal`, which implements `Pet`.

A sealed hierarchy, from `test-library/src/nativeMain/kotlin/.../cat/Observation.kt`:

```kotlin
sealed class Observation {
  data object Superposition : Observation()
  data class Alive(val cat: Cat) : Observation()
  data class Dead(val cause: String) : Observation()
}

fun openBox(name: String): Observation {
  if (name == "Oreo") return Observation.Alive(Cat("Oreo"))
  return Observation.Dead("The cat was not $name")
}
```

## Generated C#

From `Interop.cs`. `IPet` gets a default-implemented `Greet()`, and `Animal` is `abstract` with an inherited `_handle`:

```C#
public interface IPet : IDisposable
{
    string Name { get; }

    string Speak();
    string Greet();
}

public abstract class Animal : IPet
{
    internal IntPtr _handle;

    internal Animal(IntPtr handle)
    {
        _handle = handle;
    }

    public string Name
    {
        get { /* ... */ }
    }

    public string Greet()
    {
        /* delegates to the Kotlin-side override */
    }

    public string Introduce()
    {
        /* ... */
    }

    public abstract string Speak();

    public abstract void Dispose();
}
```

`Cat : Animal` inherits `_handle` and only overrides `Speak()` and `Dispose()`. It never redeclares the field.

## Overriding a read-only property with `var`

Kotlin lets a subclass widen a `val` it inherits to a mutable `var`. Whether the setter survives the
crossing depends on what member it widens, because C# does not allow an `override` to add a `set`
accessor to a get-only member (`CS0546`):

- Widening an **interface**'s `val` compiles either way: the class only *implements* the interface
  member, so its own property renders `virtual`, not `override`, and a `virtual` declaration is free
  to carry a setter the interface never declared.
- Widening an exported **base class**'s `override val` does not: the base already rendered a
  get-only `virtual` property, so a derived `{ get; set; }` `override` of it would be `CS0546`. The
  setter is dropped at plan time and the property stays read-only in C#, named by a build warning.

From `test-library/src/nativeMain/kotlin/.../cat/Clicker.kt`, the interface case:

```kotlin
interface Counter {
  val count: Int
}

class Clicker : Counter {
  override var count: Int = 0
}
```

`Clicker` implements `Counter`'s `val`, so it renders `virtual` with both accessors, against
`ICounter`'s get-only property:

```C#
public class Clicker : ICounter, INugetHandle
{
```

```C#
public interface ICounter : IDisposable
{
    int Count { get; }
}
```

`Native_Get_count` and `Native_Set_count` are both generated for `Clicker`.

From `Pet.kt`, `Animal.kt`, and `Cat.kt`, the base-class case: `Pet.vibe` is a `val`, `Animal.vibe`
widens nothing (`override val`, itself widening `Pet`'s), and `Cat.vibe` widens `Animal`'s to `var`:

```kotlin
interface Pet {
  val vibe: String // read-only in the interface, but an implementation may widen it to `var`
}
```

```kotlin
abstract class Animal(override val name: String) : Pet {
  override val vibe: String = "calm"
}
```

```kotlin
class Cat(
  name: String,
  val lives: Int = 9,
) : Animal(name) {
  override var vibe: String = "curious"
}
```

`Animal.Vibe` renders `virtual` and get-only (it implements an interface member, the same rule as
`Clicker.Count` above). `Cat.Vibe` renders as an `override`, but its setter is dropped: `Cat` still
compiles because the `override` keeps `Vibe` get-only, matching `Animal`'s accessor set exactly:

```C#
public virtual string Vibe
{
    get
    {            IntPtr nativeResult = Native_Get_vibe(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return Marshal.PtrToStringUTF8(nativeResult)!;
    }
}
```

```C#
public override string Vibe
{
    get
    {            IntPtr nativeResult = Native_Get_vibe(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return Marshal.PtrToStringUTF8(nativeResult)!;
    }
}
```

No `cat_set_vibe` export is generated, and the KSP build emits a diagnostic naming the reason:

```
[nuget:SKIPPED_UNSUPPORTED_INPUT] Skipping io.github.xxfast.kotlin.native.nuget.test.cat.Cat.vibe: its
setter is not generated because it overrides a read-only property of the exported base class Animal;
C# cannot add a set accessor to an override (CS0546). the C# property Vibe is read-only
```

<note>
    <p>The reverse case, a subclass narrowing a base's <code>var</code> to <code>val</code>, is
    already a Kotlin compile error and needs no handling here.</p>
</note>

`Observation` renders as an abstract class with each Kotlin subtype as a nested `sealed class`, plus a `FromHandle` dispatcher that reads a type tag off the native handle:

```C#
public abstract class Observation : IDisposable
{
    internal IntPtr _handle;

    public sealed class Alive : Observation
    {
        internal Alive(IntPtr handle) : base(handle) { }

        public Cat Cat
        {
            get
            {            IntPtr nativeResult = Native_Get_cat(_handle, out IntPtr error);
            if (error != IntPtr.Zero)
            {
                throw NugetErrorNative.BuildException(error);
            }
            return new Cat(nativeResult);
            }
        }

        public override bool Equals(object? obj) { /* ... */ }
        public override int GetHashCode() => Native_HashCode(_handle);
        public override string ToString() => Marshal.PtrToStringUTF8(Native_ToString(_handle))!;
        public override void Dispose() { /* ... */ }
    }

    public sealed class Dead : Observation
    {
        public string Cause
        {
            get
            {            IntPtr nativeResult = Native_Get_cause(_handle, out IntPtr error);
            if (error != IntPtr.Zero)
            {
                throw NugetErrorNative.BuildException(error);
            }
            return Marshal.PtrToStringUTF8(nativeResult)!;
            }
        }
        // Equals / GetHashCode / ToString / Dispose ...
    }

    public sealed class Superposition : Observation
    {
        public override string ToString() => "Superposition";
        public override void Dispose() { /* ... */ }
    }

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "observation_get_type")]
    private static extern int Native_GetType(IntPtr handle);

    internal static Observation FromHandle(IntPtr handle)
    {
        return Native_GetType(handle) switch
        {
            0 => new Alive(handle),
            1 => new Dead(handle),
            2 => new Superposition(handle),
            _ => throw new InvalidOperationException("Unknown sealed class type")
        };
    }

    public abstract void Dispose();
}
```

`Alive` and `Dead` are Kotlin `data class` subtypes, so they also get `Equals`/`GetHashCode`/`ToString` (see [Data classes](data-classes.md)). `Superposition` is a `data object`, which is a data class as far as Kotlin's generated members go, so it gets the same three members, backed by the same kind of native exports (`_equals`/`_hashcode`/`_tostring`) rather than a fixed literal. This matters because every C# read of a singleton mints a fresh wrapper, so two reads are never reference-equal; without a real `Equals` they would never compare equal either. `Cat` and `Cause` both read the `out IntPtr error` slot and throw on failure, the same as any class property getter; see [Every property shape on a sealed subclass](#every-property-shape-on-a-sealed-subclass).

From `Interop.cs` (`Issue54Shape.Empty`, the same shape `Observation.Superposition` gets):

```C#
public sealed class Empty : Issue54Shape
{
    internal Empty(IntPtr handle) : base(handle)
    {
    }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "issue54shape_empty_equals")]
    private static extern bool Native_Equals(IntPtr handle, IntPtr other);

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "issue54shape_empty_hashcode")]
    private static extern int Native_HashCode(IntPtr handle);

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "issue54shape_empty_tostring")]
    private static extern IntPtr Native_ToString(IntPtr handle);

    public override bool Equals(object? obj)
    {
        if (obj is Empty other) return Native_Equals(_handle, other._handle);
        return false;
    }

    public override int GetHashCode() => Native_HashCode(_handle);

    public override string ToString() => Marshal.PtrToStringUTF8(Native_ToString(_handle))!;
}
```

From `IntegrationTests/Issue54Tests.cs`, two reads of the same `data object` singleton compare equal:

```C#
using Issue54Shape first = curled.Current;
using Issue54Shape second = curled.Current;

Issue54Shape.Empty mylo = Assert.IsType<Issue54Shape.Empty>(first);
Issue54Shape.Empty alsoMylo = Assert.IsType<Issue54Shape.Empty>(second);

Assert.NotSame(mylo, alsoMylo);
Assert.Equal(mylo, alsoMylo);
Assert.Equal(mylo.GetHashCode(), alsoMylo.GetHashCode());
```

## Sealed interfaces {id="sealed-interfaces"}

A `sealed interface` maps like a sealed class exactly when it is **eligible**: no type parameters, every one of its subclasses is a nested `class` or `object` with no other superclass, and no sub-interface appears anywhere in the hierarchy. An eligible sealed interface enters the same route as `sealed class` above: it renders as `public abstract class Pulse` with nested `sealed` subclasses and a `Pulse.FromHandle(IntPtr)` discriminator, and binds at every position a sealed class does (property, return, parameter, collection component). No `IPulse` interface is ever declared ([ADR-112](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/112-sealed-interface-mapping.md)).

### Kotlin {id="sealed-interface-kotlin"}

From `test-library/src/nativeMain/kotlin/.../issue54/SealedInterfaceSample.kt`:

```kotlin
sealed interface Pulse {
  data class Beat(val bpm: Int) : Pulse
  data object Flat : Pulse
}

class Monitor {
  var current: Pulse = Pulse.Flat
  val history: List<Pulse> = listOf(Pulse.Beat(60), Pulse.Flat)
  fun latest(): Pulse = current
  fun record(pulse: Pulse): Int = when (pulse) {
    is Pulse.Beat -> pulse.bpm
    Pulse.Flat -> 0
  }
}
```

### Generated C# {id="sealed-interface-generated-c"}

From `Interop.cs`. Same abstract-class-plus-discriminator shape a sealed class gets:

```C#
public abstract class Pulse : IDisposable, INugetHandle
{
    internal IntPtr _handle;

    public sealed class Beat : Pulse
    {
        public int Bpm
        {
            get { /* ... */ }
        }
        // Equals / GetHashCode / ToString / Dispose ...
    }

    public sealed class Flat : Pulse
    {
        public override string ToString() => "Flat";
        public override void Dispose() { /* ... */ }
    }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "pulse_get_type")]
    private static extern int Native_GetType(IntPtr handle);

    internal static Pulse FromHandle(IntPtr handle)
    {
        return Native_GetType(handle) switch
        {
            0 => new Beat(handle),
            1 => new Flat(handle),
            _ => throw new InvalidOperationException("Unknown sealed class type")
        };
    }

    public abstract void Dispose();
}
```

`Monitor.Current` binds the sealed interface at a `var` property position the same way a sealed class does:

```C#
public global::TestLibrary.Issue54.Pulse Current
{
    get
    {            IntPtr nativeResult = Native_Get_current(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return global::TestLibrary.Issue54.Pulse.FromHandle(nativeResult);
    }
    set
    {            Native_Set_current(_handle, value._handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    }
}
```

### An ineligible sealed interface stays on the interface route {id="ineligible-sealed-interface"}

`Mixed`'s subclass `Odd` extends another class (`Rhythm`) as well as implementing `Mixed`, so no nested `sealed class Odd : Mixed` can be declared in C#. `Mixed` stays ineligible: it keeps its plain `IMixed` interface declaration, and `Monitor.mixed()` keeps skipping as `SKIPPED_SEALED_POSITION`. The declaration itself gets a new diagnostic naming the disqualifying subclass:

```
[nuget:SKIPPED_INELIGIBLE_SEALED_INTERFACE] Skipping io.github.xxfast.kotlin.native.nuget.test.issue54.Mixed: sealed interface `io.github.xxfast.kotlin.native.nuget.test.issue54.Mixed` is declared as `IMixed` but cannot be reconstructed in C#: subclass `Odd` extends another class `io.github.xxfast.kotlin.native.nuget.test.issue54.Rhythm`. make every subclass a nested class or object with no other superclass and no sub-interfaces, or declare it as a sealed class (ADR-112)
```

### Using it from C# {id="sealed-interface-using-it-from-c"}

From `IntegrationTests/SealedInterfaceTests.cs`:

```C#
using var monitor = new Monitor();

using Pulse current = monitor.Current;

Assert.IsType<Pulse.Flat>(current);
```

<note>
    <p>A generated type can collide with a BCL name (<code>Monitor</code> vs.
    <code>System.Threading.Monitor</code>). See <a href="classes-and-objects.md">Classes and
    objects</a> for the consumer-side <code>using</code> alias this needs.</p>
</note>

## Sealed types as property types

A property whose type is a sealed class binds as the sealed **base**, and the C# getter materialises it through the generated `FromHandle` discriminator above rather than through a constructor (the base is `abstract`, so `new` would not compile). This covers the bare type, the nullable spelling, and a collection whose component is sealed, read-only or `var` ([#54](https://github.com/xxfast/kotlin-native-nuget/issues/54), [ADR-105](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/105-sealed-property-position.md)).

From `test-library/src/nativeMain/kotlin/.../issue54/Issue54Sample.kt`:

```kotlin
data class Issue54Drawing(
  val shape: Issue54Shape,
  val maybe: Issue54Shape?,
  val shapes: List<Issue54Shape>,
  var current: Issue54Shape,
)
```

```C#
public global::TestLibrary.Issue54.Issue54Shape Shape
{
    get
    {            IntPtr nativeResult = Native_Get_shape(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return global::TestLibrary.Issue54.Issue54Shape.FromHandle(nativeResult);
    }
}
```

The nullable getter returns `null` for a null handle and discriminates otherwise; a `List` component is read per element through `NugetMarshal.FromHandle<T>`, which dispatches to the same discriminator; and a `var` of a sealed type gets an ordinary setter that passes `value._handle`. Because the getter hands back a real subclass instance, a C# consumer can pattern match.

From `IntegrationTests/Issue54Tests.cs`:

```C#
using Issue54Drawing drawing = Issue54Sample.CurledCats();

using Issue54Shape shape = drawing.Shape;

string description = shape switch
{
    Issue54Shape.Circle c => $"Oreo curled at r={c.Radius}",
    Issue54Shape.Empty => "Mylo sprawled",
    _ => throw new InvalidOperationException(),
};

Assert.Equal("Oreo curled at r=7.5", description);
```

A **mutable collection** of a sealed base also gets a real setter, not just a getter: writing it boxes each element through `NugetMarshal.Wrap<T>`'s `INugetHandle` type test, which an abstract sealed base satisfies exactly as a concrete wrapper does ([ADR-105](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/105-sealed-property-position.md) "Collection write side"). From `Issue54Sample.kt` and `Interop.cs`:

```kotlin
class Issue54Board {
  var shapes: MutableList<Issue54Shape> = mutableListOf(Issue54Shape.Empty)
}
```

```C#
public IList<global::TestLibrary.Issue54.Issue54Shape> Shapes
{
    get { /* ... reads through NugetMarshal.FromHandle<T> per element, as above ... */ }
    set
    {                IntPtr valueHandle = IntPtr.Zero;
    try
    {
        valueHandle = NugetMarshal.CreateList(value);
        Native_Set_shapes(_handle, valueHandle, out IntPtr error);
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

`Issue54Drawing`'s own constructor parameters are sealed-typed too (bare, nullable, collection and `var`); see [A sealed type at a parameter position](#a-sealed-type-at-a-parameter-position) below, where the same fixture supplies the constructor and the `Shapes` setter's consumer usage.

What still skips at a property position, each named by a build warning rather than silently dropped:

| Shape | Status |
|---|---|
| an **ineligible** sealed interface (see [Sealed interfaces](#sealed-interfaces)), bare or as a collection component | Skipped, named `SKIPPED_SEALED_POSITION` |

## A sealed type at a parameter position {id="a-sealed-type-at-a-parameter-position"}

A sealed type at a bare, nullable, or collection-component **parameter** position, including a constructor parameter, binds as an ordinary handle argument. A shared `sealedAsHandle()` rewrite, the same helper the property planner and the sealed-return route use, is applied to every declared parameter once, so a bare parameter crosses as `shape._handle` (the generated abstract base implements `INugetHandle`), a nullable one as `shape?._handle ?? IntPtr.Zero`, and a collection component boxes through `NugetMarshal.Wrap<T>`'s `INugetHandle` arm ([ADR-105](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/105-sealed-property-position.md) scope (d)).

### Kotlin {id="sealed-parameter-kotlin"}

From `test-library/src/nativeMain/kotlin/.../issue54/Issue54Sample.kt`:

```kotlin
object Issue54Shapes {
  fun describe(shape: Issue54Shape): String = when (shape) {
    is Issue54Shape.Circle -> "circle:${shape.radius}"
    Issue54Shape.Empty -> "empty"
  }

  fun describeMaybe(shape: Issue54Shape?): String =
    if (shape == null) "none" else "some:${describe(shape)}"

  fun count(shapes: List<Issue54Shape>): Int = shapes.size

  fun radii(shapes: List<Issue54Shape>): List<Double> =
    shapes.map { if (it is Issue54Shape.Circle) it.radius else 0.0 }
}
```

`Issue54Drawing`'s constructor carries all four parameter shapes (bare, nullable, collection, `var`) at once, and now generates a public constructor and `Copy`:

```kotlin
data class Issue54Drawing(
  val shape: Issue54Shape,
  val maybe: Issue54Shape?,
  val shapes: List<Issue54Shape>,
  var current: Issue54Shape,
)
```

### Generated C# {id="sealed-parameter-generated-c"}

From `Interop.cs`. The bare and nullable parameters pass the instance handle directly; the collection parameter boxes through `NugetMarshal.CreateList`:

```C#
public static string Describe(global::TestLibrary.Issue54.Issue54Shape shape)
{
    IntPtr nativeResult = Native_Describe(shape._handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return Marshal.PtrToStringUTF8(nativeResult)!;
}

public static string DescribeMaybe(global::TestLibrary.Issue54.Issue54Shape? shape)
{
    IntPtr nativeResult = Native_DescribeMaybe(shape?._handle ?? IntPtr.Zero, out IntPtr error);
    // ...
}

public static int Count(IReadOnlyList<global::TestLibrary.Issue54.Issue54Shape> shapes)
{
    IntPtr shapesHandle = IntPtr.Zero;
    try
    {
        shapesHandle = NugetMarshal.CreateList(shapes);
        int nativeResult = Native_Count(shapesHandle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return nativeResult;
    }
    finally
    {
        if (shapesHandle != IntPtr.Zero) { NugetListNative.Dispose(shapesHandle); }
    }
}
```

`Issue54Drawing`'s constructor now exists, boxing its own `shapes` component the same way:

```C#
public Issue54Drawing(global::TestLibrary.Issue54.Issue54Shape shape, global::TestLibrary.Issue54.Issue54Shape? maybe, IReadOnlyList<global::TestLibrary.Issue54.Issue54Shape> shapes, global::TestLibrary.Issue54.Issue54Shape current)
{
                IntPtr shapesHandle = IntPtr.Zero;
    try
    {
        shapesHandle = NugetMarshal.CreateList(shapes);
        IntPtr handle = Native_Create(shape._handle, maybe?._handle ?? IntPtr.Zero, shapesHandle, current._handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        _handle = handle;
    }
    finally
    {
        if (shapesHandle != IntPtr.Zero) { NugetListNative.Dispose(shapesHandle); }
    }
}
```

### Using it from C# {id="sealed-parameter-using-it-from-c"}

C# cannot construct a sealed subclass directly (ADR-009 gives them internal handle constructors only), so every sealed argument below is sourced from an existing getter. From `IntegrationTests/Issue54Tests.cs`:

```C#
using Issue54Drawing sleeping = Issue54Sample.SleepingCats();
using Issue54Drawing curled = Issue54Sample.CurledCats();

using Issue54Drawing built = new Issue54Drawing(
    curled.Shape,
    curled.Maybe,
    sleeping.Shapes,
    sleeping.Current);

Assert.Equal(7.5, Assert.IsType<Issue54Shape.Circle>(built.Shape).Radius);
```

```C#
using Issue54Shape oreo = drawing.Shape;
using Issue54Shape mylo = drawing.Current;

Assert.Equal(2, Issue54Shapes.Count(new List<Issue54Shape> { oreo, mylo }));
```

<note>
    <p>An extension function whose <b>receiver</b> is a sealed base still skips: the
    <code>sealedAsHandle()</code> rewrite above applies only to declared parameters, deliberately
    leaving the receiver alone, since a sealed receiver is a member of the ADR-009 hierarchy itself
    and has its own route. See <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md">ROADMAP.md</a>.</p>
</note>

## A class method returning a sealed base {id="a-class-method-returning-a-sealed-base"}

A class, object, or companion method returning a sealed base binds through the same `FromHandle`
discriminator a top-level sealed return already used. The result is rewritten by a shared
`sealedAsHandle()` helper that recurses through `Nullable` and `Collection` components, so a scalar
return and a `List` return both bind; the same helper also rewrites every declared parameter, see
[A sealed type at a parameter position](#a-sealed-type-at-a-parameter-position)
([ADR-009](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/009-sealed-class-mapping.md),
[ADR-105](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/105-sealed-property-position.md)).

### Kotlin {id="class-method-sealed-return-kotlin"}

From `test-library/src/nativeMain/kotlin/.../issue54/NestedShapeSample.kt` and `Issue54Sample.kt`:

```kotlin
class NestedShapeFactory {
  /** The sealed **base** at a *class method* return. */
  fun shapeOf(radius: Double): NestedShape = NestedShape.Circle(radius)
}
```

```kotlin
object Issue54Shapes {
  /** Scalar sealed return on an `object` method. */
  fun pick(n: Int): Issue54Shape =
    if (n == 0) Issue54Shape.Empty else Issue54Shape.Circle(radius = n.toDouble())

  /** Sealed collection return on an `object` method. */
  fun everyShape(): List<Issue54Shape> = shapes()
}
```

### Generated C# {id="class-method-sealed-return-generated-c"}

From `Interop.cs`:

```C#
public global::TestLibrary.Issue54.NestedShape ShapeOf(double radius)
{
    IntPtr nativeResult = Native_ShapeOf(_handle, radius, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return global::TestLibrary.Issue54.NestedShape.FromHandle(nativeResult);
}
```

```C#
public static IReadOnlyList<global::TestLibrary.Issue54.Issue54Shape> EveryShape()
{
    IntPtr listHandle = Native_EveryShape(out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    int count = NugetListNative.Count(listHandle);
    var result = new List<global::TestLibrary.Issue54.Issue54Shape>(count);
    for (int i = 0; i < count; i++)
    {
        result.Add(NugetMarshal.FromHandle<global::TestLibrary.Issue54.Issue54Shape>(NugetListNative.Get(listHandle, i)));
    }
    NugetListNative.Dispose(listHandle);
    return result.AsReadOnly();
}
```

### Using it from C# {id="class-method-sealed-return-using-it-from-c"}

From `IntegrationTests/NestedSealedSubclassPositionTests.cs` and `Issue54Tests.cs`:

```C#
using NestedShape shape = factory.ShapeOf(2.0);

var circle = Assert.IsType<NestedShape.Circle>(shape);
Assert.Equal(2.0, circle.Radius);
```

```C#
IReadOnlyList<Issue54Shape> shapes = Issue54Shapes.EveryShape();

Assert.Collection(
    shapes,
    mylo => Assert.IsType<Issue54Shape.Empty>(mylo),
    oreo => Assert.Equal(1.0, Assert.IsType<Issue54Shape.Circle>(oreo).Radius));
```

`FlatShapeFactory.Of(radius)` binds the same way at a sibling-and-nested hierarchy, discriminating
to whichever subclass the runtime handle carries: see
[A sibling sealed subclass declared beside its base](#a-sibling-sealed-subclass-declared-beside-its-base).

## Collection properties on sealed subclasses

A `List<T>` property on a sealed subclass renders as a `get { ... }` block, the same shape a `List<T>` property gets on an ordinary class (see [Collections](collections.md)), and carries the same `out IntPtr error` convention:

From `test-library/src/nativeMain/kotlin/.../cat/Issue39Sample.kt`:

```kotlin
data class Issue39Item(val name: String, val count: Int)

sealed class Issue39State {
  data class Loaded(val items: List<Issue39Item>, val refreshing: Boolean) : Issue39State()

  data object Loading : Issue39State()
}
```

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "issue39state_loaded_get_items")]
private static extern IntPtr Native_Get_items(IntPtr handle, out IntPtr error);

public IReadOnlyList<global::TestLibrary.Cat.Issue39Item> Items
{
    get
    {                IntPtr nativeResult = Native_Get_items(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    int count = NugetListNative.Count(nativeResult);
    var result = new List<global::TestLibrary.Cat.Issue39Item>(count);
    for (int i = 0; i < count; i++)
    {
        result.Add(NugetMarshal.FromHandle<global::TestLibrary.Cat.Issue39Item>(NugetListNative.Get(nativeResult, i)));
    }
    NugetListNative.Dispose(nativeResult);
    return result.AsReadOnly();
    }
}
```

The scalar `Refreshing` getter on the same subclass reads the error slot too, since sealed-subclass
properties plan onto the same [ADR-062](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md)
property plan an ordinary class property uses ([ADR-111](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/111-sealed-subclass-properties-on-the-property-plan.md)):

```C#
public bool Refreshing
{
    get
    {                bool nativeResult = Native_Get_refreshing(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return nativeResult;
    }
}
```

From `IntegrationTests/Issue39Tests.cs`:

```C#
using Issue39State state = Issue39Sample.LoadedCats();
var loaded = Assert.IsType<Issue39State.Loaded>(state);

IReadOnlyList<Issue39Item> items = loaded.Items;
Assert.IsAssignableFrom<IReadOnlyList<Issue39Item>>(items);
Assert.Equal(2, items.Count);
```

<note>
    <p>A <code>Map</code>/<code>Set</code> property on a sealed subclass parses the same way, and its
    getter reads the <code>out IntPtr error</code> slot back and throws too, the same as
    <code>List</code>/<code>MutableList</code> above.</p>
</note>

## Payload types from another namespace

A sealed subclass whose property types come from a different exported package spells them fully qualified, `global::Namespace.Name`, in the property type, the `new List<T>(count)` allocation, the `FromHandle<T>` element read and the `new T(handle)` constructor call ([#50](https://github.com/xxfast/kotlin-native-nuget/issues/50)). This is the same rule [#41](https://github.com/xxfast/kotlin-native-nuget/issues/41) applied to top-level classes; the sealed-subclass renderer had been left on bare simple names, which only resolve when both types happen to share a namespace.

### Kotlin {id="cross-namespace-sealed-kotlin"}

From `test-library/src/nativeMain/kotlin/.../Issue50Feed.kt` (root package) and `.../issue50/Issue50Remote.kt` (sub-package):

```kotlin
sealed class Issue50State {
  data object Loading : Issue50State()

  data class Success(
    val crew: List<Issue50Assignment>,
    val position: Issue50Position,
  ) : Issue50State()
}
```

### Generated C# {id="cross-namespace-sealed-csharp"}

`Issue50State` lands in `TestLibrary`; its payloads land in `TestLibrary.Issue50`:

```C#
public sealed class Success : Issue50State
{
    public IReadOnlyList<global::TestLibrary.Issue50.Issue50Assignment> Crew
    {
        get
        {
            // ...
            var result = new List<global::TestLibrary.Issue50.Issue50Assignment>(count);
            for (int i = 0; i < count; i++)
            {
                result.Add(NugetMarshal.FromHandle<global::TestLibrary.Issue50.Issue50Assignment>(NugetListNative.Get(listHandle, i)));
            }
            // ...
        }
    }

    public global::TestLibrary.Issue50.Issue50Position Position
    {
        get
        {            IntPtr nativeResult = Native_Get_position(_handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return new global::TestLibrary.Issue50.Issue50Position(nativeResult);
        }
    }
}
```

`Map` and `Set` components and enum-typed properties on a sealed subclass take the same spelling.

## A nested sealed subclass at a member position

A sealed subclass declared *inside* its sealed base is already declared as a nested C# class
under [ADR-009](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/009-sealed-class-mapping.md)
(`NestedShape.Circle`). A **member type position** referencing that subclass, a method return, a
property, or a parameter, used to spell it from the simple name alone,
`global::Namespace.Circle`, a type that does not exist, failing the whole generated `Interop.cs`
with `CS0246`. It now walks the enclosing declarations and keeps the outer sealed base in the
name, `global::Namespace.NestedShape.Circle`, the same way the payload-types spelling above keeps
the namespace ([#54](https://github.com/xxfast/kotlin-native-nuget/issues/54)).

### Kotlin {id="nested-subclass-kotlin"}

From `test-library/src/nativeMain/kotlin/.../issue54/NestedShapeSample.kt`:

```kotlin
sealed class NestedShape {
  data class Circle(val radius: Double) : NestedShape()

  data object Empty : NestedShape()
}

class NestedShapeFactory {
  fun circle(radius: Double): NestedShape.Circle = NestedShape.Circle(radius)

  val unit: NestedShape.Circle = NestedShape.Circle(1.0)

  fun radiusOf(circle: NestedShape.Circle): Double = circle.radius
}
```

### Generated C# {id="nested-subclass-generated-c"}

From `Interop.cs`. The property getter, the method return (and its `new T(handle)` construction),
and the parameter all keep `NestedShape` in front of `Circle`:

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nestedshapefactory_get_unit")]
private static extern IntPtr Native_Get_unit(IntPtr handle, out IntPtr error);

public global::TestLibrary.Issue54.NestedShape.Circle Unit
{
    get
    {            IntPtr nativeResult = Native_Get_unit(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return new global::TestLibrary.Issue54.NestedShape.Circle(nativeResult);
    }
}

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nestedshapefactory_circle")]
private static extern IntPtr Native_Circle(IntPtr handle, double radius, out IntPtr error);

public global::TestLibrary.Issue54.NestedShape.Circle Circle(double radius)
{
    IntPtr nativeResult = Native_Circle(_handle, radius, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return new global::TestLibrary.Issue54.NestedShape.Circle(nativeResult);
}

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nestedshapefactory_radiusOf")]
private static extern double Native_RadiusOf(IntPtr handle, IntPtr circle, out IntPtr error);

public double RadiusOf(global::TestLibrary.Issue54.NestedShape.Circle circle)
{
    double nativeResult = Native_RadiusOf(_handle, circle._handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return nativeResult;
}
```

### Using it from C# {id="nested-subclass-using-it-from-c"}

From `IntegrationTests/NestedSealedSubclassPositionTests.cs`:

```C#
[Fact]
public void Unit_NestedSubclassAtAPropertyPosition_ReadsTheSubclassPayload()
{
    using var factory = new NestedShapeFactory();

    using var unit = factory.Unit;

    Assert.Equal(1.0, unit.Radius);
}
```

<note>
    <p>An ordinary property of the sealed <b>base</b>'s own type (<code>val shape:
    NestedShape</code>) reads through a different function, <code>qualifiedElementCsType</code>,
    used by the sealed-subclass property renderer and the ADR-067 flow-element route. It got the
    same enclosing-scope walk, pinned by the Tier 1 <code>Wrapper(val inner: Circle)</code>
    cell.</p>
</note>

## A sibling sealed subclass declared beside its base

A sealed subclass does not have to be nested inside its base; Kotlin also allows it declared
*beside* the base, as an ordinary top-level class in the same file. Before this fix a sibling
subclass was collected twice: once by the ordinary class route, as a namespace-level type with its
own public constructor, and once by the sealed route, as a nested type with a discriminator arm.
One Kotlin type produced two different C# types, so `Base.FromHandle` and the ordinary class's own
factory returned different types for the same value, and an `is` check disagreed with itself
depending on which one the caller happened to hold ([#54](https://github.com/xxfast/kotlin-native-nuget/issues/54)).

The sealed route is now the sole owner of every sealed subclass, sibling or nested. A sibling
subclass is declared at namespace level beside its base, `public sealed class Label : FlatShape`,
with an `internal` constructor and its own `flatshape_label_*` exports, the same shape a nested
subclass gets except for where it sits. A subclass that really is nested inside its base stays
nested (`FlatShape.Circle`).

### Kotlin {id="sibling-sealed-kotlin"}

From `test-library/src/nativeMain/kotlin/.../issue54/FlatShapeSample.kt`:

```kotlin
sealed class FlatShape {
  /** Nested control: Oreo, curled, described by one non-null `Int`. */
  data class Circle(val radius: Int) : FlatShape()
}

/** The cell under test: a **sibling** subclass, declared beside [FlatShape] rather than inside it. */
data class Label(val text: String) : FlatShape()

/** Carries both subclasses and the sealed base across return and property positions. */
class FlatShapeFactory {
  /** Return position: the sibling subclass, spelled as a concrete type. */
  fun label(text: String): Label = Label(text)

  /** Property position: the nested subclass, the control spelling. */
  val circle: FlatShape.Circle = FlatShape.Circle(3)
}

fun anyFlat(): FlatShape = Label("any")
```

### Generated C# {id="sibling-sealed-generated-c"}

From `Interop.cs`. `Label` is declared at namespace level, not nested inside `FlatShape`, but it
still contributes the second arm of `FlatShape`'s discriminator:

```C#
public abstract class FlatShape : IDisposable, INugetHandle
{
    internal IntPtr _handle;

    public sealed class Circle : FlatShape
    {
        internal Circle(IntPtr handle) : base(handle) { }

        public int Radius
        {
            get
            {                int nativeResult = Native_Get_radius(_handle, out IntPtr error);
            if (error != IntPtr.Zero)
            {
                throw NugetErrorNative.BuildException(error);
            }
            return nativeResult;
            }
        }

        // Equals / GetHashCode / ToString / Dispose ...
    }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "flatshape_get_type")]
    private static extern int Native_GetType(IntPtr handle);

    internal static FlatShape FromHandle(IntPtr handle)
    {
        return Native_GetType(handle) switch
        {
            0 => new Circle(handle),
            1 => new Label(handle),
            _ => throw new InvalidOperationException("Unknown sealed class type")
        };
    }

    public abstract void Dispose();
}

public sealed class Label : FlatShape
{
    internal Label(IntPtr handle) : base(handle) { }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "flatshape_label_get_text")]
    private static extern IntPtr Native_Get_text(IntPtr handle, out IntPtr error);

    public string Text
    {
        get
        {                IntPtr nativeResult = Native_Get_text(_handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return Marshal.PtrToStringUTF8(nativeResult)!;
        }
    }

    // Equals / GetHashCode / ToString / Dispose ...
}
```

`FlatShapeFactory.Label(text)` returns the sibling subclass directly, constructed the same way any
ordinary method return would:

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "flatshapefactory_label")]
private static extern IntPtr Native_Label(IntPtr handle, [MarshalAs(UnmanagedType.LPUTF8Str)] string text, out IntPtr error);

public global::TestLibrary.Issue54.Label Label(string text)
{
    IntPtr nativeResult = Native_Label(_handle, text, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return new global::TestLibrary.Issue54.Label(nativeResult);
}
```

### Using it from C# {id="sibling-sealed-using-it-from-c"}

From `IntegrationTests/FlatSealedSubclassTests.cs`:

```C#
[Fact]
public void Label_IsDeclaredOnceAtNamespaceLevel_AndDerivesFromTheSealedBase()
{
    Assembly assembly = typeof(FlatShape).Assembly;

    Type[] labels = assembly
        .GetTypes()
        .Where(type => type.Name == "Label" && type.Namespace == "TestLibrary.Issue54")
        .ToArray();

    Assert.Single(labels);
    Assert.Null(labels[0].DeclaringType);
    Assert.Null(typeof(Label).DeclaringType);
    Assert.Equal(typeof(FlatShape), typeof(Label).BaseType);
}
```

<note>
    <p>A class method returning the sealed <b>base</b>, <code>FlatShapeFactory.Of(radius):
    FlatShape</code>, now binds and discriminates to whichever subclass the runtime handle carries,
    the sibling <code>Label</code> or the nested <code>Circle</code>; see
    <a href="#a-class-method-returning-a-sealed-base">A class method returning a sealed base</a>.
    Asserted by <code>FlatSealedSubclassTests</code>.</p>
</note>

### A sibling `object` subclass binds once too

The fix above only covered a `data class` sibling; a sibling `object` or `data object` subclass was still declared twice, once as `public sealed class Loaf : FlatShape` by the sealed route and once again as an empty `public static class Loaf { }` at namespace level, since `rootObjects` had no equivalent `isSealedSubclass()` filter. That was `CS0101` (duplicate type), plus `CS0722` at any position returning the concrete arm, since C# cannot return a `static` type ([#110](https://github.com/xxfast/kotlin-native-nuget/issues/110)). It went uncaught because every pre-existing sealed-subclass-object fixture happened to sit in the one combination the bug is invisible in: module-local **and** nested.

From `FlatShapeSample.kt`:

```kotlin
data object Loaf : FlatShape()

class FlatShapeFactory {
  fun loaf(): Loaf = Loaf
}

fun flatLoaf(): FlatShape = Loaf
```

`Loaf` is now declared exactly once, `public sealed class Loaf : FlatShape`, by the sealed route only. Two separate fixes were needed: `rootObjects` gained the same sealed-subclass filter `rootClasses` already had, for a **module-local** sibling like `Loaf`; and `reachabilityBucket()`'s object branch now checks `isSealedSubclass()` before bucketing as `OBJECT`, for a **cross-module** sealed base's nested `object` subclass (`Newsroom.nap()`/`deepNap()` in `IntegrationTests/SealedSubclassObjectTests.cs`), which used to emit a bogus, non-colliding but still public, orphan `public static class` alongside the real nested one. Only the `OBJECT` kind is qualified in that check: an intermediate sealed class is both sealed and a sealed subclass, and must keep the `SEALED_CLASS` bucket.

## Every property shape on a sealed subclass {id="every-property-shape-on-a-sealed-subclass"}

A property of a sealed subclass, a class nested inside its sealed parent, plans onto the same
[ADR-062](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md)
property plan an ordinary class property uses
([ADR-111](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/111-sealed-subclass-properties-on-the-property-plan.md)),
so every shape a class property supports is supported here too: a nullable enum, a nullable
exported reference, `Boolean`/`Boolean?`, a `List`/`Map`/`Set`, a `var` with a real setter, and
`Duration`/`Uuid`/value-class/interface types (see [ADR-103](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/103-duration-mapping.md),
[ADR-106](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/106-uuid-mapping.md)). Every getter reads the `out IntPtr error` slot and throws on failure, the same as any class property getter.

### Kotlin {id="property-plan-sealed-kotlin"}

From `test-library/src/nativeMain/kotlin/.../cat/Issue38Sample.kt`:

```kotlin
sealed class Issue38State {
  data class Loaded(
    val error: String?,
    val retries: Int?,
    val code: Int,
    val mood: Mood?,
    val friend: Cat?,
    val flag: Boolean,
    val maybeFlag: Boolean?,
    var note: String = "n",
    val took: Duration = 1500.milliseconds,
    val id: Uuid = Uuid.parse("feedface-0a1e-4c0a-b0b0-0ff1ceb0bade"),
  ) : Issue38State()

  data object Idle : Issue38State()

  class Issue38Boom : Issue38State() {
    val boom: String get() = throw IllegalStateException("boom")
  }
}
```

### Generated C# {id="property-plan-sealed-generated-c"}

From `Interop.cs`. `Mood` is a nullable enum, `Friend` a nullable exported reference read with a
single native call, `Note` the one `var` in the hierarchy, and `Took` a `Duration` bound as
`TimeSpan`:

```C#
public global::TestLibrary.Cat.Mood? Mood
{
    get
    {
    bool hasValue = Native_Get_mood(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    if (!hasValue) return null;
    int value = Native_Get_mood_value(_handle, out IntPtr error2);
    if (error2 != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error2);
    }
    return (global::TestLibrary.Cat.Mood)value;
    }
}

public global::TestLibrary.Cat.Cat? Friend
{
    get
    {                IntPtr nativeResult = Native_Get_friend(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return nativeResult == IntPtr.Zero ? null : new global::TestLibrary.Cat.Cat(nativeResult);
    }
}

public string Note
{
    get
    {                IntPtr nativeResult = Native_Get_note(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return Marshal.PtrToStringUTF8(nativeResult)!;
    }
    set
    {                Native_Set_note(_handle, value, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    }
}

public global::System.TimeSpan Took
{
    get
    {                long nativeResult = Native_Get_took(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return new global::System.TimeSpan(nativeResult);
    }
}
```

`Friend` reads once per call, unlike the legacy route's earlier double call, and `Flag`/`MaybeFlag`
carry `[return: MarshalAs(UnmanagedType.I1)]` on their `DllImport`s, the same as any `Boolean`
property. `Id` (a `Uuid`) binds as `System.Guid`, the same as on an ordinary class.

### Using it from C# {id="property-plan-sealed-using-it-from-c"}

A throwing Kotlin getter surfaces as a thrown exception, not a default value, from
`IntegrationTests/Issue38Tests.cs`:

```C#
[Fact]
public void State_Boom_ReadingBoom_Throws()
{
    using Issue38State state = Issue38Sample.Issue38State(4);
    var boom = Assert.IsType<Issue38State.Issue38Boom>(state);
    Assert.ThrowsAny<InvalidOperationException>(() => boom.Boom);
}
```

The `var` setter round-trips too:

```C#
[Fact]
public void State_Loaded_Note_SetterRoundTrips()
{
    using Issue38State state = Issue38Sample.Issue38State(0);
    var loaded = Assert.IsType<Issue38State.Loaded>(state);
    loaded.Note = "changed";
    Assert.Equal("changed", loaded.Note);
}
```

This is delivered for sealed subclasses only. A plain nested (non-sealed) class, `class Outer { data class Inner(val x: String?) }`, is never declared in C# either, but it now skips named (`SKIPPED_NESTED_DECLARATION` on the declaration, `UNDECLARED_CLASS` on any member typed with it) instead of vanishing with no diagnostic; see [Classes and objects: Nested classes and objects](classes-and-objects.md#nested-classes-and-objects).

### A `data object` subclass's own properties bind too {id="data-object-subclass-properties-bind-too"}

A `data object` sealed subclass binds its own properties exactly like a `data class` subclass does; it used to render an empty property list unconditionally, which orphaned the Kotlin export and aborted the whole `packNuget` run rather than merely dropping a member ([#107](https://github.com/xxfast/kotlin-native-nuget/issues/107)). From `test-library/src/nativeMain/kotlin/.../issue54/NestedShapeSample.kt`:

```kotlin
sealed class NestedShape {
  abstract val sides: Int?

  data class Circle(val radius: Double) : NestedShape() {
    override val sides: Int? = null
  }

  data object Empty : NestedShape() {
    override val sides: Int = 0
    val note: String = "sprawled"
  }
}
```

Using it, from `IntegrationTests/DataObjectSealedSubclassPropertyTests.cs`:

```C#
[Fact]
public void Empty_PrimitivePropertyOnADataObjectArm_BindsAndReadsBack()
{
    using NestedShape shape = NestedShapeSample.EmptyShape();

    var empty = Assert.IsType<NestedShape.Empty>(shape);
    Assert.Equal(0, empty.Sides);
}

[Fact]
public void Empty_ReferencePropertyOnADataObjectArm_RoundTripsItsValue()
{
    using NestedShape shape = NestedShapeSample.EmptyShape();

    var empty = Assert.IsType<NestedShape.Empty>(shape);
    Assert.Equal("sprawled", empty.Note);
}
```

<note>
    <p>Assertions have to reach through the concrete <code>Empty</code>/<code>Circle</code> arm,
    never the sealed base itself: <code>CirSealedClass</code> carries no <code>properties</code>
    field, so an <code>abstract val</code> declared on the base, like <code>sides</code> above,
    renders no C# member at all. Reading it polymorphically through <code>NestedShape</code>
    directly is still not possible; see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md">ROADMAP.md</a>.</p>
</note>

## Methods on a sealed subclass {id="methods-on-a-sealed-subclass"}

A public method a sealed subclass **itself declares**, including its own `override fun`, binds the
same way a method on an ordinary class does: parameters and returns plan through the same
[ADR-062](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md)
callable plan, with overloads ([ADR-090](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/090-ordinary-class-method-overloads.md)),
default-argument omitting overloads ([ADR-096](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/096-function-default-parameters.md)),
and every shape a class method supports, exported `${sealed}_${sub}_${name}[_n]` beside the arm's
own property getters ([#115](https://github.com/xxfast/kotlin-native-nuget/issues/115),
[ADR-116](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/116-sealed-subclass-methods-on-the-callable-plan.md)).
This is **declared-only**: an inherited base `open fun` body an arm does not override renders on no
arm at all. A declared `suspend fun` also binds now, as `Task<T> XxxAsync(...)` off the arm's own
export prefix, re-keying the same legacy suspend route an ordinary class method uses
([ADR-118](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/118-suspend-route-sealed-arm-owners-and-overload-numbering.md)).
A declared `Flow<T>`/`StateFlow<T>` member, at a property getter or a method return, also binds now,
re-keying the same legacy flow route an ordinary class uses onto the arm's own export prefix, see
[Flow and StateFlow members on a sealed arm](#sealed-flow-generated-c)
([ADR-124](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/124-flow-route-sealed-arm-owners.md)).
A lambda-parameter or generic method is still absent from C# and named instead of silently dropped,
since a sealed arm has no legacy route to fall back on for those shapes.

### Kotlin {id="sealed-method-kotlin"}

From `test-library/src/nativeMain/kotlin/.../issue115/JobSample.kt`:

```kotlin
sealed class Job {
  /** Base body. Under ADR-116's declared-only decision this renders on no arm at all. */
  open fun describe(): String = "job"

  /** Base body on the **suspend** loop: declared-only holds here too, so no arm carries `RestAsync`. */
  open suspend fun rest(): Int = 0

  data class Running(val progress: Int) : Job() {
    fun cancel(): Int = progress

    fun label(prefix: String): String = "$prefix$progress"

    /** Overload pair, first arm. */
    fun step(by: Int): Int = progress + by

    /** Overload pair, second arm: same public C# name `Step`, `_2` native symbol. */
    fun step(by: Int, times: Int): Int = progress + by * times

    /** Sealed **base** return: `Job.FromHandle` discriminates it back onto [Done]. */
    fun next(): Job = Done(progress)

    /** Sibling nested arm return, spelled as its own concrete type. */
    fun finish(): Done = Done(progress)

    /** Nullable top-level interface return (ADR-040). */
    fun pick(): JobListener? =
      if (progress > 0) {
        object : JobListener {
          override fun onEvent(): String = "Oreo is $progress% of the way to the bowl"
        }
      } else {
        null
      }

    /** Nullable **nested** interface return: never declared in C#, so a named skip, not a binding. */
    fun pickNested(): NestedListenerOwner.Listener? = null

    /** `suspend` on an arm: binds as `Task<int> PauseAsync()` off `job_running_pause_async`. */
    suspend fun pause(): Int = progress

    /** Second arm of a `suspend` overload pair: `_2` on both the entry point and the private extern. */
    suspend fun pause(millis: Int): Int = progress + millis

    /** `String` in and out across the async result protocol. */
    suspend fun resume(prefix: String): String = "$prefix$progress"
  }

  data class Done(val code: Int) : Job()

  data object Idle : Job() {
    /** A method on an object arm: it takes the handle receiver, not a static route. */
    fun poke(): String = "idle"

    /** Declared `override` of [Job.describe]: renders as a plain `public` method on the arm. */
    override fun describe(): String = "idle"

    /** `suspend` on a `data object` arm: same handle receiver, brings its own scope. */
    suspend fun nap(): String = "napping"
  }
}
```

### Generated C# {id="sealed-method-generated-c"}

From `Interop.cs`. `Running` gets every plannable shape at once, `Cancel` with no conversion at the
seam, `Label` marshalling `String` both ways, both `Step` overloads (the second on the `_2` native
symbol), `Next` returning the sealed base and discriminating back through `Job.FromHandle`, `Finish`
returning the sibling nested arm as its own concrete type, and `Pick` dispatching through the
top-level interface:

```C#
public sealed class Running : Job
{
    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "job_running_cancel")]
    private static extern int Native_Cancel(IntPtr handle, out IntPtr error);

    public int Cancel()
    {
        int result = Native_Cancel(_handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return result;
    }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "job_running_step")]
    private static extern int Native_Step(IntPtr handle, int by, out IntPtr error);

    public int Step(int by) { /* ... */ }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "job_running_step_2")]
    private static extern int Native_Step_2(IntPtr handle, int by, int times, out IntPtr error);

    public int Step(int by, int times) { /* ... */ }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "job_running_next")]
    private static extern IntPtr Native_Next(IntPtr handle, out IntPtr error);

    public global::TestLibrary.Issue115.Job Next()
    {
        IntPtr nativeResult = Native_Next(_handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return global::TestLibrary.Issue115.Job.FromHandle(nativeResult);
    }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "job_running_finish")]
    private static extern IntPtr Native_Finish(IntPtr handle, out IntPtr error);

    public global::TestLibrary.Issue115.Job.Done Finish()
    {
        IntPtr nativeResult = Native_Finish(_handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return new global::TestLibrary.Issue115.Job.Done(nativeResult);
    }
}
```

An `object`/`data object` arm's methods take the same handle receiver a `data class` arm's do,
rather than becoming static: `Idle`'s `Poke()` and its own declared `override fun describe()`:

```C#
public sealed class Idle : Job
{
    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "job_idle_poke")]
    private static extern IntPtr Native_Poke(IntPtr handle, out IntPtr error);

    public string Poke()
    {
        IntPtr nativeResult = Native_Poke(_handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return Marshal.PtrToStringUTF8(nativeResult)!;
    }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "job_idle_describe")]
    private static extern IntPtr Native_Describe(IntPtr handle, out IntPtr error);

    public string Describe()
    {
        IntPtr nativeResult = Native_Describe(_handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return Marshal.PtrToStringUTF8(nativeResult)!;
    }
}
```

`Idle.Describe()` is a plain `public` method: not `override` (the C# sealed base declares nothing
to override, `CS0115`) and not `virtual` (a `virtual` member on a `public sealed class` is
`CS0549`). `Job.Running` has no `Describe()` at all, since it does not override the base's
`open fun describe()` and the base body itself is not carried onto any arm (declared-only).

### Suspend methods on a sealed arm {id="sealed-method-suspend-generated-c"}

A `suspend fun` an arm **declares** binds the same shape an ordinary class's suspend method has had
since [ADR-019](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/019-suspend-function-mapping.md),
re-keyed onto the arm's own export prefix. Two overloads on one arm are one C# overload set, the
second numbered `_2` on both the `[DllImport]` entry point and the private extern's C# name, so a
mis-numbered extern would silently dispatch to the first overload's body instead of failing to
compile:

```C#
public sealed class Running : Job, IAsyncDisposable
{
    internal IntPtr _scopeHandle;

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "job_running_pause_async")]
    private static extern IntPtr Native_PauseAsync(IntPtr handle, IntPtr scopeHandle, IntPtr callback, IntPtr userData);

    public Task<int> PauseAsync(CancellationToken cancellationToken = default) { /* ... */ }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "job_running_pause_2_async")]
    private static extern IntPtr Native_Pause_2Async(IntPtr handle, IntPtr scopeHandle, int millis, IntPtr callback, IntPtr userData);

    public Task<int> PauseAsync(int millis, CancellationToken cancellationToken = default) { /* ... */ }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "job_running_resume_async")]
    private static extern IntPtr Native_ResumeAsync(IntPtr handle, IntPtr scopeHandle, [MarshalAs(UnmanagedType.LPUTF8Str)] string prefix, IntPtr callback, IntPtr userData);

    public Task<string> ResumeAsync(string prefix, CancellationToken cancellationToken = default) { /* ... */ }
}
```

A `suspend fun` on an arm returning `List<T>`, `Set<T>` or `Map<K, V>` binds as `Task<IReadOnlyList<T>>` (and kin), spelled exactly as the arm's own property of the same type is spelled and read through the same collection helpers; any other generic return on the arm is absent and named `SKIPPED_UNSUPPORTED_RETURN`. See [`suspend fun` returning a collection](coroutines-and-flow.md#suspend-fun-returning-a-collection) ([ADR-119](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/119-collection-returns-on-the-legacy-suspend-route.md)).

An arm with a suspend member also declares `IAsyncDisposable`, with `DisposeAsync` draining its own
scope before disposing the handle; `Dispose()` cancels and disposes the scope first, then calls the
same `Native_Dispose`. An arm with **no** suspend member (`Done`) is unchanged: it stays
`IDisposable` only, and its `Dispose()` body is the plain hand-rolled form, not the scope-aware one.
An `object`/`data object` arm takes the same shape on the same handle receiver:

```C#
public sealed class Idle : Job, IAsyncDisposable
{
    internal IntPtr _scopeHandle;

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "job_idle_nap_async")]
    private static extern IntPtr Native_NapAsync(IntPtr handle, IntPtr scopeHandle, IntPtr callback, IntPtr userData);

    public Task<string> NapAsync(CancellationToken cancellationToken = default) { /* ... */ }
}

public sealed class Done : Job   // no suspend member: IDisposable only, unchanged
{
    public override void Dispose()
    {
        if (_handle != IntPtr.Zero)
        {
            Native_Dispose(_handle);
            _handle = IntPtr.Zero;
        }
    }
}
```

<note>
    <p>The sealed <b>base</b> itself stays <code>: IDisposable, INugetHandle</code>; only an arm that
    declares a suspend member gains <code>IAsyncDisposable</code>. A consumer holding a <code>Job</code>
    has to pattern-match to the concrete arm before <code>await using</code> / <code>DisposeAsync()</code>.</p>
</note>

```C#
Job job = JobSample.AnyJob(40);
if (job is Job.Running running) await running.DisposeAsync();   // drains the arm's scope first
else job.Dispose();                                              // sync path, works on every arm
```

What is absent from C# entirely, each named rather than silent, from `NugetDiagnostics.json`:

```
[nuget:SKIPPED_UNSUPPORTED_TYPE] Skipping io.github.xxfast.kotlin.native.nuget.test.issue115.Job.Running.pickNested:
    its UNDECLARED_INTERFACE type combination is not supported. interface
    `io.github.xxfast.kotlin.native.nuget.test.issue54.NestedListenerOwner.Listener` is nested
    inside a class, and a nested interface is never declared as a C# interface (only top-level ones
    are), so every member typed with it is skipped rather than emitted as a dangling reference;
    move it to the top level of its file
```

### Flow and StateFlow members on a sealed arm {id="sealed-flow-generated-c"}

A `Flow<T>`/`StateFlow<T>` an arm **declares**, at a property getter or a method return, binds the
same shape an ordinary class's flow member has had since [ADR-065](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/065-stateflow-mapping.md),
re-keyed onto the arm's own export prefix, through the same collect and value thunks the
ordinary-class route uses ([#129](https://github.com/xxfast/kotlin-native-nuget/issues/129),
[ADR-124](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/124-flow-route-sealed-arm-owners.md)).
An overload pair on an arm's flow-returning method numbers `_2` on both the entry point and the
private extern's stem, exactly as an ordinary flow method overload does.

From `test-library/.../issue115/JobSample.kt`, a flow-only arm (no `suspend` member of its own):

```kotlin
data class Watching(val id: String) : Job() {
  private val _ticks: MutableStateFlow<Int> = MutableStateFlow(id.length)

  val ticks: StateFlow<Int> get() = _ticks

  fun labels(prefix: String): Flow<String> = flow { emit("$prefix$id") }

  fun labels(prefix: String, times: Int): Flow<String> =
    flow { repeat(times) { index -> emit("$prefix#$index") } }
}
```

From `Interop.cs`. `Watching` gets its own `_scopeHandle` and `IAsyncDisposable` from the flow route
alone, exactly as a flow-only ordinary class does:

```C#
public sealed class Watching : Job, IAsyncDisposable
{
    internal IntPtr _scopeHandle;

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "job_watching_get_ticks_collect")]
    private static extern IntPtr Native_GetTicksCollect(IntPtr handle, IntPtr scopeHandle, IntPtr onNext, IntPtr onComplete, IntPtr onError, IntPtr userData);

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "job_watching_get_ticks_value")]
    private static extern IntPtr Native_GetTicksValue(IntPtr handle);

    public KotlinStateFlow<int> Ticks
    {
        get
        {
            if (_handle == IntPtr.Zero)
                throw new ObjectDisposedException(nameof(Watching));
            return new KotlinStateFlow<int>((onNext, onComplete, onError, userData) =>
                Native_GetTicksCollect(_handle, GetOrCreateScope(), onNext, onComplete, onError, userData),
                () => Native_GetTicksValue(_handle));
        }
    }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "job_watching_labels_collect")]
    private static extern IntPtr Native_LabelsCollect(IntPtr handle, IntPtr scopeHandle, [MarshalAs(UnmanagedType.LPUTF8Str)] string prefix, IntPtr onNext, IntPtr onComplete, IntPtr onError, IntPtr userData);

    public KotlinFlow<string> Labels(string prefix) { /* ... */ }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "job_watching_labels_2_collect")]
    private static extern IntPtr Native_Labels_2Collect(IntPtr handle, IntPtr scopeHandle, [MarshalAs(UnmanagedType.LPUTF8Str)] string prefix, int times, IntPtr onNext, IntPtr onComplete, IntPtr onError, IntPtr userData);

    public KotlinFlow<string> Labels(string prefix, int times) { /* ... */ }
}
```

A base-declared flow **property** binds on every arm, under each arm's own prefix (the ADR-111
all-properties rule); a base-declared flow **method** binds on no arm (the ADR-116 declared-only
rule for methods), the same asymmetry the suspend route already has.

<note>
    <p>An arm that already carries a <code>suspend</code> member gains a flow member onto the
    <b>same</b> scope: one <code>_scopeHandle</code>, one <code>DisposeAsync</code>, not two.
    <code>Job.Running.Beats</code> is a <code>StateFlow&lt;Int&gt;</code> alongside
    <code>Running</code>'s <code>PauseAsync</code>/<code>ResumeAsync</code>, and both routes share
    the one scope field.</p>
</note>

### Using it from C# {id="sealed-flow-using-it-from-c"}

From `IntegrationTests/SealedSubclassMethodTests.cs`:

```C#
await using Job.Watching mylo = factory.Watching("Mylo");

Assert.Equal(4, mylo.Ticks.Value);

await foreach (string label in mylo.Labels("windowsill:"))
{
    labels.Add(label);
}

Assert.True(typeof(IAsyncDisposable).IsAssignableFrom(typeof(Job.Watching)));
Assert.False(typeof(IAsyncDisposable).IsAssignableFrom(typeof(Job.Done)));
```

The coexistence cell, one scope shared by both routes on `Running`:

```C#
await using Job.Running oreo = factory.Running(60);

Assert.Equal(60, oreo.Beats.Value);
Assert.Equal(60, await oreo.PauseAsync());
```

### Using it from C# {id="sealed-method-using-it-from-c"}

From `IntegrationTests/SealedSubclassMethodTests.cs`:

```C#
using var factory = new JobFactory();
using Job.Running oreo = factory.Running(5);

using Job next = oreo.Next();
Job.Done done = Assert.IsType<Job.Done>(next);
Assert.Equal(5, done.Code);

using Job.Running sixty = factory.Running(60);
using IJobListener? listener = sixty.Pick();
Assert.NotNull(listener);
Assert.Equal("Oreo is 60% of the way to the bowl", listener!.OnEvent());

using Job.Idle mylo = factory.Idle();
Assert.Equal("idle", mylo.Poke());
Assert.Equal("idle", mylo.Describe());
```

The suspend members, both overloads on one receiver, and the arm's `IAsyncDisposable`:

```C#
using var factory = new JobFactory();
await using Job.Running oreo = factory.Running(10);

Assert.Equal(10, await oreo.PauseAsync());
Assert.Equal(15, await oreo.PauseAsync(5));

await using Job.Running running = factory.Running(80);
Assert.Equal("hallway:80", await running.ResumeAsync("hallway:"));

Assert.False(typeof(IAsyncDisposable).IsAssignableFrom(typeof(Job.Done)));
```

The absences are asserted by reflection, since a missing member is invisible to the compiler in the
other direction. `RestAsync` is absent on every arm, including `Idle` and `Done`, because `Job.rest`
is a base body no arm overrides (declared-only on the suspend loop too), and `PickNested` stays a
named skip (a nested interface return):

```C#
Assert.Null(typeof(Job.Running).GetMethod(
    "Describe", BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly));
Assert.Null(typeof(Job.Running).GetMethod("RestAsync"));
Assert.Null(typeof(Job.Idle).GetMethod("RestAsync"));
Assert.Null(typeof(Job).GetMethod("RestAsync"));
Assert.Null(typeof(Job.Running).GetMethod("PickNested"));
```

## Defaulted interface members on implementing classes

A class implementing an interface without overriding one of its defaulted members still has to carry that member in C#: the generated class declares the interface, so omitting the member is `CS0535`. The defaulted body is reached by ordinary dynamic dispatch on the Kotlin instance behind the handle, so no separate delegation is generated for it.

From `test-library/src/nativeMain/kotlin/.../cat/Greeter.kt`:

```kotlin
interface Greeter {
  val greeting: String get() = "hello"

  fun greet(): String = "$greeting from a $species"

  val species: String
}

class Parrot(override val species: String) : Greeter
```

`Parrot` declares only `species`; `greeting` and `greet()` are inherited defaults, and both still bind:

```C#
public class Parrot : IGreeter
{
    internal IntPtr _handle;

    public Parrot(string species)
    {
        /* ... */
    }

    public virtual string Species
    {
        get { /* ... */ }
    }

    public string Greeting
    {
        get { /* ... */ }
    }

    public string Greet()
    {
        /* ... */
    }
}
```

From `IntegrationTests/DefaultedInterfaceMemberTests.cs`:

```C#
[Fact]
public void Parrot_Greeting_UsesDefaultProperty()
{
    using var parrot = new Parrot("macaw");
    Assert.Equal("hello", parrot.Greeting);
}

[Fact]
public void Parrot_Greet_UsesDefaultMethod()
{
    using var parrot = new Parrot("macaw");
    Assert.Equal("hello from a macaw", parrot.Greet());
}

[Fact]
public void IGreeter_Greet_ReachesTheDefaultThroughTheInterface()
{
    using IGreeter greeter = new Parrot("cockatoo");
    Assert.Equal("hello", greeter.Greeting);
    Assert.Equal("hello from a cockatoo", greeter.Greet());
}
```

<note>
    <p>
        This used to be a gap: a class with no <code>ClassKind.CLASS</code> supertype (interface-only)
        skipped its defaulted interface members entirely, with no export, no C# member, and no
        diagnostic, while the generated class still declared the interface. Two planners and one
        translator each answered "does this class have a superclass" differently; all four
        membership checks now share one predicate.
    </p>
</note>

## Interface-typed return values

A Kotlin function or property whose declared return type is an interface (`fun closestFriend(): Pet`, `var friend: Pet?`) surfaces in C# as `IFoo` / `IFoo?`, constructed from the generated `sealed class Foo : IFoo` backing wrapper. The concrete Kotlin object behind the interface can be any implementation, including an anonymous `object`, which is why the wrapper dispatches through generated Kotlin interface-dispatch exports (`pet_get_name`, `pet_speak`, ...) rather than resolving a concrete C# type.

From `Pet.kt` and `Cat.kt`:

```kotlin
interface Pet {
  val name: String // String getter: needs UTF8 marshalling
  val legs: Int // primitive getter: no conversion at all - catches an open-coded conversion bug
  val nickname: String? // nullable String getter: IntPtr.Zero -> null
  fun speak(): String // String-returning method
  fun greet(): String = "Hi, I'm $name" // default method: dispatch must reach the override
  fun fetch(item: String): String // String *input* on the dispatch export
  fun nap() // Unit-returning method (void export)
}

// The strongest polymorphism proof: an anonymous object with no generated C# wrapper of its own,
// so the consumer can only reach it through `pet_*` dispatch.
fun strayPet(): Pet = object : Pet {
  override val name: String = "Whiskers the Stray"
  override val legs: Int = 3
  override val nickname: String? = null
  override fun speak(): String = "Mrrp?"
  override fun fetch(item: String): String = "eyes the $item warily but doesn't fetch it"
  override fun nap() = Unit
}
```

`Cat` gains a nullable interface-typed property (both get and set), an interface-typed parameter, and both a nullable and non-null interface-typed method return:

```kotlin
var friend: Pet? = null

fun befriend(pet: Pet) {
  friend = pet
}

fun closestFriend(): Pet = friend ?: this

fun maybeFriend(): Pet? = friend

val self: Pet get() = this
```

## Generated C#: the backing class

Every Kotlin interface still gets its `IFoo` projection, plus a new `sealed class Foo : IFoo` that dispatches each member through a generated `foo_*` Kotlin export:

```C#
public interface IPet : IDisposable
{
    string Name { get; }
    int Legs { get; }
    string? Nickname { get; }

    string Speak();
    string Greet();
    string Fetch(string item);
    void Nap();
}

public sealed class Pet : IPet
{
    internal IntPtr _handle;

    internal Pet(IntPtr handle)
    {
        _handle = handle;
    }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "pet_get_name")]
    private static extern IntPtr Native_Get_name(IntPtr handle, out IntPtr error);

    public string Name
    {
        get { /* ... */ }
    }

    // Legs, Nickname, Speak(), Greet(), Fetch(item), Nap() follow the same pet_* dispatch pattern.
}
```

`Cat`'s interface-typed positions construct `Pet` but declare `IPet`:

```C#
public IPet? Friend
{
    get
    {
        IntPtr nativeResult = Native_Get_friend(_handle, out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        return nativeResult == IntPtr.Zero ? null : (NugetMarshal.TryResolveCSharp(nativeResult, out IPet csharpOriginal) ? csharpOriginal : new Pet(nativeResult));
    }
    set
    {
        IntPtr valueHandle = NugetMarshal.HandleOfOrZero(value, out bool valueOwned);
        Native_Set_friend(_handle, valueHandle, out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        if (valueOwned) { NugetMarshal.Dispose(valueHandle); }
    }
}

public IPet ClosestFriend()
{
    IntPtr nativeResult = Native_ClosestFriend(_handle, out IntPtr error);
    if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
    return (NugetMarshal.TryResolveCSharp(nativeResult, out IPet csharpOriginal) ? csharpOriginal : new Pet(nativeResult));
}
```

`NugetMarshal.TryResolveCSharp` probes the returned handle for a C#-originated bridge token before falling back to the ordinary `new Pet(nativeResult)` wrapper; see [Implementing a Kotlin interface in C#](#implementing-a-kotlin-interface-in-c) below. `HandleOfOrZero`/`HandleOf` return an `owned` flag so the caller can dispose a handle it minted itself (a bridge transfer handle) without touching one it merely read off an existing wrapper's `_handle`.

`befriend(pet: Pet)` is an interface-typed **parameter**. It accepts a Kotlin-backed `IPet` (one of the generated wrapper classes, which carry `_handle`) or a C#-implemented one (see [Implementing a Kotlin interface in C#](#implementing-a-kotlin-interface-in-c) below), extracted via a shared helper:

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "cat_befriend")]
private static extern void Native_Befriend(IntPtr handle, IntPtr pet, out IntPtr error);

public void Befriend(IPet pet)
{
    IntPtr petHandle = NugetMarshal.HandleOf(pet, out bool petOwned);
    Native_Befriend(_handle, petHandle, out IntPtr error);
    if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
    if (petOwned) { NugetMarshal.Dispose(petHandle); }
}
```

`NugetMarshal.HandleOf` checks whether the value implements the internal `INugetHandle` interface
(see [ADR-094](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/094-reflection-free-generic-dispatch.md));
when it does not, i.e. the object is a C#-implemented `IPet` rather than a Kotlin one, it falls back to a bridge instead of throwing:

```C#
internal static IntPtr HandleOf(object value)
{
    if (value is INugetHandle wrapper) return wrapper.Handle;
    return NugetBridge.HandleFor(value);
}
```

The `petOwned` flag distinguishes a handle `HandleOf` merely read off an existing wrapper (not owned, must not be disposed here) from one it minted fresh for a bridge (owned, a one-shot transfer handle disposed right after the call).

<note>
    <p>Each interface-typed return mints a <b>fresh</b> <code>StableRef</code>, so a returned <code>IPet</code> wrapper disposes independently of the object it came from. Reading a Kotlin-backed <code>cat.Friend</code> twice produces two distinct C# wrapper instances over the same underlying Kotlin object, consistent with <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/005-object-return-semantics.md">ADR-005</a>. A <b>C#-implemented</b> <code>IPet</code> stored and read back does not go through this path at all: it resolves to the original C# instance instead, see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/084-csharp-implemented-interfaces.md">ADR-084</a>.</p>
</note>

## Using interface-typed returns from C#

From `IntegrationTests/InterfaceReturnTests.cs`:

```C#
[Fact]
public void Befriend_ThenFriend_ReturnsBefriendedPetAsIPet()
{
    using var oreo = new Cat("Oreo", 9);
    using var mylo = new Cat("Mylo", 3);

    oreo.Befriend(mylo);

    using IPet? friend = oreo.Friend;
    Assert.NotNull(friend);
    Assert.Equal("Mylo", friend!.Name);
    Assert.Equal(4, friend.Legs);
    Assert.Equal("Meow! My name is Mylo", friend.Speak());
    Assert.Equal("Hi, I'm Mylo", friend.Greet());
}

// The seam that fails if the backing class ever tries to resolve the concrete Kotlin type:
// the runtime object is an anonymous `object : Pet` with no generated C# wrapper at all.
[Fact]
public void StrayPet_AnonymousKotlinObject_DispatchesThroughIPet()
{
    using IPet stray = PetKt.StrayPet();
    Assert.Equal("Whiskers the Stray", stray.Name);
    Assert.Equal(3, stray.Legs);
    Assert.Null(stray.Nickname);
    Assert.Equal("Mrrp?", stray.Speak());
    Assert.Equal("Hi, I'm Whiskers the Stray", stray.Greet()); // interface default implementation
    Assert.Equal("eyes the yarn warily but doesn't fetch it", stray.Fetch("yarn"));
    stray.Nap(); // void dispatch export - just must not throw
}
```

## Declaring every exported interface {id="declaring-every-exported-interface"}

Everything above covers an interface that is **reachable**, one that actually appears in a planned return position, and therefore gets ADR-040's backing class. An interface that is only ever *implemented*, never returned, still gets its `IFoo` declared, and that declaration used to come from a separate, string-keyed walk over Kotlin simple names rather than from the forward plan the implementing class itself uses. The two disagreed: a reference-typed member rendered raw `IntPtr` on `IFoo` while the implementing class's own property was the real wrapper type (`CS0738`), a member the class route skipped for being unbridgeable was still declared on `IFoo` with nothing to satisfy it (`CS0535`), and a property colliding with a same-named method (`val collarTag` + `fun collarTag(code: Int)`) declared both with no guard at all (`CS0102`) ([#112](https://github.com/xxfast/kotlin-native-nuget/issues/112)).

Interface declarations now come from a second, declaration-only plan built over **every** exported interface, reachable or not, so `IFoo` never loses a member for reachability reasons; the export-driving plan stays exactly as reachability-scoped as before. From `test-library/src/nativeMain/kotlin/.../issue112/Issue112Sample.kt`:

```kotlin
interface Advertisement {
  val identifier: String
  val collarTag: CollarTag?
  val codes: Collection<String>
  fun collarTag(code: Int): ByteArray?
  fun describe(prefix: String): String
}

class BleAdvertisement(
  override val identifier: String,
  override val collarTag: CollarTag?,
) : Advertisement {
  override val codes: Collection<String> get() = listOf(identifier)
  override fun collarTag(code: Int): ByteArray? = null
  override fun describe(prefix: String): String = "$prefix$identifier"
}
```

`Advertisement` is never returned anywhere, so it has no ADR-040 backing class; only `BleAdvertisement` implements it. `codes` (`Collection<String>` is not one of the six collection kinds the classifier knows) and `collarTag(code: Int)` (unbridgeable, and it would collide with the `collarTag` property if it survived) are both bridgeable-filtered out silently, the class route having already reported the skip once. Generated C#:

```C#
public interface IAdvertisement : IDisposable
{
    string Identifier { get; }
    global::TestLibrary.Issue112.CollarTag? CollarTag { get; }

    string Describe(string prefix);
}
```

Using it, from `IntegrationTests/Issue112Tests.cs`:

```C#
[Fact]
public void InterfaceMember_ReferenceType_MatchesTheImplementingClassProjection()
{
    PropertyInfo? onInterface = typeof(IAdvertisement).GetProperty(nameof(IAdvertisement.CollarTag));
    PropertyInfo? onClass = typeof(BleAdvertisement).GetProperty(nameof(BleAdvertisement.CollarTag));

    Assert.NotNull(onInterface);
    Assert.NotNull(onClass);
    Assert.NotEqual(typeof(IntPtr), onInterface!.PropertyType);
    Assert.Equal(onClass!.PropertyType, onInterface.PropertyType);
}

[Fact]
public void InterfaceProperty_SkippedByThePlan_IsAbsent()
{
    Assert.Null(typeof(IAdvertisement).GetProperty("Codes"));
}

[Fact]
public void Interface_IsImplementedByTheExportedClass()
{
    Assert.True(typeof(IAdvertisement).IsAssignableFrom(typeof(BleAdvertisement)));
}
```

If a Kotlin declaration genuinely has a property and a method sharing a C# name and both survive the bridgeability filter, that is a fatal `ERROR_CSHARP_NAME_COLLISION`, not a rename: the same house rule the class route already applies (see [FEATURES.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/FEATURES.md)).

<note>
    <p>A member typed with the interface's own type parameter (<code>fun read(): T</code> on
    <code>interface Box&lt;T&gt;</code>) keeps rendering bare, never dropped: it is valid C# in
    scope, and only lacked a plan entry, not bridgeability.</p>
</note>

## Implementing a Kotlin interface in C#

A C# class implementing `IPet` with no `_handle` field, an ordinary class, not one of the generated wrappers, can now be passed at an interface-typed parameter or property setter. `HandleOf`'s bridge fallback builds a Kotlin-side object with one function pointer per interface member and dispatches through it, so a Kotlin call against the parameter reaches the real C# implementation, not a stub.

From `IntegrationTests/BidirectionalTests.cs`:

```C#
private class Dog : IPet
{
    public string Name { get; }
    public int Legs => 4;
    public string? Nickname { get; }
    public Dog(string name, string? nickname = null) { Name = name; Nickname = nickname; }
    public string Speak() => "Woof!";
    public string Greet() => $"Hi, I'm {Name} the dog";
    public string Fetch(string item) => $"{Name} enthusiastically fetches the {item}";
    public void Nap() { }
    public void Dispose() { }
}

[Fact]
public void CSharpDog_ImplementsIPet()
{
    using IPet dog = new Dog("Rex");
    using var oreo = new Cat("Oreo", 9);

    oreo.Befriend(dog);

    // Both values only come back correct if Kotlin actually dispatched into `dog` through the
    // generated function-pointer slots: `ClosestFriend().Speak()` calls back into the C# object,
    // and `Interview` is a Kotlin extension (`"${pet.name} says: ${pet.speak()}"`) that reads two
    // separate slots and composes the result itself.
    Assert.Equal("Woof!", oreo.ClosestFriend().Speak());
    Assert.Equal("Rex says: Woof!", oreo.Interview(dog));
}

[Fact]
public void StoredCSharpPet_RoundTripsToTheOriginalInstance()
{
    using var oreo = new Cat("Oreo", 9);
    using IPet dog = new Dog("Rex");

    oreo.Befriend(dog);

    Assert.Same(dog, oreo.ClosestFriend());
    Assert.Same(dog, oreo.Friend);
}
```

### Generated Kotlin: the bridge factory

One factory export per interface, `pet_bridge_create`, with a fnPtr/ctx pair per member (`val` getters and non-`Unit`-returning methods included) plus a release fnPtr/ctx pair. It builds an anonymous `object : Pet` that dispatches every member through its own slot and carries a `createCleaner` tied to the release slot:

From `CNameExports.kt`:

```kotlin
@CName("pet_bridge_create")
@OptIn(ExperimentalNativeApi::class)
public fun export_pet_bridge_create(
  nameGetPtr: COpaquePointer, nameGetCtx: COpaquePointer,
  legsGetPtr: COpaquePointer, legsGetCtx: COpaquePointer,
  nicknameGetPtr: COpaquePointer, nicknameGetCtx: COpaquePointer,
  speakPtr: COpaquePointer, speakCtx: COpaquePointer,
  greetPtr: COpaquePointer, greetCtx: COpaquePointer,
  fetchPtr: COpaquePointer, fetchCtx: COpaquePointer,
  napPtr: COpaquePointer, napCtx: COpaquePointer,
  releasePtr: COpaquePointer, releaseCtx: COpaquePointer,
  token: COpaquePointer,
  errorOut: COpaquePointer?,
): COpaquePointer? = try {
  val nameGetFn = nameGetPtr.reinterpret<CFunction<(COpaquePointer) -> COpaquePointer?>>()
  val releaseFn = releasePtr.reinterpret<CFunction<(COpaquePointer) -> Unit>>()
  // ... one reinterpret per remaining slot ...
  val bridge = object : io.github.xxfast.kotlin.native.nuget.test.cat.Pet, NugetCSharpBridge {
    override val nugetToken: COpaquePointer = token
    @Suppress("unused")
    private val cleaner = createCleaner(releaseFn to releaseCtx) { (fn, ctx) ->
      fn.invoke(ctx)
    }
    override val name: String
      get() {
        val ref = nameGetFn.invoke(nameGetCtx)!!
        val value = ref.asStableRef<String>().get()
        ref.asStableRef<Any>().dispose()
        return value
      }
    // ... legs, nickname, speak(), greet(), fetch(item), nap() follow the same pattern ...
  }
  StableRef.create(bridge).asCPointer()
} catch (e: Throwable) { /* ... */ null }
```

### Generated C#: the bridge state

C# pins one delegate per slot, calls the factory once per crossing, and frees every pin from the
release slot. Each slot's function pointer is a shared `[UnmanagedCallersOnly]` static thunk keyed
off that slot's own `GCHandle` ctx (`NugetThunks`, see
[Publishing Kotlin to C#: AOT and trimming](forward-overview.md#aot-and-trimming),
[ADR-102](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/102-aot-safe-forward-callbacks.md)),
not a per-instance runtime-built thunk:

```C#
internal sealed class PetBridgeState : NugetBridgeState
{
    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "pet_bridge_create")]
    private static extern IntPtr Native_Create(IntPtr nameGetPtr, IntPtr nameGetCtx, /* ... */ IntPtr token, out IntPtr error);

    internal static PetBridgeState Create(TestLibrary.Cat.IPet impl)
    {
        var state = new PetBridgeState();
        state.Root();
        IntPtr token = state.TokenFor(impl);
        NugetBridgeObjectCallback nameGet = _ => { string result = impl.Name; return NugetMarshal.WrapString(result); };
        // ... legsGet, nicknameGet, speak, greet, fetch follow the same pattern ...
        NugetBridgeVoidCallback release = _ => state.FreeAll();
        IntPtr nameGetCtx = state.Pin(nameGet);
        // ... one state.Pin(...) call per slot ...
        IntPtr releaseCtx = state.Pin(release);
        state.KotlinHandle = Native_Create(
            NugetThunks.NugetBridgeObjectCallbackPtr, nameGetCtx, /* ... */
            NugetThunks.NugetBridgeVoidCallbackPtr, releaseCtx, token, out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        return state;
    }
}
```

### Lifetime and identity

<note>
    <p>The bridge's Kotlin-side cleaner only fires on a later GC round, never at the moment the C# reference is dropped: release is <b>GC-timed, not deterministic</b>. A <code>nuget_gc_collect</code> export exists so tests, and hosts that genuinely need it, can force a collection round rather than wait for one.</p>
</note>

`NugetMarshal.TryResolveCSharp` (used by every interface-typed return and getter shown above) probes a returned handle's `nuget_csharp_token` before constructing a fresh wrapper, so a stored C#-implemented object handed back to C# resolves to the **original instance**: `Assert.Same(dog, oreo.Friend)` holds. This is C#-side identity only. Passing the same `Dog` to Kotlin twice builds two separate bridge objects, one per crossing, so Kotlin-side `===` on the underlying bridge is not preserved, the same way identity is not preserved across two reads of a Kotlin-backed interface return (see the note above).

<note>
    <p>An <code>internal IntPtr NugetHandle</code> member on the generated <code>IFoo</code> was considered instead of the reflective helper, and rejected: <code>Interop.cs</code> compiles into the consumer assembly, so an abstract member would break any consumer-written <code>IFoo</code> implementer with <code>CS0535</code>.</p>
</note>

## Nested interfaces skip named {id="nested-interfaces-skip-named"}

An `interface` declared nested inside another class is never declared in C#: `rootInterfaces`
(`NugetProcessor.kt`) filters `parentDeclaration == null`, the same rule `rootEnums` applies to a
nested enum (see [Enums: Nested enums skip named](enums.md#nested-enums-skip-named)). Unlike the
nested-enum case, this was never a dangling-reference bug: `interfaceType`
(`ForwardBridgeTypeClassifier.kt`) has always gated every member typed with an undeclared interface
out of the export set, so nothing was ever spelled against a `IFoo` that no declaration backs. What
was missing was a name for the skip: it landed in the generic "unsupported type combination" bucket,
and a nullable return position was misreported as failing on `NULLABLE` rather than on the interface
itself. The skip is now the same `UNDECLARED_INTERFACE` reason `UNDECLARED_ENUM` uses, and the
nullable-return misattribution is fixed.

From `test-library/src/nativeMain/kotlin/.../issue54/NestedListenerOwner.kt`:

```kotlin
class NestedListenerOwner {

  /** Module-local, nested, and therefore never declared in C#. */
  interface Listener {
    fun onEvent(): String
  }

  /** Property position, nullable. */
  var listener: Listener? = null

  /** Parameter position, non-null. */
  fun attach(listener: Listener) {
    this.listener = listener
  }

  /** Return position, nullable. */
  fun current(): Listener? = listener

  /** Control: the sibling that must survive the gate. */
  val name: String = "owner"
}
```

The parameter and return positions skip with `SKIPPED_UNSUPPORTED_TYPE`, naming the
`UNDECLARED_INTERFACE` reason and the move-to-top-level fix:

```
[nuget:SKIPPED_UNSUPPORTED_TYPE] Skipping io.github.xxfast.kotlin.native.nuget.test.issue54.NestedListenerOwner.attach:
    its UNDECLARED_INTERFACE type combination is not supported. interface
    `io.github.xxfast.kotlin.native.nuget.test.issue54.NestedListenerOwner.Listener` is nested inside a
    class, and a nested interface is never declared as a C# interface (only top-level ones are), so
    every member typed with it is skipped rather than emitted as a dangling reference; move it to the
    top level of its file
```

The property position (`var listener: Listener?`) skips the same way, but through the ordinary
`SKIPPED_UNSUPPORTED_PROPERTY` message the property planner already emits for any type it has no
getter/setter shape for: it names the interface but not the move-to-top-level hint, the same gap
[Enums: Nested enums skip named](enums.md#nested-enums-skip-named) documents for a nested enum
property. The owning class still generates, and its unrelated `name` member still binds; see
`IntegrationTests/NestedInterfaceGateTests.cs`.

## Limitations

- A C#-implemented interface's bridge factory only ever gets `val` getters and `Unit`/primitive/`Boolean`/enum/`String`/`String?`-returning methods of arity 0-2. An interface with a `var` property, an object- or collection-typed member, a `suspend` member, or generics plans **no factory at all**, silently: `NugetMarshal.HandleOf` keeps the old `NotSupportedException` for it, with no diagnostic naming why.
- A C#-implemented object's bridge is released only when Kotlin's GC actually collects it, on a later collection round; there is no deterministic, prompt release comparable to `IDisposable`.
- Kotlin-side `===` on a C#-implemented object's bridge is not preserved across repeated crossings of the same C# instance: each crossing builds a new bridge object. C#-side identity (`Assert.Same` on the object read back from Kotlin) is preserved via a token probe.
- An interface member whose own return type is another interface or a class handle (chained resolution) is not supported.
- Interfaces with generic type parameters, suspend interface members, and `Flow`/`StateFlow`-valued interface members are not supported as return positions.
- A backing class and its dispatch exports are only generated for interfaces that actually appear in a planned return position; an interface only ever used as an `add`/`remove` subscription parameter (like `ICatEventListener`, see [Lambdas and callbacks](lambdas-and-callbacks.md)) does not get one.
- Object identity is not preserved across reads of a **Kotlin-backed** interface-typed property: two reads produce two distinct C# wrapper instances over the same Kotlin object (each disposes independently). A **C#-implemented** object read back is the one exception, see above.
- A sealed type in the export scope now binds at every position: property, callable return, and callable/constructor parameter (bare, nullable, or a collection component, read-only or mutable), see [Sealed types as property types](#sealed-types-as-property-types), [A class method returning a sealed base](#a-class-method-returning-a-sealed-base), and [A sealed type at a parameter position](#a-sealed-type-at-a-parameter-position). An **eligible** `sealed interface` binds the same way, see [Sealed interfaces](#sealed-interfaces). A value class whose underlying type is sealed also binds the same way, at a property, callable, or `List<T>` component position, see [Value classes: Over a sealed type](value-classes.md#over-a-sealed-type). What still does not bind: an extension function's **receiver** typed as a sealed base (`sealedAsHandle()` rewrites declared parameters only), an **ineligible** sealed interface at any position, and a sealed class **outside the export scope**. See [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).
- [Overriding a read-only property with `var`](#overriding-a-read-only-property-with-var) only guards against the exported-base-class shape. A base class's own `open val`/`open var` never renders `virtual` (its own modifier is never read), so any subclass `override` of it is `CS0506`; and an unimplemented base `abstract val`/`abstract var` has no abstract-property path at all, so a subclass `override` of it is `CS0115`. Neither is fixed. See [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).
- [Declaring every exported interface](#declaring-every-exported-interface) has its own residual gaps: `CirInterface` has no super-interface list, so `interface Derived : Base` still flattens (`IDerived` no longer redeclares `Base`'s members after ADR-113, but doesn't inherit them either); a `var` interface property still renders `{ get; }` only (`hasSetter` is never derived from the plan); the CS0102 property/method name-collision guard is interface-route only, the same collision on the ordinary class route is unguarded; and an interface that is neither reachable nor implemented by any exported class still silently loses its unbridgeable members with no diagnostic naming why. See [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).
- A sealed **base**'s own `abstract val`/`abstract var` renders no C# member at all (`CirSealedClass` has no `properties` field); see [A `data object` subclass's own properties bind too](#data-object-subclass-properties-bind-too) and [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).
- [Methods on a sealed subclass](#methods-on-a-sealed-subclass) is declared-only: a base `open fun` (and, since [ADR-118](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/118-suspend-route-sealed-arm-owners-and-overload-numbering.md), a base `open suspend fun`) body an arm does not override renders on no arm, and neither the base's own methods nor its `abstract val`/`abstract var` (above) are ever readable through the C# base type directly, only through a concrete arm. A lambda-parameter or generic method on an arm is still a named `SKIPPED_UNSUPPORTED_COMBINATION` skip rather than a binding; a declared `suspend fun` or a declared `Flow<T>`/`StateFlow<T>` member now binds instead, and only an arm that declares one of those two gains `IAsyncDisposable`, so a consumer holding the sealed base has to pattern-match to the concrete arm before `await using` / `DisposeAsync()` (see [Suspend methods on a sealed arm](#sealed-method-suspend-generated-c) and [Flow and StateFlow members on a sealed arm](#sealed-flow-generated-c)). An arm's own `fun dispose()` collides with the always-emitted `Dispose()`, the same pre-existing hazard an ordinary class has; a same-arity suspend overload pair differing only in reference nullability, a `suspend fun` returning plain `Flow<T>`, and an ordinary-class suspend method returning a nested arm by simple name are also pre-existing, unfixed gaps on the legacy suspend route; see [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).

## Using it from C#

Polymorphism through `IPet`, from `IntegrationTests/InterfaceTests.cs`:

```C#
[Fact]
public void IPet_Greet_UsesDefaultImplementation()
{
    using IPet pet = new Cat("Oreo", 9);
    Assert.Equal("Hi, I'm Oreo", pet.Greet());
}

[Fact]
public void IPet_CanBeUsedPolymorphically()
{
    using IPet pet = new Cat("Mylo", 9);
    string greeting = Greet(pet);
    Assert.Equal("Hi, I'm Mylo", greeting);
}

private static string Greet(IPet pet) => pet.Greet();
```

Abstract-class inheritance, from `IntegrationTests/AbstractClassTests.cs`:

```C#
[Fact]
public void Animal_CannotBeInstantiated()
{
    // Animal is abstract — this verifies the C# class is also abstract
    Assert.True(typeof(Animal).IsAbstract);
}
```

Pattern matching over a sealed hierarchy, from `IntegrationTests/SealedClassTests.cs`:

```C#
[Fact]
public void Observation_WorksWithPatternMatching()
{
    using Observation result = ObservationKt.OpenBox("Oreo");

    string message = result switch
    {
        Observation.Superposition => "Unknown - cat is in superposition",
        Observation.Alive a => $"Alive: {a.Cat!.Name}",
        Observation.Dead d => $"Dead: {d.Cause}",
        _ => throw new InvalidOperationException(),
    };

    Assert.Equal("Alive: Oreo", message);
}
```

<seealso>
    <category ref="related">
        <a href="classes-and-objects.md">Classes and objects</a>
        <a href="data-classes.md">Data classes</a>
        <a href="lambdas-and-callbacks.md">Lambdas and callbacks</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/009-sealed-class-mapping.md">ADR-009: Sealed class mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/040-interface-return-type-mapping.md">ADR-040: Interface return type mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md">ADR-064: Forward unsupported-declaration diagnostics</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/075-collection-property-getter-setter-independence.md">ADR-075: Collection property getter/setter independence</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/084-csharp-implemented-interfaces.md">ADR-084: C#-implemented Kotlin interfaces</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/094-reflection-free-generic-dispatch.md">ADR-094: Reflection-free generic dispatch</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/102-aot-safe-forward-callbacks.md">ADR-102: AOT-safe forward callbacks</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/105-sealed-property-position.md">ADR-105: Sealed types at property positions</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/111-sealed-subclass-properties-on-the-property-plan.md">ADR-111: Sealed-subclass properties on the property plan</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/112-sealed-interface-mapping.md">ADR-112: Sealed interface mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/113-interface-declaration-on-the-forward-plan.md">ADR-113: Interface declaration on the forward plan</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/116-sealed-subclass-methods-on-the-callable-plan.md">ADR-116: Sealed-subclass methods move onto the ADR-062 callable plan</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/118-suspend-route-sealed-arm-owners-and-overload-numbering.md">ADR-118: Suspend route: sealed-arm owners and overload numbering</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/124-flow-route-sealed-arm-owners.md">ADR-124: Flow route: sealed-arm owners</a>
    </category>
</seealso>
