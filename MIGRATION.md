# Forward Marshalling Centralization Migration

This is the living checklist for replacing position-specific forward marshalling with a validated
callable ABI plan shared by Kotlin export generation and C# CIR generation. Each phase must leave the
bridge working and be independently shippable.

Current status: Phases 1 through 7 complete; Phase 8 next (enum/Char returns, Map/Set collection
generalization, and remaining return/property gaps).

## Migration invariants

- [ ] Keep ordinary synchronous forward values in scope: properties, constructors, functions,
  methods, companions, objects, extensions, data-class copy operations, and value-class methods.
- [ ] Keep suspend, `Flow`, generic-declaration, lambda, callback, interface-bridge, and sealed-helper
  protocols on explicit named legacy routes.
- [ ] Route a callable's Kotlin and C# halves through either the complete legacy path or the complete
  planned path. Never split a callable between paths.
- [ ] Preserve all working native signatures during migration.
- [ ] Preserve the shipped property and top-level nullable-primitive two-call ABI.
- [ ] Preserve ADR-061's single-call method and extension `valueOut` ABI.
- [ ] Fix known unsupported type-by-position combinations only in their named migration slices.
- [ ] Fail generation with a specific diagnostic for unsupported migrated combinations. Never emit a
  pointer, raw `IntPtr`, or `0` fallback for an unknown combination.
- [ ] Derive the runtime ABI contract from the same callable plan used by both renderers.

## Target architecture

- [x] Classify alias-expanded `KSType` once into a sealed `BridgeType` model covering Unit,
  primitives, Char, String, enum, object handles, value classes, collections, nullable composition,
  specialized protocols, and unsupported types.
- [x] Model semantic transfer independently from declaration syntax:
  - flow: into Kotlin or out of Kotlin;
  - passing: value, out, or in/out;
  - evaluation: exactly once or compatibility-only legacy two-call;
  - ownership: borrowed, owned handle, or materialized.
- [ ] Retain declaration origin only for symbol naming, invocation construction, diagnostics, and
  selection of preserved legacy conventions.
- [x] Introduce a typed `ForwardCallablePlan` containing the invocation, public C# signature, native
  exports/imports, ordered ABI parameters and directions, result convention, error slot, lift/lower
  operations, ownership, cleanup, and helper requirements.
- [ ] Make composable type marshallers produce declarative value-transfer plans rather than Kotlin or
  C# source text.
- [ ] Render KotlinPoet exports from the plan and project the same plan into CIR for C# rendering.
- [ ] Derive a `ForwardAbiSignature` from the plan and compare it with both generated projections.
- [ ] Replace name-based `valueOut` and `errorOut` inference with explicit parameter direction.
- [ ] Replace declaration rescans and mutable collection-helper tracking with plan requirements.

## Implementation phases

### 1. Baseline and guardrails

- [x] Add sorted ABI snapshots for every working synchronous declaration position.
- [x] Add representative generated Kotlin and C# characterization tests.
- [x] Introduce an explicit legacy-route allowlist with no generic fallback.
- [x] Extend ABI validation beyond standalone `CirDllImport` nodes to methods, properties,
  constructors, companions, and value classes.
- [x] Confirm generated behavior is unchanged and `scripts/verify.sh` passes.

### 2. Shadow planning

- [x] Implement `BridgeType`, semantic transfer models, `ForwardCallablePlan`, validation, and helper
  requirements without changing emission.
- [x] Build plans in shadow mode and compare their normalized ABI signatures with legacy signatures.
- [x] Reject plans containing raw `KSType`, raw collections, unknown pointer fallbacks, missing
  conversions, or untyped error defaults.

### 3. Direct-value walking skeleton

- [x] Migrate Unit and blittable primitive parameters and returns for class methods and extensions.
- [x] Switch Kotlin and C# generation together for each migrated callable family.
- [x] Keep comparing plan-derived and rendered ABI signatures.

### 4. ADR-061 method and extension returns

