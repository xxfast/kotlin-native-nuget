# ADR-104: Reverse thunk error channel: one trailing opaque error out-parameter carrying a managed-exception GCHandle

## Status

Accepted

Implemented as designed (Alternative 1, all four forks as gated). The Decision's "Mechanism claims
ledger" flagged two inferred claims as unspiked; both held and are now verified by
`OreoExamine_StructReturn_Throws_SurfacesArgumentException` and
`OreoExamineThrows_ThenMyloExamineCalm_OnTheSameInfirmary` (`IntegrationTests/InfirmaryRoundTripTests.cs`):
"returning `default` from a thunk that also has unwritten struct out-pointers is safe" held, and
the implementation made the check-first ordering structural rather than conventional.
The generator's `wrapInvoke` emits every call site as `nugetCall { err -> fn.invoke(..., err) }`,
so the check-and-throw lives inside the invoke expression itself, before any `requireNotNull` on
the result: there is no emission site left where the check-before-read ordering could be
forgotten.

`scripts/verify.sh --plugin` unfiltered: 1148 passed, 0 failed (1137 previously-passing plus the 11
that previously aborted the host). `:nuget-plugin:test`: 498 passed. All eight target thunks
(`Discharge`, `Temperature`, `Chart`, `Admit`, `Occupancy_Get`, `Examine`, `WardSign_Set`,
`Quarantine.Ctor`) went warm in coverage.

Two deliberate deviations from a strict reading of ADR-049's "let it crash": the shared reverse
runtime (`nuget_runtime_register`) is now emitted unconditionally rather than only when the package
has interface slots, because every call site now routes through `nugetCall`; and "let it crash" is
superseded for user-code thunks specifically, while runtime plumbing thunks and the ADR-085
`createBridge` factory keep it, exactly as Fork A scoped.

## Context

A Kotlin consumer calls a method on a bound C# NuGet type. The generated Kotlin stub dispatches
through a registered function pointer into an `[UnmanagedCallersOnly]` thunk in the generated C#
shim. That thunk calls the real C# method. **If the C# method throws, the managed exception has
nowhere to go**: the thunk has no `catch` and no error out-parameter, the frame below it is a
Kotlin/Native frame, and .NET terminates the host process rather than unwinding a managed exception
through it. Nothing is catchable from Kotlin; the whole process dies.

This is the reverse-direction mirror of a problem the forward direction solved in ADR-024 (trailing
error out-parameter on every `@CName` export) and that ADR-087 stage 2 solved for the one Kotlin
`staticCFunction` slot boundary. The channel this ADR designs runs the **opposite way to both**:

| Channel | Error originates | Envelope owned by | Read by | Status |
|---|---|---|---|---|
| ADR-024 forward exports | Kotlin | Kotlin (`StableRef<NugetError>`) | C# (via `nuget_error_*` `DllImport`) | Shipped |
| ADR-087 stage 2 slots | Kotlin | Kotlin (`StableRef<NugetKotlinError>`) | C# (via `nuget_kotlin_error_*` `DllImport`) | Shipped |
| **This ADR** | **C#** | **C# (managed heap)** | **Kotlin** | Proposed |
| Phase 7 forward callbacks | C# | C# | Kotlin | Not started (ADR-102 `FailFast`) |

Both shipped channels are Kotlin-owns-the-envelope, so **no C# → Kotlin error envelope exists
today at all** (verified: the only error accessors in the generated runtime are the eight
`nuget_kotlin_error_*` `@CName` exports in `NugetRuntime.kt`, all of which read a Kotlin-side
`StableRef`; grep for any managed error accessor finds nothing). ADR-087's envelope cannot be
inverted by reuse: it is a `StableRef` to a Kotlin object read through static native exports, and
the mirror needs a managed object read from Kotlin, which cannot be a `DllImport` because there is
no native entry point for managed code. Kotlin can only reach managed code through a **registered
function pointer**, which is the shape this ADR has to add.

