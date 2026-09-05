package io.github.xxfast.kotlin.native.nuget.test.issue54

/**
 * Fixture for the **undeclared-enum gate**, shape (a): a *module-local* `enum class` nested inside
 * an exported class.
 *
 * `rootEnums` (`NugetProcessor.kt`) filters `parentDeclaration == null`, so [NestedModeOwner.Mode]
 * is never declared as a C# enum, and the reachability closure never admits a module-local
 * declaration either. The forward classifier's enum branch has no membership gate, though, so
 * every member typed with it is still classified as a C# enum reference and spelled
 * `global::TestLibrary.Issue54.NestedModeOwner.Mode` — a type that does not exist. `Interop.cs`
 * then fails to compile with CS0246, taking the whole consumer build with it.
 *
 * After the gate, each enum-typed member must skip with a **named** diagnostic
 * (`SKIPPED_UNSUPPORTED_TYPE` / `UNDECLARED_ENUM` naming `NestedModeOwner.Mode`, and
 * `SKIPPED_UNSUPPORTED_PROPERTY` for the property), the class must still generate and construct,
 * and [name] must still bind.
 *
 * Every classifier-fed position the nested enum can occupy, once each — the point is the widest
 * set of seams, not the fewest members, because a fixture trimmed to one position would go green
 * against a gate that only covers that one:
 * - [mode] — **property** position (classifier via `ForwardPropertyPlanner`),
 * - [set] — **parameter** position (spelled on the way in, ordinal-unwrapped),
 * - [current] — **return** position (spelled on the way out),
 * - [name] — the **control**, an unrelated `String` member that must keep binding so a fix that
 *   drops the whole class is distinguishable from a fix that drops only the enum-typed members.
 *
 * Deliberately absent: no constructor parameter typed [Mode]. A skipped primary constructor would
 * make [NestedModeOwner] unconstructible from C#, which would collide with the "the owning class
 * still constructs" half of the assertion. That cell belongs in a Tier 1 test on the KSP output.
 *
 * Oreo owns the switch: he is either ON (yowling at 5am, black with the white middle) or OFF
 * (asleep in the laundry basket). There is no third mode, however much Mylo lobbies for one.
 */
class NestedModeOwner {

  /** Module-local, nested, and therefore never declared in C#. */
  enum class Mode { ON, OFF }

  /** Property position. */
  var mode: Mode = Mode.ON

  /** Parameter position. */
  fun set(mode: Mode) {
    this.mode = mode
  }

  /** Return position. */
  fun current(): Mode = mode

  /** Control: the sibling that must survive the gate. */
  val name: String = "owner"
}
