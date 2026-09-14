# `baseClassOverridee`'s fallback is name-only against the base's unsubstituted `getAllFunctions()`

- ROADMAP: line text as of 2026-09-14: "Inferred: `baseClassOverridee`'s fallback (`ForwardClassMembership.kt` ~:349-372) is still name-only against the base's UNSUBSTITUTED `getAllFunctions()`, so a strict key there would flip `override fun f(x: String)` over `Base<T>.f(x: T)` to `virtual` incorrectly; it needs ADR-082's wildcard comparison instead, a different helper from the one ADR-101's 2026-09-13 amendment gave `isDeclaredBy`."
- Researched: 2026-09-14, ~10 of 15 minutes, reading only (no spike, none load-bearing; see Findings 3 and 7)
- Restatement: forward. A Kotlin `override fun` in a subclass renders C# `override` exactly when the *base class* carries a member of matching signature, where a base-side type parameter slot matches any subclass type (ADR-082's wildcard). Kotlin declares, C# consumes.
- Verdict: fix, no ADR (149 reserved, not needed: one helper reused, no alternative with a different consumer surface). The ROADMAP's stated generic shape is a latent hazard (correct today by the direct path, wrong only if someone tightens the fallback), but the same name-only fallback has a real, non-generic mis-render today (Finding 4) that the same fix closes.

## Findings

1. **Verified by reading.** `baseClassOverridee` (`nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardClassMembership.kt:352-374`) answers in two stages: `findOverridee()` first, trusted only when the overridee's parent is `ClassKind.CLASS` (`:361-364`); otherwise the fallback `superClass.getAllFunctions().firstOrNull { it.simpleName.asString() == name }` (`:370`), properties likewise by name (`:367`). Gate at `:355`: a member without `Modifier.OVERRIDE` returns `null` before either stage.