Scope note, from the task statement: this ADR is **the channel only**. A single generic Kotlin
exception carrying the managed type name and message satisfies it. Mapping .NET exception types to
Kotlin analogs, carrying the .NET stack trace, and mapping `InnerException` to a Kotlin `cause`
chain are three separate open Phase 11 lines and are deliberately not designed here; the channel is
chosen so all three are additive (see Consequences).

### What the shipped reverse ABI looks like today

Verified by reading real generated output (`test-library/build/nuget-interop/`, regenerated
2026-08-31 09:36, i.e. **newer than** the generator sources it came from, so it reflects current
`NugetGenerateShimsTask`/`NugetGenerateBindingsTask`):

Thunks are **not** uniform in return shape. From `ShelterRegistration.cs`:

```csharp
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static IntPtr Ctor__dd52...Thunk()                                   // handle return

[UnmanagedCallersOnly(...)]
private static unsafe void Admit__1ac1...Thunk(IntPtr selfHandle, ..., int* outCount, int* outMood)
                                                                             // struct return via out-pointers

[UnmanagedCallersOnly(...)]
private static unsafe void Current_Get_Thunk(IntPtr selfHandle, int* outCollar_Girth, ...)

[UnmanagedCallersOnly(...)]
private static void Current_Set_Thunk(IntPtr selfHandle, int value_Collar_Girth, ...)
```

plus, elsewhere in the same output, `IntPtr` string returns, `int`/`byte` scalar returns, static
route thunks, struct-member thunks and generic-witness thunks. **All of them are plain C
functions with a fixed parameter list, so a trailing pointer parameter can be appended to every
one mechanically** (verified: the emitters already append heterogeneous trailing out-pointers for
struct returns).

Kotlin call sites are heterogeneous in *scoping*, which is the real implementation cost. From
`Sanctuary.kt` and `Shelter.kt` (verified, real output):

```kotlin
return nugetTransferScope { fn.invoke(handle.require("Sanctuary"), handleOf(feedable, "...")) }   // no memScoped
nugetTransferScope { memScoped { fn.invoke(...) } }                                               // memScoped
memScoped { /* alloc<IntVar>() x8 */ fn.invoke(...); /* read out-params */ }                      // memScoped + allocs
val ptr: COpaquePointer? = fn.invoke(handle.require("Sanctuary"))                                 // bare
```

An error out-parameter needs a `memScoped` (or an equivalent placement) at **every** call site,
including the ones that have none today.

Registration and drift detection (verified, `RirBridging.kt` + generated `ShelterBindings.kt`):
each type registers through `nuget_{ns}_{type}_register(slotCount, contractHash, ...ptrs)` and
`NugetRegistry.checkContract` refuses every pointer if either leading scalar disagrees.
`contractHash` is `fnv1a64(name + "|" + registrables.joinToString("|") { contractSignature })`,
and `contractSignature` is built purely from member kind/name/parameter types/return type. **It
contains no version tag** (verified, `RirBridging.kt:928-947`). Adding an error out-parameter
therefore changes every thunk's true arity while leaving the hash input byte-identical: exactly
the same-arity drift class the hash exists to catch, and it would not catch it. ADR-087 hit this
and solved it by putting a version tag inside the hashed string (`"kotlin_bridge_v2:createBridge("
+ ...`, verified `RirBridging.kt:1266`).

The shared runtime already registers managed function pointers Kotlin calls back into
(verified, `NugetRuntime.kt` + `NugetRuntimeRegistration.cs`):

```
nuget_runtime_register(slotCount = 3, contractHash = 4635601892286953020L,
                       freeGcHandlePtr, weakenGcHandlePtr, resolveGcHandlePtr)
```

with `NUGET_RUNTIME_CONTRACT_HASH = fnv1a64("runtime:freeGcHandle(...);weakenGcHandle(...);resolveGcHandle(...)")`
(verified, `RirBridging.kt:1001`). `FreeGcHandle_Thunk` is `GCHandle.FromIntPtr(h).Free()`, so
**Kotlin can already free an arbitrary managed GCHandle** with no new machinery.

