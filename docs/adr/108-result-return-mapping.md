# ADR-108: `Result<T>` at return positions unwraps with `getOrThrow()` onto the existing exception channel

## Status
Accepted

**As shipped, correction to this ADR's prose**: a `Result<T>` at a parameter position still skips
named, but as `SKIPPED_UNSUPPORTED_TYPE`, not `SKIPPED_UNSUPPORTED_INPUT` as the Diagnostics section
below states. `ForwardDiagnostic.kt`'s `ForwardPlanSkipReason.VALUE_CLASS` maps to
`SKIPPED_UNSUPPORTED_TYPE` regardless of position (return, property, or parameter), so a `Result`
parameter is not special-cased to the input kind. Still a named, `droppedFromCSharp = true` skip,
which is the property this ADR relied on.

## Context

GitHub #56 part 1: a class method `fun run(): Result<Unit>` is skipped with
`[nuget:SKIPPED_UNSUPPORTED_TYPE] Skipping sample.Service.run: its VALUE_CLASS type combination
is not supported`. The consumer wants to call it from C#.

Why it skips today (**verified** by reading source, cited by line):

- `kotlin.Result` is a stdlib `value class Result<out T>(internal val value: Any?)`. The
  classifier has no `kotlin.Result` line, so it falls to the `isValueClass()` branch at
  `forward/ForwardBridgeTypeClassifier.kt:122` and `valueClass()` (`:294-311`) mints
  `BridgeType.ValueClass("kotlin.Result", underlying = classify(Any?), "value")`. The type
  argument `T` is discarded (`valueClass()` never receives `type.arguments`).
- `kotlin.Any` is not in `context.exportedObjectHandles`, so the underlying is
  `Nullable(Unsupported(...))` (`:174-181`), never one of the four ordinary underlyings.
- At a return position `shapeOrNull()` routes `ValueClass` to `valueClassResultShape`
  (`forward/ForwardCallablePlanner.kt:1864`), which opens with
  `if (!type.underlying.isOrdinaryValueClassUnderlying()) return null` (`:2136`;
  `isOrdinaryValueClassUnderlying` at `:2152` admits only String/Primitive/Enum/ObjectHandle).
  `planOrSkip` then takes `result.skipReason()` (`:1380`), whose `ValueClass` arm is
  `ForwardPlanSkipReason.VALUE_CLASS` (`:2279`), rendered as `SKIPPED_UNSUPPORTED_TYPE`
  (`forward/ForwardDiagnostic.kt:226`). This is a `droppedFromCSharp = true` named skip.
- Property position: `ForwardPropertyPlanner.isPlannable`'s `ValueClass` arm (`:479-484`) admits
  the same four underlyings, so a `val r: Result<T>` property skips named. Parameter position:
  `inputSkipReason()`'s `ValueClass` arm (`:2323-2324`) likewise.

Adjacent decisions this must not collide with:

- **ADR-064 cell 23** (BUG-010): `suspend inline fun <reified T> Receiver.get(): Result<T>` is a
  named `SKIPPED_UNSUPPORTED_COMBINATION` skip, gated at `ForwardCallablePlanner.kt:1299` by
  `isUnsupportedSuspendGenericResultExtension()` (`:1335-1341`: suspend + inline + reified +
  `kotlin.Result` return). That gate runs **before** `planOrSkip`, so this ADR's rewrite (inside
  `planOrSkip`) never sees that shape. The cell's reason is `inline`+`reified`+`suspend`, not
  `Result` itself, so the two decisions are orthogonal. Unchanged.
- **ADR-077** value classes at ordinary positions: the value class crosses as its underlying wire
  value with `BOX_/UNBOX_VALUE_CLASS`. `Result` cannot ride that: its underlying is `Any?`, which
  has no wire, and its C# projection would be a `readonly record struct Result` over nothing.
  This ADR does not extend ADR-077; it lowers `Result<T>` to `T` before ADR-077's machinery runs.
- **ADR-014** value-class-own members keep a **no-errorOut ABI** (`ForwardKotlinPlanEmitter.kt:
  304-306`, verified). A `getOrThrow()` inside such an export would have no error slot to write
  and would abort the process on failure. `VALUE_CLASS`-origin plans are therefore excluded from
  the rewrite (see Scope).
