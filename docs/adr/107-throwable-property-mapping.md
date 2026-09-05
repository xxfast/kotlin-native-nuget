# ADR-107: `Throwable`-typed properties read as a constructed, unthrown `System.Exception`

## Status
Accepted

**As shipped, corrections to this ADR's prose:**

- The fixture sealed class is `Issue56LoadState`, not `LoadState`: a second `LoadState` in another
  package crashes KSP on the export-symbol collision recorded in
  [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md) Phase 4
  (`SealedClassExports.kt:27` derives the export prefix from the lowercase simple name only, no
  package qualifier).
- The "Files touched" checklist below missed one arm: `ForwardPropertyPlanner.isPlannable`'s
  `else -> error(...)` branch also needed a `Throwable` case, or adding the type crashes KSP
  outright rather than skipping.
- The checklist's plan for the refused setter ("the existing read-only diagnostic ... 'cannot be
  written into a Kotlin collection'") was wrong for a `Throwable` setter: `ForwardDroppedPropertySetter`
  gained an optional `reason` field instead, carrying a `Throwable`-specific sentence
  (`ForwardPropertyPlanner.kt:19-30`, `:301-315`).
- Deviation from the Decision's classifier arm: `isStdlibThrowable()`'s supertype walk is gated on
  `containingFile == null` (klib/stdlib origin only, `ForwardBridgeTypeClassifier.kt:320-334`), so a
  module-local, non-exported `class MyError : Exception()` keeps its prior classification and skips
  named rather than mapping to `Throwable`. `classifyActualTypeAliasTarget` also maps a `Throwable`
  target back to `Unsupported` to preserve ADR-074's actual-typealias skip
  (`ForwardBridgeTypeClassifier.kt:225-240`). Both are stated as limitations in Scope below.

## Context

