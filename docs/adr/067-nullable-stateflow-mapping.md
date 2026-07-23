# ADR-067: Nullable StateFlow mapping — `StateFlow<T?>` (nullable element) and `StateFlow<T>?` (nullable member)

## Status

Accepted

## Context

ADR-065 mapped `StateFlow<T>` → `KotlinStateFlow<T> : KotlinFlow<T>` with a synchronous
`T Value { get; }`. It **explicitly deferred** the two nullable shapes (ADR-065 "Deferred",
ROADMAP line 114):

1. **Nullable element — `StateFlow<T?>`.** The state holder always exists, but its *current value*
   can be null. `.Value` must surface `T?`, and each `await foreach` emission can be null.
2. **Nullable member — `StateFlow<T>?`.** The whole StateFlow property / return can be null. The
   consumer sees `KotlinStateFlow<T>?` and must null-check before subscribing or reading `.Value`.

These are orthogonal and can co-occur (`StateFlow<T?>?`), but each is decided independently below.

This ADR extends ADR-065 and reuses ADR-002 (two-call / presence-probe nullable convention) and
ADR-061 (the nullable-return cascade: nullable reference/`String` are single-call `IntPtr.Zero ?
null`, nullable primitive is the special case). It changes **no** ABI shape that ADR-065 shipped for
the non-null case; it adds a null channel on top.

### The two seams the current code cannot cross (verified-in-repo)

Both the `_value` getter body and the `_collect` body box the value the same way:

```kotlin
// _value export body — ClassExports.kt:434-449 (verified-in-repo)
return StableRef.create(obj.$propName.value as Any).asCPointer()

// _collect body — ClassExports.kt:379, :417 (verified-in-repo)
obj.$propName.collect { value ->
  val itemRef = StableRef.create(value as Any).asCPointer()
  onNext.invoke(itemRef, 0.toByte(), userData)
}
```

The `_value` export currently declares a **non-null** `COpaquePointer` return
(`ClassExports.kt:145`, `returns(cOpaquePointer)`), and both bodies box through `value as Any`.

- **Null cannot be boxed at `value as Any`.** *Inferred (not verified against the Kotlin/Native
  toolchain — no `konanc`/`kotlinc` in this environment).* `value as Any` is an unchecked cast of a
  possibly-null value to the **non-nullable** type `Any`; by Kotlin language semantics an `as`-cast
  of `null` to a non-nullable type throws `NullPointerException` at runtime. Independently,
  `StableRef.create` is `fun <T : Any> create(value: T)` — its receiver is `Any`, so a null current
  value has no representation to hand it. **If this claim is wrong** (e.g. the cast silently yields a
  null `Any` reference and `StableRef.create` accepts it), the null-emit guard added below is still
  correct and merely redundant; the design does not break either way, but the *reason* for the guard
  is stated as inferred. No repo code today boxes a null through this seam, so it is not verified-in-repo.

- **`NugetMarshal.FromHandle<T>` cannot unwrap a `Nullable<T>` value type.** *Verified by spike this
  session (see "Spike" below).* `FromHandle<T>` dispatches on `typeof(T) == typeof(int)` etc.
  (`CirMarshalRenderer.kt:104-183`). For `T = int?` (`Nullable<int>`), `typeof(int?) != typeof(int)`,
  so no primitive branch matches and control falls to `Activator.CreateInstance(typeof(int?), … new
  object[]{ handle })` (`:180-182`), which throws `MissingMethodException` — `Nullable<int>` has no
  `(IntPtr)` constructor. So a **non-null** nullable-primitive element crashes with today's helper.
  The zero-handle guard (`:106`, `if (handle == IntPtr.Zero) return default!`) already returns
  `null` for `T = int?` because `default(int?)` is null — so the *null* case is already handled; only
  the *non-null value-type* case is broken.

### Reference vs value element (verified-in-repo + spike)

The nullable-element treatment splits cleanly by element kind, mirroring ADR-061's split:

- **Reference element** (`StateFlow<String?>`, `StateFlow<Cat?>`): `FromHandle<string>` /
  `FromHandle<Cat>` already return `null` for `IntPtr.Zero` (`default!` for a reference type is
  null) and construct normally for a non-null handle (verified-in-repo, `:106`). So **no marshal
  change is needed** — only (a) the Kotlin side must emit a null pointer instead of boxing, and (b)
  the C# declared type gains the `?` annotation. A nullable reference element is essentially free.