And the string ownership rule is already established in both directions (verified): C# returns
strings as `Marshal.StringToCoTaskMemUTF8`, Kotlin reads with `toKString()` and frees with
`freeManagedString`, which is `free()` on posix and `CoTaskMemFree` on mingw
(`NugetInterop.kt` actuals).

## Alternatives Considered

### 1. One trailing `IntPtr* errOut` carrying a GCHandle to the managed exception, read through two new runtime accessor slots (chosen)

Every user-code thunk gains one trailing `IntPtr* errOut`, wraps its body in
`try`/`catch (Exception ex)`, and on catch writes `GCHandle.ToIntPtr(GCHandle.Alloc(ex))` and
returns `default`. `nuget_runtime_register` grows from 3 slots to 5:
`managedErrorType(IntPtr) -> IntPtr` and `managedErrorMessage(IntPtr) -> IntPtr`, both returning
`Marshal.StringToCoTaskMemUTF8` strings. The free path reuses the **already-registered**
`freeGcHandleFn`, so no third slot is needed. Kotlin's generated stub allocates one
`COpaquePointerVar`, passes `.ptr`, and calls one runtime helper that (on non-null) pulls type and
message, frees both strings and the handle, and throws.

Pros: one parameter per thunk, so the expensive change (every thunk emitter, every `*Bindings.kt`
`CFunction` type, every `delegate* unmanaged[Cdecl]<...>` cast, every Kotlin call site, every
contract hash) happens **once**; the three deferred Phase 11 items (type mapping, stack trace,
cause chain) then land as *runtime accessor slots only*, never re-touching thunk arity. Keeps the
option of exception identity round-trip later (the real managed exception object is still alive
behind the handle) which is what the currently-skipped integration test's assertions want. Every
mechanism piece is already shipped somewhere in the repo.

Cons: two new registration slots and a runtime contract-hash change; the error path re-enters
managed code (an accessor call) which must itself be exception-proof; a Kotlin stub that forgets
to check leaks a GCHandle.

### 2. Two trailing string out-params (`IntPtr* errType, IntPtr* errMessage`), no registration change

The catch marshals both strings immediately; Kotlin reads them with `toKString()` and
`freeManagedString`. No GCHandle, no accessors, no runtime registration change, no managed
re-entry on the error path.

Pros: the fewest moving parts; reuses the single most-proven pattern in the codebase (C# hands
Kotlin a CoTaskMem UTF-8 string, Kotlin frees it). Cannot leak a managed object, only two strings.
Cons: two parameters per thunk instead of one, and two allocations at every call site; every later
widening (stack trace = a third param) re-touches **every** thunk emitter, every CFunction type,
every call site and every contract hash again. A `cause`/`InnerException` chain is variable-depth
and cannot be expressed as flat out-params at all, so that item would force Alternative 1 later
anyway. Discards the exception object, so identity round-trip becomes impossible.

### 3. One out-param pointing at a C#-allocated native block with a hand-agreed layout

`Marshal.AllocCoTaskMem(2 * sizeof(IntPtr))` holding `{typePtr, msgPtr}`; Kotlin reads two slots
and frees three blocks. One parameter, extensible by widening the block.
Rejected: it invents a hand-rolled struct layout that neither generator has any other reason to
model, that the contract hash does not describe, and that a 32-bit RID would have to get right by
hand. Alternative 1 gets the same one-parameter extensibility using the handle vocabulary the
codebase already speaks.

### 4. A single packed string (type `\n` message)

One out-param, no layout, no accessors. Rejected: splitting on the first newline is unambiguous
for exactly two fields and stops being unambiguous at three, and messages legitimately contain
newlines. It buys nothing over Alternative 1 and cannot grow.

### 5. Thread-local error slot, or a Kotlin-registered error callback

Rejected for the reasons ADR-024 already recorded and ADR-087 re-recorded: a check-after-call
contract that is not enforced by the signature is fragile, and thread-locals interact badly with
the .NET thread pool (a thunk can execute on any managed thread). A callback adds an ABI shape for
no gain over the out-param both other channels already use.

