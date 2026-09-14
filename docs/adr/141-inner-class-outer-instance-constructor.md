# ADR-141: Inner classes: a Kotlin `inner class` is declared as a C# nested type whose constructor takes the outer instance first

## Status

Proposed

## Context

[ADR-133](133-nested-types.md) declares a public nested type as a real C# nested type, and
[ADR-134](134-nested-types-under-deferred-owners.md) widened the owner set to everything C# has a
declaration block for. Two arms of the nesting gate still name `inner`
(`NugetProcessor.kt`, **verified by reading**):

- `unsupportedNestedCandidateReason()`: `modifiers.contains(Modifier.INNER)` ->
  "an `inner class` needs the outer instance its constructor takes"
- `unsupportedNestedOwnerReason()`: `modifiers.contains(Modifier.INNER)` ->
  "an `inner class` owner needs the outer instance to construct"

ADR-134's Alternative 3 recorded the shape (`new Host.Guest(host, 3)` / `host_guest_create(outer,
visits)`) and deferred it to this ADR because it is a constructor-plan question, not a nesting one.
ROADMAP Phase 4 carries it as the last `inner` item; `enum class` and generic owners are out of
scope here (C# has no expressible shape for either, ADR-134).

What an `inner class` is, at the boundary: a Kotlin inner class instance carries a reference to its
outer instance (**inferred from the language docs**, [Nested and inner classes](https://kotlinlang.org/docs/nested-classes.html):
"Inner classes carry a reference to an object of an outer class"), and is constructed from outside
through that instance, `Outer().Inner()` (same page, `val demo = Outer().Inner().foo()`). So the
only thing the bridge is missing is a constructor whose Kotlin body has a receiver. Everything else
about the type (its handle, its members, its type positions, its dispose) is already what ADR-133
does for a plain nested class: the inner instance's own `StableRef` keeps the outer alive through
that reference, so a member reading `this@Host` needs nothing at the ABI.

### What the code does today (verified by reading)

- `ForwardCallablePlanner.constructorEntry()` (~:1553) plans every constructor with
  `receiver = ForwardReceiver.Static`, `origin = CONSTRUCTOR`, `target = owner` (the qualified
  name), and `parameters` from `constructor.parameters` only.
- `ForwardKotlinPlanEmitter.invocationExpression()` (~:899) renders the CONSTRUCTOR arm as
  `"${target}($arguments)"` and ignores the receiver; the EXTENSION arm renders
  `"${receiverExpression(receiver)}.$functionName($arguments)"`, and `receiverExpression` lowers an
  `ObjectHandle` receiver to `outer.asStableRef<qualified.Owner>().get()` (`loweredArgument`, ~:1103).
  The emitter finds the receiver as the index-0 `ForwardAbiRole.RECEIVER` slot (~:31).
- `ForwardCallablePlanner.receiverParameter()` (~:3156) mints a `ForwardReceiver.Handle` as one
  `POINTER` / `IN` / `HANDLE_TO_STABLE_REF` / `BORROWED` wire parameter with role `RECEIVER`, using
  the receiver's own `name`.
- `ForwardCirPlanProjection.constructor()` (~:148) projects `plan.publicParameters()` (declared
  parameters only) and takes the trivial `hasErrorCheck = true` path when every parameter
  `isTrivialInput()`; that path's C# (`renderConstructor`, `CirClassRenderer.kt` ~:393) passes the
  raw public parameter names to `Native_Create`. `ForwardCirPlanProjection.extension()` (~:418,
  ADR-132) is the receiver-first precedent: it prepends a `CirParameter(receiver.csharpName,
  receiverType, "IntPtr")` and feeds `ForwardPublicParameter(receiver.name, receiver.transfer.type)`
  at the head of the `resultProjection` input list so the receiver shares `callArgument`
  (`outer._handle` for an `ObjectHandle`, ~:564), the preludes and the cleanups.
- `CirClass.constructorNativeImport()` (`CirNativeImports.kt` ~:56) derives the `[DllImport]`
  parameter list from `ctor.nativeParameters ?: ctor.parameters`.
- `exportedObjectHandles = exportedTypes` (`CirTranslator.kt` ~:155), and `allClasses` is
  `declaredClasses + nestedDeclared.filter { CLASS && !isValueClass() }` (`NugetProcessor.kt`
  ~:1050), so a declared inner class reaches the classifier's membership test with no further
  change and is spelled `nestedCsName()` (`Host.Guest`) at member positions.
- `Tier1Harness` compiles the generated `CNameExports.kt` for the JVM against the fixture
  (`Tier1Harness.kt` ~:14, ~:268), so a Kotlin-side spelling that does not compile fails the first
  Tier 1 cell loudly.

## Prior art

- **Kotlin/JVM.** A Java consumer constructs a Kotlin inner class as `host.new Guest(3)`; the
  bytecode constructor takes the outer instance as its first parameter (JLS
  [§15.9.2](https://docs.oracle.com/javase/specs/jls/se17/html/jls-15.html#jls-15.9.2) qualified
  class instance creation; the parameter ordering is **inferred** from the JLS/javac convention, not
  spiked). Outer first.
- **Kotlin/Native ObjC export.** **Verified by reading**
  [`getMethodBridge.kt`](https://github.com/JetBrains/kotlin/blob/master/native/objcexport-header-generator/impl/analysis-api/src/org/jetbrains/kotlin/objcexport/analysisApiUtils/getMethodBridge.kt):
  `getFunctionMethodBridge` appends one extra bridge slot **after** the value parameters when the
  containing class `isInner` ("inner class edge case"), and the K1
  [`ObjCExportMapper.bridgeMethodImpl`](https://github.com/JetBrains/kotlin/blob/master/native/objcexport-header-generator/impl/k1/src/org/jetbrains/kotlin/backend/konan/objcexport/ObjCExportMapper.kt)
  bridges every `allParameters` entry past the receiver. So ObjC export does export inner
  constructors, and puts the outer **last**. That the extra slot surfaces in the header as an
  initializer parameter for the outer, and its selector name, is **inferred** (no framework built).
- **Swift export.** The docs
  ([native-swift-export](https://kotlinlang.org/docs/native-swift-export.html)) list neither nested
  nor inner classes as supported or unsupported; no precedent to lean on. Not investigated further.
- **JS/Wasm export.** Skipped: neither has a nested-class declaration form that would settle a
  parameter-order question.
- **C# itself.** C# has no inner classes; the hand-written equivalent is a nested class whose
  constructor takes the enclosing instance as its first parameter (`new LinkedList<T>.Node(list,
  value)`-style). Outer first is what a C# developer writes by hand.

Two precedents, two orders. The chosen order follows the JVM and the hand-written C# shape, and it
is also the codebase's own rule: ADR-132 made the receiver parameter zero of every receiver-carrying
plan, and `validateRoles` admits one `RECEIVER` slot at index 0 only.

## Alternatives Considered

### 1. A public constructor with the outer instance as its first parameter (chosen)

`public Guest(Host outer, int visits)` under `Host`, exporting `host_guest_create(outer, visits,
error)`, Kotlin body `outer.asStableRef<Host>().get().Guest(visits)`. Pros: the JVM and hand-written
C# shape; reuses ADR-132's receiver-first projection unchanged; ADR-034 secondary numbering and
ADR-091 omitting overloads carry over untouched, since the receiver is not a plan parameter. Cons: a
declared constructor parameter named `outer` collides (CS0100), the same hazard `receiver` already
carries on extensions.

### 2. The outer instance as the last parameter (the ObjC export order)

Pros: byte-identical to Kotlin/Native's other native export. Cons: the receiver would land at a
non-zero index and fail `validateRoles` (ADR-132 records that a receiver at index 1 fails the plan
outright), so it needs a second receiver kind or a plan-model change; and a trailing outer sits
where ADR-091's trailing-default truncation operates, so every omitting overload would have to
re-order around it. Rejected.

### 3. A factory member on the outer: `host.NewGuest(3)` / `Host.Guest.Create(host, 3)`

Pros: reads like the JVM's `host.new Guest(3)`; no constructor projection change. Cons: invents a
member name no Kotlin scope has (the ADR-134 Alternative 2 objection), needs a collision rule against
a genuine `newGuest` on the owner, and leaves `Host.Guest` with only an `internal Guest(IntPtr)`,
which ADR-134 already verified `new Host.Guest(9)` binds to silently. Rejected.

### 4. Keep the named skip

Zero cost; the reason already explains itself. Rejected because the mechanism turns out to be one
receiver on one existing plan shape, and the type is otherwise fully supported by ADR-133.

## Decision

A public `inner class` declared directly inside an admitted, non-generic, non-`inner` `class` owner
(ADR-133's class owner, root or ADR-066 dependency, `open`/`abstract` included) is declared as a C#
nested type exactly as a plain nested class is, with every public constructor projected as a C#
constructor whose **first** parameter is the outer instance, named `outer`.

### Consumer API

Kotlin:

```kotlin
class Host(val name: String) {
  inner class Guest(val visits: Int) {
    val greeting: String get() = "$name welcomes guest #$visits"
  }
  fun guestAt(visits: Int): Guest = Guest(visits)
  fun visitsOf(guest: Guest): Int = guest.visits
}
```

C# (what IntelliSense shows):

```csharp
public class Host : IDisposable
{
    public Host(string name);
    public string Name { get; }
    public Host.Guest GuestAt(int visits);
    public int VisitsOf(Host.Guest guest);

