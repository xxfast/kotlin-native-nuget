package io.github.xxfast.kotlin.native.nuget.test.admission

import dev.other.bykind.Tuft
import dev.other.bytype.Bedding
import dev.other.bytype.Eartag
import dev.other.bytype.PurrLevel
import dev.other.bytype.Rimguard
import dev.other.bytype.Waterbowl

/**
 * ADR-154: the in-root reacher that pulls the `admit(...)`-ed dependency types through ADR-066's
 * reachability closure, so per-type admission is exercised end to end rather than described.
 *
 * `dev.other.bytype` and `dev.other.bykind` are NOT in `include(...)`; `test-library`'s publish
 * block admits three types by qualified name and one package by prefix. So this class crosses:
 *
 * - **by-value conversion**, the enums: [Bedding] at a return position, as a parameter, as a
 *   property ([purr]), at a nullable return ([quietest]) and inside a `List<>` ([purrs]) — the
 *   five positions spike 1a proved compile, never executed until now.
 * - **handle**, the class: [Waterbowl] returned, taken as a parameter, held as a property
 *   ([spare]), nullable ([missingBowl]) and in a `List<>` ([bowls]).
 * - **prefix admission**: [tuft], whose type is admitted by the package entry alone.
 * - **absence**: [rimguard] and [eartag] must not reach C# at all. `Rimguard` is an un-admitted
 *   sibling *class* of an admitted class (ADR-154 §3, no package walk); `Eartag` is the
 *   un-admitted top-level **value class** of ROADMAP line 38, which today is spelled
 *   `global::...Eartag` with no declaration and no diagnostic. Both must be named skips whose
 *   hint reads `add admit("<qualified type>")`, and both must appear as ADR-064/#276 `<remarks>`
 *   paragraphs on this class.
 *
 * Asserted by `IntegrationTests/DependencyAdmissionTests.cs` and one `LeakTests` row.
 *
 * Oreo audits the store room nightly. Mylo audits the food shelf only.
 */

/**
 * The in-root reacher for the `admit(...)`-ed dependency types; the prose is the file-level block
 * above. Deliberately ONE paragraph: ADR-150 folds every later paragraph into the same `<remarks>`
 * as the #276 skip paragraphs, and this type's remark is asserted member by member.
 */
class Storeroom(val keeper: String) {

  // ---- admitted enums: by-value at every position -------------------------------------------

  /** Return position. */
  fun bedding(): Bedding = Bedding.FLEECE

  /** Parameter position. */
  fun describe(bedding: Bedding): String = "$keeper sleeps on ${bedding.name.lowercase()}"

  /** Property position, on the constructor-property enum (`LogLevel` shape). */
  val purr: PurrLevel = PurrLevel.RUMBLE

  /** Nullable return: `null` for the one cat who never purrs on demand. */
  fun quietest(): PurrLevel? = if (keeper == "Oreo") null else PurrLevel.SILENT

  /** Collection element. */
  fun purrs(): List<PurrLevel> = listOf(PurrLevel.SILENT, PurrLevel.CHIRRUP, PurrLevel.RUMBLE)

  /** The companion member of the admitted enum, reached through the reacher. */
  fun contented(): PurrLevel = PurrLevel.contented()

  // ---- admitted class: handle at every position ----------------------------------------------

  /** Return position: mints a handle, which is the route the `LeakTests` row hammers. */
  fun bowl(): Waterbowl = Waterbowl("$keeper's bowl")

  /** Parameter position: the handle travels back into Kotlin. */
  fun labelOf(bowl: Waterbowl): String = bowl.label

  /** Property position. */
  val spare: Waterbowl = Waterbowl("the spare bowl")

  /** Nullable return. */
  fun missingBowl(): Waterbowl? = null

  /** Collection element. */
  fun bowls(): List<Waterbowl> = listOf(Waterbowl("kitchen"), Waterbowl("landing"))

  // ---- prefix-admitted package ----------------------------------------------------------------

  /** `dev.other.bykind` is admitted by the package entry, with no type named anywhere. */
  fun tuft(): Tuft = Tuft("brown")

  // ---- un-admitted: both must be absent from C#, both named on this class ---------------------

  /**
   * Un-admitted sibling class of an admitted class: `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`, hint
   * `add admit("dev.other.bytype.Rimguard")`.
   */
  fun rimguard(): Rimguard = Rimguard("silicone")

  /**
   * ROADMAP line 38: un-admitted top-level dependency **value class**. Must be a named skip, hint
   * `add admit("dev.other.bytype.Eartag")` — never an undeclared `global::...Eartag` spelling.
   * `kotlin.Result` must keep working (ADR-108), which the `Result` fixtures next door pin.
   */
  fun eartag(): Eartag = Eartag("${keeper.lowercase()}-0001")
}
