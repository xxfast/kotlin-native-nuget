package dev.other.core

/**
 * Fixture for the **undeclared-enum gate**, shape (c): a *top-level* dependency enum in a package
 * that the admission predicate never admits.
 *
 * `dev.other.core` sits outside `rootPackage` (the same negative case `Advertisement` pins for
 * classes), so this enum is never admitted and never declared — but the classifier's enum branch
 * has no membership gate, so a member typed with it is still spelled as a C# enum reference. This
 * is the `containingFile == null` half of the gate, the one that must land on
 * `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` with the `include("dev.other.core")` hint rather than the
 * generic unsupported reason.
 *
 * Named `Airwave` rather than `Band`/`Signal` because both of those are already declared types in
 * this repository's fixtures, and the C# assertions scan the assembly for the enum's *type name*.
 */
enum class Airwave { AM, FM }