- **ADR-024/028/029/030**: the synchronous exception channel. Every plan-emitted export wraps its
  invocation in `try { ... } catch (e: Throwable) { errorOut = StableRef.create(buildError(e)) }`
  (`ForwardKotlinPlanEmitter.kt:893-1010`, six body shapes, verified) and the C# projection throws
  the mapped `KotlinException` family when `errorOut != 0`. `buildError(e)` receives the `Throwable`
  itself, so ADR-029's type mapping (`IllegalArgumentException` → `KotlinArgumentException`) and
  ADR-028's cause chain apply to a `Result.failure(e)` exactly as to a thrown `e`.

GOALS.md rule 2 ("generated bridge should be C# idiomatic"). C#'s idiom for a fallible operation
is an exception, with `bool TryX(out T)` for the expected-failure case; `Result`-style structs
(`OneOf`, `LanguageExt.Either`) are niche third-party additions, not BCL patterns.

## Alternatives Considered

### 1. Unwrap with `getOrThrow()` at the return position; failure rides the shipped exception channel (chosen)

`fun run(): Result<Unit>` surfaces as `void Run()`; `fun load(): Result<String>` as
`string Load()`. The Kotlin export calls `x.run().getOrThrow()` inside the existing `try`, so a
`Result.failure(e)` is indistinguishable at the boundary from `throw e`, and C# sees the mapped
`KotlinException` subtype with `KotlinType`, `Message`, cause chain.

- Pros: **zero wire change** (the plan's `publicSignature.result` becomes `T`, the native call is
  whatever `T`'s shape already is); zero C# projection change (the projection reads
  `plan.publicSignature.result`, `ForwardCirPlanProjection.kt:28/119`, verified); every `T` the
  planner already shapes at a return position is free, including `Result<Unit>` → `void`; the C#
  idiom for failure is exactly what the consumer gets; ObjC/Swift consumers of the same Kotlin
  code already get nothing better (see Prior art).
- Cons: **semantic loss**. A Kotlin caller distinguishes `Result.failure` (expected, modelled)
  from an exception (unexpected). A C# caller cannot: both arrive as a `KotlinException`. Whether a
  C# author would rather have a `Try` shape is the human question below.

### 2. Success/error pair projection (`TryRun(out T value)` or a `(bool IsSuccess, T Value, KotlinException? Error)` struct) (deferred, additive later)

- The `bool TryRun(out string value)` C# idiom happens to match the ADR-061/079 single-call wire
  already shipped for nullable primitives (`BOOLEAN` result + `valueOut` OUT pointer + `errorOut`),
  so the *native* signature exists. But the C# body would have to **not throw** when `errorOut` is
  set and instead surface it (return `false`, or fill an `out KotlinException? error`), which is a
  new C# body shape in `ForwardCirPlanProjection` (every existing shape throws on `errorOut`), a
  new Kotlin body shape (`if (r.isSuccess) write valueOut; else write errorOut; return isSuccess`),
  and for `T` = handle/String/collection a third variant each, since `valueOut` today carries only
  primitives (`valueOutTransferType`, `:1937`). Honest price: 3 files for the primitive `T` case,
  plus one arm per `T` family for the rest, plus a naming decision (`TryRun` vs `Run` returning a
  generated struct). A generated `Result<T>` struct would additionally mint a new public type per
  `T` in the consumer namespace.
- Not chosen for v1 because the consumer ask is "call the method"; a `Try` overload can ship later
  **beside** `Run()` without touching this ADR's output (the plan model gains a second entry, not a
  changed one).

### 3. Keep the named skip, improve the hint (rejected as the whole answer)

Zero code beyond a hint string. Leaves the issue's method uncallable. The hint improvement is
folded into this ADR for the shapes that still skip (Scope).

### 4. Classifier-level mapping of `kotlin.Result` to `T` at every position (rejected)

A `kotlin.Result` line before `isValueClass()` returning `classify(T)` would admit `Result<T>`
properties and parameters too. Property: the getter would emit `obj.r` where the plan expects a
`T`, a Kotlin compile error in generated code, not a named skip. Parameter: C# would pass a `T`
where Kotlin needs a `Result<T>`; no sensible lowering. The issue asks for return position only and
ADR-105 chose the same "keep the classifier position-unaware" posture.