2. **Verified by reading.** Three callers key the `override`/`virtual` pair and the setter rule on it: `ForwardCallablePlanner.kt:1019-1020` (`isCsharpOverride`, also ADR-096's synthesis gate), `CirClassTranslator.kt:612` (planned properties) and `:908` (abstract methods), `ForwardPropertyPlanner.kt:502` (`readOnlyOverrideeOwner`, CS0546 guard). One helper, four consumers: a change there moves all four together, which is the point of ADR-101's 2026-09-11 hoist.

3. **Inferred (KSP function-side `findOverridee`, same API as the property side ADR-101 probed).** For the ROADMAP shape, `open class Base<T> { open fun f(x: T) }` / `class Sub : Base<String>() { override fun f(x: String) }`, `findOverridee()` lands on `Base.f`, parented to a `CLASS`, so the direct stage answers and the fallback is never reached. If that inference is wrong and the fallback *is* reached, name-only still matches `f` and answers `override`. Either way the answer today is `override`; the name-only key is not the mis-render. The ROADMAP hazard is real only once the fallback key is tightened to `forwardSignatureKey()` (`:433-436`): the base's unsubstituted `f(x: T)` keys `["f", "T"]`, the subclass's `["f", "kotlin.String"]`, no match, `virtual`, CS0506/CS0114 territory. So: do not tighten; wildcard.

4. **Verified by reading, not reproduced by a fixture.** The fallback's name-only match over-matches today, without generics: `open class Shelf { fun groom(times: Int) }` + `interface Groomable { fun groom(): String }` + `class Ledge : Shelf(), Groomable { override fun groom() }`. `findOverridee()` answers `Groomable.groom` (interface; the shipped `Ledge` fixture at `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/ledge/Ledge.kt:15-34` relies on exactly this, pinned `virtual` by `Tier1KeptBaseInterfaceListTest.kt:84-95`), the fallback then finds `Shelf.groom(Int)` by name and answers `override`: `public override string Groom()` against a base that only has `Groom(int)`, CS0115. The shipped `Shelf` has only `height()`, so no fixture reaches it. This is the red cell for the fix (Sample test).

5. **Verified by reading.** ADR-082's wildcard comparison lives in `ForwardSupertypeMembers` (`ForwardCallablePlanner.kt:343-406`): `Signature(name, parameters: List<String?>)`, `null` for a supertype type-parameter position (`typeKey`, `:402-405`, delegating to `forwardTypeKey()`), match rule `inherited == null || inherited == declared` per position with equal arity (`:353-364`). It is built over `cls.getAllSuperTypes()` (interfaces included, `:368-370`), has a private constructor, and does not key the extension receiver, unlike `forwardSignatureKey()`. So the *comparison* is reusable; the *class* is not the right container (the fallback needs the base class chain only, and the receiver in the key to stay consistent with `isDeclaredBy`).

6. **Verified by reading.** `isDeclaredBy` (`ForwardClassMembership.kt:407-421`) is strict by design (ADR-101 2026-09-13 amendment, `docs/adr/101-unexported-supertype-skip.md:720-790`): a declared and a substituted member of identical signature never co-exist in `getAllFunctions()`, so strict is right there and wildcard is right in the fallback. Two helpers, one primitive (`forwardTypeKey()`, `:442-448`).

7. **Verified by execution elsewhere (ROADMAP sibling row, `IntegrationTests/GenericBaseOverloadTests.cs:44-53` `GenericBase_CarriesNoDescribeOfItsOwn`).** A generic base's own functions have no C# carrier (`translateGenericClass` projects properties only). So the ROADMAP's generic shape cannot compile in a consumer today regardless of the `override`/`virtual` answer: `override` is CS0115 against an `F`-less `Base<T>`. The wildcard fallback only becomes consumer-observable for generics once that sibling row closes; until then the generic shape is pinned generator-side only (Tier 1 text assertion, Sample test B).

8. **Verified by reading.** Generic base classes *are* admitted as supertypes: `Parcel<T>`/`NamedParcel` and `Crate<T>`/`LabelledCrate` (`test-library/.../parcel/Parcel.kt:14-45`) render `: Parcel<string>` / `: Crate<string>` (ADR-101 2026-09-11 amendment; `LabelledCrate_StillInheritsItemFromGenericBase`). Nothing in ADR-101's unexported-supertype skip blocks the shape.

9. **Verified by reading.** `LabelledCrate.describe(tag: Int)` carries no `override`, so `baseClassOverridee` returns `null` at the `:355` gate and it renders plain (`Tier1GenericBaseOverloadTest.kt` asserts no `override string Describe`). It is not a test subject for this row; `IsVirtual`/`GetBaseDefinition` on it already pin "declared by `LabelledCrate`" (`GenericBaseOverloadTests.cs:40-41`) and would not move.

## Recommendation

Hoist ADR-082's wildcard comparison out of `ForwardSupertypeMembers` into `ForwardClassMembership.kt` beside `forwardSignatureKey()`, and make the fallback use it:

```kotlin
/** [forwardSignatureKey] with a type-parameter position wildcarded to null (ADR-082). */
internal fun KSFunctionDeclaration.forwardInheritedSignatureKey(): List<String?> =
  listOf(simpleName.asString()) +
      listOfNotNull(extensionReceiver?.resolve()?.let { it.forwardWildcardTypeKey() }) +
      parameters.map { it.type.resolve().forwardWildcardTypeKey() }

internal fun KSType.forwardWildcardTypeKey(): String? =
  if (expandAliases().declaration is KSTypeParameter) null else forwardTypeKey()

/** ADR-082's rule: equal arity, each inherited slot null (wildcard) or equal. */
internal fun List<String?>.admits(declared: List<String>): Boolean =
  size == declared.size && zip(declared).all { (inherited, own) -> inherited == null || inherited == own }
```

Fallback (`:369-370`) becomes `superClass.getAllFunctions().firstOrNull { it.forwardInheritedSignatureKey().admits(key) }` with `key = forwardSignatureKey()`. `ForwardSupertypeMembers.Signature` and its `declares(function)` delegate to the same two helpers (receiver keyed on both sides now; a supertype member extension is rare enough that this is a no-op for every fixture, but say so in the ADR-082 note). Properties stay name-only.

Priced: 2 processor files (`ForwardClassMembership.kt`, `ForwardCallablePlanner.kt`), 1 fixture file, 1 Tier 1 test file (2 cells), 1 xunit file. No ABI, no leak row (no export minted or removed).

Rejected:
- Tighten the fallback to the strict `forwardSignatureKey()`: flips the ROADMAP shape to `virtual` (Finding 3). This is the hazard the row names.
- Reuse `ForwardSupertypeMembers.of(superClass)` as-is: keys every supertype including interfaces, so `Ledge.groom()` would match `Groomable.groom` and answer `override` again; and its constructor is private.
- Drop the fallback and trust `findOverridee()` alone: loses the abstract-base shape (`Cat.speak` over `Animal : Pet` where `Animal` leaves `speak` abstract; overridee is the interface, base class renders `abstract Speak()`, C# needs `override`).
- ADR: none. One idiomatic C# answer, one existing helper; the decision is which key, and ADR-082 plus ADR-101's amendment already made it.

## Files an implementation touches

- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardClassMembership.kt` (fallback `:369-370`, new helpers beside `:433-448`, KDoc `:338-351`)
- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardCallablePlanner.kt` (`ForwardSupertypeMembers` `:343-406` delegates)
- `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/ledge/Ledge.kt` (add `fun groom(strokes: Int): String` to `Shelf`, or a sibling pair if mutating `Shelf` disturbs the nine xunit files that cite `Ledge`)
- `nuget-processor/src/test/kotlin/.../tier1/Tier1KeptBaseInterfaceListTest.kt` (cell A) and `Tier1GenericBaseClassTest.kt` or a new `Tier1BaseClassOverrideeWildcardTest.kt` (cell B)
- `IntegrationTests/InterfaceBesideBaseTests.cs` (reflection fact)
- `docs/adr/101-unexported-supertype-skip.md` (amendment note), `docs/adr/082-value-class-inherited-members.md` (helper moved), `FEATURES.md`, `ROADMAP.md` (delete this row)

## Sample test

Cell A, red today (Finding 4), Tier 1:

```kotlin
@Test
fun `an interface override beside a base-class overload of another arity renders virtual`() {
  val result = Tier1Harness.run("""
    package tier1.ledge.overload
    open class Shelf { fun groom(strokes: Int): String = "shelf x${'$'}strokes" }
    interface Groomable { fun groom(): String }
    class Ledge : Shelf(), Groomable { override fun groom(): String = "Mylo: groomed" }
  """.trimIndent())
  assertTrue(result.compiledClean, result.compileErrors.toString())
  assertContains(result.generatedCSharp, "public virtual string Groom()")
  assertFalse("override string Groom()" in result.generatedCSharp)
}
```

Cell B, green today, guards the ROADMAP shape against a strict key (Finding 3; generator-only until the generic-base-function carrier row closes, say so in its KDoc):

```kotlin
open class Base<T> { open fun f(x: T): String = "base" }
class Sub : Base<String>() { override fun f(x: String): String = "sub" }
// assertContains(cs, "public override string F(string x)")
```

xunit, `InterfaceBesideBaseTests.cs`, after the `Shelf.groom(strokes)` fixture lands:

```csharp
[Fact]
public void Groom_IsAFreshVirtualSlot_NotAnOverrideOfShelfsOverload()
{
    MethodInfo groom = typeof(Ledge).GetMethod("Groom", Type.EmptyTypes)!;
    Assert.True(groom.IsVirtual);
    Assert.Equal(typeof(Ledge), groom.GetBaseDefinition().DeclaringType);
    Assert.Equal(typeof(Shelf), typeof(Ledge).GetMethod("Groom", new[] { typeof(int) })!.DeclaringType);
}
```

## Deferred scope

- The generic shape's consumer-visible outcome (`Sub : Base<string>` with `override F(string)`) waits on the sibling ROADMAP row "a function declared on a generic base has no C# carrier" (`GenericBase_CarriesNoDescribeOfItsOwn` flip). When that closes, add the xunit `IsVirtual`/`GetBaseDefinition` fact on the generic pair; cell B is the placeholder.
- `forwardTypeKey()`'s outer-name-only limit (`List<Int>` vs `List<String>`, the neighbouring ROADMAP row) applies to the wildcard key equally; unchanged here.
- Property fallback stays name-only (properties cannot overload).

## Open what-questions

- Whether to mutate `Shelf` (nine xunit files cite `Ledge`) or add a sibling pair. Main-thread recommendation: mutate `Shelf`, since an extra `Groom(int)` on the base changes no existing assertion (all pins are on `Groom()`, `Brushes()`, `Height()`); fall back to a sibling pair if a red harness row says otherwise.
- Whether cell B (green today, pins CS0115 output generator-side) is worth the confusion. Recommendation: keep it, with a KDoc line naming the sibling row it waits on; it is the only executable statement of the ROADMAP claim.
