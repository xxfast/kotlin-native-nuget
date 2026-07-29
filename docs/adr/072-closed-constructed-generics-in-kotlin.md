# ADR-072: Closed constructed generics from C# in Kotlin: one generic Kotlin class over per-instantiation slot tables

## Status

Accepted. Implemented and verified: `./gradlew :nuget-plugin:test` passes 417/417, and
`./scripts/verify.sh --plugin` completes with `dotnet test` reporting `Failed: 0, Passed: 757,
Skipped: 1` (the one skip, `BidirectionalTests.CSharpDog_ImplementsIPet`, is pre-existing and
unrelated to this ADR). Three points were ruled on explicitly by a human at the feature-design
Step 2 gate, and are settled:

1. **Shape**: a real Kotlin generic class (ADR-010's accepted Option 2, mirrored), not
   monomorphization into `BoxOfInt` / `BoxOfString`. Decision 1 and Alternative 2.
2. **BCL instantiations**: the narrowing in Decision 9 is **approved**. The original brief asked for
   `List<int>` / `Dictionary<string,int>` to bind as generic types; the reasoning in Alternative 5
   (an unbound definition has zero extracted members, so it binds as a handle you cannot read) was
   accepted, and they are diagnosed instead. ROADMAP line 220 owns binding them properly.
3. **The `` Box`1 `` leak** (Context item 2, closed by Decision 10) is fixed **inside this feature**
   rather than split into its own commit, because it is only reachable through generics. It is a
   pre-existing latent bug and the PR body should say so.

## Context

ROADMAP Phase 10, first item: *"Map closed constructed generics (`List<int>`, `Dictionary<string, T>`
at concrete use sites), the reverse of type-erased-bridge + typed variants (mirror of ADR-010; needs
ADR, open generic *types* stay excluded per ADR-043)."*

Direction is **reverse**: C# declares the generic type, Kotlin consumes it. Kotlin declaring or
implementing a generic type for C# is not in scope, in this ADR or any planned one.

The problem this slice fixes: when a bound package's member signature mentions a closed constructed
generic (`Box<int>`, `Pairing<string,int>`, `List<int>`), the member is dropped, and for most shapes
it is dropped with **no diagnostic at all**. A Kotlin consumer sees the method simply not exist.

### What the pipeline does today (verified by running the real reader in this session)

Spike, in a scratch dir, never the repo. The repo's `NugetMetadataReader/Program.cs` and `.csproj`
were **copied** to the scratch dir and run unmodified there (then instrumented, see below):

```
$ cd <scratch>/probe && dotnet build          # net8.0, Nullable=enable, namespace Test.Boxes
#   class Box<T>            { Box(T value); T Value {get;}; string Describe(); }
#   class Pairing<TKey,TValue> { ... }   class Crate<T> where T : class { T? Item {get;set;} }
#   static class BoxFactory { Box<int> IntBox(int); Box<string> StringBox(string);
#                             int Unbox(Box<int>); List<int> Numbers(); Dictionary<string,int> Counts(); }
$ cd <scratch>/reader && dotnet run -- --package Probe <scratch>/probe/bin/Debug/net8.0/Probe.dll \
      --include Test.Boxes
```

Real output, abridged:

```json
{ "kind": "class", "name": "Box`1",
  "methods": [ { "name": "Describe", "returnType": {"kind":"string"},
                 "managedSignature": "method|instance|Test.Boxes.Box`1|Describe|()|System.String" } ],
  "properties": [], "constructors": [] }
{ "kind": "class", "name": "Pairing`2", "methods": [], "properties": [], "constructors": [] }
{ "kind": "class", "name": "Crate`1",  "constructors": [ { "managedSignature":
                 "ctor|instance|Test.Boxes.Crate`1|.ctor|()|System.Void", "parameters": [] } ] }
{ "kind": "class", "name": "BoxFactory", "isStatic": true,
  "methods": [], "properties": [], "constructors": [] }
"diagnostics": [
  { "kind": "skipped_open_generic", "typeName": "Box`1",     "memberName": ".ctor" },
  { "kind": "skipped_open_generic", "typeName": "Box`1",     "memberName": "Value" },
  { "kind": "skipped_open_generic", "typeName": "Pairing`2", "memberName": "Key" },  ... ]
```

So, **verified**:

1. **`BoxFactory` loses every one of its five methods, and not one diagnostic is emitted for them.**
   `IntBox`, `StringBox`, `Unbox`, `Numbers`, `Counts` vanish silently. This is
   `SignatureDecoder.GetGenericInstantiation` (`NugetMetadataReader/Program.cs:2596`) returning
   `TypeRefOrDiag(null, null, rawName)` for every non-async instantiation, with the comment that the
   `skipped_unbound_type_reference` from the open base type is *deliberately* dropped. The static
   class is still emitted, as an empty Kotlin `object`.
2. **A package-declared generic *class* has no guard at all** and reaches the RIR under its
   arity-mangled CLR name, ``Box`1``, sometimes carrying bridgeable members. Only structs guard
   (`Program.cs:781`, `generic struct ... not bridgeable in v1`) and only interfaces get a named
   diagnostic (`Program.cs:324`, `skipped_generic_interface`). ``Box`1`` above carries a bridgeable
   `Describe()`; ``Crate`1`` carries a bridgeable parameterless constructor.
   This is a **latent bug**, and repo code proves the downstream half of it:
   `NugetGenerateShimsTask.kt:128` writes `"${cls.name}Registration.cs"`, `:714` emits
   `internal static class ${cls.name}Registration`, `:1072` emits
   `${cls.name} receiver = (${cls.name})GCHandle.FromIntPtr(...)`, and
   `registrationExportName()` (`RirBridging.kt:916`) snake-cases the same string into a C export
   symbol. No sanitizer for `` ` `` exists anywhere in `nuget-plugin/src/main/kotlin` (grepped).
   The generated C# would be `Box`1Registration`, the export symbol `` nuget_test_boxes_box`1_register ``,
   and the Kotlin class `` class Box`1 ``: three separate compile failures. It has never fired
   because no shipped fixture declares a generic class.
3. The type arguments of an instantiation arrive **already decoded** as real `RirTypeRef`s, and the
   open definition arrives resolved when it is bound. Instrumented run of the scratch reader copy
   (one `Console.Error.WriteLine` added inside `GetGenericInstantiation`):

   ```
   SPIKE GetGenericInstantiation raw=[Test.Boxes.Box`1]  def=Test.Boxes|Box`1 args=[int/RirPrimitiveType]
   SPIKE GetGenericInstantiation raw=[Test.Boxes.Box`1]  def=Test.Boxes|Box`1 args=[string/RirStringType]
   SPIKE GetGenericInstantiation raw=[Test.Boxes.Box`1]  def=Test.Boxes|Box`1 args=[Test.Boxes.Ferret/RirObjectHandleType]
   SPIKE GetGenericInstantiation raw=[System.Collections.Generic.List`1]       def=NULL args=[int/RirPrimitiveType]
   SPIKE GetGenericInstantiation raw=[System.Collections.Generic.Dictionary`2] def=NULL args=[string/RirStringType, int/RirPrimitiveType]
   ```

   A **bound** definition arrives as a non-null `RirObjectHandleType(namespace, "Box\`1")`; a
   **BCL/external** definition arrives as `null` with the raw CLR name. That null-vs-non-null split
   is a ready-made, zero-cost discriminator for Decision 9.
4. Generic parameter names and constraints are readable from metadata
   (separate `System.Reflection.Metadata` spike over the same DLL):

   ```
   GENERIC Test.Boxes.Box`1     -> 0:T:attrs=None:constraints=0
   GENERIC Test.Boxes.Pairing`2 -> 0:TKey:attrs=None:constraints=0 | 1:TValue:attrs=None:constraints=0
   GENERIC Test.Boxes.Crate`1   -> 0:T:attrs=ReferenceTypeConstraint:constraints=0
   ```

5. `RirTypeRef` has variants `void`/`string`/`primitive`/`handle`/`enum`/`struct`/`interface`
   (`rir/RirModel.kt:167-232`, read in source) and **no generic variant**, so an instantiation has
   nowhere to land in the RIR either. The JSON contract is ADR-046.

### The ABI constraint that shapes everything below (verified)

A scratch compile of four `[UnmanagedCallersOnly]` shapes:

```
Bad.cs(8,6):  error CS8895: Methods attributed with 'UnmanagedCallersOnly' cannot have generic
              type parameters and cannot be declared in a generic type.     // static class Thunks<T>
Bad.cs(14,6): error CS8895: ... same ...                                    // static int GenericMethod<T>(IntPtr)
Bad.cs(18,36):error CS8894: Cannot use 'Box<int>' as a parameter type on a method attributed
              with 'UnmanagedCallersOnly'.                                  // Box<int> parameter
Bad.cs(24,22):error CS1960: Invalid variance modifier. Only interface and delegate type parameters
              can be specified as variant.                                  // class Variant<out T>
              // the only shape that COMPILED: static int OkClosed(IntPtr) => ((Box<int>)GCHandle...).Value;
```

Consequences, all **verified** by that output:

- A registration thunk can never be generic nor live in a generic type. **Every thunk must name a
  closed instantiation** (`(Box<int>)`), which is C# monomorphization on the managed side. This is
  not a design choice; it is CS8895.
- A generic instance still crosses as an `IntPtr` `GCHandle`, exactly like ADR-051.
- Variance is a non-issue for generic **classes**: C# forbids it (CS1960). It exists only on
  interfaces and delegates, and generic interfaces stay excluded (ADR-070's
  `skipped_generic_interface` is untouched by this ADR).

### The tension with ADR-043, and how this ADR amends it

[ADR-043](043-bridgeable-subset-boundary.md) section 3 excludes "open (uninstantiated) generics",
reasoning that "at code-generation time the Gradle plugin ... sees `IEnumerable<T>` but `T` is
unknown. No concrete thunk signature can be emitted." Its own text already points here:
*"Instantiated generics are handled separately per the collection and generics reverse ADRs."*

ADR-043 conflated two different things under one bullet:

- **An open generic type parameter at a use site**, with no instantiation to resolve it: a generic
  *method* `T Identity<T>(T value)`, or a member of a generic type reached without going through an
  instantiation. Here ADR-043 is right and **its exclusion survives unchanged**. The reader keeps
  emitting `skipped_open_generic` from `GetGenericMethodParameter`, and keeps emitting it from
  `GetGenericTypeParameter` for any type parameter this ADR's substitution pass cannot resolve.
- **A generic type *definition* that is reachable through at least one bound closed
  instantiation.** Here ADR-043's premise is false: `T` *is* known, once per instantiation, and a
  concrete thunk signature *can* be emitted, one set per instantiation. **This ADR lifts the
  exclusion for exactly that case.** The definition itself never produces a thunk; only its
  instantiations do.

The thing ADR-043 assumed impossible and this ADR adds is **instantiation enumeration**: a pass that
collects the closed instantiations actually used by the bound API surface, which is the reverse-side
equivalent of what KSP does by scanning usages in ADR-010.

### Prior art, reverse direction (a host language importing a foreign generic)

- **.NET for Android / Xamarin Java bindings** (the closest mirror: C# consuming a foreign generic).
  They **erase**. `Java.Util.ArrayList<T>` binds without a type parameter, members typed
  `Java.Lang.Object`. The open investigation issue
  [dotnet/java-interop#918 "Investigation: Bind Java Generics As C# Generics?"](https://github.com/xamarin/java.interop/issues/918)
  states the blocker in terms that map one-to-one onto this project's registration table:
  *"when invoking `JNIEnv::RegisterNatives()`, you can only register methods for a class, not for an
  instance"*, and attempting to register a non-reified generic fails with *"Late bound operations
  cannot be performed on types or methods for which ContainsGenericParameters is true."* Our
  `nuget_{ns}_{type}_register` export is precisely a `RegisterNatives` per type, and our resolution
  differs only because we can enumerate instantiations at build time and register **per
  instantiation**. Docs also record that
  [generic type constraints are not forwarded](https://github.com/xamarin/java.interop/issues/669)
  into the binding, and that
  [members of a generic instance class derived from a non-instantiated generic stay `Java.Lang.Object`](https://learn.microsoft.com/en-us/previous-versions/xamarin/android/internals/limitations).
- **Kotlin consuming Java** (the gold standard). Java generics map straight through to Kotlin
  generics, with declaration-site variance recovered from use-site wildcards. Kotlin's own answer to
  "foreign generic type" is "a real Kotlin generic type", never a monomorphized family.
  [Java interop docs](https://kotlinlang.org/docs/java-interop.html).
- **Kotlin/Native cinterop**: C has no generics, so there is nothing to import; ObjC lightweight
  generics are erased and only *class* type parameters exist, mapping `out`/`in` to
  `__covariant`/`__contravariant` on export.
  [ObjC interop docs](https://kotlinlang.org/docs/native-objc-interop.html).
- **Python.NET** (CLR guest): closes open generics **at runtime** via reflection,
  `List[int]()` calling `MakeGenericType`. Not available to us: the Kotlin/Native side has no CLR
  reflection and no runtime type dispatch over a `CFunction`.
  [Python.NET generics docs](https://pythonnet.github.io/pythonnet/python.html).
- **Forward direction, ADR-010** (this project): chose "type-erased bridge + generic C# class", with
  a `NugetMarshal.FromHandle<T>` doing a **runtime** `typeof(T)` dispatch. The reverse has no
  equivalent: Kotlin/Native cannot switch on a type parameter at runtime, and `reified` is a
  call-site compile-time device that cannot help a class body. Decision 1 below is the same shape
  with the dispatch moved from runtime to generation time.

## Alternatives Considered

### 1. Real Kotlin generic class over a per-instantiation witness bridge (chosen)

One Kotlin `class Box<T>` per open definition. The class holds an ADR-051 handle plus an internal
`BoxBridge<T>` witness object, and every member delegates to the witness. One witness object, one
bindings table, one registration export, and one set of C# thunks per **closed instantiation**. The
generator picks the witness at each site that produces the type, so the choice is entirely
compile-time.

**Pros:** the consumer sees a genuine Kotlin generic (`Box<Int>`, `Box<String>`), which is what
Kotlin-consuming-Java does. The wire stays erased (`IntPtr`), matching ADR-010's accepted option and
ADR-051's handle model. No runtime type dispatch anywhere. Slot tables stay per-instantiation, so
ADR-054's contract hash keeps its teeth. Marshalling for the type argument is confined to the
witness implementation, which is the only generated code that knows what `T` is.
**Cons:** one extra generated indirection (an interface + one object per instantiation). Public
construction needs a rule (Decision 5). Slot count multiplies by the number of instantiations.

### 2. Monomorphization: one Kotlin class per instantiation (`BoxOfInt`, `BoxOfString`)

ADR-010's rejected Option 1, reverse.

**Pros:** simplest possible generator; no witness; each class is self-contained.
**Cons:** the consumer loses the generic entirely, so nothing polymorphic can be written over
`Box<T>`. Names are ugly and unstable (adding a `Box<long>` use site in the package adds a new public
Kotlin type). Explicitly ruled out by the human's decision 1 for this slice, and by ADR-010's own
precedent. **Killed by:** it is not the API a Kotlin developer expects from IntelliJ.

### 3. Erasure: one non-generic Kotlin class with `Any?`-typed members (the Xamarin answer)

`class Box` with `val value: Any?`.

**Pros:** one slot table per definition instead of per instantiation; no enumeration pass; trivially
handles instantiations we never saw.
**Cons:** every read needs an unchecked cast by the consumer, which is exactly the ergonomics
Xamarin users complain about, and it does not even remove the ABI problem: `Box<int>.Value` returns
`int` and `Box<string>.Value` returns a `char*`, so a single erased thunk would have to box, forcing
`object` returns and a `GCHandle` per int. **Killed by:** it trades a type-safe API for a slower
wire, and .NET for Android only accepted it because JNI genuinely cannot register per instantiation
and we can.

### 4. One shared slot table plus runtime dispatch on the type argument

Keep one registration export per definition, and have the Kotlin class choose among per-type-argument
slots at runtime by carrying a `KClass<*>` tag.

**Pros:** the closest literal mirror of ADR-010's `NugetMarshal` `typeof(T)` dispatch.
**Cons:** Kotlin/Native cannot obtain `T::class` inside a non-inline class body, so the tag would have
to be threaded through every construction site anyway, which is Decision 1 with worse typing and a
runtime `when`. It also breaks ADR-054: two instantiations sharing one export means one contract hash
covering a union of signatures, so `Box<int>` and `Box<string>` landing on the same slot with
different marshalling is invisible, which is the exact stale-shim class of bug ADR-054 exists to
catch. **Killed by:** it weakens the contract check for no gain.

### 5. Bind BCL instantiations (`List<int>`, `Dictionary<string,int>`) as generic Kotlin classes too

The human's stated coverage for this slice included "(b) BCL collection instantiations bound *as
generic types*", with the `IReadOnlyList<T>` to `List<T>` idiom mapping left to ROADMAP line 220.

**Pros:** more of a real package's surface binds now; `Boxes.Numbers()` stops vanishing.
**Cons:** three of them, and together they are decisive.
1. It requires generating a Kotlin `class List<T>` in a package like `system.collections.generic`.
   `kotlin.collections.List` is a default import, so every generated file that mentions the bound
   one needs qualification, and the consumer's IntelliJ autocomplete shows two `List`s.
2. The definition lives outside the bound assemblies, so its members are not extracted at all
   (the reader's namespace map only walks bound assemblies). The bound `List<Int>` would therefore
   have **zero members**: no `count`, no `get`, no iteration. A handle you cannot read is worse
   than a diagnostic, because it looks like it works.
3. It pre-empts and would have to be broken by line 220. Once `Numbers()` returns
   `system.collections.generic.List<Int>`, the collections ADR changing it to
   `kotlin.collections.List<Int>` is a breaking change to a shipped API.
**Killed by:** point 2 on its own. **This is the one place where the boundary the human drew does
not hold**, and Decision 9 says so explicitly rather than papering over it: BCL instantiations get a
named diagnostic in this slice (an improvement over today's silent drop) and are bound by the
collections item, which is the only item that can give them members.

### 6. Fail the build on an unbindable instantiation

Rejected for the same reason ADR-043 alternative 3 was rejected: every real package contains at
least one `List<T>` in its public surface.

## Decision

Adopt **Alternative 1**. Ten decisions.

### Decision 1: shape

A bound generic class definition ``Box`1`` produces **one** Kotlin generic class, over an erased
handle, with all member dispatch behind a per-instantiation witness.

```kotlin
// generated: test/boxes/Box.kt
internal class Box<T> internal constructor(
  handle: COpaquePointer,
  private val bridge: BoxBridge<T>,
) : NugetHandleOwner, AutoCloseable {
  override val handle: NugetObjectHandle = NugetObjectHandle(handle)

  @Suppress("unused")
  private val cleaner = createCleaner(this.handle) { it.free() }

  override fun close(): Unit = handle.free()

  val value: T get() = bridge.value(handle)
  fun describe(): String = bridge.describe(handle)
  fun rewrap(): Box<T> = bridge.rewrap(handle)
}

// generated: test/boxes/BoxBridge.kt  (one per open definition)
internal interface BoxBridge<T> {
  fun value(handle: NugetObjectHandle): T
  fun describe(handle: NugetObjectHandle): String
  fun rewrap(handle: NugetObjectHandle): Box<T>
}

// generated: test/boxes/BoxOfIntBridge.kt  (one per closed instantiation)
internal object BoxOfIntBridge : BoxBridge<Int> {
  override fun value(handle: NugetObjectHandle): Int {
    val fn = requireNotNull(BoxOfIntBindings.valueGetterFn) {
      NugetRegistry.notRegistered("Test.Boxes.Box`1[System.Int32]", "TestDependency")
    }
    return fn.invoke(handle.require("Box"))
  }
  // describe(): the same UTF8 marshalling any bound string return uses
  // rewrap():   Box(fn.invoke(...) ?: error(...), BoxOfIntBridge)
}
```

Every member goes through the witness, including members whose signature never mentions `T`
(`describe()`). That is forced, not stylistic: C# generics are reified, so the thunk must name
`Box<int>` concretely to have a receiver at all, and CS8895 forbids a shared generic thunk
(**verified** above).

Lifetime, equality and nullability of the *instance* are unchanged from ADR-051: `Cleaner`-primary,
idempotent `close()`, wrapper identity equality, `IntPtr.Zero` for null.

### Decision 2: the metadata reader enumerates the instantiation set

**The reader, not the Gradle generator.** Three reasons: it is the only component that decodes
signatures; the RIR is the contract (ADR-046), so anything both generators need must be in it; and
the shared-source-of-truth rule (ADR-049 Alternative 10) says a set both generators derive must be
derived once.

Two phases, to avoid a circular dependency between "is this member bridgeable" and "is this
instantiation bound":

- **Phase A (discovery).** Walk every public member signature of every included type, regardless of
  whether that member will survive the ordinary bridgeability filter. Each `GetGenericInstantiation`
  whose definition resolved to a bound type (non-null `TypeRef`, **verified** discriminator above)
  and whose type arguments are all in the Decision 6 vocabulary contributes one candidate
  `(definitionKey, typeArguments)`.
- **Phase B (closure).** For each candidate, substitute its type arguments into its definition's own
  member signatures; any instantiation produced by substitution is itself a candidate. Iterate to a
  fixed point.

Termination is structural, not a heuristic: Decision 6 forbids a generic instantiation as a type
argument, so the candidate set is a subset of `definitions x vocabulary^arity`, which is finite, and
each round only adds. The implementation must still `check()` a hard iteration cap (8 is ample) and
fail loudly rather than loop, per the fail-fast convention.

Phase A deliberately over-collects: an instantiation whose only use site is a member that gets
skipped for an unrelated reason (a `Span<T>` sibling parameter) still gets bound, costing an unused
slot table. That is the cheap direction of the error.

### Decision 3: RIR additions (ADR-046 contract)

Additive, all defaulted so existing `reverse-ir.json` keeps parsing:

```kotlin
// RirClass gains two fields. The name stays the CLR name, "Box`1" (see Decision 10).
data class RirClass(
  override val name: String,
  ...
  val typeParameters: List<String> = emptyList(),          // ["T"] / ["TKey","TValue"], from GenericParam
  val instantiations: List<RirInstantiation> = emptyList(), // closed instantiations discovered per Decision 2
) : RirType