## Decision

Lower `Result<T>` to `T` **in the planner, at the return position of ordinary callables only**,
and mark the plan so the Kotlin emitter appends `.getOrThrow()` to the invocation expression.

### Mechanism (five touch points, all in `nuget-processor/src/main/kotlin/.../forward/`)

1. **Model** `ForwardMarshallingModel.kt`: `BridgeType.ValueClass` gains
   `val typeArguments: List<BridgeType> = emptyList()` (additive, defaulted; every existing
   construction site compiles unchanged). `ForwardInvocation` gains
   `val unwrapsKotlinResult: Boolean = false`.
2. **Classifier** `ForwardBridgeTypeClassifier.kt:294` `valueClass(...)`: receives
   `type.arguments` and fills `typeArguments` with `classify(arg.type.resolve())` for each
   argument whose `type != null`; a star projection leaves the list empty. No other classifier
   change: `kotlin.Result` still classifies as `ValueClass`, so every non-return position keeps its
   existing named skip (**verified** the gates at `ForwardPropertyPlanner.kt:479`,
   `ForwardCallablePlanner.kt:2324` test `underlying`, which stays `Nullable(Unsupported)`).
3. **Planner** `ForwardCallablePlanner.kt` `planOrSkip` (`:1343`), immediately before
   `result.shapeOrNull()` (`:1378`):

   ```kotlin
   val unwrapped: BridgeType? = result.kotlinResultPayloadOrNull(origin)
   val effectiveResult: BridgeType =
     if (unwrapped != null && unwrapped.shapeOrNull() != null) unwrapped else result
   val unwrapsKotlinResult: Boolean = effectiveResult !== result
   ```

   with

   ```kotlin
   private fun BridgeType.kotlinResultPayloadOrNull(origin: ForwardCallableOrigin): BridgeType? {
     if (this !is BridgeType.ValueClass || qualifiedName != "kotlin.Result") return null
     // ADR-014: value-class-own members have no errorOut slot; getOrThrow() there would abort.
     if (origin == ForwardCallableOrigin.VALUE_CLASS || origin == ForwardCallableOrigin.CONSTRUCTOR) return null
     return typeArguments.singleOrNull()
   }
   ```

   `effectiveResult` replaces `result` in the shape lookup, the `publicSignature.result`, and the
   skip path. The fallback rule is load-bearing: **if the payload `T` has no return shape, the
   skip stays the original `Result`'s `VALUE_CLASS` skip**, never `T`'s own reason. Otherwise a
   `Result<Shape>` (sealed payload) would take `SEALED_PROTOCOL`, a `droppedFromCSharp = false`
   legacy deferral whose re-emit in `exports/FunctionExports.kt` keys on the *declared* return
   type (`Result`, not sealed) and would therefore drop the method silently, the same defect class
   ADR-105 named for `fun x(): List<Shape>`.
   `ForwardInvocation(..., unwrapsKotlinResult = unwrapsKotlinResult)` at `planOrSkip`'s
   construction site (`:1434`, verified). The other `ForwardInvocation(` site (`:1252`) belongs to
   the `LEGACY_TWO_CALL` planner, which never runs through `planOrSkip` and whose emitter
   (`ForwardKotlinPlanEmitter.kt:20-21` early return, own `invocationExpression` call at `:253`)
   is therefore never reached by a rewritten plan; see the note under step 4.
4. **Kotlin emitter** `ForwardKotlinPlanEmitter.kt:58`: after
   `val invocation = invocationExpression(plan, receiver, arguments)`, append `.getOrThrow()` when
   `plan.invocation.unwrapsKotlinResult`. Every one of the six result bodies (`:893-1010`) takes
   `invocation` as an opaque string inside its `try`, so the throw lands in the existing `catch`.
   `Result<Unit>`: `effectiveResult == BridgeType.Unit` (`knownScalarType("kotlin.Unit")`,
   classifier `:352`), the body is `errorHandlingUnitBody`, and `x.run().getOrThrow()` is a valid
   Unit-typed statement. The emitter's own guard
   `(call.result == VOID) == (publicSignature.result == Unit)` (`:38`) holds because both sides
   now see `Unit`.
   Note on the `LEGACY_TWO_CALL` route: `topLevelNullablePrimitivePlan` (`:1131`) is selected by
   the top-level planner on the *declared* result being `Nullable(Primitive | Instant | Duration |
   Enum | has-value value class)` (`require` at `:1141-1146`, verified). A declared `Result<Int?>`
   is a `ValueClass`, so it never enters that route; it goes through `planOrSkip`, is rewritten to
   `Nullable(Primitive)`, and takes `nullableResultShape`'s ADR-061 single-call `valueOut` shape,
   the one methods and extensions already use. **Inferred**: that shape's emitter/projection bodies
   are origin-agnostic, so a top-level `Result<Int?>` compiles; the only observable difference from
   a declared top-level `Int?` is one native export instead of two.