- **Value element** (`StateFlow<Int?>`): needs a `Nullable<T>`-aware unwrap (the spike shows the
  current one throws). This is the one genuine new mechanism.

### `qualifiedElementCsType` erases nullability today (verified-in-repo)

`qualifiedElementCsType` (`CirTypeMapping.kt:146-156`) maps the element by simple name only; it never
consults `isMarkedNullable` and never emits `?`. So `StateFlow<Int?>` and `StateFlow<Int>` currently
produce the identical `int` element spelling. Threading the element's `isMarkedNullable` (and its
value-vs-reference kind) through to the `KotlinStateFlow<…>` type argument and the marshal-path
selection is the KSP-side change.

### How other ecosystems represent a nullable-current-value stream

- **SKIE (Touchlab, Swift).** SKIE ships a **distinct** type for the nullable-element StateFlow:
  `SkieSwiftOptionalStateFlow<T>` alongside `SkieSwiftStateFlow<T>` (named in ADR-065's SKIE survey,
  `skie.touchlab.co/features/flows`). *Inferred from docs:* its `.value` is `T?` and its
  `AsyncSequence` element is optional. Precedent for "nullable element is a separate, optional-typed
  surface, not the same non-null type."
- **Kotlin/Native ObjC / Swift export, JNI.** Per ADR-061's survey (inferred from docs): a nullable
  value crosses as a **boxed object pointer** whose `nil`/`NULL` is the null (ObjC `KotlinInt *`,
  Swift `KotlinInt?`, JNI `java.lang.Integer`). The null-pointer-means-null channel this ADR uses is
  the same idea; C# additionally has real `Nullable<T>`, so the *consumer* type is `int?`, not a box.
- **C# idiom.** A nullable current value is `int?` / `string?` / `Cat?`. A whole-stream-can-be-absent
  is `KotlinStateFlow<T>?` and the consumer null-checks before `await foreach` / `.Value`. Nothing
  exotic; these are the ordinary nullable spellings.

## Alternatives Considered

### Nullable element — how a null current value crosses

#### 1. Null pointer on the wire + `Nullable<T>`-aware unwrap for value types (chosen)

Kotlin emits `IntPtr.Zero` when `stateFlow.value` (or a collected item) is null, and a non-null
`StableRef` box otherwise. C# surfaces `KotlinStateFlow<T?>` and unwraps with a helper that
understands `Nullable<T>`.

Kotlin `_value` export (nullable element), return type widened to `COpaquePointer?`:

```kotlin
@CName("catmoodtracker_get_nickname_value")
fun export_catmoodtracker_get_nickname_value(handle: COpaquePointer): COpaquePointer? {
  val obj = handle.asStableRef<CatMoodTracker>().get()
  val v = obj.nickname.value                       // T? — may be null
  return if (v != null) StableRef.create(v).asCPointer() else null
}
```

Kotlin `_collect` body (nullable element) — a genuine null element crosses as `(null, isCancelled=0)`,
which the C# `onNext` already distinguishes from the cancel signal `(null, isCancelled=1)`:

```kotlin
obj.nickname.collect { value ->
  val itemRef = if (value != null) StableRef.create(value).asCPointer() else null
  onNext.invoke(itemRef, 0.toByte(), userData)     // isCancelled stays 0 even for a null element
}
```

C# consumer type: `KotlinStateFlow<int?>` (value) / `KotlinStateFlow<string?>` /
`KotlinStateFlow<Cat?>` (reference). `.Value` and each emission are `T?`.

- **Value element** — `.Value => FromHandleNullable<int?>(_readValue())` and the enumerator's
  `Channel<int?>` reads with the same helper. The helper detects `Nullable.GetUnderlyingType(typeof(T))`
  and dispatches to the existing per-primitive box readers, returning `null` for `IntPtr.Zero`.
- **Reference element** — `.Value => FromHandle<Cat>(_readValue())` **unchanged** (already returns
  null for zero, constructs otherwise). Only the declared type gains `?`.

**Pros:** one uniform null channel (`IntPtr.Zero`) for both `_value` and `_collect`; reuses ADR-061's
"null reference is single-call `IntPtr.Zero ? null`" verbatim for reference elements; the value-type
case adds exactly one helper that reuses the shipped box readers; `cancel` and `null element` stay
distinguishable via the existing `isCancelled` byte (verified-in-repo, `CirFlowRenderer.kt:46`).
Matches the ObjC/Swift/JNI "null pointer is the null" precedent and SKIE's optional-element surface.