- [x] Move object, nullable object, List, nullable String, and nullable numeric returns into shared
  result planning.
- [x] Preserve the exact single-call Boolean plus `valueOut` ABI for nullable numeric returns.
- [x] Source collection helper emission only from plan requirements.

### 5. Properties

- [x] Migrate class, top-level, extension, and companion getters and setters.
- [x] Represent shipped nullable-primitive two-call exports as `LegacyTwoCall`.
- [x] Centralize null sentinels, enum conversion, handle ownership, collection materialization, and
  setter input conversion.

### 6. Ordinary callable families

- [x] Migrate top-level functions, object methods, companion methods, constructors, secondary
  constructors, and data-class copy operations.
- [x] Preserve the shipped top-level nullable-primitive two-call ABI.
- [x] Fix object-method object returns and top-level factory returns in this slice.

### 7. Input positions

- [x] Migrate parameters across constructors, top-level functions, class methods, companion and
  object methods, extensions, and setters.
- [x] Add correct nullable String, object, and primitive parameter contracts.
- [x] Add Char, enum, object-handle, and collection parameter conversion.
- [x] Remove only the strict expected failures fixed by this slice.

### 8. Remaining synchronous categories

- [ ] Add enum and Char returns across every migrated result position.
- [ ] Generalize collection returns from List to Map, Set, and mutable variants.
- [ ] Compose nested element, key, and value marshallers and derive helper requirements from plans.
- [ ] Fix class and extension enum, Char, Map, and Set return gaps, plus Char properties.

### 9. Value-class positions

- [ ] Route value-class constructors, properties, and methods through the shared planner.
- [ ] Preserve reconstruction semantics for reference and primitive underlying types.
- [ ] Add method parameters for both underlying-type branches, closing ADR-060 cells 15 and 16.

### 10. Totality and cleanup

- [ ] Reduce the ordinary synchronous legacy allowlist to zero.
- [ ] Remove direct position-level selection of parameter/return mappings, StableRef conversion,
  collection materializers, and general `defaultValueFor` behavior.
- [ ] Remove pointer and numeric fallthroughs in favor of named KSP diagnostics.
- [ ] Keep specialized protocols behind explicit adapters rather than a catch-all fallback.
- [ ] Record the enduring architecture in an ADR referencing ADR-004, ADR-055, and ADR-061.
- [ ] Run the repository workflow serially: documenter first, then refactorer.

## Test gates

Each phase must pass its applicable gates before its checklist is advanced.

- [x] Classifier tests cover aliases, nullability, generics, exported and unexported objects, value
  classes, enum, Char, and all collection variants.
- [ ] A total marshalling matrix proves every `BridgeType` and semantic transfer pair produces either
  a valid plan or a specific diagnostic.
- [x] Negative validation tests cover unknown wire types, missing conversions, invalid call counts,
  raw collections, and missing ownership or cleanup.
- [ ] A declaration-routing matrix covers every synchronous syntax position with one direct primitive
  and one conversion-requiring type.
- [x] Nullable numeric tests assert one invocation plus `valueOut` for methods and extensions, and the
  preserved two-call signatures for properties and top-level functions.
- [ ] ABI tests detect missing, duplicated, reordered, wrongly directed, and width-mismatched
  parameters for every declaration form.
- [x] Tier 1 compiles generated Kotlin and structurally inspects generated C#.
- [ ] Consumer-side tests cover null and non-null paths, disposal, enum, Char, nullable parameters,
  Map, Set, and value-class parameters before each corresponding behavior fix.
- [x] `GeneratedBindingsCheck` passes with warnings as errors.
- [x] `scripts/verify.sh` passes from clean fixture state.

## Compatibility decisions

- No consumer configuration or public generator API is added by this migration.
- Existing working native signatures do not change.
- A new or corrected signature is allowed only for a combination that currently fails generation,
  disappears from C#, or is unusable.
- Replacing property or top-level nullable-primitive two-call conventions with exactly-once
  out-parameters requires a separate versioned ADR.