@Serializable data class RirInstantiation(val typeArguments: List<RirTypeRef>)

// a member type that IS a type parameter of the declaring type
@Serializable @SerialName("typeparam")
data class RirTypeParameterType(val index: Int, val name: String) : RirTypeRef

// a member type that is a closed instantiation of a bound generic definition
@Serializable @SerialName("generic")
data class RirGenericInstanceType(
  val namespace: String,
  val name: String,                       // the CLR name, "Box`1"
  val typeArguments: List<RirTypeRef>,
  val nullable: Boolean = false,
) : RirTypeRef
```

`RirClass` is extended rather than a new `RirGenericClass` node added, because a new node would
duplicate every member-shaped field (`methods`, `properties`, `constructors`, `interfaces`) and the
reader's `ProcessType` already builds a `RirClass`. The cost is that both generators must route
`typeParameters.isNotEmpty()` classes to the generic path **before** their existing class path. The
failure mode of missing a route is loud, not silent: `RirTypeParameterType` has no ABI mapping, so
`abiArgs` hits its `requireNotNull`/`else ->` and the build fails.

**Now verified** (spike, plugin sources read in full). Most `RirTypeRef` dispatch sites are exhaustive
sealed `when`s with no `else`, so the compiler forces them to be extended. **Six are permissive and
will silently emit wrong output**, and each must get an explicit branch as part of this feature:

| Site | Dispatch | What goes wrong silently |
|---|---|---|
| `RirBridging.kt:560` `isNullable` | `else -> false` | binds a nullable-capable generic instance as non-null: the ADR-053/ADR-070 failure class, third instance. The Decision 3 note below names only the C# `NullabilityHelpers`; this Kotlin-side accessor has the same bug |
| `RirBridging.kt:882` `signaturePart` | `else -> describe()` | renders a generic instance without its type arguments, so `Box<int>` and `Box<string>` produce the **same** `signaturePart` and the same contract hash inputs. Must recurse over `typeArguments`. Directly defeats Decision 4 |
| `NugetGenerateBindingsTask.kt:2241` `argConversion` | boolean-guard `when`, `else -> name` | **highest risk**: not `is`-exhaustive at all, so the compiler cannot force an update. A generic-instance argument is passed as the raw Kotlin wrapper instead of being unwrapped to its handle |
| `NugetGenerateShimsTask.kt:313` `thunkParamName` | `else -> p.name` | loses the `Handle` suffix convention for a handle-carrying parameter (naming only) |
| `NugetGenerateBindingsTask.kt:1358` `declKotlinType` qualification | `else -> null` | emits an unqualified name instead of the cross-package qualified one: silent name collision across namespaces |
| `NugetGenerateBindingsTask.kt:3751` `kotlinCollisionType` | `else -> declKotlinType(this)` | same gap inside the ADR-057 collision diagnostic, so a real collision can go undetected |

Also flagged, lower severity: `NugetGenerateShimsTask.kt:512` `structTypeNamespaces` has
`else -> emptySet()` by design (struct-only), but needs a twin branch if a generic instance argument
requires its own namespace `using` in the shim.

**A seventh gap, found only during implementation, not by this spike:** none of the three
generic-file content builders (`genericClassWrapperFileContent`, `genericBridgeInterfaceFileContent`,
`genericWitnessObjectFileContent` in `NugetGenerateBindingsTask.kt`) collected cross-package
`import` lines at all when they were first written, since they render nothing else in this project
does. `Box<Ferret>` (a bound handle type argument from a different namespace than `Box<T>` itself)
would have compiled to an unqualified `Ferret` reference. The C# shim side had the identical gap:
the generic registration classes had no `using` collection for a cross-package type argument either.
Both sides now derive their generic-aware import/`using` set the same way `structTypeNamespaces` and
`referencedEnumTypes` already do for a struct or enum reference, extended to also walk an
instantiation's own type arguments and a generic instance return/parameter/property's referenced
types.

Parsing fails closed by construction: `RirTypeRef` is a `sealed interface` with
`@JsonClassDiscriminator("kind")` and no default-subtype registration, and `RirParsing.kt:7`'s
`ignoreUnknownKeys` only covers unknown *keys*, not an unrecognised discriminator, so an unknown
`kind` throws rather than being dropped.

`RirInterfaceType`-style nullability handling applies: `RirGenericInstanceType` is
nullable-capable (it is a reference type), so `NullabilityHelpers.IsNullableCapable` and
`ApplyNullable` must both learn it. Omitting it is the ADR-053/ADR-070 silent-non-null failure
class, a third time.

### Decision 4: slot accounting is per instantiation

- **One registration export per closed instantiation**, not per definition:
  `nuget_test_boxes_box_of_int_register`, `nuget_test_boxes_box_of_string_register`. Forced by
  CS8895 (**verified**): the C# thunks are already per instantiation, so the pointer table is too.
- The **slot list** for an instantiation is the definition's ordinary ADR-057 canonical registrable
  list (constructors, static methods, instance methods, per-property getter then setter), with every
  type parameter substituted. Ordering is unchanged; substitution does not reorder, because
  ADR-057's ordering key is the managed signature of the *definition* member, which is identical
  across instantiations. Every instantiation of a definition therefore has the **same slot count**.
- Because slot counts match across instantiations, `slotCount` alone cannot distinguish a `Box<int>`
  shim from a `Box<string>` one. Two independent things prevent a mixup: the export **symbol name**
  differs per instantiation (a wrong-instantiation shim does not link to the right export), and the
  **contract hash** differs. The hash is computed with the existing bare-name overload
  (`contractHash(name, registrables, structs)`, `RirBridging.kt:806`, verified present) passing the
  **canonical instantiation signature** as the name:

  ```
  Test.Boxes.Box`1[System.Int32]      ->  hash A
  Test.Boxes.Box`1[System.String]     ->  hash B
  Test.Boxes.Box`1[System.String?]    ->  hash C
  ```

  and with the registrable list already substituted, so the per-slot `signaturePart` of every
  argument and return also differs. Both layers change; a stale shim is caught by ADR-054's existing
  `NugetRegistry.checkContract`.