**Cons:** widens the `_value` export return to `COpaquePointer?` (an ABI annotation change, not a
wire change — still one pointer); adds `FromHandleNullable<T>` (or a `Nullable<T>` branch inside
`FromHandle`) to the shared marshal helper.

#### 2. Sentinel value for null primitives

Reserve a magic value (`Int.MIN_VALUE`) to mean null, cross a plain unboxed primitive. **Rejected:**
identical to ADR-002's rejected sentinel option — steals a legal value from the domain, and does not
generalize to `String`/object elements at all. The null-pointer channel already exists for references,
so a second, incompatible null convention for primitives is strictly worse.

#### 3. Box-with-flag struct `{ bool hasValue; T value }` returned by value

A `@CStruct` single-call return. **Rejected:** ADR-002 already rejected `@CStruct` for nullables
(cinterop complexity on the Kotlin side); the whole StateFlow substrate is pointer-based, and mixing
a by-value struct return into the `_value` getter is a new ABI shape for no gain.

### Nullable member — how the whole StateFlow being null crosses

Unlike an object property, a StateFlow **does not cross as a single handle** we can null-check: it is
reconstructed C#-side from per-member `_collect` / `_value` exports (ADR-065). There is no `IntPtr`
representing "the StateFlow" whose `IntPtr.Zero` could mean "absent." So the member-presence must be
its own probe.

#### 1. Presence-probe export `_has_value` → `Boolean` + normal construction (chosen)

A new per-member export returns whether the StateFlow member is non-null. C# calls it once in the
getter; if false it returns `null` (a `KotlinStateFlow<T>?`); if true it constructs the normal
`KotlinStateFlow<T>` whose `_collect` / `_value` lambdas are the ADR-065 ones unchanged.

```kotlin
@CName("catmoodtracker_get_maybe_mood_has_value")
fun export_catmoodtracker_get_maybe_mood_has_value(handle: COpaquePointer): Boolean {
  val obj = handle.asStableRef<CatMoodTracker>().get()
  return obj.maybeMood != null                     // maybeMood: StateFlow<String>?
}
```

```csharp
public KotlinStateFlow<string>? MaybeMood
{
    get
    {
        if (_handle == IntPtr.Zero) throw new ObjectDisposedException(nameof(CatMoodTracker));
        if (!Native_GetMaybeMoodHasValue(_handle)) return null;
        return new KotlinStateFlow<string>(
            (onNext, onComplete, onError, userData) =>
                Native_GetMaybeMoodCollect(_handle, GetOrCreateScope(), onNext, onComplete, onError, userData),
            () => Native_GetMaybeMoodValue(_handle));
    }
}
```

The `_collect` and `_value` exports are the ADR-065 shapes, but reference the nullable member
defensively (`obj.maybeMood?.collect { … }` completing immediately if somehow null; `obj.maybeMood?.value`).
Because the getter probes a `val` StateFlow property, the probe is **idempotent** — the exact safety
condition ADR-002's two-call pattern and ADR-061 require, and the same idempotence the ordinary
nullable-object property getter already relies on. This is the "two-call" family (probe, then the
real calls), not a literal double-invocation of a value-producing function.

**Pros:** reuses ADR-002's presence convention and ADR-065's collect/value machinery verbatim; the
consumer sees the natural `KotlinStateFlow<T>?`; the probe is a single cheap `Boolean` crossing on a
`val`, so it is safe under the ADR-002 purity requirement. Composes with the nullable-element design
above with zero interaction (`StateFlow<T?>?` = presence probe + nullable-element `.Value`).

**Cons:** one extra export per nullable-member StateFlow (`_has_value`), and one extra P/Invoke on
each property read before construction. The `_collect`/`_value` exports gain a defensive `?.` (a null
member after a true probe should not occur, but the guard keeps a race from crashing the coroutine).

#### 2. Fold presence into a nullable `_value` return only

Skip the probe; make `_value` return `COpaquePointer?` and treat `IntPtr.Zero` as "member absent."
**Rejected:** conflates two different nulls — "the StateFlow is absent" vs "the StateFlow exists and
its current value is null" (the nullable-element case) — which are distinct and can co-occur
(`StateFlow<T?>?`). It also cannot express absence at the *subscription* surface: C# must decide
whether `MaybeMood` itself is `null` **before** touching `_collect`, and `_value` is not consulted on
the `await foreach` path at all. A dedicated presence probe keeps the two nulls separate.

#### 3. Return an always-non-null `KotlinStateFlow<T>` that yields no elements when absent