    public class Guest : IDisposable
    {
        public Guest(Host outer, int visits);   // outer first, named `outer`
        public int Visits { get; }
        public string Greeting { get; }         // reads this@Host.name on the Kotlin side
        internal Guest(IntPtr handle);           // ADR-005 handle constructor, unchanged
    }
}
```

```csharp
using var host = new Host("Oreo");
using var guest = new Host.Guest(host, 3);
Assert.Equal("Oreo welcomes guest #3", guest.Greeting);
```

A secondary constructor and every ADR-091 omitting overload keep the same leading `outer`
(`new Host.Guest(host)` when `visits` has a default), numbered `host_guest_create_2` and up exactly
as ADR-034 numbers them today.

### Bridge mechanism

Every claim below is **inferred** (a design over verified seams, not yet run) unless marked
otherwise; the seams themselves are the verified-by-reading list in Context.

Generated Kotlin (`CNameExports.kt`):

```kotlin
@CName("host_guest_create")
fun export_host_guest_create(outer: COpaquePointer, visits: Int, errorOut: CPointerVar<...>): COpaquePointer? =
  /* ADR-005 try/catch envelope unchanged */
  NugetHandles.retain(outer.asStableRef<io.github.xxfast.kotlin.native.nuget.test.nested.Host>().get().Guest(visits))
