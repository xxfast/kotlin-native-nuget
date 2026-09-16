package io.github.xxfast.kotlin.native.nuget.test.kdoc

// ADR-150 fixture: the `expect`/`actual` row, laid out the way the ADR-074 fixture
// (`test/platform`) lays one out: the KDoc lives on the `expect` here in `nativeMain`, both
// `actual`s are bare, and the per-target files are named differently from each other. Per spike 1
// finding 5 the `actual`'s own `docString` is null, so the generated C# carries this text only if
// the expect is consulted.

/** Oreo's sunny window perch. */
expect class SunSpot {
  fun sunbeam(): String
}