Map `StateFlow<T>?` to a non-null `KotlinStateFlow<T>` that completes immediately / throws on
`.Value` when the underlying member is null. **Rejected:** lies about nullability (GOALS #2) — the
Kotlin API said the member can be absent and the C# surface must say so too (`?`). A consumer would
have no way to distinguish "present, empty" from "absent."

## Decision

Adopt **Alternative 1 for each shape**, composed:

1. **Nullable element `StateFlow<T?>`** → `KotlinStateFlow<T?>`. Kotlin emits `IntPtr.Zero` for a
   null current value / null collected item (both `_value` and `_collect` bodies gain the
   `if (v != null) … else null` guard; the `_value` export return widens to `COpaquePointer?`). C#
   surfaces `T?`. **Reference** elements reuse the shipped `FromHandle<T>` unchanged (null pointer →
   null). **Value** elements route through a new `Nullable<T>`-aware unwrap (`FromHandleNullable<T>`,
   or a `Nullable.GetUnderlyingType` branch prepended to `FromHandle<T>`) that dispatches to the
   existing per-primitive box readers and returns `null` for `IntPtr.Zero`.

2. **Nullable member `StateFlow<T>?`** → `KotlinStateFlow<T>?`. A new per-member `_has_value`
   `Boolean` export probes member presence; the C# getter returns `null` when false, else constructs
   the ADR-065 `KotlinStateFlow<T>` unchanged. The `_collect` / `_value` bodies reference the member
   with a defensive `?.`.

3. **Both `StateFlow<T?>?`** → `KotlinStateFlow<T?>?`: the presence probe from (2) around the
   nullable-element construction from (1). No new interaction.

The `isCancelled` byte on `onNext` (verified-in-repo, `CirFlowRenderer.kt:44-49`) already
disambiguates "cancelled" (`isCancelled=1`, item ignored) from "null element" (`isCancelled=0`,
item is null) — so the collect path needs no new callback.

### KSP-side threading

- **Nullability of the element** — read `propTypeResolved.arguments.firstOrNull()?.type?.resolve()
  ?.isMarkedNullable` at the StateFlow detection sites (`CirClassTranslator.kt:148-157`,
  `ClassExports.kt:119-121, :229-231`). When true, (a) render the C# element as `T?` (extend
  `qualifiedElementCsType`, `CirTypeMapping.kt:146`, to append `?` and, for value types, produce
  `Nullable<T>` spelling), (b) select `FromHandleNullable<T>` for value elements, (c) emit the
  null-guarded `_value` / `_collect` bodies (`buildStateFlowValue*Body`, `buildFlow*CollectBody` in
  `ClassExports.kt`).
- **Nullability of the member** — read the property/return `isMarkedNullable` (the same flag
  `CirClassTranslator.kt:708, :358, :376` already read for ordinary nullable properties/returns).
  When true, emit the `_has_value` export and the `?`-typed getter with the presence check.

### Marshalling claim labelling

- *Verified-in-repo:* the `_value` / `_collect` boxing shape and non-null `COpaquePointer` return
  (`ClassExports.kt:145, :379, :417, :434-449`); `FromHandle<T>`'s zero-guard-returns-`default!`,
  primitive `typeof` dispatch, and `Activator` fallback (`CirMarshalRenderer.kt:104-183`);
  `KotlinStateFlow<T>.Value => FromHandle<T>(_readValue())` (`CirFlowRenderer.kt:143`); the
  `isCancelled` disambiguation in `onNext` (`CirFlowRenderer.kt:44-49`); nullable-object property is
  single-call `IntPtr.Zero ? null` while nullable-primitive property is ADR-002 two-call
  (`CirClassTranslator.kt:873`; ADR-061); `qualifiedElementCsType` erases nullability
  (`CirTypeMapping.kt:146-156`).
- *Verified by spike this session (dotnet, see below):* `typeof(int?) != typeof(int)`;
  `default(int?)` is null so `FromHandle<int?>(IntPtr.Zero)` returns null; `FromHandle<int?>(nonZero)`
  throws `MissingMethodException` (proves the value-type nullable element needs a new unwrap);
  `KotlinStateFlow<int?> : KotlinFlow<int?> : IAsyncEnumerable<int?>` is valid C# (`Nullable<int>` is
  a legal type argument).
