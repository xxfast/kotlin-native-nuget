# Legacy per-call and stored-callback routes admit a Kotlin builtin collection or `Any` payload

The ADR-039 subscription (`add`/`remove` interface-bridge) route now refuses a listener member
whose parameter is outside ADR-160's callback payload set, named on both halves (the
2026-09-26 amendment). The two other callback routes have no equivalent gate:

- The stored-callback route (`add*`/`remove*` over a lambda type, `translateStoredCallbackMethod`,
  `cir/CirClassTranslator.kt` around line 3621) spells a lambda's payload via
  `qualifiedElementCsType` (`cir/CirTypeMapping.kt` around line 355, the ADR-123 check). A stored
  pair over `(List<Int>) -> Unit` aborts the whole KSP round with
  `ERROR_INTERNAL_GENERATOR_FAILURE`: "Kotlin builtin kotlin.collections.List reached the
  user-type C# speller". `(Any) -> Unit` fails the same way. Verified by spike.
- The per-call route (`translateCallbackMethod`, same file, around line 3535) has no such guard
  and renders `Action<List>` for a `List`-typed lambda parameter, e.g.
  `fun onBatch(cb: (List<Int>) -> Unit)`. That this fails to compile in the generated C# is
  inferred, not spiked.

Both were found by the Tier 1 spike behind the ADR-039 amendment (finding 7 in
`interface-bridge-primitive-test.md`); which of a fixture's mixed members triggered the KSP
abort was not bisected.

## What a fix needs

- A named refusal before the route partition on both halves, mirroring
  `legacyRefusedInterfaceBridgePair`. Five call sites carry the same shape today
  (`refusedNullableLambdaPayload`): `cir/CirClassTranslator.kt` around line 920,
  `exports/ClassExports.kt:259`, `exports/LambdaParameterExports.kt:313`,
  `NugetProcessor.kt` around lines 818 and 874.
- A decided admitted set: ADR-160's `isCallbackPayload` (refuses `Char`, admits interface
  payloads) versus the stored route's own narrower hand-written test
  (`simple in KOTLIN_TO_CSHARP_PARAM && simple != "String" && simple != "Char"`,
  `CirClassTranslator.kt` around line 3424), which currently differs from ADR-160's set and from
  the ADR-039 route's.

## Scope

Supporting `List`/`Map`/`Set` as a genuine callback payload (rather than refusing it) is a larger,
separate design question: it needs a collection wire on both halves of whichever route carries it,
handle ownership per ADR-036's 2026-09-11 amendment, a `LiveHandleTests.cs` row per collection
kind, and a delegate-naming scheme that does not collide with the shared `Nuget...VoidCallback`
pool. No route decides this today; only file that as its own item if the human wants collection
payloads supported.