- **Inferred** (not run): that `bridgeId`/`bridgeSuffix` digests, which hash `managedSignature`,
  stay distinct across instantiations. They will **not**: the definition's `managedSignature` is
  `method|instance|Test.Boxes.Box\`1|Describe|()|System.String`, identical for every instantiation.
  Since the digests are only ever used to name members *within one* bindings object, and each
  instantiation gets its own bindings object, this is harmless, but `bridgeIds()`'s collision
  `require` must be called **per instantiation**, never over the union. Getting this wrong fails the
  build loudly (that is what the `require` is for), so it is a safe kind of wrong.

### Decision 5: construction, and the ambiguity rule

A generic type's public constructors bind as **top-level "fake constructor" functions**, one per
instantiation, named exactly like the type. This is the documented Kotlin convention (the stdlib's
own `List(size) { ... }`, `MutableStateFlow(...)`), and at a call site it reads as construction with
the type argument inferred:

```kotlin
val n: Box<Int> = Box(42)
val t: Box<String> = Box("hello")
```

The ambiguity rule, because two instantiations can produce the same Kotlin overload: compute each
candidate fake constructor's parameter list **erased to non-null** Kotlin types. If two or more
instantiations of the same definition produce equal erased lists, **all** of them are skipped (not
"all but one": the outcome must not depend on iteration order) with
`skipped_ambiguous_generic_constructor`. This covers both hazards:

