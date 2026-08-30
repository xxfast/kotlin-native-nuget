package io.github.xxfast.kotlin.native.nuget.test

import io.github.xxfast.kotlin.native.nuget.test.issue41.Issue41Thing

/**
 * Issue [#41](https://github.com/xxfast/kotlin-native-nuget/issues/41) fixture, root-package half.
 *
 * A root-namespace (`TestLibrary`) class referencing a type from a sub-namespace
 * (`TestLibrary.Issue41`). `CirTypeMapping.kt:191-202` owns the "same namespace stays bare,
 * everything else renders `global::Namespace.Name`" rule, but only the `Flow`/`StateFlow`
 * type-argument sites call it. Every other render site writes the referenced type's *simple*
 * name, so the generated `Interop.cs` is expected to emit a bare `Issue41Thing` in all of:
 *
 *  - the `Things` property type (`IReadOnlyList<Issue41Thing>`),
 *  - the constructor parameter types (`IReadOnlyList<Issue41Thing>` and `Issue41Thing`),
 *  - the getter body's `new List<Issue41Thing>(count)`,
 *  - the getter body's `NugetMarshal.FromHandle<Issue41Thing>` / `new Issue41Thing(handle)`,
 *  - the `Copy(...)` parameter and return positions,
 *
 * none of which resolve from inside `namespace TestLibrary` -- CS0246. This is the shape reported
 * in the field against `joreilly/PeopleInSpace#503`, where a root-namespace
 * `data class PeopleState(val people: List<Assignment>)` could not see `Assignment` over in the
 * `...Remote` sub-namespace.
 *
 * Deliberately kept disjoint from the sibling issue fixtures (#38/#39/#40/#42): no sealed classes,
 * no nullable properties, no `Flow`/`StateFlow`, no supertypes or interfaces. The only thing under
 * test is name qualification. The bundle is the carrier the cats travel in; Oreo rides with a
 * whole `List` of things, Mylo insists on exactly one.
 */
data class Issue41Bundle(
  val things: List<Issue41Thing>,
  val one: Issue41Thing,
)
