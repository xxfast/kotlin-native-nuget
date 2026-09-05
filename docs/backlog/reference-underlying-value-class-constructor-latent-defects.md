# A reference-underlying value-class constructor ignores `ctor.nativeArguments`, recomputes its suffix from the index, and hands an `IntPtr` to a class-typed parameter

> Extracted verbatim from `ROADMAP.md` (Phase 3).

**`CirObjectRenderer.kt`'s `renderReferenceValueClass`, the render path for a reference-underlying
value-class constructor, carries three latent defects, none exercised by any fixture today.** `:69`
recomputes `nativeSuffix` from the constructor's loop index (`if (index > 0) "_$index" else ""`)
instead of reading the already-computed `ctor.nativeSuffix`, the value the primitive-underlying
sibling path (`renderValueClass`) reads at `:34`, so the two branches could diverge on overload
numbering the moment either one changes. `:76` builds the native call arguments from the raw
`paramNames` list rather than `ctor.nativeArguments`, so an ADR-077 wire lowering (for example, an
enum-underlying constructor parameter that needs `(int)mood`) would be silently dropped from the
native call if a reference-underlying value class ever declared one. `:86` (`this(${ctor.body})`)
hands the constructor an `IntPtr` to a class-typed positional parameter.

All three are latent, not reproducible today, because a reference-underlying value class currently
emits no constructor at all: [ADR-035](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/035-value-class-primary-constructor-validation.md)
defers reference-underlying primary `init` validation, so `renderReferenceValueClass`'s constructor
loop has never actually run against a real fixture, and nothing has exercised these three lines.
Established by source reading only, not runtime-reproduced. Discovered alongside the cross-package
value-class underlying qualification fix
([ADR-066](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/066-forward-export-reachability-closure.md)),
while reading the value-class render sites for the qualification fix.