5. **C# projection** `ForwardCirPlanProjection.kt`: **no change**. The public return type is
   `plan.publicSignature.result` (`:28`, `:119`), already `T`. `CirClassTranslator.kt:471-486`
   renders planned class methods from the plan, so the C# signature is `T Run()`.

### Consumer API

```kotlin
class Service {
  fun run(): Result<Unit> = Result.success(Unit)
  fun feed(catName: String): Result<String> =
    if (catName == "Oreo") Result.failure(IllegalArgumentException("Oreo is on a diet!"))
    else Result.success("$catName got a treat")
}
fun service(): Service = Service()
```

```csharp
var service = new Service();
service.Run();                                   // void; a Result.failure would throw
string v = service.Feed("Mylo");                 // "Mylo got a treat"
var ex = Assert.ThrowsAny<ArgumentException>(() => service.Feed("Oreo"));
Assert.IsType<KotlinArgumentException>(ex);      // ADR-029 mapping, same as a thrown exception
Assert.Equal("kotlin.IllegalArgumentException", ((IKotlinException)ex).KotlinType);
Assert.Equal("Oreo is on a diet!", ex.Message);
```

(The thrown type follows ADR-029 exactly as for a thrown exception: `IllegalArgumentException`
payloads arrive as `KotlinArgumentException : ArgumentException`, unmapped ones as `KotlinException`.
Use `ThrowsAny` + `IsType`, never `Assert.Throws<KotlinException>`, which is exact-type and fails
for a mapped subtype.)

### Diagnostics

Shapes that still skip keep `SKIPPED_UNSUPPORTED_TYPE` / `SKIPPED_UNSUPPORTED_PROPERTY` /
`SKIPPED_UNSUPPORTED_INPUT` with their existing reasons. Recommended, optional: when the skipped
type is `ValueClass("kotlin.Result", ...)`, the `SKIPPED_UNSUPPORTED_TYPE` hint should say
"`Result<T>` binds only at an ordinary return position, as `T` with failure thrown; this position
is not supported" instead of the generic value-class text. One string in `ForwardDiagnostic.kt`.

## Scope

