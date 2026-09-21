# `validateKotlinSignatures` skips generic class definitions and never looks at `RirStruct` methods.

ADR-057's `error_kotlin_signature_collision` check now covers class methods, properties, and
bound-interface methods, but two routes are still uncovered:

- Generic class definitions are excluded entirely (`NugetGenerateBindingsTask.kt:6725`,
  `filterNot { it.typeParameters.isNotEmpty() }`).
- `RirStruct` methods are never examined (`filterIsInstance<RirClass>()` only).

A colliding pair on either route still emits redeclared Kotlin instead of failing generation with
the structured diagnostic. Verified by reading; the struct half was not additionally spiked.
