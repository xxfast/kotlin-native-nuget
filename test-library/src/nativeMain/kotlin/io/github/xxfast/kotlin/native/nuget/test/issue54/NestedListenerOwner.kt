package io.github.xxfast.kotlin.native.nuget.test.issue54

/**
 * Fixture for the **nested-interface gate**: a module-local `interface` nested inside an exported
 * class.
 *
 * `rootInterfaces` (`NugetProcessor.kt`) filters `parentDeclaration == null`, exactly as
 * `rootEnums` does for [NestedModeOwner.Mode], so [NestedListenerOwner.Listener] is never declared
 * as a C# interface and the reachability closure never admits a module-local declaration either.
 * Nothing in the generated C# may spell it: `interfaceType` (`ForwardBridgeTypeClassifier.kt`)
 * renders an interface as a namespace-root `I{SimpleName}`, so a leak here would not even be a
 * nested `NestedListenerOwner.IListener`, it would be a bare `TestLibrary.Issue54.IListener` that
 * no declaration backs, and `Interop.cs` would fail the consumer compile with CS0246.
 *
 * Every member typed with it must skip with a **named** diagnostic (`SKIPPED_UNSUPPORTED_TYPE` /
 * `SKIPPED_UNSUPPORTED_PROPERTY` naming `NestedListenerOwner.Listener`), the class must still
 * generate and construct, and [name] must still bind.
 *
 * Every classifier-fed position the nested interface can occupy, once each. The point is the widest
 * set of seams, not the fewest members, because a fixture trimmed to one position would go green
 * against a gate that only covers that one:
 * - [listener], the **property** position (classifier via `ForwardPropertyPlanner`), nullable, so
 *   the nullable interface path is crossed too,
 * - [attach], the **parameter** position (spelled on the way in, handle-unwrapped), non-null,
 * - [current], the **return** position (spelled on the way out), nullable,
 * - [name], the **control**, an unrelated `String` member that must keep binding so a fix that
 *   drops the whole class is distinguishable from a fix that drops only the interface-typed
 *   members.
 *
 * Deliberately absent: no constructor parameter typed [Listener]. A skipped primary constructor
 * would make [NestedListenerOwner] unconstructible from C#, which would collide with the "the
 * owning class still constructs" half of the assertion.
 *
 * Mylo listens for the treat cupboard from three rooms away. Oreo pretends not to, then beats him
 * there anyway.
 */
class NestedListenerOwner {

  /** Module-local, nested, and therefore never declared in C#. */
  interface Listener {
    fun onEvent(): String
  }

  /** Property position, nullable. */
  var listener: Listener? = null

  /** Parameter position, non-null. */
  fun attach(listener: Listener) {
    this.listener = listener
  }

  /** Return position, nullable. */
  fun current(): Listener? = listener

  /** Control: the sibling that must survive the gate. */
  val name: String = "owner"
}