- `Crate<string>()` and `Crate<Ferret>()`, both erasing to `()`.
- `Box<string>` and `Box<string?>`, both erasing to `(String)`. Nullability-only differences are
  excluded deliberately: `Box("x")` would silently pick the more specific overload, and `Box(null)`
  the other, which is a coin flip the consumer cannot see.

Instances of a skipped-constructor instantiation are still obtainable from any bound member that
returns them, which is how the fixture reaches `Box<string>`.

### Decision 6: v1 type-argument vocabulary

A type argument may be: a primitive, `string` (nullable or not), a bound enum, a bound class handle,
or a bound interface. Anything else disqualifies the whole instantiation with
`skipped_generic_type_argument`, and every member mentioning it is skipped: another generic
instantiation (`Box<Box<int>>`), a struct (`Box<Point>`), an array, a `ref struct`, an unresolved
type parameter, or an unbound external type.

Excluding nested instantiations is what makes Decision 2's fixed point terminate, so it is
load-bearing, not just scope.

### Decision 7: nullability of a type argument needs an index-aware decode (verified problem)

The repo's `NullabilityHelpers.DecodeNullableAttributeByte` (`Program.cs:2186-2201`) reads
**index 0** of the `NullableAttribute` `byte[]`, justified in its own comment: *"the v1 bridgeable
subset only ever produces depth-1 type trees, so the array form (when it appears at all) is always
uniform and index 0 is always representative."* Generic instantiations break that premise.

