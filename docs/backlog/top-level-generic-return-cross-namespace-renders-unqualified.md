# A top-level function returning a generic type declared in another namespace renders an unqualified reference

**A top-level function returning a generic class declared in a different Kotlin package than the
function's own (`fun f(): Box<Int>` where `Box` lives in `test.cat` and `f` lives elsewhere) renders
`Box<int>` unqualified in the generated C#, `CS0246` in the consumer's build.** The same-package
case works: a top-level function in `Box`'s own package returning `Box<Int>` compiles today
(`WrapInBox<T>`, `Interop.cs:1404`, both declared under `namespace TestLibrary.Cat`).

The legacy top-level generic-return route (`FunctionExports.kt`/`CirFunctionTranslator.kt`, the
`kotlinReturnType = simpleName` spelling) emits the type's bare simple name with no `global::`
qualification and no `using`, unlike every other position that names a cross-package type
qualified. Verified by execution in research H (`research/H-run1-evidence/generated-bindings-check.log:3,8`):

```
Interop.cs(25323,23): error CS0246: The type or namespace name 'Box<>' could not be found
```

with the failing use under `namespace TestLibrary.Unrouted` (`H-run1-evidence/Interop.cs:25310`)
and `Box<T>`'s own declaration under `namespace TestLibrary.Cat` (`H-run1-evidence/Interop.cs:8248`).

This reproduction lives only in the research run's evidence copy: the triggering cell
(`UnroutedTopLevelGeneric.kt`) was moved to `.kt.disabled` before the final green verify specifically
because it doesn't compile, so no fixture in the shipped tree exercises it and no diagnostic names
it; a cross-package top-level generic return still fails silently (from `packNuget`'s point of view)
today. Discovered alongside [ADR-064](../adr/064-forward-unsupported-declaration-diagnostics.md)'s
2026-09-13 amendment.
