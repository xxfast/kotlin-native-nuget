# A per-call lambda-parameter class method with a non-`Unit` return fails the build

**A class method taking a per-call lambda parameter and returning anything other than `Unit`
(`fun callbackParamOnClass(cb: (Int) -> Unit): Int`) fails `packNuget` with a forward ABI
mismatch, not a silent drop or a bad C# file.** KSP reports:

```
Forward ABI mismatch for depot_callbackParamOnClass; expected depot_callbackParamOnClass(in pointer,
in pointer, in pointer, out pointer) -> pointer, actual depot_callbackParamOnClass(in pointer, in
pointer, in pointer, out pointer) -> int
```

`ForwardAbiContract.assertMatches` sets `expected` from the C# `DllImport` half and `actual` from
the Kotlin `@CName` half, so the C# half of the lambda-parameter class-method route types the
result as a handle (`pointer`) regardless of the method's declared Kotlin return type, while the
Kotlin half correctly exports the primitive `int`. The defect is in the CIR callback-member
translator that renders that C# half (`CirClassTranslator.kt`'s per-call callback-member arm); the
exact line was not established, only that the exporter's own half is correct.

It went unnoticed because every existing pin of this route (`Cat.forEachToy`, and every
per-call-lambda fixture before this one) returns `Unit`, so the C# return type happening to be
wrong was never exercised. The same failure reproduces on the interface-default form of the same
shape (a `Flow`/lambda-parameter interface default with a non-`Unit` return), one level removed:
the interface-default route re-emits through the same class-owner machinery.

There is no fixture for this today: research H's fixture changed both triggering cells to a `Unit`
return specifically to get past this build failure and measure the rest of the position matrix (see
`research/H-observed-matrix.md` section 3). Discovered alongside
[ADR-064](../adr/064-forward-unsupported-declaration-diagnostics.md)'s 2026-09-13 amendment.