```

Generated C# (inside `Host`'s block, ADR-133 indentation):

```csharp
[DllImport("...", CallingConvention = CallingConvention.Cdecl, EntryPoint = "host_guest_create")]
private static extern IntPtr Native_Create(IntPtr outer, int visits, out IntPtr error);

public Guest(Host outer, int visits)
{
    IntPtr handle = Native_Create(outer._handle, visits, out IntPtr error);
    if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
    _handle = handle;
}
```

- **Handle kind.** The outer crosses as a borrowed ADR-005 `StableRef` pointer (the
  `HANDLE_TO_STABLE_REF` conversion `receiverParameter()` already mints for a
  `ForwardReceiver.Handle`); the result is a fresh `NugetHandles.retain` on the inner instance,
  the same route `host_guest_dispose` releases. No new handle kind.
- **Lifetime.** The inner instance references its outer on the Kotlin heap (language docs, above),
  so disposing the C# `Host` while a `Guest` lives only drops the outer's `StableRef`; Kotlin's GC
  keeps the `Host` object alive through the `Guest`. `Guest.Dispose()` releases the inner's
  `StableRef`; the outer follows Kotlin's normal reachability. Nothing to add at the ABI for
  members that read `this@Host`: `Guest.greeting` rides the ordinary CLASS-origin plan,
  `handle.asStableRef<...Host.Guest>().get().greeting`.
- **Null / disposed outer.** `outer._handle` on a null `outer` is a `NullReferenceException` before
  the crossing, and on a disposed one whatever ADR-122 handle parameters do today; both are the
  existing handle-parameter behaviour, not a new contract.

### Code seams

1. `NugetProcessor.kt` `unsupportedNestedCandidateReason()`: drop the `Modifier.INNER` arm. The
   owner-side `INNER` arm stays (see Scope), reworded to "an `inner class` owner's own nested types
   are deferred". `nestedDeclarationDeferral()`, the collision check and the declared/deferred
   partition are unchanged; a declared inner class lands in `allClasses` through the existing
   `nestedDeclared.filter { CLASS && !isValueClass() }`.
2. `ForwardCallablePlanner.constructorEntries()` / `constructorEntry()`: for
   `cls.modifiers.contains(Modifier.INNER)`, `receiver = ForwardReceiver.Handle(
   BridgeType.ObjectHandle(outerQualifiedName), name = "outer")` where the outer is
   `cls.parentDeclaration as KSClassDeclaration`, and `target = cls.simpleName.asString()`
   (`Guest`, not the qualified name, because the call is receiver-qualified). Every entry the
   function builds (primary, secondaries, ADR-091 truncations) goes through `constructorEntry`, so
   all of them carry the receiver by construction. The COPY entry (data class `copy`) is untouched:
   its receiver is already the instance.
3. `ForwardKotlinPlanEmitter.invocationExpression()` CONSTRUCTOR arm: when `receiver != null`,
   `"${receiverExpression(receiver)}.${target}($arguments)"`; otherwise byte-identical to today.
4. `ForwardCirPlanProjection.constructor()`: mirror `extension()`. Read the index-0 `RECEIVER` slot
   off `plan.singleNativeImport()` when present, prepend `CirParameter("outer", receiverType,
   "IntPtr")` to `publicParams`, and put `ForwardPublicParameter(receiver.name,
   receiver.transfer.type)` at the head of the list `needsCustomParams`, the preludes, the cleanups
   and `callArgument` iterate. **Silent-loss hazard, named:** if the receiver is prepended to
   `publicParams` but not to that input list, a primitive-only inner constructor keeps the trivial
   `hasErrorCheck = true` path and `renderConstructor` emits `Native_Create(outer, visits, out
   error)` with `outer` typed `Host` against an `IntPtr` slot. That is CS1503 at the consumer's
   compile, loud, not silent, but it is the one place the two lists can drift. `nativeParameters =
   plan.nativeInCirParameters(nativeCall.parameters)` already includes the receiver slot, so the
   `[DllImport]` is right either way.
5. `CirClassTranslator.kt` ~:576 (ADR-034 identical-signature check) needs no change: the receiver
   is prepended to every constructor of the class, so signature equality is preserved.
6. `ForwardDiagnostic.kt`: no new kind. The `SKIPPED_NESTED_DECLARATION` hint "move it to the top
   level of its file" stays for the remaining shapes.

### Fixture and tests

- `test-library/.../nested/Inner.kt`: the `Host`/`Guest` pair above, with `guestAt`/`visitsOf`
  named to dodge the ADR-133 owner-scope collision rule (never `guest`). `Host`/`Guest` are the
  names `Tier1NestedTypesTest`'s `deferredSource` already uses for the skip cell, which now flips.
- `IntegrationTests/InnerClassTests.cs`, one sample cell:

```csharp
[Fact]
public void InnerClass_ConstructedWithTheOuterFirst_ReadsTheOuter()
{
    using var host = new Host("Oreo");
    using var guest = new Host.Guest(host, 3);

    Assert.Equal(3, guest.Visits);
    Assert.Equal("Oreo welcomes guest #3", guest.Greeting);
    Assert.Equal(3, host.VisitsOf(guest));
    Assert.NotNull(typeof(Host).GetNestedType("Guest"));
}
```

  plus: `host.GuestAt(2)` round-trips (a member-returned inner instance and a constructed one are
  the same C# type), `Guest`'s constructor parameter list starts with `Host outer` (reflection),
  and a disposed-outer cell: dispose `host`, then read `guest.Greeting` still returns the outer's
  name (the lifetime claim above, otherwise unpinned).
- `Tier1NestedTypesTest.kt`: `Host.Guest` moves from the deferred list to an admitted cell asserting
  `host_guest_create` takes `outer` first and the Kotlin body spells `.get().Guest(`; the
  `deferredSource` list shrinks to `Box.Lid` and `Season.Almanac`. A second cell keeps an inner-of-
  inner (`inner class Guest { inner class Deep }`) a named skip through the owner arm.
- **Leak row.** `LeakTests/LiveHandleTests.cs` gains Row 1c: `using var host = new Host("Oreo");
  using var guest = new Host.Guest(host, 3); Assert.Equal(3, guest.Visits);` returns to baseline.
  A new constructor route mints a handle, and the inner's Kotlin-side reference to the outer is
  exactly the kind of retained edge this harness exists to check; Row 1a (nested class) is the
  template.

## Scope

**v1:** an `inner class` as a nested **candidate** directly under a non-generic, non-`inner`
`class` owner (`ClassKind.CLASS`, not sealed, not `value`), at any nesting depth of that owner
(`Aviary.Middle` may own an inner class if `Middle` is itself admitted). Primary, secondary and
ADR-091 omitting constructors. `data inner class` rides the same plan plus the existing COPY route.

**Deferred, still a named skip:**
- An `inner class` as an **owner** (only another `inner class` can nest inside one: a non-inner
  nested class inside an inner class is `NESTED_CLASS_NOT_ALLOWED`, **inferred** from the compiler
  diagnostic, not spiked; if wrong, the child keeps skipping named through the owner arm, no
  silent output either way). The mechanism is the same receiver, one level up
  (`guest.Deep()`); a follow-up drops the owner arm once this ADR's cell is green.
- An `inner class` under a sealed base or arm owner (Kotlin allows it; the receiver would be the
  abstract base handle, and the sealed route's constructor rendering is ADR-009's, not
  `renderConstructor`). Named skip with a reason naming the sealed owner.
- A generic `inner class` (already deferred by the candidate gate's generic arm).
- An inner class of a generic outer (already deferred by the owner gate's generic arm).

## Consequences

- Additive: `Host.Guest` appears with `public Guest(Host outer, ...)`; no released name or entry
  point changes (a non-inner class's plan is byte-identical, since `receiver` stays `Static`).
- A declared constructor parameter named `outer` is now CS0100 in the generated C# and a duplicate
  parameter in the generated Kotlin. Same class of hazard as ADR-132's `receiver`; recorded, not
  guarded (a rename rule would need a collision check for one name).
- `SKIPPED_NESTED_DECLARATION` survives for: an `enum class` owner, a generic owner, an `inner
  class` **owner**, an inner class under a sealed owner, a `value class` owner, and a nested sealed
  hierarchy candidate.
- ROADMAP's Phase 4 inner-class line closes for the candidate half; the owner half becomes its own
  line. FEATURES.md's class row and `docs/topics/classes-and-objects.md`'s nested-types section
  (~:1104, "an `inner class` owner (its constructor needs the outer instance, no C# equivalent)")
  need the documenter's pass.
- Cost, priced: 4 source files (`NugetProcessor.kt` one arm, `ForwardCallablePlanner.kt` one
  receiver, `ForwardKotlinPlanEmitter.kt` one arm, `ForwardCirPlanProjection.kt` one projection),
  one new fixture file, one xunit file, one Tier 1 test file edited, one leak row, three fixture
  comments that name inner as deferred (`Deferred.kt` ~:38, `Aviary.kt` ~:40, `ProbeOuter.kt` ~:19),
  this ADR, one docs topic, FEATURES.md, ROADMAP.md.

## Verified claims (by reading repo code, this session)

Every seam in Context's "What the code does today" list, with the line numbers given: the two
`Modifier.INNER` gate arms; `constructorEntry`'s `ForwardReceiver.Static`; the emitter's
CONSTRUCTOR and EXTENSION arms and its index-0 receiver lookup; `receiverParameter()`'s wire
shape; `constructor()` versus `extension()` in `ForwardCirPlanProjection`; `callArgument`'s
`._handle` lowering; `constructorNativeImport`'s parameter derivation; `renderConstructor`'s raw
name pass-through on the trivial path; `exportedObjectHandles = exportedTypes`; `Tier1Harness`
compiling the generated Kotlin.

Also verified by reading upstream source: Kotlin/Native ObjC export appends one bridge slot for an
inner class's constructor after the value parameters (`getMethodBridge.kt`).

## Inferred claims (not run; each fails loud, none silently wrong)

1. `outer.asStableRef<Host>().get().Guest(visits)` is valid Kotlin. Grounded in the official
   `Outer().Inner()` example; if wrong, the first Tier 1 cell fails to compile `CNameExports.kt`.
2. KSP's `getConstructors()` on an inner class reports only the declared parameters, no synthetic
   outer (KSP is source-level; the JVM outer parameter is a bytecode artifact). If wrong, the plan
   carries an extra parameter and the generated Kotlin call has one argument too many: compile
   error, loud.
3. `asStableRef<pkg.Host.Guest>()` accepts an inner class as a type argument. If wrong, compile
   error, loud.
4. An inner instance keeps its outer reachable on the Kotlin heap (language docs). If wrong, the
   disposed-outer xunit cell crashes rather than passing; pinned deliberately for that reason.
5. `NESTED_CLASS_NOT_ALLOWED` inside an inner class (Scope). Harmless either way.
6. The JVM's outer-first constructor parameter and ObjC export's header spelling for the trailing
   slot: prior-art colour only, nothing in the decision depends on them.

No spike was run: nothing here is a metadata or marshalling claim whose failure would be silent,
and the repo's own Tier 1 harness is the spike for claims 1 to 3 on the first implementation run.