### 6. Keep `Environment.FailFast` / do nothing

Status quo for reverse thunks (which do not even catch) and ADR-102's deliberate v1 policy for
forward callback thunks. Rejected as the whole point of the item: a C# library method throwing
`ArgumentException` is *normal*, and killing the host for it makes every bound NuGet package
unusable in any code path that validates input.

## Decision

Adopt Alternative 1.

### Wire shape

```
thunk(<existing parameters...>, IntPtr* errOut) -> <existing return type>
```

`errOut` is always **last**, always present on every user-code thunk (no opt-in, matching ADR-024's
plug-and-play posture), and is `IntPtr.Zero`-initialized by the **caller** (Kotlin), never written
on the success path.

Generated C# thunk, before and after:

```csharp
// before (verified, real output, ShelterRegistration.cs)
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static unsafe void Current_Get_Thunk(IntPtr selfHandle, int* outCollar_Girth, ...)
{
    Shelter receiver = (Shelter)GCHandle.FromIntPtr(selfHandle).Target!;
    Nest result = receiver.Current;
    *outCollar_Girth = result.Collar.Girth;
    ...
}

// after
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static unsafe void Current_Get_Thunk(IntPtr selfHandle, int* outCollar_Girth, ..., IntPtr* errOut)
{
    try
    {
        Shelter receiver = (Shelter)GCHandle.FromIntPtr(selfHandle).Target!;
        Nest result = receiver.Current;
        *outCollar_Girth = result.Collar.Girth;
        ...
    }
    catch (Exception ex)
    {
        *errOut = GCHandle.ToIntPtr(GCHandle.Alloc(ex));
    }
}
```

For a value-returning thunk the catch ends `return default;` (`IntPtr.Zero` for handle/string
returns, `0`/`false` for scalars), exactly as ADR-102's forward thunks already do after `FailFast`
(verified, `CirCallbackRenderer.kt:93-96`). **Kotlin must check `errOut` before touching the
return value or any out-parameter**, and must not free any string out-parameter on the error path.

Generated Kotlin stub, before and after:

```kotlin
// before (verified, real output, Sanctuary.kt)
fun introduce(feedable: IFeedable): String {
  val fn = requireNotNull(SanctuaryBindings.introduceFn) { NugetRegistry.notRegistered(...) }
  val resultPtr = nugetTransferScope { fn.invoke(handle.require("Sanctuary"), handleOf(feedable, "...")) }
  ...
}

// after
fun introduce(feedable: IFeedable): String {
  val fn = requireNotNull(SanctuaryBindings.introduceFn) { NugetRegistry.notRegistered(...) }
  val resultPtr = nugetCall { err ->                       // memScoped + alloc<COpaquePointerVar> + check
    nugetTransferScope { fn.invoke(handle.require("Sanctuary"), handleOf(feedable, "..."), err) }
  }
  ...
}
```

with one new runtime helper in `NugetRuntime.kt`:

```kotlin
internal inline fun <T> nugetCall(block: (CPointer<COpaquePointerVar>) -> T): T = memScoped {
  val err = alloc<COpaquePointerVar>()
  err.value = null
  val result: T = block(err.ptr)
  val handle: COpaquePointer? = err.value
  if (handle != null) nugetThrowManagedError(handle)   // never returns
  result
}
```

`nugetThrowManagedError` reads type and message through the two new runtime accessors, frees both
strings with `freeManagedString`, frees the GCHandle with the already-registered `freeGcHandleFn`,
and throws.

### Runtime registration change

`nuget_runtime_register` goes 3 slots → 5, adding `managedErrorTypePtr` and
`managedErrorMessagePtr`, and `NUGET_RUNTIME_CONTRACT_HASH`'s hashed string gains the two new rows
(so a stale runtime shim fails `checkContract` loudly, ADR-054). The C# side:

```csharp
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static IntPtr ManagedErrorType_Thunk(IntPtr err)
{
    try { return Marshal.StringToCoTaskMemUTF8(GCHandle.FromIntPtr(err).Target!.GetType().FullName ?? "System.Exception"); }
    catch { return IntPtr.Zero; }   // an accessor must never throw back across the boundary
}
```

The `catch` on the accessors is not paranoia theatre: a user exception subclass can override
`Message` with a throwing implementation, and this thunk has no error channel of its own by
construction (it *is* the error channel).

### Contract hash

Every per-type `contractHash` input string must gain a version tag, because the arity change is
otherwise invisible to it (verified above: `contractSignature` has no version component). Prefix
the hashed string with a tag, e.g. `"reverse_v2:" + name + "|" + ...`, mirroring ADR-087's
`kotlin_bridge_v2:` precedent verbatim. Without this, a stale C# shim would pass `checkContract`
and then call every thunk with one argument too few: the exact silent-corruption failure mode
CLAUDE.md's symptom table exists for. This is the single most load-bearing line in this ADR.

The ADR-085/086/087 `kotlinBridgePlan` slot ABI (Kotlin → C#) is **untouched**; its
`kotlin_bridge_v2` tag does not move.

### Consumer-visible Kotlin

```kotlin
// bound C# type; Shelter.Admit throws ArgumentException when the litter is empty
try {
    shelter.admit(Litter(mother, basket, count = 0, mood = CatMood.Grumpy))
} catch (e: NugetManagedException) {
    e.managedType   // "System.ArgumentException"
    e.message       // "count must be positive (Parameter 'count')"
}
```

`NugetManagedException(managedType: String, message: String?) : RuntimeException(message)`, emitted
into the reverse runtime package (`io.github.xxfast.kotlin.native.nuget.internal`). Visibility
matches the bound types themselves, which are `internal` (verified: `internal class Shelter` in
real output), so a consumer in the same module can catch it and a consumer in another module could
not have called the API in the first place. If bound types are ever made public, this moves with
them.

### Mechanism claims ledger

**Verified (repo source and real generated output, read this session; the generated output is
newer than its generators, so it is current):**

1. Reverse thunks have no `catch` and no error out-parameter today; a managed exception escaping
   one terminates the host (`MenagerieRoundTripTests.cs:290-301` records this observed live, and
   `NugetGenerateShimsTask.kt:71-74` documents it as deliberate).
2. Thunk shapes are heterogeneous (`void`, `IntPtr`, scalar, struct-out-pointer, ctor, getter,
   setter) but all are plain C functions taking a fixed parameter list, so a trailing pointer
   appends mechanically to all of them.
3. Kotlin call sites are heterogeneous in scoping; several have no `memScoped` today.
4. `contractHash`'s input string has **no version tag**; adding a parameter to every thunk does not
   change it (`RirBridging.kt:878-947`).
5. ADR-087 solved the same invisibility with an in-string version tag (`RirBridging.kt:1266`).
6. `nuget_runtime_register` already carries 3 registered managed function pointers with an
   ADR-054-checked `slotCount`/`contractHash`, and `freeGcHandleFn` already lets Kotlin free an
   arbitrary managed `GCHandle` (`NugetRuntime.kt:22-67`, `NugetRuntimeRegistration.cs:16-51`).
7. `Marshal.StringToCoTaskMemUTF8` → Kotlin `toKString()` → `freeManagedString` (`free` on posix,
   `CoTaskMemFree` on mingw) is the shipped string-ownership pattern for C# → Kotlin.
8. `catch (Exception)` inside an `[UnmanagedCallersOnly]` thunk followed by `return default` is
   already generated and shipped forward-side (`CirCallbackRenderer.kt:80-96`).
9. No C# → Kotlin error envelope exists today; the only error accessors are the eight Kotlin-owned
   `nuget_kotlin_error_*` exports (`NugetRuntime.kt:267-310`).
10. The C# fallback exception type is `KotlinException : Exception`
    (`CirErrorRenderer.kt:78,88`), i.e. **not** an `InvalidOperationException`.

**Inferred (documentation/composition only; NOT verified by a build or a spike this session):**

1. **`GCHandle.Alloc(ex)` on a caught exception behaves like any other object handle**, so Kotlin
   can hold it across the return and free it later through `freeGcHandleFn`. Composition of two
   shipped pieces (bridge handles + free thunk), not spiked for an `Exception` specifically. Low
   risk: if wrong, it fails loudly at the first throw, not silently.
2. **Returning `default` from a thunk that also has unwritten struct out-pointers is safe**,
   because the Kotlin stub checks `errOut` first and never reads them. Depends entirely on the
   generator honouring the check-first ordering at every site; if a site is missed, Kotlin reads
   uninitialized `alloc` memory. This is a codegen invariant, not a runtime guarantee, and the
   implementation should carry a test per return shape.
3. **A thunk that throws part-way through writing struct out-parameters leaks whatever
   `StringToCoTaskMemUTF8` blocks it already wrote.** Bounded (only reachable if the marshalling
   itself throws, e.g. OOM) and accepted, not fixed.
4. **A managed accessor call from Kotlin on the error path is an ordinary reverse call**, not a
   re-entrant one, because the thunk has already returned and its stack has unwound. Reasoned from
   the C ABI, not spiked. ADR-089 flagged one genuinely re-entrant shape (`nuget_kotlin_release` on
   the .NET finalizer thread) and queues work rather than calling out; if an exception can ever be
   raised *on that same finalizer path*, the accessor call there would be that same unspiked shape.
5. **`ex.GetType().FullName` is non-null for every reachable exception type.** Documented as null
   only for generic parameters/arrays of those; the `?? "System.Exception"` fallback makes it moot.

**Not verified and load-bearing, stated in the red register:** nobody has run any of this; there
was no spike. If the contract-hash tag bump (Decision, "Contract hash") is omitted or
mis-implemented, `checkContract` passes on a mixed build and the two mismatch directions fail
differently. Reasoning is from the cdecl/AAPCS calling conventions and is **inferred, not spiked**:

- *Old C# shim + new native library* (Kotlin passes `N+1` args to a thunk declared with `N`): the
  extra argument is ignored by the callee under cdecl, Kotlin's error slot stays null, and the
  channel **silently does not exist**. Every throw kills the host exactly as it does today, with
  nothing anywhere saying why. Not corruption, but indistinguishable from "the feature does not
  work".
- *New C# shim + old native library* (Kotlin passes `N` args to a thunk declared with `N+1`): the
  thunk's `errOut` is an undefined register/stack slot. The success path never touches it, so
  everything looks fine until something throws, at which point the thunk writes a `GCHandle` through
  a garbage pointer: **memory corruption, on the error path only, arbitrarily far from the cause**.

The implementing agent must confirm the tag lands by regenerating and observing that a type's
`contractHash` literal in `*Bindings.kt` changed value before it changes anything else. Nobody has
verified the two failure modes above by execution; if a spike is cheap at implementation time, the
second one is the one worth reproducing, using the ADR-054 recipe (a scratch console app
P/Invoking the real built `.dylib` with a deliberately wrong signature).

## Scope

Recommendations at each fork; the narrowest option that satisfies the restatement is marked
**Recommended**.

**Gate decisions (2026-08-31).** All four forks took the narrowest option:

- **Fork A: user-code thunks only.** Runtime plumbing and the ADR-085 `createBridge` factory keep
  "let it crash".
- **Fork B: managed type name + message only.** Stack trace, `InnerException` chain and the
  .NET-to-Kotlin type map stay deferred Phase 11 items, additive as accessor slots.
- **Fork C: relax the parked test** to the channel's actual contract. The type and stack
  assertions come back when the Fork B deferrals land.
- **Fork D: one `NugetManagedException`** carrying `managedType: String`.

The "cheap insurance" `GCHandle` retention under Fork C was explicitly declined for v1: the handle
is on the wire either way, so starting to retain it later needs no ABI change.

### Fork A: which thunks get `errOut`

- **Recommended: every thunk that dispatches into user (package) code.** Instance methods,
  constructors, property getters, property setters, static-route methods (ADR-095), struct
  constructors/methods/computed getters (ADR-056/058), and generic-witness thunks (ADR-072/094).
  This is the whole of the reverse call surface a consumer can trigger, and it is what un-blocks
  the skipped test. Price: the ~23 `[UnmanagedCallersOnly]` emission sites in
  `NugetGenerateShimsTask.kt` and the ~25 `fn.invoke(...)` emission sites in
  `NugetGenerateBindingsTask.kt`, plus the matching `CFunction<...>` types and contract hash.
- Not in v1: the runtime plumbing thunks (`FreeGcHandle`, `WeakenGcHandle`, `ResolveGcHandle`, and
  the two new accessors) and the ADR-085 `createBridge` factory thunk. None of them run package
  code; a throw there is a bug in our own generated shim, and "let it crash" stays the right
  policy. Price to add later: two more runtime slots, no per-type ABI change.

### Fork B: what the envelope carries in v1

- **Recommended: managed type name + message.** Two accessor slots. Satisfies the restatement.
- Deferred: `StackTrace`, `InnerException` chain, .NET-type → Kotlin-type mapping. Each is one or
  two more runtime accessor slots plus Kotlin-side construction; **none** re-touches thunk arity or
  the per-type contract hash under this design. Priced: stack trace is one accessor + one field;
  the cause chain is a `count`/indexed accessor pair mirroring `nuget_kotlin_error_cause_*`
  verbatim; the type map is a Kotlin `when` over the type-name string, the ADR-029 table reversed.

### Fork C: the skipped integration test

`IntegrationTests/MenagerieRoundTripTests.cs:301`
(`KotlinNoVacancy_DescribeThrows_MapsToKotlinInvalidOperationException`) is named in the backlog
item as what defines done. **This ADR's channel alone does not make it pass as written.** Its path
is Kotlin `NoVacancy.describe()` throws → ADR-087 slot envelope → C# `IFeedableBridge.Describe()`
throws `TestLibrary.KotlinInvalidOperationException` → inside `Sanctuary.Introduce` → escapes
`Introduce_Thunk` → **this channel** → Kotlin `NugetManagedException` → escapes the forward-exported
sample function → forward ADR-024 channel → C#. Assertion by assertion:

| Assertion | With the channel alone |
|---|---|
| `Assert.Equal("no vacancy", ex.Message)` | Passes (message is carried verbatim both hops) |
| `Assert.ThrowsAny<InvalidOperationException>` | **Fails**: the forward map sees the Kotlin type `NugetManagedException` and falls through to `KotlinException : Exception` (verified `CirErrorRenderer.kt:78,88`) |
| `Assert.IsType<KotlinInvalidOperationException>` | **Fails**, same reason. Needs the deferred .NET→Kotlin type mapping (which, for the `Kotlin*Exception` family specifically, would round-trip the type home through text) |
| `Assert.Contains("IFeedableBridge", ex.StackTrace)` | **Fails**: the .NET stack of the inner throw is discarded; the outer `KotlinException`'s own `StackTrace` is the P/Invoke call site. Needs the deferred stack-trace item, and even then it lands on `KotlinStackTrace`, not `StackTrace` |

- **Recommended: relax the test to the channel's contract** (host survives; a catchable exception
  reaches the C# caller; message preserved) and leave the type/stack assertions behind a follow-up
  that the deferred Phase 11 items un-skip. Price: edit four assertions in a test we own.
- Alternative: pull the type-mapping and stack-trace items into this run. Price: most of two more
  ADRs' worth of decisions (which .NET types map to which Kotlin types, whether the `Kotlin*`
  family is special-cased to round-trip, what carries the .NET stack), explicitly out of scope per
  the task statement.