Issue [#56](https://github.com/xxfast/kotlin-native-nuget/issues/56) (part 2): a property whose
declared type is `kotlin.Throwable`, `Throwable?`, or a stdlib subtype (`Exception?`,
`IllegalStateException?`) on an exported class is skipped today with
`SKIPPED_UNSUPPORTED_PROPERTY ... kotlin.Throwable? has no property getter or setter shape`
(`NugetProcessor.kt:178`). The classic shape is a failed-state record:

```kotlin
data class Failure(val reason: String, val error: Throwable?)
```

Why it skips (**verified**, read from source): `ForwardBridgeTypeClassifier.classifyNonNullable`
has no known-stdlib branch for `Throwable` (the block at `:93-99` recognises only `Char`, `String`,
`Instant`, `Duration`), so `kotlin.Throwable` falls to the `exportedObjectHandles` membership
test at `~:168` and, because a stdlib declaration has `containingFile == null`, becomes
`BridgeType.Unsupported(..., isUnexportedDependency = true)`. `ForwardPropertyPlanner.isPlannable`
(`:467`) has no arm for `Unsupported`, so the property is dropped. The same property on a
**sealed subclass** never reaches the planner: it skips on the legacy ADR-009 route with a
different kind, `SKIPPED_UNSUPPORTED_TYPE` from `CirClassTranslator.kt:1042-1058` (**verified**),
while its Kotlin export is still emitted (see Decision, sealed-route arms).

The bridge already knows how to move a Throwable out of Kotlin, in the *thrown* position
(**verified**, `GenericClassExports.kt:743-770`, `CirErrorRenderer.kt`):

- Kotlin: `buildError(e): NugetError` copies `type` (qualified class name), `message` (or
  `"Kotlin error"`), `stackTraceToString()` and the `cause` chain (cycle-guarded) into a plain
  `NugetError` tree; every throw site writes `StableRef.create(buildError(e)).asCPointer()` into
  the trailing `errorOut` slot. No reference to the original `Throwable` survives.
- C#: `NugetErrorNative.BuildException(IntPtr): Exception` reads the tree through the
  `nuget_error_type/message/stacktrace/cause_*` exports, calls `NugetMarshal.Dispose(errorPtr)`
  (→ `nuget_dispose` → `asStableRef<Any>().dispose()`), and returns a **constructed, not thrown**
  exception: `KotlinException` for an unmapped type, or one of the ADR-029 mapped subclasses
  (`KotlinArgumentException : ArgumentException, IKotlinException`, ...). Callers throw it.
- The plan model already describes this slot as `ForwardTransfer(type =
  BridgeType.ObjectHandle("kotlin.Throwable"), flow = OUT_OF_KOTLIN, conversion =
  STABLE_REF_TO_HANDLE)` (`ForwardCallablePlanner.kt:1468`, `ForwardPropertyPlanner.kt:432`).

So the wire, the envelope, the disposal and the C# reconstruction all exist; the only gap is
that nothing emits them for a *value* position.

## Alternatives Considered

### 1. Reuse the error envelope; C# property typed `System.Exception?`, value always `IKotlinException` (chosen)

The getter export returns the same `StableRef<NugetError>` pointer a throw would have written,
in the *result* slot; the C# getter returns `NugetErrorNative.BuildException(nativeResult)`
without throwing. Consumer reads `failure.Error?.Message`, pattern-matches on
`KotlinArgumentException`, walks `InnerException`, casts to `IKotlinException` for
`KotlinType`/`KotlinStackTrace`.

- Pro: zero new native exports, zero new C# runtime types, same lifetime rule as the thrown path,
  the same object a `catch` would have seen (ADR-028 cause chain and ADR-029 mapping for free).
- Pro: `System.Exception` is the .NET idiom for a stored failure (`Task.Exception`,
  `AggregateException.InnerExceptions`, `ExceptionDispatchInfo.SourceException`).
- Con: static type is `Exception?`, not `KotlinException?`. This is forced, not chosen: the
  ADR-029 mapped classes derive from their BCL analogue, not from `KotlinException`
  (**verified**, `renderMappedException`), so `KotlinException?` would make `IllegalArgumentException`
  un-representable. The documented guarantee is "null, or an `IKotlinException`".
- Con: no identity. Each read builds a fresh `Exception`; `ReferenceEquals(f.Error, f.Error)`
  is false. Snapshot semantics, same as the thrown path.

### 2. Property typed `KotlinException?` (rejected)

Literal reading of "reuse the thrown object". Rejected for the hierarchy reason above: either
ADR-029 mapping is dropped at property positions (a `KotlinArgumentException` would have to be
re-wrapped as a plain `KotlinException`, losing `catch (ArgumentException)` parity) or the
mapped classes are re-parented, an ABI break for every consumer catching them today.

### 3. `IKotlinException?` as the static type (rejected; human confirmed `Exception?`, decision 2)

Truthful about what crosses, but hides `Message`, `InnerException` and `ToString()` behind a
cast; IntelliSense shows two string properties and nothing exception-like. Rejected as less
idiomatic; cheap to flip since the reconstruction expression is the same.

### 4. A record of `(KotlinType, Message)` only (rejected)

Strictly less than what the thrown path already delivers (drops the stack trace and the cause
chain), invents a new public type, and a consumer wanting to rethrow has to build an exception
by hand.

### 5. Live handle: export `kotlin.Throwable` as a C# class `KotlinThrowable` with `Message`/`Cause`/`StackTrace` getters (rejected for v1)

The ObjC-export shape (`KotlinThrowable` wrapper; `NSError` only under `@Throws`). Preserves
identity and allows passing the Throwable back into Kotlin, but needs a new generated class, four
new native accessor exports, `IDisposable` lifetime on the consumer, and produces a value that is
*not* a `System.Exception`, so it cannot be rethrown or caught. Fails the consumer goal
("exception-shaped value"). Revisit only if a `Throwable` *parameter* is ever needed.

## Decision

Add a `BridgeType.Throwable` singleton (the `Instant`/`Duration` "known stdlib type" pattern),
plannable at **property positions only**, getter-only, riding the existing error envelope, on
**both** property routes: the planner route (ordinary exported classes) and the legacy ADR-009
sealed-subclass route.

### Human decisions recorded at the Step 2 gate

1. v1 **includes sealed-subclass properties** (`sealed class LoadState { data class Failure(val
   error: Throwable?) : LoadState() }`): the issue's motivation is failed states in state
   machines, and those are sealed here. Arms below.
2. The C# static type is `System.Exception?` (Alternative 1), not `IKotlinException?`.
3. Exported user exception classes (`class LookupError : Exception()` in the export set) stay
   plain handle classes for now.
4. A Kotlin-only-constructible `Failure` (constructor skipped because of its `Throwable`
   parameter) is accepted.

### Consumer API

```csharp
public sealed class Failure   // generated, as today
{
    public string Reason { get; }
    public global::System.Exception? Error { get; }   // KotlinException or an ADR-029 subclass, never a bare Exception
}

var f = Lookups.Find("Oreo");
if (f.Error is KotlinArgumentException e)                 // ADR-029 mapping preserved
    Console.WriteLine(((IKotlinException)e).KotlinType); // "kotlin.IllegalArgumentException"
Console.WriteLine(f.Error?.InnerException?.Message);      // ADR-028 cause chain preserved
```

A non-null `Throwable` property spells `global::System.Exception` (non-nullable).

### Bridge mechanism

Kotlin side (`ForwardPropertyKotlinEmitter.kt`). Getter export returns `COpaquePointer?`:

```kotlin
// val error: Throwable?   -- nullable: null in-band, same as an ObjectHandle? getter
return try {
  val result = receiver.error?.let(::buildError)
  if (result == null) null else StableRef.create(result).asCPointer()
} catch (e: Throwable) { /* existing errorOut write */ null }

// val error: Throwable    -- non-null
return try { StableRef.create(buildError(receiver.error)).asCPointer() } catch (e: Throwable) { /* same */ null }
```

No new body helper is needed: this is exactly `nullableHandleBody("$access?.let(::buildError)",
"errorOut")` and `handleBody("buildError($access)", "errorOut")` (**verified** against the helper
bodies at `ForwardPropertyKotlinEmitter.kt:457-482`: the invocation string is evaluated once into
`result`, null-checked, then boxed). Both helpers are `internal` and are already imported by the
sealed route too. **Verified by construction**: `valueBody`/`handleBody` already emit
`buildError(e)` and ship, so property getters live in the same generated Kotlin file as the
file-private `buildError`; a `::buildError` reference to a private top-level function in the same
file is legal Kotlin.

C# side (`ForwardCirPropertyProjection.checkedGetter`):

```csharp
IntPtr nativeResult = Native_Get_error(_handle, out IntPtr error);
if (error != IntPtr.Zero) { throw NugetErrorNative.BuildException(error); }
return nativeResult == IntPtr.Zero ? null : NugetErrorNative.BuildException(nativeResult);
```

`BuildException` is `internal static` in the same generated assembly (**verified**,
`CirErrorRenderer.kt`). It disposes the envelope, so the C# getter owns the `StableRef` for
exactly the duration of the read, identical to the thrown path. Two envelopes are never both
live: if the getter itself throws, the result pointer is null and only `errorOut` is written.

Plan model: wire `POINTER`, conversion `STABLE_REF_TO_HANDLE`, direct (single-call) nullable
since `hasValueFanOutInner()` returns null for a pointer wire (**verified**,
`ForwardPropertyPlanner.kt:534-545`).

### Files touched (a kotlin-dev's checklist; every arm is load-bearing)

Adding `Throwable` to `isPlannable` without every downstream arm is a **crash, not a skip**:
`ForwardPropertyKotlinEmitter.kt:98` hits `else -> error("Forward property direct nullable
getter is invalid ...")` at KSP time, and the projection's `else -> return nativeResult` produces
CS0029 (**verified**, both `else` branches read).

1. `forward/ForwardMarshallingModel.kt`: `data object Throwable : BridgeType`. It shadows
   `kotlin.Throwable` inside that file; qualify any real `kotlin.Throwable` reference there (the
   file already spells `kotlin.Boolean` on `viaDiscriminator` for the same reason).
2. `forward/ForwardBridgeTypeClassifier.kt`: in the known-stdlib block (`:93-99`), before the
   `exportedObjectHandles` test. Must be **supertype-aware** so `Exception?`,
   `IllegalStateException?` and every stdlib throwable qualify: match when
   `qualifiedName == "kotlin.Throwable"` or `classDeclaration.getAllSuperTypes()` contains it,
   *and* `qualifiedName !in context.exportedObjectHandles` (an exported user exception class
   keeps its existing `ObjectHandle` binding, see Consequences). The supertype check is
   **inferred** (`getAllSuperTypes()` on a klib-origin `KSClassDeclaration` is expected to
   resolve; not spiked).
3. `forward/ForwardPropertyPlanner.kt`: `isPlannable` → true; `isReadableComponent` → **false**
   (so `List<Throwable>` skips named, the #52 precedent, rather than crashing); `wireType()` →
   `POINTER`; `conversion(OUT_OF_KOTLIN)` → `STABLE_REF_TO_HANDLE`; refuse the setter before
   `conversion(INTO_KOTLIN)` is reached so `var error: Throwable?` plans get-only. Which existing
   drop path/diagnostic kind the setter takes is **inferred**: ADR-075's read-only collection
   property is the precedent to copy.
4. `forward/ForwardPropertyKotlinEmitter.kt`: the two getter arms above (nullable at `:56`,
   non-null at `:122`).
5. `forward/ForwardCirPropertyProjection.kt`: first `when` in `checkedGetter` (declare `IntPtr
   nativeResult`), the reconstruction arms (nullable and non-null), and `csharpType()` (`~:606`)
   → `"global::System.Exception"`.
6. `FEATURES.md` (Exception Handling table: `Throwable` property → `Exception?`),
   `docs/topics/exceptions.md`.
7. Tests: `test-library/src/nativeMain/kotlin/.../test/issue56/Issue56Sample.kt`,
   `IntegrationTests/Issue56Tests.cs`, `nuget-processor/src/test/.../tier1/Tier1ThrowablePropertyTest.kt`.
   Fixture shape (mirrors `Issue54Sample.kt`; every constructor with a `Throwable` parameter is
   skipped, decision 4, so each cell ships a Kotlin factory):

   ```kotlin
   // planner route (items 1-5)
   data class Issue56Failure(val reason: String, val error: Throwable?, val fatal: Throwable)
   fun issue56Find(name: String): Issue56Failure   // "Mochi" -> error = null; "Oreo" -> IllegalArgumentException(cause = RuntimeException)

   // sealed route (items 8-9), decision 1
   sealed class Issue56LoadState {
     data object Loading : Issue56LoadState()
     data class Loaded(val value: String) : Issue56LoadState()
     data class Failure(val error: Throwable?) : Issue56LoadState()
   }
   fun issue56Load(name: String): Issue56LoadState  // "Oreo" -> Failure(IllegalStateException("clinic offline")); "Mochi" -> Loaded
   ```

   C# assertions: `Assert.Null`, `Assert.IsType<KotlinArgumentException>(f.Error)`,
   `Assert.IsType<KotlinException>(f.Error!.InnerException)`, and for the sealed cell
   `Assert.IsType<KotlinInvalidOperationException>(((Issue56LoadState.Failure)state).Error)`.

### Sealed-subclass route (ADR-009) arms, decision 1

The sealed route never consults `ForwardPropertyPlanner`; it iterates
`subclass.getAllProperties()` twice, once per side, against its own type tests. **There is no
ADR-105 precedent to copy here** (**verified**: `git show e9e441b --stat` filtered to
`SealedClassExports.kt` and `CirClassTranslator.kt` is empty; ADR-105 kept its fixture on a plain
data class). The closest precedents on this route are issue #38 (nullable primitives) and #50
(qualified C# spelling).

What the route does with `Throwable?` today (**verified**, read from source):

- Kotlin, `SealedClassExports.kt:62-165`: the type tests are `isEnumType`, `isPrimitiveType`
  (a `kotlin.*` scalar whitelist at `:76-81`) and a final `else` (`:153-165`) that treats *every*
  other type as a handle and emits `nullableHandleBody(access)` / `handleBody(access)`. So a
  `Throwable?` property **already gets a Kotlin export today**, returning a raw
  `StableRef<Throwable>`; it is dead code because the C# side drops the property.
- `errorOut` **is carried**: `sealedPropertyGetter` (`:194-198`) declares
  `errorOut: COpaquePointer?` on every getter and every body writes it on throw.
- The nullable **null sentinel is already the same** as the ordinary route's: `nullableHandleBody`
  returns Kotlin `null` → C null pointer for a null property, and the C# arm tests `IntPtr.Zero`.
  `Throwable?` needs nothing new here.
- C#, `CirClassTranslator.kt:985-1200`: `isReferenceType` is "not scalar/enum/collection/lambda"
  (`:1039-1040`); the guard at `:1042-1058` then emits **`SKIPPED_UNSUPPORTED_TYPE`** ("its type
  'kotlin.Throwable' is not in the bridgeable subset") for any reference type outside
  `exportedTypes` and returns `null` from the `mapNotNull`. So the sealed shape of #56 skips
  with a *different* diagnostic kind from the plain-class shape.

Arms to add:

8. `exports/SealedClassExports.kt`: compute `isThrowableType` next to `isEnumType` (`:73`):
   qualified name is `kotlin.Throwable` or `getAllSuperTypes()` contains it, and the type is not
   in the exported set (decision 3). Insert a branch **before** the final `else`:
   `if (isNullable) nullableHandleBody("$access?.let(::buildError)", "errorOut") else
   handleBody("buildError($access)", "errorOut")`, returning `cOpaquePointer.copy(nullable = true)`
   like the `else` does. Everything else (`sealedPropertyGetter`, `errorOut`, dispose) is
   unchanged.
9. `cir/CirClassTranslator.kt`: compute the same `isThrowableType` next to `isEnumType`
   (`:1000`); exclude it from `isReferenceType` (`:1039`) so the `:1042` skip guard does not
   fire; `nativeReturnType = "IntPtr"` (`:1082`); `type = "global::System.Exception"` +
   `"?"` when nullable (`:1092`); and a getter string that is **multi-line** so
   `CirSealedRenderer.renderSealedSubclassProperty` (`:119-131`, **verified**) renders a block
   body instead of `=> expr;`:

   ```csharp
   IntPtr nativeResult = Native_Get_error(_handle, out IntPtr error);
   if (error != IntPtr.Zero) { throw NugetErrorNative.BuildException(error); }
   return nativeResult == IntPtr.Zero ? null : NugetErrorNative.BuildException(nativeResult);
   ```

   **Do not copy the existing nullable-reference arm at `:1181`.** It is a single expression that
   calls `Native_Get_$propName` **twice** (once for the `IntPtr.Zero` test, once inside `new`),
   which mints two `StableRef`s and never disposes the first (**verified** by reading; a
   pre-existing leak for ordinary handles on this route, out of scope here). For `Throwable` it
   would build two envelopes and leak one. The list branch at `:1110-1126` is the shape to mirror:
   one call, `errorOut` read, block body.
   `CirProperty` (`CirModel.kt`) and `CirSealedRenderer.kt` need no change: the getter is a
   string and `hasSyncErrorOut` is already unconditionally `true` on this route (`:1200-1206`).

Untouched: `GenericClassExports.kt` (envelope), `CirErrorRenderer.kt` (runtime), every
`exports/*.kt` throw site, `CirSealedRenderer.kt`, `CirModel.kt`, the ABI of every existing
export. The sealed route's existing `Throwable?` Kotlin export changes body (raw `StableRef<Throwable>`
→ `StableRef<NugetError>`) but not signature; nothing consumed the old body.

## Consequences

- `Failure.Error` reads as a real `System.Exception` with ADR-027 stack trace, ADR-028
  `InnerException` chain and ADR-029 type mapping, using the runtime that already ships.
- **Snapshot, not identity**: every read allocates a new `Exception`; mutations to the Kotlin
  Throwable after the read are invisible; two reads are not `ReferenceEquals`.
- The stack trace is whatever Kotlin/Native captured when the Throwable was *constructed*
  (**inferred**: K/N fills the trace at construction, so an unthrown Throwable still has one);
  ROADMAP line 65's host-frame noise applies to it too. A null `message` surfaces as
  `"Kotlin error"`, inherited from `buildError`.
- **The data-class constructor is not bound**: `Failure(String, Throwable?)` has a `Throwable`
  parameter, so the constructor and `copy` skip through the existing input-skip path and
  `Failure` is Kotlin-constructible only (**inferred** from the ADR-105 spike, where a class
  survived with `_handle` getters while members skipped). Fixtures need a Kotlin factory.
- **User-defined exception classes in export scope are unchanged**: `class LookupError :
  Exception()` inside the exported package is already an ordinary `ObjectHandle` and binds as a
  plain handle class, not as an `Exception`. Unifying that is a separate decision.
- `var error: Throwable?` binds get-only; a setter would need C# → Kotlin Throwable
  construction, which the envelope cannot express (type would be lost).

## Scope

**v1 (this ADR)**: `Throwable`, `Throwable?`, and stdlib subtypes at a **property** getter on
(a) an exported class/object/data class via the planner route, and (b) a **sealed subclass**
(`sealed class LoadState { data class Failure(val error: Throwable?) : LoadState() }`) via the
legacy ADR-009 route, decision 1. The two routes share the Kotlin body helpers and the C#
`BuildException` call but are separate code paths (items 1-5 vs. 8-9), and both must land for the
issue's motivating state-machine shape to bind. Extension-property getters bind if they share the
planner path (**inferred**). Value class over `Throwable`: not planned (the `ValueClass`
underlying whitelist is unchanged).

**As-shipped limitation, not in the original Decision**: the classifier's `Throwable` supertype
walk only fires for a klib/stdlib-origin declaration (`containingFile == null`). A module-local,
non-exported `class MyError : Exception()` therefore keeps its prior classification and a property
typed with it skips named rather than mapping to `Throwable`; and `classifyActualTypeAliasTarget`
deliberately maps a `Throwable` target back to `Unsupported` so an `expect`/`actual` pair keeps
ADR-074's skip rather than silently becoming a `System.Exception`.

**Deferred, separate work (not free)**: method/function **return** `fun cause(): Throwable?`
needs arms in `ForwardCallablePlanner` (`skipReason`, `shapeOrNull`, wire/conversion),
`ForwardKotlinPlanEmitter`, and `ForwardCirPlanProjection` (`~:808/:890`); the reconstruction
expression is identical, the plumbing is not. Collection components `List<Throwable>` (skip
named). Constructor parameter and method **parameter** typed `Throwable`: **out of scope**; C#
cannot mint a typed Kotlin `Throwable` from strings, and Alternative 5 is the only route.

## Prior art (only as far as it changes the decision)

**Inferred, from memory, no web fetch this session.** Kotlin ObjC export surfaces
`kotlin.Throwable` as a `KotlinThrowable` ObjC class (a live handle wrapper, Alternative 5) and
converts to `NSError` only for `@Throws` functions; KMP-NativeCoroutines bridges errors via
`KotlinThrowable.asNSError()` at the throw site. Both confirm the two shapes on the table (live
wrapper vs. converted error value) and neither offers a third. The thrown-position precedent in
this repo (ADR-023/024/028/029) already picked "converted value", so a property follows it.

## Inferred vs verified claims

Verified (read from repo source this session, file:line cited above): the skip path and its
cause; the `NugetError` envelope contents and `buildError` semantics; `BuildException` constructs
without throwing and disposes the envelope; ADR-029 subclasses do not derive from
`KotlinException`; property getters can call `buildError` (same generated file, by construction);
the two `else` crash arms; the existing `ObjectHandle("kotlin.Throwable")` errorSlot modelling;
direct nullable on pointer wires. Sealed route: every sealed-subclass getter declares `errorOut`
(`SealedClassExports.kt:194-198`); the final `else` already emits a handle body for `Throwable?`;
the C# guard skips it with `SKIPPED_UNSUPPORTED_TYPE`; the existing nullable-reference C# arm
calls the export twice; a multi-line getter string renders as a block body
(`CirSealedRenderer.kt:119-131`); `nullableHandleBody`/`handleBody` compose with
`?.let(::buildError)` / `buildError(...)` without a new helper; e9e441b (ADR-105) touched neither
sealed-route file.

Inferred (not spiked; nothing compiled this session): `getAllSuperTypes()` resolves for stdlib
throwables in KSP (needed on both routes); the exact diagnostic kind a refused setter reports on
the planner route; K/N stack capture at construction; class survival with a skipped constructor
(both the plain data class and the sealed subclass, whose `Failure(Throwable?)` constructor is
likewise skipped); extension-property coverage. None of these silently corrupts output if wrong:
the first two fail at KSP/compile time or produce a visibly different diagnostic, the third only
changes the content of `KotlinStackTrace`, and the fourth shows up as a missing class in
`Interop.cs`.