- *Inferred, load-bearing, NOT spiked (no Kotlin/Native toolchain in this environment):* that
  `obj.$prop.value as Any` throws `NullPointerException` when the current value is null — hence the
  null-emit guard. **If wrong, the guard is merely redundant, not incorrect.** Separately, that the
  guarded Kotlin bodies (`if (v != null) StableRef.create(v).asCPointer() else null`, and the
  widened `COpaquePointer?` return) compile and round-trip on the real mingwX64/macosArm64 toolchain,
  and that a `[DllImport]` returning `bool` binds the `_has_value` export (the ordinary nullable
  path already ships exactly this `Boolean` export, ADR-002/061, so the adjacent evidence is strong).
  The implementing agent MUST confirm both via the walking-skeleton integration test (ADR-055/060)
  before relying on them.

### Spike (this session)

```
$ cd "$(mktemp -d)" && dotnet new console -o probe && cd probe   # net8.0
# Program.cs replicated FromHandle<T>'s typeof-dispatch + Activator fallback, then:
typeof(int?) == typeof(int): False
default(int?) is null: True
FromHandle<int?>(Zero) = null
FromHandle<int>(Zero) = 0
FromHandle<int?>(1) threw: MissingMethodException: MissingMethodException
IAsyncEnumerable<int?> compiles: True
```

This is what makes the value-type unwrap load-bearing: without a `Nullable<T>` branch, a non-null
`StateFlow<Int?>.Value` throws `MissingMethodException` at the `Activator` fallback. Reference
elements are unaffected (their `default!` is null and their non-null path constructs normally).

### Kotlin sample (test-library) — the Step 3 fixture

Extend the existing `CatMoodTracker` (`test-library/src/nativeMain/kotlin/.../cat/CatMoodTracker.kt`,
the ADR-065 fixture) so mutations stay explicit (no timers):

```kotlin
// --- nullable element: the tracker always exists, its current value can be null ---
private val _nickname: MutableStateFlow<String?> = MutableStateFlow(null)   // reference element
/** StateFlow<String?> — nullable reference element; .Value is string?, null crosses as IntPtr.Zero. */
val nickname: StateFlow<String?> = _nickname.asStateFlow()

private val _streak: MutableStateFlow<Int?> = MutableStateFlow(null)        // value element
/** StateFlow<Int?> — nullable VALUE element; needs the Nullable<int> unwrap. */
val streak: StateFlow<Int?> = _streak.asStateFlow()

fun setNickname(name: String?) { _nickname.value = name }
fun setStreak(n: Int?)         { _streak.value = n }

// --- nullable member: the whole StateFlow can be absent ---
private var _maybeMood: MutableStateFlow<String>? = null
/** StateFlow<String>? — the member itself is null until [startTracking]; then non-null. */
val maybeMood: StateFlow<String>? get() = _maybeMood?.asStateFlow()

fun startTracking(initial: String) { _maybeMood = MutableStateFlow(initial) }
fun setMaybeMood(m: String)        { _maybeMood?.value = m }

// --- both, exercised together ---
private var _maybeStreak: MutableStateFlow<Int?>? = null
/** StateFlow<Int?>? — nullable member AND nullable value element. */
val maybeStreak: StateFlow<Int?>? get() = _maybeStreak?.asStateFlow()
```

### C# consumer contract (Step 3 failing test)

```csharp
using var tracker = new CatMoodTracker("Milo");

// 1. Nullable REFERENCE element — .Value is string?, starts null.
KotlinStateFlow<string?> nick = tracker.Nickname;
Assert.Null(nick.Value);
tracker.SetNickname("Mr. Whiskers");
Assert.Equal("Mr. Whiskers", tracker.Nickname.Value);
tracker.SetNickname(null);
Assert.Null(tracker.Nickname.Value);

// 2. Nullable VALUE element — .Value is int?, null crosses correctly (not 0).
KotlinStateFlow<int?> streak = tracker.Streak;
Assert.Null(streak.Value);                 // must be null, NOT default(int)==0
tracker.SetStreak(7);
Assert.Equal(7, tracker.Streak.Value);
tracker.SetStreak(null);
Assert.Null(tracker.Streak.Value);

// 3. Nullable value element over await foreach — a null element is a real emission.
tracker.SetStreak(null);
var seen = new List<int?>();
var cts = new CancellationTokenSource();
await foreach (var s in tracker.Streak.WithCancellation(cts.Token))
{
    seen.Add(s);                           // first emission is the current value (may be null)
    if (seen.Count >= 1) cts.Cancel();
}
Assert.Null(seen[0]);

// 4. Nullable MEMBER — the whole StateFlow is null until startTracking().
Assert.Null(tracker.MaybeMood);            // KotlinStateFlow<string>? is null
tracker.StartTracking("curious");
KotlinStateFlow<string>? present = tracker.MaybeMood;
Assert.NotNull(present);
Assert.Equal("curious", present!.Value);

// 5. Both — nullable member AND nullable value element.
Assert.Null(tracker.MaybeStreak);
// (after a startTracking-style enabler) MaybeStreak becomes KotlinStateFlow<int?>? whose .Value is int?

// 6. After Dispose the getter throws (parity with ADR-065).
tracker.Dispose();
Assert.Throws<ObjectDisposedException>(() => { var _ = tracker.Nickname; });
```