- Alternative (cheap insurance, not recommended for v1): keep the `GCHandle` alive on the Kotlin
  exception (a `createCleaner` freeing it, the shipped `NugetObjectHandle` pattern) so a later item
  can `ExceptionDispatchInfo.Capture(original).Throw()` on the way back out and pass all four
  assertions verbatim with full identity and stack fidelity. Price now: one field plus a cleaner;
  price if skipped now: nothing, because Alternative 1 keeps the handle in the wire either way.

### Fork D: exception type granularity in Kotlin

- **Recommended: one `NugetManagedException`** with a `managedType: String`. The restatement asks
  for exactly this.
- Deferred: a Kotlin exception hierarchy mirroring ADR-029's C# `KotlinException` family in
  reverse. Purely additive: the throw site becomes a `when` over `managedType`.

## Consequences

- Every reverse thunk, every `*Bindings.kt` `CFunction` type, every `delegate* unmanaged[Cdecl]`
  cast and every generated Kotlin call site changes in one regeneration. Both halves ship in the
  same `.nupkg` from the same build, so there is no cross-version compatibility obligation; the only
  mixed-build risk is stale local state, which the tag bump plus ADR-054 turns into a loud startup
  failure.
- The per-type contract hash tag moves to `reverse_v2`, so every fixture's baked hash literal
  changes. Any test asserting a literal hash value needs regenerating.
