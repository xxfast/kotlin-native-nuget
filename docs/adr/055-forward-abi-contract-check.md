# ADR-055: Forward ABI contract check — verify each generated P/Invoke against its `@CName` export at generation time

## Status

Accepted

## Context

The forward bridge emits two generated representations of every Kotlin-to-C# native crossing:

1. a C# `CirDllImport`, rendered into `Interop.cs`; and
2. a Kotlin `@CName` wrapper, rendered into `CNameExports.kt`.

**Verified (repository source):** `NugetProcessor.process()` calls `generateCNameWrappers()` and
`generateCSharpBindings()` independently. The C# path translates declarations into CIR
(`CirDllImport.parameters` plus `hasSyncErrorOut`), while the Kotlin path directly builds
KotlinPoet `FunSpec`s in the export-builder files. Neither path currently compares its ABI to the
other.

This has already caused a production-shaped defect. **Verified (repository source and regression
test):** nullable top-level functions' `_has_value` and `_value` Kotlin exports had a trailing
`errorOut: COpaquePointer?`, while their `CirDllImport`s omitted the corresponding `out IntPtr
error`. A thrown Kotlin exception consequently wrote through an argument the C# caller had not
supplied; the road map records the observed `SIGBUS`. The local regression test now asserts the
two nullable DllImports contain the error out-parameter, but only covers that one translation path.

**Inferred (Microsoft documentation, not re-verified by this ADR):** a P/Invoke declaration must
match the unmanaged implementation's calling convention and signature; mismatches can cause data
corruption or fatal crashes. This is why compiler-valid generated C# is insufficient evidence of
ABI correctness.

The reverse bridge needs ADR-054's runtime registration self-check because its C# source shims and
Kotlin native library can come from different package generations. That is not the primary forward
failure mode: both forward artifacts are generated from the same KSP invocation before packaging.
The immediate risk is a generator branch changing one representation and not the other, which is
best caught before a native library is run.

## Alternatives Considered

### 1. Generation-time ABI assertion (chosen)

Have both generator paths report a normalized ABI signature for each C# P/Invoke / Kotlin export
pair, then fail KSP generation if a pair is missing or differs in export name, return wire type,
ordered parameter wire types, or calling-convention-relevant modifiers.

The assertion is internal tooling only. It changes neither the public C# API nor the native ABI.

**Pros:**

- Fails at the source of the defect, before packaging or execution.
- Covers every future export family rather than encoding a special rule for `errorOut`.
- Does not add a branch, scalar preamble, or native call to the forward call path.
- Gives an actionable error naming the C# declaration, Kotlin export, and first differing slot.

**Cons:**

- Requires an explicit normalized ABI model because C# CIR types and KotlinPoet source types are
  not textual equivalents.
- Every new export family must register its contract, which is deliberate maintenance work.

### 2. Runtime self-check on every forward `@CName` export (rejected for v1)

Add leading version/hash parameters to each P/Invoke and verify them in each Kotlin export, in the
style of ADR-054's registration export contract.

**Rejected:** it changes every public native export ABI and can only diagnose a call that reaches
the native function. It does not prevent the same KSP invocation from generating inconsistent
source, and it adds another independently-maintained signature element to every export. ADR-054
needs this design because its two halves are compiled and cached independently; no equivalent
forward artifact boundary is established by the repository today.

### 3. Compile generated bindings only (rejected as sufficient)

Keep `GeneratedBindingsCheck` as the sole guard.

**Rejected:** compilation verifies C# syntax and managed type use, not agreement with the Kotlin
native function declaration. The nullable defect compiled successfully and failed only when an
exception exercised the missing ABI argument.

## Decision

Add a generation-time **forward ABI contract collector and assertion** in the KSP processor.
It is a build-time invariant, not a generated consumer API.

The normalized model should be intentionally wire-level, for example:

```kotlin
data class ForwardAbiSignature(
  val exportName: String,
  val result: ForwardAbiType,
  val parameters: List<ForwardAbiParameter>,
  val callingConvention: ForwardCallingConvention = CDECL,
)
```