| Shape | v1 | Route |
|---|---|---|
| `fun f(): Result<T>` on class / top-level / object / companion / extension, `T` any type with a return shape today (Unit, primitives, `Char`, `String`, enum, `Instant`, `Duration`, `ObjectHandle`, `Interface`, implementable `BoundInterface`, bridgeable `Collection`, ordinary-underlying value class, and the `Nullable` spellings `nullableResultShape` accepts) | **yes** | this ADR |
| `fun f(): Result<Unit>` (the issue's case) | **yes** | `void F()` |
| `Result<T>` where `T` has no return shape (sealed base, `Flow`, lambda, unexported type, `Result<Result<X>>`) | no | keeps `VALUE_CLASS` named skip (fallback rule above) |
| `Result<*>`, `Result<T>` with `T` a type parameter | no | `typeArguments` empty / `RawKSType`; named skip |
| Value-class-own method returning `Result<T>` | no | no-errorOut ABI (ADR-014); named skip |
| `val r: Result<T>` property, `fun f(r: Result<T>)` parameter, `List<Result<T>>`, `Flow<Result<T>>` | no | unchanged, named skips (property/input) or legacy `Flow` route untouched |
| `suspend fun f(): Result<T>` | no | legacy suspend route (`CirClassTranslator.kt:551-590`) reads KSP `returnType` directly and is not plan-routed; see open question |
| `Try`-style pair (Alternative 2) | deferred | additive later |

## Consequences

- `Service.run()` from #56 binds as `void Run()`; every `Result<T>` return over an
  already-shapeable `T` binds as `T`. No native export shape changes, no `contractHash` input
  change (forward has no hash; ADR-098 verified).
- **Semantic loss, deliberate**: `Result.failure(e)` and `throw e` are indistinguishable in C#.
  Documented in `docs/topics/bridgeable-subset.md` and `FEATURES.md` (`Result<T>` row, `→`).
- Fixture: extend `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/cat/SyncExceptions.kt`
  (or a sibling `ResultSample.kt` in the same package) with a class exposing `Result<Unit>`,
  `Result<String>`, `Result<Int>`, `Result<Cat>` and a failing branch; tests in
  `IntegrationTests/ResultReturnTests.cs` in the style of `SyncExceptionPropagationTests.cs`.
  Tier 1: a routing-matrix cell in `ForwardDeclarationRoutingMatrixTest.kt` asserting the
  `Result<Shape>` fallback keeps `VALUE_CLASS`, and an emitter test in
  `ForwardKotlinPlanEmitterTest.kt` asserting the `.getOrThrow()` suffix appears inside the `try`.
- ADR-064 cell 23 stays exactly as shipped.

## Inferred vs Verified claims

**Verified** (read in repo source this session, file:line cited above): the classifier path that
mints `ValueClass("kotlin.Result")`; the `:2136` gate and `:2279` skip reason; the property and
input gates that keep non-return positions skipping; the C# projection reading
`publicSignature.result`; `CirClassTranslator` rendering planned methods from the plan; the six
Kotlin result bodies taking `invocation` as an opaque string inside `try`; the no-errorOut ABI of
value-class-own members; the cell-23 gate running before `planOrSkip`.

**Inferred** (not built; no Gradle run this session by instruction):

- That `classify(Any?)` is `Nullable(Unsupported)` rather than something that `isPlannable`
  admits. Read from `:174-181` (`qualifiedName !in exportedObjectHandles → Unsupported`); the
  issue's own diagnostic text (`VALUE_CLASS`) is consistent with it. If wrong, a `Result` property
  would bind with a broken getter; the Tier 1 cell above is the check.
- That `x.run().getOrThrow()` compiles in the generated export for every `T` the planner shapes.
  `getOrThrow()` is a public inline stdlib member on `Result<T>` returning `T`; the generated file
  already imports nothing special for `Result`. **If wrong, the failure is a generated-Kotlin
  compile error, not silent wrong output.**
- That a `suspend fun f(): Result<T>` today renders through the legacy route with an unmapped C#
  return type (`CirClassTranslator.kt:551-590` reads KSP `returnType`). Not investigated; out of
  scope; named as an open question because if true it is a silent bad-emit adjacent to this ADR.
- That an **abstract** method `abstract fun run(): Result<Unit>` in an abstract class, which is
  not plan-routed (`CirClassTranslator.kt:495-540` renders unplanned abstract methods from KSP
  with `else -> methodReturn` → C# type `Result`), produces CS0246 today. Adjacent, not this ADR.
- Prior art claims below are from documentation, not spiked.

## Prior art (to the depth that changes the decision)

Kotlin/Native ObjC export does not model `kotlin.Result`: inline classes over a reference type
export as `id`, so a `Result<T>` return arrives in Swift as `Any?` with no way to reach the
payload or failure (Kotlin docs, [Interoperability with Swift/Objective-C](https://kotlinlang.org/docs/native-objc-interop.html);
community write-ups such as [kotlin-swift-interopedia](https://github.com/kotlin-hands-on/kotlin-swift-interopedia)
recommend replacing `Result` with a sealed type before export). Inferred: Kotlin/JS `@JsExport`
warns `NON_EXPORTABLE_TYPE` for value classes, `Result` included. So no Kotlin export target
today gives a consumer *more* than this ADR does; throwing at the boundary is strictly better than
`Any?`. KMP-NativeCoroutines surfaces suspend failures as Swift `throws`, the same "failure is the
host language's exception" posture. On the C# side the BCL models expected failure with exceptions
or `bool TryX(out T)`; `OneOf`/`LanguageExt` exist but are not what a C# developer expects from a
library's IntelliSense. This is why Alternative 2 is framed as a future `Try` overload rather than
a `Result` struct.