- `nuget_runtime_register` goes 3 slots → 5 with a new runtime contract hash.
- ADR-087's Status paragraph ("still terminates the host ... tracked as its own ROADMAP Phase 11
  item") is closed by this ADR and should be updated when it lands.
- The three deferred Phase 11 exception items become runtime-accessor-only changes; none of them
  needs to touch thunk arity again. That is the main reason this envelope shape was chosen over the
  cheaper flat-string one.
- `Environment.FailFast` stays the policy for forward callback thunks (ADR-102) and for reverse
  runtime plumbing thunks until their own items land.

## Forward-direction convergence (Phase 7, not implemented here)

The Phase 7 item (`docs/backlog/csharp-callback-exception-into-kotlin.md`) is the same error
direction as this one: a **managed** exception must reach **Kotlin**. Answer: **the same envelope
shape and the same trailing-out-parameter convention are right for both, and they should not
diverge.**

- Same wire: append one trailing `IntPtr* errOut` to the ADR-102 callback thunk signatures, replace
  `Environment.FailFast(...)` with the same `*errOut = GCHandle.ToIntPtr(GCHandle.Alloc(ex))`, and
  have the Kotlin invocation site check and throw. ADR-102's ~16 shared per-signature thunks make
  this cheaper than the reverse case, not more expensive: the arity change is per *signature*, not
  per member.