It must preserve parameter order and direction (`in`, `out`, callback/function-pointer where
applicable) and use canonical ABI kinds rather than source spellings. For example, the C#
`out IntPtr error` and Kotlin `COpaquePointer? errorOut` must normalize to the same `out pointer`
slot. The exact Kotlin/Native C lowering for each source type is **inferred until verified by the
walking skeleton**; the contract model must encode only mappings already proved by repository
end-to-end tests, and add a test whenever a new mapping is admitted.

Both sides must register a contract under the actual exported C symbol (including nullable
`_has_value` / `_value` and helper exports). After both `generateCNameWrappers()` and
`generateCSharpBindings()` have contributed their entries, the processor compares them and reports
a KSP error for:

- an export that has only a C# P/Invoke or only a Kotlin `@CName` declaration;
- a duplicate symbol with non-identical contracts; or
- a differing result, parameter count, parameter direction, parameter wire type, or parameter
  position.

The error should include the export symbol and compact expected/actual signatures. It must stop
generation rather than warn: emitting an unverifiable ABI is unsafe.

### Source seams

**Verified (repository source):**

- `NugetProcessor.kt`: owns both generation passes and is the natural owner of one per-round
  collector plus the final assertion.
- `cir/CirModel.kt`: `CirDllImport` is the C#-side declaration seam; `CirClassRenderer.kt` adds
  `out IntPtr error` when `hasSyncErrorOut` is set.
- `cir/CirFunctionTranslator.kt:translateNullableFunction`: demonstrated forward regression seam;
  each of its two `CirDllImport`s now has `hasSyncErrorOut = true`.
- `exports/FunctionExports.kt`: demonstrated Kotlin-side seam; each nullable `FunSpec` adds
  `errorOut: COpaquePointer?`.
- The remaining files in `exports/` and `cir/` that create a `FunSpec` / `CirDllImport` are in
  scope; the check must not be implemented as a nullable-function-only special case.

The implementation may construct `ForwardAbiSignature` at translation time or derive it from the
already-created CIR/KotlinPoet declarations. Prefer a single shared pure factory per export family
where practical. Do not compare rendered source text: it is formatting-dependent and cannot
reliably infer native wire types.

## Consequences

- No C# consumer-facing API change, no package-format change, and no runtime overhead.
- A generator author who changes an export signature must update the matching contract contribution
  in the same change; a missing update fails the KSP build immediately.
- This is a guard for same-generation source drift. It does not prove an arbitrary hand-written
  P/Invoke, manually replaced native binary, or a compiler's final C ABI lowering is compatible.
  Those remain integration-test concerns.

## Verification strategy

1. Unit-test the canonicalizer directly: primitive, pointer/handle, string, nullable two-call,
   error-out, callback, and `Unit`/void shapes. Each case is **verified only when backed by an
   existing repository integration test or a new walking-skeleton test**.
2. Add negative unit tests that deliberately remove `hasSyncErrorOut`, reorder a parameter, or
   change a wire type and assert KSP reports the symbol and expected/actual signature.
3. Keep the current `CirFunctionTranslatorNullableTest` as focused regression coverage, but make
   the new contract assertion the general guard.
4. Run `scripts/verify.sh` from a clean fixture state. Include a nullable-returning function that
   throws so the existing C# `KotlinException` behavior proves the `errorOut` slot crosses the
   real ABI.
5. Optional hardening: inspect the produced native header/symbol interface or invoke the built
   library from a minimal P/Invoke probe. Any conclusion about Kotlin/Native's final exported ABI
   is **inferred** until that probe is executed on every supported target family.

## Inferred claims to validate in the walking skeleton

- `COpaquePointer?` used as an error receiver and C# `out IntPtr` have equivalent final ABI
  passing semantics on every supported Kotlin/Native target.
- All currently generated C# `CallingConvention.Cdecl` declarations match Kotlin/Native `@CName`
  exports on supported targets.
- A normalized model can faithfully cover callback/function-pointer and string-marshalling shapes
  without false positives or false negatives.
