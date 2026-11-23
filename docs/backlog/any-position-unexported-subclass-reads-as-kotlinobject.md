# An unexported subclass of an exported class read at an `Any` position arrives as `KotlinObject`

Found with issue #612 (ADR-241). Verified by reading source; predates ADR-241 and was not reproduced on `main`.

`ReadAny` (`cir/CirAnyRenderer.kt:79-86`, from ADR-237, commit `ab42c6af`) looks the factory up by Kotlin class name in `KotlinTypes` and otherwise returns `new KotlinObject(handle)`. An unexported subclass of any exported class, exception or not, has no entry, so it arrives as a `KotlinObject` instead of its nearest exported type.