- Same Kotlin-side helper and the same `NugetManagedException`, so a consumer catches one type
  regardless of whether the managed throw came from a bound NuGet method or from their own C#
  callback.

Two things the forward run would still have to build, neither of which changes the shape:

1. **Where the accessors come from.** This ADR's accessors are registered through
   `nuget_runtime_register`, which is emitted by the *nuget plugin's reverse* pipeline. A forward
   library with no NuGet dependencies has no reverse runtime at all, so the forward run needs either
   its own KSP-emitted registration or an unconditional runtime emission. ADR-087 already proved the
   source-set direction here (**verified**, ADR-087 Status): KSP emits into the per-target child
   source set (`macosArm64Main`), reverse bindings live in `nativeMain`, its parent, and a parent
   cannot see a child. A child *can* see its parent, so forward code could consume a reverse-owned
   `NugetManagedException`, but only when the reverse pipeline ran. Unconditional emission of the
   managed-error half of the runtime is the obvious answer and is a forward-run decision.
2. **What happens to the caught managed exception when it then escapes a forward `@CName` export.**
   It re-crosses to C# through the ADR-024 channel as a Kotlin exception wrapping text, i.e. the
   same round-trip-fidelity loss ADR-087 already deferred in its own direction. Same posture, same
   deferral, same eventual fix (identity preservation via the retained handle, Fork C's third
   option).

There is no forward-specific constraint that pushes toward a different envelope: the forward
callback thunks are the same `[UnmanagedCallersOnly]` statics with the same C ABI, and ADR-102's
`ctx`-echo convention is orthogonal to a trailing out-parameter.
