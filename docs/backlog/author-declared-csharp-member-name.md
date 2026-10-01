# An author-declared C# name for one member, to resolve a CS0102 collision without renaming the Kotlin API (#366)

Issue [#366](https://github.com/xxfast/kotlin-native-nuget/issues/366). Since
[ADR-151](../adr/151-bytearray-mapping.md) made `ByteArray` bridge, `interface Record { val payload: Payload?;
fun payload(code: Int): ByteArray? }` fails with `ERROR_CSHARP_NAME_COLLISION` (CS0102: a property and a
method cannot share a C# name). The error is correct per [ADR-110](../adr/110-top-level-function-pascal-case.md),
but the only resolution it offers is renaming the Kotlin declaration, which in a KMP library renames it for
Android and iOS too, to satisfy a C#-only rule. `Issue112Sample.kt` hid this by using `Sequence<Int>` (refused
by name) in place of `ByteArray?` for the colliding method.

Ask: an explicit, per-member, author-declared C# name in the spirit of `@ObjCName`
([ADR-064](../adr/064-forward-unsupported-declaration-diagnostics.md) already cites it as precedent). ADR-110 and
ADR-113 reject renames the generator picks on its own ("a silently different API"); an annotation the author
writes is not silent, so that objection does not apply. Not wanted: automatic renaming or suffixing on
collision, skipping one side (ADR-055's both-halves contract), or downgrading the error to a warning.

Open what-questions for the ADR:
- Where the annotation lives. There is no annotations module. `nuget-runtime` is native-only and the plugin
  wires it to `${target}MainApi`, not `commonMain`, so a `commonMain` author cannot see it (ADR-130). A new
  common `nuget-annotations` module reverses ADR-063's "no source dependency on the export module" objection.
  The alternative is reading the stdlib's `kotlin.native.ObjCName` with the existing KSP idiom, no new module
  but an ObjC-specific annotation reused.
- Semantics: does the declared name still take the suspend `Async` suffix; must an `override` keep its
  base's name; is the name validated as a C# identifier and keyword-escaped via `toCSharpName`.

Mechanics: there is no single chokepoint for a member's C# name. PascalCase is recomputed inline at about
50 sites across `ForwardCallablePlanner.kt`, `ForwardPropertyPlanner.kt`, `ForwardInterfaceBridgePlanner.kt`,
the `ForwardCir*Projection.kt` files, `CirClassTranslator.kt`, `CirTranslator.kt`, `CirFunctionTranslator.kt`
and the collision checker's own `KotlinSpellings` in `CirMemberNameCollisions.kt`. The work is one helper
(declared name, else PascalCase) routed through those sites. The ABI is untouched: `@CName` symbols and
ADR-095 numbering derive from the Kotlin name.