**Verified** by dumping the real attribute blobs from the probe assembly with
`System.Reflection.Metadata` (prolog `1,0`, then a 4-byte element count, then the elements, then
`0,0` named-arg count):

| C# at the position | attribute | blob |
|---|---|---|
| `Box<string?> MaybeBox()` | `NullableAttribute` on return param | `[1,0, 2,0,0,0, **1,2**, 0,0]` |
| `Pairing<string?,string> P1()` | `NullableAttribute` on return param | `[1,0, 3,0,0,0, **1,2,1**, 0,0]` |
| `Pairing<string,string?> P2()` | `NullableAttribute` on return param | `[1,0, 3,0,0,0, **1,1,2**, 0,0]` |
| `Box<Ferret?> FerretMaybe()` | `NullableAttribute` on return param | `[1,0, 2,0,0,0, **1,2**, 0,0]` |
| `Box<int>? NullableIntBox()` | `NullableContextAttribute` on the method, no array at all | `[1,0, **2**, 0,0]` |
| `Box<int> IntBox2()` | `NullableContextAttribute` on the method | `[1,0, **1**, 0,0]` |

Reading index 0 of `[1,2]` yields `1`, non-null, so today's decoder would bind `Box<string?>`'s
argument as non-null `String` and let ADR-053's fail-fast guard crash on a legitimate null.

