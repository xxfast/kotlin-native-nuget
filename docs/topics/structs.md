# C# structs

A bridgeable C# `struct` becomes an immutable Kotlin `data class`: no handle, nothing to `close()`,
and structural `==`/`hashCode()`. A copy crosses the bridge each time, so a change on the Kotlin side
is never seen by C# and vice versa; use `copy()` and pass the result back where you need an update.

```C#
// TestDependency/Geometry.cs (trimmed)
public readonly struct Point
{
    public Point(int x, int y) { X = x; Y = y; }
    public Point(int value) : this(value, value) { }
    public Point(bool unit) : this(unit ? 1 : 0, unit ? 1 : 0) { }
    public Point(Size size) : this(size.Width, size.Height) { }
    public int X { get; }
    public int Y { get; }
    public int Magnitude => Math.Abs(X) + Math.Abs(Y);
    public Point Offset(int dx, int dy) => new Point(X + dx, Y + dy);
    public string Format() => $"({X},{Y})";
    public static Point Origin() => new Point(0, 0);
}

public static class Geometry
{
    public static Point Translate(Point p, int dx, int dy) => new Point(p.X + dx, p.Y + dy);
}
```

```kotlin
val moved: Point = Geometry.translate(Point(x, y), dx, dy)
val magnitude: Int = Point(x, y).magnitude
val formatted: String = Point(x, y).offset(dx, dy).format()
val origin: Point = Point.origin()

val a = Point(3, 4)
val b = a.copy()
a == b                 // true, structural equality
a.copy(x = 99) != a    // true
```

The generated struct type is `internal`, so a function your library exports forward to C# cannot take
or return it directly; pass its components as primitives, strings, or enums instead.

## Which structs bind

A public, top-level, non-generic C# struct binds one of two ways:

- **A state constructor.** Exactly one public constructor whose parameters cover all stored state,
  each parameter matching a public property or field of the same type by name (case-insensitively).
  Kotlin's generated components, and their order, follow that constructor's parameter list. Other
  public constructors on the same struct bind as [alternate constructors](#members-on-the-struct).
- **No such constructor.** Every stored field must then be a public settable field, or a public
  auto-property with a `set` or `init` setter. Components, and their order, follow the C# field
  declaration order. There are no alternate constructors in this shape.

Either way, a component's type must be a primitive, `string`, a bound enum, or another bridgeable
struct (nested, at any depth; see [Nested structs](#nested-structs)).

A struct is skipped, with no generated Kotlin type, if it fails both shapes: for example, a private
field with no public component covering it, a `readonly` public field (an object initializer can't
set it), a hand-written property setter (metadata can't prove it writes the field it appears to), or
zero stored state. If a nested component itself fails, the whole outer struct is skipped too, and the
build warning names the failing component's path, not the outer struct's own rules.

## Component order for the no-constructor shape

When a struct has a state constructor, its component order is already public C# API: reordering the
parameters breaks C# callers too, so the Kotlin side can't drift silently. When a struct has no state
constructor, component order is the field declaration order, which C# callers never see, since they
always construct with named properties. Reordering two same-typed fields in the C# source is
source-compatible in C# but silently reorders the generated Kotlin `data class` constructor.

```C#
// TestDependency/Collar.cs (trimmed) -- no constructor covers this state, so it binds this way
public struct Collar
{
    public int Girth;                   // public field
    public string Colour { get; set; }  // settable auto-property
    public bool Belled { get; init; }   // init-only auto-property
    public char Initial { get; init; }
    public CatMood Mood { get; set; }
}
```

Always use named arguments when constructing this shape from Kotlin:

```kotlin
val c: Collar =
  Collar(girth = 1, colour = "black", belled = true, initial = 'O', mood = CatMood.CALM)
```

## Members on the struct

Alternate public constructors (state-constructor shape only) become Kotlin secondary constructors.
Non-void instance methods and get-only computed properties (not themselves components) become member
functions and `val`s; public static methods land in the `companion object`. `Equals`, `GetHashCode`,
`ToString`, `Deconstruct`, operators, setters, and void-returning instance methods are not bound;
Kotlin's `data class` keeps its own equality and stringification instead.

```kotlin
val primary = Point(2, 3)
val scalar = Point(4)                  // alternate constructor
val converted = Point(true)            // alternate constructor
val structArgument = Point(Size(5, 6)) // alternate constructor taking another struct

Point.origin().format()   // static factory, then an instance method
Point(x, y).magnitude     // get-only computed property
```

## Struct-typed properties and methods on classes

A struct works as a parameter, return, or settable property on a bound class's static or instance
members, the same as on a top-level function. `Cattery` is a real handle-backed class with a
`weigh` instance method taking and returning a struct, and a settable struct-typed property,
`currentProfile`:

```kotlin
val cattery = Cattery(name)
try {
  val result: Metrics = cattery.weigh(Metrics(heartRateBpm, weightKg, temperatureC), factor)
  cattery.currentProfile = Profile(tag, active, 'A', mood)
  val current: Profile = cattery.currentProfile
} finally {
  cattery.close()
}
```

`Cattery` itself still needs `close()` (or `use { }`), the same as any other handle-backed class;
`Metrics` and `Profile` are plain values and have none of that.

## Nested structs

A struct component can itself be a struct, at any depth. The Kotlin surface stays nested, one `val`
per component, each typed as its own generated `data class`:

```C#
// TestDependency/Litter.cs (trimmed)
public readonly struct Litter
{
    public Litter(Profile mother, Extent basket, int count, CatMood mood)
    {
        Mother = mother; Basket = basket; Count = count; Mood = mood;
    }
    public Profile Mother { get; }
    public Extent Basket { get; }
    public int Count { get; }
    public CatMood Mood { get; }
}
```

```kotlin
internal data class Litter(
  val mother: Profile,
  val basket: Extent,
  val count: Int,
  val mood: CatMood,
)
```

Equality and `copy()` compose through the nesting with no extra work:

```kotlin
val a = Litter(Profile("o", true, 'A', CatMood.CALM), Extent(1, 2), 3, CatMood.CALM)
a == a.copy()                                  // true
a != a.copy(mother = a.mother.copy(tag = "p")) // true, a change two levels down is observable
```

### The 22-argument ceiling

Each struct component becomes its own ABI argument (or out-pointer, for a return), and nesting
multiplies that count. A member whose flattened argument count, receiver and out-pointers included,
exceeds 22 is skipped entirely rather than generated, with a build warning naming the member and the
count:

```
w: [nuget:TestDependency] Skipping Test.Structs.Litters.Merge(...): flattened ABI arity (24)
exceeds the 22-argument CFunction.invoke ceiling verified for Kotlin/Native (ADR-059 Constraint 3).
Shrink one of the nested structs in this member's signature (fewer components, or less nesting), or
split it into multiple members with fewer struct parameters.
```

Shrink a nested struct in the signature, or split the member into smaller ones, to bring it back
under the ceiling.

## Limitations

- **`Nullable<T>` components** are not supported, including a nullable nested struct.
- **Class-typed (handle) components** inside a struct are not supported: a handle doesn't compose
  with an immutable value copy.
- **Generic structs** and **structs as collection elements** (`List<Point>`) are not supported.
- **A state constructor's parameter nullability is not decoded**: a `string?` parameter surfaces in
  Kotlin as non-nullable `String`. A `null` from C# there fails fast rather than corrupting memory.
  The no-constructor shape's components are decoded correctly and are unaffected.

<seealso>
    <category ref="related">
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
        <a href="bridgeable-subset.md">The bridgeable subset</a>
        <a href="objects-and-handles.md">Objects and handles</a>
        <a href="instance-members.md">Instance members</a>
        <a href="value-classes.md">Value classes</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/056-csharp-structs-in-kotlin.md">ADR-056: C# structs (value types) in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/058-csharp-shape-b-structs-in-kotlin.md">ADR-058: C# Shape B structs in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/059-nested-struct-components-in-kotlin.md">ADR-059: Nested struct components in Kotlin</a>
    </category>
</seealso>