## Consequences

### What changes

- **Kotlin exports.** The nullable-element `_value` export return widens to `COpaquePointer?` and its
  body (and the `_collect` body) gains the `if (v != null) … else null` guard. The nullable-member
  case adds a `_has_value` `Boolean` export and a defensive `?.` in the member's `_collect`/`_value`
  bodies. All in `ClassExports.kt` (`buildStateFlowValue*Body`, `buildFlow*CollectBody`, and the two
  StateFlow detection loops).
- **C# marshal.** A `Nullable<T>`-aware unwrap (`FromHandleNullable<T>`, or a
  `Nullable.GetUnderlyingType` branch at the top of `FromHandle<T>`) in `CirMarshalRenderer.kt`,
  reusing the shipped per-primitive box readers. Reference elements are unaffected.
- **C# type spelling.** `qualifiedElementCsType` (`CirTypeMapping.kt`) learns to append `?` for a
  nullable element and to spell value types as `Nullable<T>` / `T?`. `KotlinStateFlow<T?>` and
  `KotlinStateFlow<T>?` are the two new consumer spellings.
- **`ForwardAbiContract` (ADR-055).** Sees the widened `COpaquePointer?`/`IntPtr` on both sides (no
  wire change) and the new `_has_value` `bool` export/DllImport pair, keeping them in lockstep — no
  change to the contract check itself, same as ADR-061.
- **KotlinStateFlow<T> C# helper is unchanged** — `Value => FromHandle<T>(_readValue())` already
  works for reference nullable elements; only value nullable elements select the new unwrap at the
  generation site (the getter emits `FromHandleNullable<int?>` for that member).

### What breaks

Nothing shipped. This is purely additive: a StateFlow with a nullable element or nullable member is
currently **skipped** (the element-nullability is erased and the member-nullability path is not
generated), never mis-rendered.

### v1 scope

- **Nullable reference element** (`StateFlow<String?>`, `StateFlow<Cat?>`) — property and non-suspend
  function return. No marshal change; type annotation + null-emit guard only.
- **Nullable value element** (`StateFlow<Int?>` and the blittable numerics `FromHandle` already
  supports) — property and function return, via the `Nullable<T>` unwrap.
- **Nullable member** (`StateFlow<T>?`) — property and function return, via the `_has_value` probe.
- **Both** (`StateFlow<T?>?`) — the composition, no new mechanism.

**Coverage gap (not a bug):** the nullable-member `_has_value` presence probe is implemented
symmetrically for both a `StateFlow<T>?` property and a non-suspend function returning
`StateFlow<T>?`, but the `CatMoodTracker` fixture and `NullableStateFlowTests.cs` only exercise the
property shape (`maybeMood`, `maybeStreak`). The function-return shape compiles but has no test
coverage today; confirm it before a future feature assumes it is verified (ROADMAP line 114).
- `MutableStateFlow<T?>` / `MutableStateFlow<T>?` at those positions → the read-only
  `KotlinStateFlow<…>` view (ADR-065 unchanged).

### Deferred

- **`Boolean?` / `Char?` value elements** — the ADR-056 / ADR-061 width fragility for `bool`/`char`
  applies to the box-reader path too; confirm width or keep deferred (mirror ADR-061's scope).
- **Settable nullable `.Value`** on `MutableStateFlow<T?>` — rides ADR-065's deferred settable
  `.Value` (a C#→Kotlin write, Phase 7).
- **Nullable `SharedFlow`** — `SharedFlow` itself is deferred (ROADMAP line 108); its nullable shapes
  follow it, not this ADR.
- **`suspend fun` returning a nullable StateFlow**, **nullable StateFlow as a parameter / generic
  type argument** — mirror the corresponding non-nullable StateFlow deferrals (ADR-065) and the Flow
  nullable-member item (ROADMAP line 120).