The rule the bytes demonstrate: the array is a **pre-order flattening of the type tree, one byte per
annotatable node**, where annotatable means reference type or type parameter. `int` contributes no
byte, which is why `Box<int>` is a single-node tree and Roslyn collapses it to a
`NullableContextAttribute` byte instead of an array. This matches the Roslyn
[nullable metadata spec](https://github.com/dotnet/roslyn/blob/main/docs/features/nullable-metadata.md);
the table above is the verified instance of it for the shapes this ADR needs.

**Required:** the reader computes the annotatable-node count of the decoded type tree and consumes
one byte per node in pre-order, assigning each to its node. **Fail-fast:** if the byte count does not
equal the computed node count, skip the member with a diagnostic rather than guess. Uniform cases
(single byte, or a `NullableContextAttribute`) apply that byte to every node, which is exactly what
the current code already does for depth-1 and stays correct.

A **cheaper fallback** if this proves disproportionate at implementation time: detect a non-uniform
byte array at an instantiation position and skip the member. That keeps the safety and loses
`Box<string?>`. It must be a conscious choice recorded in the PR, not an omission.

**As implemented: the fallback was not taken.** The full index-aware pre-order decode described
above shipped as written, and `Box<string?>` genuinely round-trips a null value
(`BoxOfMaybeText_Null_StaysGenuinelyNull` in `IntegrationTests/BoxesRoundTripTests.cs` passes
against the real bridge). This is recorded here as the conscious choice this Decision required,
not an omission.

### Decision 8: variance, constraints, arity

- **Variance:** nothing to do. CS1960 (**verified**) forbids variance on C# classes, and generic
  interfaces stay excluded. When generic interfaces land, `out T`/`in T` map one-to-one.
- **Constraints:** v1 emits **no** Kotlin type-parameter constraints. A constraint can only reject
  an instantiation that metadata already proved legal, so omitting it never rejects valid code.
  `where T : class` is readable (`GenericParameterAttributes.ReferenceTypeConstraint`, **verified**)
  and can be mapped to `<T : Any>` later. The visible consequence: a consumer can *write* the type
  `Box<Double>` even though no such instantiation is bound. They can never obtain a value of it (no
  witness, no factory), so the failure is at the first attempt to get one, at compile time.
- **Arity:** any arity, each parameter substituted independently. Kotlin type parameter names come
  from `GenericParam.Name` verbatim (`T`, `TKey`, `TValue`, **verified** readable).

### Decision 9: BCL instantiations are diagnosed, not bound

`List<int>`, `Dictionary<string,int>` and every other instantiation whose definition is outside the
bound assemblies get a new `skipped_unbound_generic_instantiation` diagnostic, and the member is
skipped. The discriminator is free: **verified** above, an external definition arrives at
`GetGenericInstantiation` with a null `TypeRef`, a bound one does not.

This is a deliberate narrowing of the coverage the human specified, for the reason set out in
Alternative 5: an unbound definition has no extracted members, so binding it as a generic type
produces a handle with nothing on it, and the collections item (ROADMAP line 220) is the only item
that can give `List<int>` a `count` and an iterator. Where the boundary actually sits, precisely:

- **This ADR:** generic type *definitions declared in the bound package*, reached through their
  closed instantiations. Kotlin sees `Box<Int>` and can call its members.
- **ROADMAP line 220:** BCL collection instantiations, mapped to Kotlin collection *idioms*
  (`IReadOnlyList<T>` to `List<T>`, eager copy, mirror of ADR-011). Not "bound as generic types
  first, idiom-mapped later"; there is no useful intermediate state.
- Net change from this ADR for a BCL instantiation: today it is dropped silently, after this ADR it
  is dropped with a named, actionable diagnostic.

### Decision 10: names, and the `` Box`1 `` fix

- The RIR keeps the CLR name (``Box`1``) as identity, matching what the reader already produces
  (**verified**) and what `RirGenericInstanceType` references. Both generators derive the emitted
  simple name by stripping at the backtick: Kotlin `class Box<T>`, C# `Box<int>`.
- A namespace declaring both `Box` and ``Box`1`` cannot produce two Kotlin types called `Box`. That
  is a hard generation error, `error_generic_arity_name_collision`, following ADR-057's
  `error_kotlin_signature_collision` precedent.
- **Internal** instantiation-tagged names (never consumer-visible): `BoxOfInt` from the definition's
  stripped name plus `Of` plus each argument's tag joined by `And`, where the tag is the Kotlin
  simple type name and a nullable reference argument is prefixed `Nullable`. So
  `BoxOfNullableString`, `PairingOfStringAndInt`. From that one tag come the bindings object
  (`BoxOfIntBindings`), the witness (`BoxOfIntBridge`), the C# registration class
  (`BoxOfIntRegistration`), and the export symbol (`registrationExportName` over the tag,
  `nuget_test_boxes_box_of_int_register`). If two instantiations of one definition produce the same
  tag, generation fails (same error kind as above) rather than silently sharing a table.
- **A generic definition with zero discovered instantiations emits nothing at all**: no Kotlin type,
  no export, no C# class, plus an `info_uninstantiated_generic_type` diagnostic. This is what
  actually closes the latent ``Box`1`` leak documented in Context item 2, and it must be tested by a
  fixture type that is never instantiated.

### New diagnostics

| Kind | When | Level | Emitted by |
|---|---|---|---|
| `skipped_unbound_generic_instantiation` | instantiation of a definition outside the bound assemblies (`List<int>`) | warn | reader (`reverse-ir.json`) |
| `skipped_generic_type_argument` | a type argument outside the Decision 6 vocabulary (`Box<Box<int>>`, `Box<Point>`) | warn | reader (`reverse-ir.json`) |
| `skipped_nullable_type_parameter` | a member whose type is a bare type parameter annotated `[Nullable(2)]` (`T? Peek()`): not representable per instantiation | warn | reader (`reverse-ir.json`) |
| `info_uninstantiated_generic_type` | a bound generic definition with no discovered instantiation; nothing emitted | info | reader (`reverse-ir.json`) |
| `skipped_ambiguous_generic_constructor` | two instantiations' fake constructors erase to the same Kotlin parameter list | warn | **Gradle plugin only**, a build warning, never `reverse-ir.json` |
| `error_generic_arity_name_collision` | `Box` and ``Box`1`` in one namespace, or two instantiations sharing an internal tag | error, fails generation | **Gradle plugin only**, a `require()` failure at generation time, never `reverse-ir.json` |

**As implemented, this table needed a fourth column.** `skipped_ambiguous_generic_constructor` and
`error_generic_arity_name_collision` cannot be reader diagnostics: both are stated in terms of
parameter lists "erased to non-null **Kotlin** types" (Decision 5) or a Kotlin internal tag
(Decision 10), and only the Gradle generator (`NugetGenerateBindingsTask`) ever computes a Kotlin
type. The reader (`NugetMetadataReader`, C#) has no notion of either. Both are routed through the
same plugin-derived-diagnostic path that `skipped_member_name_collision` (ADR-051/052) already
established, and both surface only as a Gradle build warning of the form:

```
w: [nuget:TestDependency] Skipping Box`1.constructor(System.String): two or more
instantiations of Box`1 erase their fake constructor to the same Kotlin parameter list
(String) - ambiguous, ALL are skipped.
```

An implementing agent who expected either kind in `reverse-ir.json` (as the four reader-side kinds
above are) would have written a failing test against the wrong artifact; this cost exactly one
failing test during implementation before the plugin-side routing above was in place.

`skipped_open_generic` is **unchanged** and still fires for generic methods and for any type
parameter substitution cannot resolve. That is ADR-043's surviving half.

### C# side (sketch)

```csharp
// generated: BoxOfIntRegistration.cs
internal static class BoxOfIntRegistration
{
    [UnmanagedCallersOnly]
    internal static IntPtr Ctor__<digest>(int value) =>
        GCHandle.ToIntPtr(GCHandle.Alloc(new Box<int>(value)));

    [UnmanagedCallersOnly]
    internal static int Value_Get(IntPtr selfHandle)
    {
        Box<int> receiver = (Box<int>)GCHandle.FromIntPtr(selfHandle).Target!;
        return receiver.Value;
    }
    // Describe_<digest>: identical to any bound string return (UTF8 alloc + freeManagedString)
    // Rewrap_<digest>:   returns GCHandle.ToIntPtr(GCHandle.Alloc(receiver.Rewrap()))

    [ModuleInitializer]
    internal static unsafe void Register() =>
        nuget_test_boxes_box_of_int_register(4, <hash>, &Ctor__<digest>, &Describe_<digest>,
                                             &Rewrap_<digest>, &Value_Get);
}
```

The `(Box<int>)` cast throws `InvalidCastException` on a mismatched handle. Kotlin's static types
make that unreachable through the generated API; until Phase 11 an escaping managed exception at the
boundary is fatal, unchanged from every other bound member.

## Consequences

- ADR-043's open-generics exclusion is **amended, not revoked**: open generic *parameters* stay
  excluded, generic type *definitions reachable through a bound closed instantiation* become
  bindable. ADR-043 should get a status note pointing here.
- A latent generator crash is closed: a package-declared generic class can no longer leak
  ``Box`1`` into Kotlin and C# source. Worth calling out in the PR, because it is a bug fix
  hiding inside a feature.
- Silent drops become diagnostics. Some packages will suddenly emit many warnings (every
  `List<T>`-returning member). That is the intended visibility, and matches ADR-043's model.
- Generated output grows roughly linearly with the number of instantiations: per instantiation, one
  witness object, one bindings object, one Kotlin registration export, one C# registration class.
- ADR-054's contract check keeps full strength: hash inputs include the canonical instantiation
  signature and every substituted argument type.
- The reader gains its first **fixed-point** pass over signatures (the struct extractor's fixed point
  is over types, not signatures). Cap the iterations and fail loudly.
- Two `RirTypeRef` variants are added. Every `when` over `RirTypeRef` in the plugin must be revisited;
  the ones that fail closed are fine, the ones that do not are latent silent-wrong-output bugs.

## Scope

**In scope (v1 of this slice)**

- Generic **classes** declared in the bound package, reached through at least one closed
  instantiation, at return, parameter and property positions, static and instance.
- Type arguments: primitives, `string` and `string?`, bound enums, bound class handles, bound
  interfaces. Any arity.
- Instantiations of the generic type at its own members' positions (`Box<T>.Rewrap(): Box<T>`).
- Per-instantiation registration exports, contract hashes, and C# thunks.
- Fake-constructor overloads where unambiguous.
- Index-aware pre-order `NullableAttribute` decoding at instantiation positions (Decision 7).
- A named diagnostic wherever a member is skipped, replacing today's silent drop.

**Deferred, with what this design forecloses (nothing, in each case)**

- **Generic structs / `KeyValuePair<K,V>`** (ROADMAP line 219, deferred by ADR-056). Nothing here
  forecloses them: a generic struct's instantiation would be discovered by exactly the Decision 2
  pass, and its components substituted before ADR-056's flattening runs. The struct guard at
  `Program.cs:781` would need to move from "any generic struct" to "any *uninstantiated* generic
  struct".
- **`Nullable<T>`** (ROADMAP lines 200/209). It is a closed generic struct, so it will arrive at
  `GetGenericInstantiation` like any other, but its mapping is the `byte hasValue` + out-pointer
  shape from ADR-056, not a handle. Decision 6 excludes struct type arguments and Decision 9
  excludes unbound definitions, so `System.Nullable<T>` keeps falling through to its own item. If
  that item lands first, it must special-case `System.Nullable\`1` *before* Decision 9's diagnostic.
- **Nested and recursive type arguments** (`Box<Box<int>>`, `Box<Profile>` where `Profile` is a
  struct). Excluded by Decision 6 with `skipped_generic_type_argument`. Lifting the nesting ban
  later requires replacing Decision 2's structural termination argument with a real depth cap.
- **BCL collection instantiations as Kotlin collections** (ROADMAP line 220, mirror of ADR-011).
  See Decision 9; this is the deliberate deviation from the requested coverage.
- **Generic interfaces.** ADR-070's `skipped_generic_interface` stands. The witness shape composes
  with ADR-070's `IFooHandle` (both are handle-backed slot dispatchers) but variance and the
  instantiation-by-interface matrix need their own call.
- **Generic methods** (`T Identity<T>(T)`), still `skipped_open_generic`, permanently unless a caller
  can pin the type argument.
- **Kotlin declaring or implementing a generic type for C#.** Not in scope here or anywhere.
- **Constraints in the Kotlin surface** (`where T : class` to `<T : Any>`).
- A generated generic type whose Kotlin name shadows a default-imported stdlib type (`Pair`, `Map`)
  is legal but confusing, and is not diagnosed in v1.

## Fixture

New feature-scoped file `TestDependency/Boxes.cs`, namespace `Test.Boxes`, bound in
`test-library/build.gradle.kts` with `include("Test.Boxes")` / `alias("Test.Boxes", "test.boxes")`.
Every line below crosses a seam that a generator could otherwise fake its way past. In particular
`Box<int>` alone would pass against a generator that never implements type-argument marshalling,
which is why `Box<string>` is mandatory.

```csharp
using Test.Enums;      // CatMood: cross-namespace enum type argument

namespace Test.Boxes;

/// <summary>The generic type under test. T-typed property, T-free method, self-instantiating method.</summary>
public class Box<T>
{
    public Box(T value) { Value = value; }
    public T Value { get; }                       // T at a property position: the marshalling seam
    public string Describe() => $"box[{Value}]";  // T-FREE member: still needs a per-instantiation thunk
    public Box<T> Rewrap() => new(Value);         // instantiation reached by substitution (Decision 2 phase B)
    public T? Peek() => Value;                    // -> skipped_nullable_type_parameter
}

/// <summary>Arity 2 with distinct parameter names (TKey/TValue reach Kotlin verbatim).
/// Deliberately NOT named Pair: kotlin.Pair is a default import.</summary>
public class Pairing<TKey, TValue>
{
    public Pairing(TKey key, TValue value) { Key = key; Value = value; }
    public TKey Key { get; }
    public TValue Value { get; }
}

/// <summary>ReferenceTypeConstraint, which v1 reads and deliberately does not emit (Decision 8).</summary>
public class Crate<T> where T : class
{
    public Crate(T item) { Item = item; }
    public T Item { get; }
}

/// <summary>Never instantiated anywhere. MUST produce no Kotlin type, no export, no C# class, and
/// exactly one info_uninstantiated_generic_type. This is the regression test for the Box`1 leak.</summary>
public class Unused<T>
{
    public Unused(T value) { Value = value; }
    public T Value { get; }
}

public static class Boxes
{
    public static Box<int> OfNumber(int value) => new(value);            // no conversion needed
    public static Box<string> OfText(string value) => new(value);        // UTF8 conversion needed
    public static Box<string?> OfMaybeText(string? value) => new(value); // Decision 7: blob [1,2]
    public static Box<CatMood> OfMood(CatMood mood) => new(mood);        // enum arg, cross-namespace import
    public static int Unwrap(Box<int> box) => box.Value;                 // instantiation at a PARAMETER
    public static Pairing<string, int> Tally(string label, int count) => new(label, count);
    public static Crate<string> CrateOfText(string item) => new(item);

    // Each of the next four must be SKIPPED, each with its own named diagnostic, not silently:
    public static List<int> Numbers() => new() { 1, 2, 3 };              // skipped_unbound_generic_instantiation
    public static Dictionary<string, int> Counts() => new();             // ditto, arity 2
    public static Box<Box<int>> Nested() => new(new Box<int>(1));        // skipped_generic_type_argument
    public static T Identity<T>(T value) => value;                       // skipped_open_generic (ADR-043 survives)
}
```

Expected consequences the tests should assert, beyond the happy path:

- `Box<string>` and `Box<string?>` both lose their fake constructor to
  `skipped_ambiguous_generic_constructor` (both erase to `(String)`), and are reached only via
  `Boxes.ofText` / `Boxes.ofMaybeText`. `Box<Int>`, `Pairing`, `Crate` keep theirs.
- Five `Box` instantiations exist (`int`, `string`, `string?`, `CatMood`, and `Box<int>` again via
  `Rewrap`, which is already in the set: the fixed point converges in two rounds).
- Optional extra seam, trim if the fixture gets heavy: `Box<Ferret>` (`Test.Menagerie.Ferret`), a
  bound **handle** type argument across a package boundary.

## Kotlin consumer API the tests assert against

```kotlin
import test.boxes.Box
import test.boxes.Boxes
import test.boxes.Crate
import test.boxes.Pairing
import test.enums.CatMood

// construction: fake constructor, type argument inferred
val n: Box<Int> = Box(42)
check(n.value == 42)
check(n.describe() == "box[42]")

// obtained from a bound static member (the only route for Box<String>)
val t: Box<String> = Boxes.ofText("hello")
check(t.value.uppercase() == "HELLO")           // a real Kotlin String, not a pointer

val maybe: Box<String?> = Boxes.ofMaybeText(null)
check(maybe.value == null)                       // Decision 7: the type argument is genuinely nullable

val mood: Box<CatMood> = Boxes.ofMood(CatMood.SLEEPY)
check(mood.value == CatMood.SLEEPY)              // enum arg, no cast at the call site

// instantiation at a parameter position
check(Boxes.unwrap(n) == 42)

// a member of the generic type returning its own instantiation
val again: Box<Int> = n.rewrap()
check(again.value == 42)

// arity 2, parameter names from metadata
val tally: Pairing<String, Int> = Boxes.tally("cats", 3)
check(tally.key == "cats" && tally.value == 3)

// constrained definition; the constraint is not emitted in v1
val crate: Crate<String> = Crate("boxed")
check(crate.item == "boxed")

// ADR-051 lifetime is unchanged and generic-agnostic
Box(7).use { check(it.value == 7) }

// polymorphism over the generic, which is the whole point of not monomorphizing.
// internal, not public: Box itself is internal (Decision 1), so a public function exposing it
// would not compile
internal fun <T> describeAll(boxes: List<Box<T>>): List<String> = boxes.map { it.describe() }
```

## Verified vs inferred

**Verified in this session** (command and real output shown above, all in a scratch dir):

1. `GetGenericInstantiation` drops every non-async instantiation with no diagnostic; the five
   `BoxFactory` methods disappear silently.
2. A package-declared generic class reaches the RIR as ``Box`1``, with bridgeable members
   (`Describe`) and a bridgeable constructor (`Crate`1`). Repo code (`NugetGenerateShimsTask.kt`
   :128/:714/:1072, `RirBridging.kt:916`) proves this name is interpolated straight into a C# class
   name, a C# cast, a filename and a C export symbol, with no sanitizer anywhere in the plugin.
3. A **bound** definition arrives with a non-null `RirObjectHandleType(ns, "Box\`1")`; a **BCL**
   definition arrives with a null `TypeRef`. Type arguments arrive pre-decoded as `RirTypeRef`s.
4. `[UnmanagedCallersOnly]` cannot be generic or live in a generic type (CS8895), cannot take
   `Box<int>` as a parameter (CS8894), and a C# class cannot declare variance (CS1960). A closed
   cast thunk over an `IntPtr` compiles.
5. `NullableAttribute` byte arrays for instantiation positions are pre-order and **non-uniform**
   (`Box<string?>` is `[1,2]`, `Pairing<string,string?>` is `[1,1,2]`), while `Box<int>` collapses to
   a `NullableContextAttribute` single byte. The repo's index-0 decoder would silently lose the type
   argument's nullability.
6. Generic parameter names and `ReferenceTypeConstraint` are readable from `GenericParam`.
7. `contractHash` already has a bare-name overload (`RirBridging.kt:806`), and `RirTypeRef` has no
   generic variant (`RirModel.kt`).

**Settled by the pre-Step-3 spike** (these were the two "nobody has verified, and it matters" items):

8. **Not every `RirTypeRef` dispatch fails closed.** Six permissive sites, tabulated in Decision 3.
   Two of them (`isNullable`, `signaturePart`) would silently break Decision 3 and Decision 4
   respectively; `argConversion` is a boolean-guard `when` the compiler cannot force. Fixing all six
   is part of this feature, not a follow-up.
9. **A top-level fake constructor sharing a class's name compiles in this project's `nativeMain`.**
   Probe compiled with `./gradlew :test-library:compileKotlinMacosArm64` (a `class ProbeBox<T>` with
   an internal constructor plus two same-named top-level overloads, and a `val n: ProbeBox<Int> =
   ProbeBox(42)` inference site), `BUILD SUCCESSFUL`, probe deleted. **Decision 5 stands as written**;
   the `Box.ofInt(42)` fallback is not needed.

**Inferred, to be settled at implementation time** (documentation or code reading only, not run):

1. The exact Roslyn rule for which nodes consume a nullable byte. Decision 7's table verifies it for
   every shape in scope (reference type and type parameter consume one, `int` consumes none), but a
   shape outside the table (arrays, pointers, nested value-type generics) is inferred from the
   [nullable metadata spec](https://github.com/dotnet/roslyn/blob/main/docs/features/nullable-metadata.md).
   The fail-fast count check in Decision 7 exists because of this.
2. That substituting type arguments into a definition's ADR-057 registrable list preserves the
   canonical order. Argued from the ordering key being the definition's managed signature (identical
   across instantiations), not observed.
3. That an instantiation reached only through Decision 2 phase B substitution is discoverable before
   the bridgeability filter runs. Phase A/B ordering is a design assertion about code that does not
   exist yet.

Both remaining items fail loudly if wrong (a `require` on canonical order, a missing instantiation
that produces a diagnostic rather than bad output), unlike the two the spike just closed.

## Implementation handoff

Where the feature-design workflow stopped, so this can be resumed without re-deriving it.

**Done:** Step 0 (item validated against ROADMAP line 218, confirmed reachable, Phase 9 fully
ticked), Step 1 (this ADR), Step 2 (human gate, see Status), the pre-Step-3 spike (verified
items 8 and 9 above; the `RirRegistrable`-keyed dispatch sites are confirmed unaffected), Step 3
(tests), Step 4 (implementation, `./gradlew :nuget-plugin:test` 417/417, `scripts/verify.sh
--plugin` green), and Step 5 (docs and refactor).

**Carried into Step 4 from the spike:** the six permissive dispatch sites in Decision 3's table.
`RirBridging.kt:560` and `:882` are correctness-critical (nullability, and per-instantiation contract
hash distinctness); `NugetGenerateBindingsTask.kt:2241` is the one the compiler cannot catch.

**Test seams for Step 3**, per the reverse-feature workflow: fast inner loop is generator-level unit
tests in `nuget-plugin/src/test/kotlin` (`reverse-ir.json` fixture in, expected Kotlin stub and C#
shim text out; precedents `NugetGenerateBindingsTaskTest`, `NugetGenerateShimsTaskTest`). Outer loop
is the ADR-050 round trip: the Fixture section's `TestDependency/Boxes.cs`, consumed from
`test-library`, surfaced forward, asserted in `IntegrationTests`.

**Three bugs this feature carries** (all verified, all fixed as part of this feature, all recorded
by the documenter): the `` Box`1 `` interpolation leak (Context item 2, Decision 10), the index-0
`NullableAttribute` decode losing a type argument's nullability (Decision 7, the third instance of
the ADR-053 failure class), and a third, **pre-existing and unrelated to generics**, only uncovered
while building this feature's fixture: the ordinary (non-generic) Kotlin bindings generator and C#
shim generator both collected a cross-package `import`/`using` for a bound **enum** argument, but
never for a bound **class handle** argument, at the same code path. It had never fired because no
fixture shipped before this one put a cross-package bound handle at that position; ADR-072's own
`Boxes.OfFerret(Ferret)` seam (`Test.Menagerie.Ferret` used from `Test.Boxes`) is what exposed it.
Both the Kotlin and the C# side are fixed as part of this ADR's Decision 3 import-collection work,
even though the underlying gap predates generics entirely.
