package io.github.xxfast.kotlin.native.nuget.test.issue54

/**
 * Fixture for the **nested-declaration skip**, shape (a): a *module-local* plain `class` and
 * `object` nested inside an exported class.
 *
 * The enum and interface siblings in this package ([NestedModeOwner], [NestedListenerOwner]) cover
 * the two nested kinds that already skip with a named diagnostic. Plain classes and objects are the
 * hole: every root bucket in `NugetProcessor.kt` filters `parentDeclaration == null`, so
 * [ProbeOuter.Nested] and [ProbeOuter.Marker] are never collected and **no diagnostic is emitted
 * for the declarations at all**. They vanish silently. Members typed with them do skip, but through
 * the generic `SKIPPED_UNSUPPORTED_TYPE` route whose hint says to "expose a bridgeable adapter"
 * (and `NULLABLE` at a nullable position), which points at the wrong repair: the type *is*
 * bridgeable, it is only nested.
 *
 * After the fix each nested declaration must carry its own `SKIPPED_NESTED_DECLARATION` warning,
 * and each member typed with one must skip with the `UNDECLARED_CLASS` reason and a "move it to the
 * top level" hint. What is observable from compiled C# is narrower: the declarations must not exist
 * anywhere in the assembly, the typed members must be absent, and everything around them must keep
 * binding.
 *
 * Every classifier-fed position the nested declarations can occupy, once each. The point is the
 * widest set of seams, not the fewest members, because a fixture trimmed to one position would go
 * green against a gate that only closes that one:
 * - [make], the nested **class** at a non-null **return** position,
 * - [maybe], the same class at a **nullable** return position, which today takes the `NULLABLE`
 *   branch of the generic skip and so is a different code path, not a duplicate of [make],
 * - [single], the nested **object** at a return position, a different `ClassKind` from [Nested] and
 *   the one a class-only gate would miss,
 * - [label], the **control**, an unrelated `String` property that must keep binding so a fix that
 *   drops the whole owning class is distinguishable from one that drops only the typed members.
 *
 * The `companion object` is the carve-out: it is a nested declaration by every structural test, but
 * it is how [ProbeOuter.make] reaches C# as a static factory today, so a gate that treats it like
 * [Nested] would silently delete working API. It stays here as the guard for that carve-out.
 *
 * Deliberately absent: no constructor parameter typed [Nested]. A skipped primary constructor would
 * make [ProbeOuter] unconstructible from C#, colliding with the "the owning class still constructs"
 * half of the assertion, exactly as [NestedModeOwner]'s note explains.
 *
 * Oreo (black with the white middle) probes every nested cardboard box in the house. Mylo (brown
 * and creamy) waits for Oreo to prove it is safe, then takes the box.
 */
class ProbeOuter {

  /** Module-local, nested, and therefore never declared in C#. Nullable member on purpose. */
  data class Nested(val x: String?)

  /** Module-local, nested, `OBJECT` kind: the cell a class-only gate would miss. */
  object Marker

  /** Non-null return position. */
  fun make(): Nested = Nested("n")

  /** Nullable return position: today the `NULLABLE` branch of the generic skip. */
  fun maybe(): Nested? = null

  /** Nested-object return position. */
  fun single(): Marker = Marker

  /** Control: the sibling that must survive the gate. */
  val label: String = "outer"

  companion object {
    /** Carve-out guard: a companion is nested too, but must still bridge as a static factory. */
    fun make(): ProbeOuter = ProbeOuter()
  }
}
