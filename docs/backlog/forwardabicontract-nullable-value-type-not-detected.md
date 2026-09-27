# `ForwardAbiContract` cannot detect a nullable value type on a `DllImport`

- Discovered alongside the ADR-122 2026-09-26 amendment (issue #299 nullable scalar/`String`
  parameter fix). Verified by reading, not reproduced by a failing test.
- `ForwardAbiContract.csharpType` and `kotlinType` (`ForwardAbiContract.kt:~453`) both call
  `removeSuffix("?")` before classifying a type, so `int?` on the C# `DllImport` side and `Int` on
  the Kotlin export side both resolve to `ForwardAbiType.INT` and compare equal. The check therefore
  passes a `DllImport` declaring `int? x` against a Kotlin export declaring `x: Int`, even though
  `Nullable<int>` is not a blittable P/Invoke parameter type and such a declaration would fail at
  P/Invoke marshalling time, not at this check.
- No route in this repository emits that exact mismatch today (the ADR-122 amendment's own
  `${name}HasValue` fan-out never puts a `Nullable<T>` directly on a `DllImport`), so there is no
  reproducing fixture; this is a gap in the check's own soundness, not a currently-observed defect.
- Candidate fix: `ForwardAbiContract` should distinguish a bare value type from its `Nullable<T>`
  wrapper on the C# side before mapping to `ForwardAbiType`, rather than stripping `?` first.
