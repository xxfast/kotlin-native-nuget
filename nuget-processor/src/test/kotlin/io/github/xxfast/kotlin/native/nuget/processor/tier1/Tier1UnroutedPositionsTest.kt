package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-064 amendment (unrouted positions). `FLOW_PROTOCOL` / `CALLBACK_PROTOCOL` / `GENERIC` /
 * `SUSPEND_CALLBACK_PROTOCOL` are `droppedFromCSharp = false` legacy-route **deferrals**: the
 * planner hands the member to a legacy route instead of dropping it. Research H measured every
 * owner/position combination against a real `verify.sh` run (see
 * `research/H-observed-matrix.md`) and found that only six of those combinations have a route at
 * all. Everywhere else the member vanishes from **both** halves with **no diagnostic of any
 * kind**: `grep -c "test\.unrouted"` over `pack.log` and `NugetDiagnostics.json` was 0.
 *
 * This class is the pin for the fix: every measured-silent cell must become a *named* skip
 * (`droppedFromCSharp = true` reclassification), and every measured-routed cell must stay silent
 * and keep binding — warning about a member a consumer can still call is the false positive this
 * kind of reclassification is most likely to introduce (the `LEGACY_ROUTED_PROTOCOLS` precedent in
 * [Tier1NamedSkipDiagnosticsTest]).
 *
 * Naming, per position (the amendment's decision table):
 *  - a parameter (function / constructor / extension parameter) -> [SKIPPED_UNSUPPORTED_INPUT]
 *  - a return no route re-emits -> [SKIPPED_UNSUPPORTED_RETURN]
 *  - a structural own-`<T>` method -> [SKIPPED_UNSUPPORTED_COMBINATION]
 *  - a collection **element** carrying one of the reasons -> whichever of the two matches the
 *    position the collection itself sits at.
 *
 * Every message must name the declaration's qualified name (`tier1.unrouted.Depot.flowParamOnClass`
 * style), so the author can find it; the assertions below match on that substring, never on the
 * kind alone.
 *
 * Deliberately NOT pinned here, each a split-out bug rather than this item (see
 * `H-observed-matrix.md` sections 2 and 3):
 *  - (no longer a gap) the two interface-default PART cells (`flowReturnOnInterface`,
 *    `callbackParamOnInterface`): declared on `IManifest` since ADR-160 (the lambda) and ADR-174
 *    (the Flow, once `Manifest` is reachable); asserted in `the interface-default PART pair...`;
 *  - the cross-namespace generic return (`fun f(): Box<Int>` at top level) — emitted unqualified,
 *    `CS0246`;
 *  - `fun callbackParamOnClass(cb: (Int) -> Unit): Int` (non-`Unit` return): ADR-160 binds it off
 *    the plan, so the hard forward-ABI mismatch it used to be is gone; the `Unit` form below is
 *    still the one this class mirrors.
 *
 * `compiledClean` is deliberately not asserted: the class flow route emits `CFunction`-typed
 * subscription callbacks that `Tier1CinteropStub` does not model (the same pre-existing harness
 * limit [Tier1NamedSkipDiagnosticsTest]'s StateFlow cell documents).
 */
class Tier1UnroutedPositionsTest {

  /** One measured-silent cell: the qualified member name, and the kind its position must name. */
  private data class Cell(val member: String, val kind: ForwardDiagnosticKind)

  private fun input(member: String): Cell =
    Cell(member, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT)

  private fun returns(member: String): Cell =
    Cell(member, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN)

  private fun structural(member: String): Cell =
    Cell(member, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_COMBINATION)

  /**
   * The 24 individually-named silent cells (ADR-160 took three of the original 29 out: the object,
   * top-level and extension per-call lambda parameters all bind off the plan now, and ADR-197 two
   * more: the class and object structural own-`<T>` members). The three
   * `Dock` secondary constructors are the 30th to 32nd: they all render the one symbol
   * `Dock.<init>`, so they are counted rather than matched one by one ([constructorCells]).
   */
  private val cells: List<Cell> = listOf(
    // --- ordinary class owner (`Depot`) -------------------------------------------------------
    // row 2: a Flow binds at a class-method RETURN (pinned routed below) but not at a parameter.
    input("tier1.unrouted.Depot.flowParamOnClass"),
    // row 10: the mirror image of the routed lambda parameter — no route returns a lambda from a
    // class method.
    returns("tier1.unrouted.Depot.callbackReturnOnClass"),
    // row 21a/21b: a generic *type* (`Box<Int>`) at either position on a class.
    returns("tier1.unrouted.Depot.genericReturnOnClass"),
    input("tier1.unrouted.Depot.genericParamOnClass"),
    // extra cell X1: `SUSPEND_CALLBACK_PROTOCOL` — a `suspend` lambda parameter. Fully silent
    // today even on the one owner the ordinary lambda route does serve.
    input("tier1.unrouted.Depot.suspendCallbackParamOnClass"),
    // rows 7 / 14 / 23: the reason is carried by the collection *element*; the position is the
    // collection's own, which here is a return.
    returns("tier1.unrouted.Depot.flowElementOnClass"),
    returns("tier1.unrouted.Depot.callbackElementOnClass"),
    returns("tier1.unrouted.Depot.genericElementOnClass"),

    // --- object owner (`DepotRegistry`) -------------------------------------------------------
    // rows 3a / 22a / 18a: no LEGACY route is keyed to an object owner at all, so the Flow and
    // generic shapes that bind on a class vanish here. Row 13a (the per-call lambda parameter) is
    // no longer one of them: it is on the ADR-062 plan since ADR-160, which is keyed to the
    // position rather than to the owner kind.
    returns("tier1.unrouted.DepotRegistry.flowReturnOnObject"),
    input("tier1.unrouted.DepotRegistry.flowParamOnObject"),
    returns("tier1.unrouted.DepotRegistry.callbackReturnOnObject"),
    returns("tier1.unrouted.DepotRegistry.genericReturnOnObject"),
    input("tier1.unrouted.DepotRegistry.genericParamOnObject"),

    // --- interface-default owner (`Manifest`) -------------------------------------------------
    // `flowParamOnInterface` is not a row of its own in the observed matrix, but it is measured:
    // before ADR-160/174 the C# `IManifest` declared only `int OkOnInterface();`, and the only two
    // members bound anywhere were `flowReturnOnInterface` / `callbackParamOnInterface` (the PART
    // pair, now declared on `IManifest` itself). So a Flow *parameter* on an interface default is
    // silent, like everywhere else a Flow sits at a parameter.
    input("tier1.unrouted.Manifest.flowParamOnInterface"),
    returns("tier1.unrouted.Manifest.genericReturnOnInterface"),
    structural("tier1.unrouted.Manifest.structuralOnInterface"),

    // --- extension owner (receiver `Depot`) ---------------------------------------------------
    // The extension planner's symbol carries no receiver: `{package}.{function}` (verified in
    // `ForwardCallablePlanner.extensionEntry`), so these names have no `Depot.` segment.
    returns("tier1.unrouted.flowReturnOnExtension"),
    input("tier1.unrouted.flowParamOnExtension"),
    returns("tier1.unrouted.genericReturnOnExtension"),
    structural("tier1.unrouted.structuralOnExtension"),

    // --- top-level owner ----------------------------------------------------------------------
    // row 4: today this is the LIE cell — both halves emit and the C# renders `Flow<int>`, a type
    // that exists nowhere in `Interop.cs` (the class route spells the same thing `KotlinFlow<int>`)
    // -> `CS0246` in `GeneratedBindingsCheck`. There is no top-level Flow route, so the fix is to
    // name it as a skip and stop emitting, not to invent one.
    returns("tier1.unrouted.flowReturnOnTopLevel"),
    input("tier1.unrouted.flowParamOnTopLevel"),
    input("tier1.unrouted.genericParamOnTopLevel"),
    // row 16: `fun <T> f(): List<T>` — the structural top-level route refuses it internally
    // (`paramIndex == -1 -> return`), and that refusal is total for the declaration, so the
    // declaration is unrouted at its return.
    returns("tier1.unrouted.structuralRefusedOnTopLevel"),
  )

  /** Row 24: three secondary constructors, one per reason, beside a good primary. */
  private val constructorCells: Int = 3

  /**
   * The two interface-default cells that bind: ADR-160 declares the lambda one on `IManifest`, and
   * ADR-174 the Flow one (the fixture makes `Manifest` reachable through `makeManifest`). Excluded
   * from the skip count because they are not skips; asserted present in their own test.
   */
  private val partMembers: List<String> = listOf(
    "flowReturnOnInterface",
    "callbackParamOnInterface",
  )

  /**
   * Mirrors `test-library/.../test/unrouted/UnroutedPositionsSample.kt` and
   * `UnroutedTopLevelFlow.kt` cell for cell. `Box<T>` is declared in the fixture's own package
   * here (the cross-namespace spelling is the split-out `CS0246` bug, not a cell).
   */
  private val source: String = """
    package tier1.unrouted

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flowOf

    class Box<T>(val value: T)

    class Depot {
      fun okOnClass(): Int = 1
      fun flowReturnOnClass(): Flow<Int> = flowOf(1)
      fun flowParamOnClass(events: Flow<Int>): Int = 0
      fun callbackParamOnClass(cb: (Int) -> Unit) { cb(1) }
      fun callbackReturnOnClass(): (Int) -> Unit = {}
      fun genericReturnOnClass(): Box<Int> = Box(1)
      fun genericParamOnClass(box: Box<Int>): Int = box.value
      fun <T> structuralOnClass(value: T): T = value
      fun suspendCallbackParamOnClass(cb: suspend (Int) -> Unit): Int = if (cb === cb) 0 else 1
      fun flowElementOnClass(): List<Flow<Int>> = emptyList()
      fun callbackElementOnClass(): List<(Int) -> Unit> = emptyList()
      fun genericElementOnClass(): List<Box<Int>> = emptyList()
    }

    class Dock(val id: Int) {
      constructor(id: Int, onDockCallbackParamOnConstructor: (Int) -> Unit) : this(id)
      constructor(id: Int, flowParamOnConstructor: Flow<Int>) : this(id)
      constructor(id: Int, genericParamOnConstructor: Box<Int>) : this(id)
      fun okOnDock(): Int = id
    }

    object DepotRegistry {
      fun okOnObject(): Int = 1
      fun flowReturnOnObject(): Flow<Int> = flowOf(1)
      fun flowParamOnObject(events: Flow<Int>): Int = 0
      fun callbackParamOnObject(cb: (Int) -> Unit): Int { cb(1); return 1 }
      fun callbackReturnOnObject(): (Int) -> Unit = {}
      fun genericReturnOnObject(): Box<Int> = Box(1)
      fun genericParamOnObject(box: Box<Int>): Int = box.value
      fun <T> structuralOnObject(value: T): T = value
    }

    interface Manifest {
      fun okOnInterface(): Int = 1
      fun flowReturnOnInterface(): Flow<Int> = flowOf(1)
      fun flowParamOnInterface(events: Flow<Int>): Int = 0
      fun callbackParamOnInterface(cb: (Int) -> Unit) { cb(1) }
      fun genericReturnOnInterface(): Box<Int> = Box(1)
      fun <T> structuralOnInterface(value: T): T = value
    }

    class ManifestDesk : Manifest {
      fun okOnManifestDesk(): Int = 2
    }

    fun makeManifest(): Manifest = ManifestDesk()

    fun flowReturnOnTopLevel(): Flow<Int> = flowOf(1)
    fun flowParamOnTopLevel(events: Flow<Int>): Int = 0
    fun callbackReturnOnTopLevel(): (String) -> String = { it }
    fun callbackParamOnTopLevel(cb: (Int) -> Unit): Int { cb(1); return 1 }
    fun genericParamOnTopLevel(box: Box<Int>): Int = box.value
    fun <T> structuralOnTopLevel(value: T): T = value
    fun <T> structuralRefusedOnTopLevel(): List<T> = emptyList()

    fun Depot.flowReturnOnExtension(): Flow<Int> = flowOf(1)
    fun Depot.flowParamOnExtension(events: Flow<Int>): Int = 0
    fun Depot.callbackParamOnExtension(cb: (Int) -> Unit): Int { cb(1); return 1 }
    fun Depot.genericReturnOnExtension(): Box<Int> = Box(1)
    fun <T> Depot.structuralOnExtension(value: T): T = value
  """.trimIndent()

  // Without coroutines on KSP's own classpath the classifier sees `<ERROR TYPE: Flow>` and every
  // Flow cell would prove nothing about FLOW_PROTOCOL.
  private fun run(): Tier1Result =
    Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))

  @Test
  fun `every silently-dropped position is named by a skip diagnostic`() {
    val result: Tier1Result = run()

    cells.forEach { cell ->
      val matches: List<String> = result.kspWarnings.filter { it.contains(cell.member) }
      assertTrue(
        matches.isNotEmpty(),
        "${cell.member} vanishes from both halves today with no diagnostic at all; it must be " +
            "named by a ${cell.kind.name} skip. kspWarnings=${result.kspWarnings}",
      )
      assertTrue(
        matches.any { it.contains(cell.kind.name) },
        "expected ${cell.member} to be named by ${cell.kind.name} (its position decides the " +
            "kind); got=$matches",
      )
    }
  }

  @Test
  fun `each constructor carrying an unrouted parameter is named once`() {
    val result: Tier1Result = run()

    val constructorWarnings: List<String> = result.kspWarnings.filter {
      it.contains("tier1.unrouted.Dock.<init>")
    }
    assertEquals(
      constructorCells,
      constructorWarnings.size,
      "each of Dock's three secondary constructors carries one unrouted parameter (a lambda, a " +
          "Flow and a generic type) and must be named once; all three render the same " +
          "`Dock.<init>` symbol, so this is a count. kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      constructorWarnings.all {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name)
      },
      "a constructor parameter is an input position; got=$constructorWarnings",
    )
    // `Dock` keeps a bindable primary constructor, so the class-level warning must not fire: the
    // class is still constructible and saying otherwise would be a false alarm.
    assertFalse(
      result.kspWarnings.any { it.contains("WARNING_NO_PUBLIC_CONSTRUCTOR") },
      "Dock's primary constructor binds, so no class-level constructor warning may fire; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `each silently-dropped position is reported exactly once`() {
    val result: Tier1Result = run()

    val named: List<String> = result.kspWarnings.filter { warning ->
      cells.any { warning.contains(it.member) } || warning.contains("tier1.unrouted.Dock.<init>")
    }
    assertEquals(
      cells.size + constructorCells,
      named.size,
      "one skip per silent cell, no more (a per-parameter and a per-callable reclassification " +
          "both firing would double-report) and no fewer. named=$named",
    )
    // The PART pair binds (on `IManifest` and on `ManifestDesk`), so this reclassification must not
    // sweep it up: a skip naming a member the consumer can call is a false positive. Asserted
    // against every warning, not just [named]: they are not in [cells], so filtering first would
    // make this vacuous.
    partMembers.forEach { member ->
      assertFalse(
        result.kspWarnings.any { it.contains(member) },
        "$member binds on IManifest (ADR-160 / ADR-174), so it must not become a " +
            "skip here; kspWarnings=${result.kspWarnings}",
      )
    }
  }

  @Test
  fun `the interface-default PART pair is declared on the interface`() {
    val result: Tier1Result = run()

    listOf(
      "void CallbackParamOnInterface(Action<int> cb);",
      "KotlinFlow<int> FlowReturnOnInterface();",
    ).forEach { declaration ->
      assertTrue(
        result.generatedCSharp.contains(declaration),
        "expected IManifest to declare `$declaration`; generatedCSharp=" +
            "${result.generatedCSharp.lines().filter { it.contains("OnInterface") }}",
      )
    }
  }

  @Test
  fun `no sealed skip kind is misapplied to an ordinary owner`() {
    val result: Tier1Result = run()

    val sealedKinds: List<String> = result.kspWarnings.filter { warning ->
      warning.contains("tier1.unrouted") && warning.contains("SEALED")
    }
    assertTrue(
      sealedKinds.isEmpty(),
      "ADR-116's sealed kinds (SKIPPED_SEALED_POSITION, the SEALED_*_UNROUTED reasons) belong to " +
          "sealed hierarchies; this fixture declares none, so reusing one here would send the " +
          "author after a hierarchy that does not exist. got=$sealedKinds",
    )
  }

  @Test
  fun `every silently-dropped member is absent from the generated C#`() {
    val result: Tier1Result = run()

    // Public C# names of the silent cells, per owner. The trailing `(` also catches the private
    // `Native_*` DllImport, which is exactly the stronger absence claim: no half of the member.
    listOf(
      "FlowParamOnClass(",
      "CallbackReturnOnClass(",
      "GenericReturnOnClass(",
      "GenericParamOnClass(",
      "SuspendCallbackParamOnClass(",
      "FlowElementOnClass(",
      "CallbackElementOnClass(",
      "GenericElementOnClass(",
      "FlowReturnOnObject(",
      "FlowParamOnObject(",
      "CallbackReturnOnObject(",
      "GenericReturnOnObject(",
      "GenericParamOnObject(",
      "FlowParamOnInterface(",
      "GenericReturnOnInterface(",
      "StructuralOnInterface(",
      "FlowReturnOnExtension(",
      "FlowParamOnExtension(",
      "GenericReturnOnExtension(",
      "StructuralOnExtension(",
      "FlowReturnOnTopLevel(",
      "FlowParamOnTopLevel(",
      "GenericParamOnTopLevel(",
      "StructuralRefusedOnTopLevel(",
    ).forEach { member ->
      assertFalse(
        result.generatedCSharp.contains(member),
        "$member is skipped, so no half of it may be rendered into the C# — a named skip that " +
            "still emits is the LIE case row 4 is (today `public static Flow<int> " +
            "FlowReturnOnTopLevel()` renders against a type that does not exist)",
      )
    }
  }

  @Test
  fun `the routed class Flow return still binds and is not named as a skip`() {
    val result: Tier1Result = run()

    assertTrue(
      result.generatedCSharp.contains("public KotlinFlow<int> FlowReturnOnClass()"),
      "a Flow at a class-method return is re-emitted by the legacy class flow route; " +
          "generatedCSharp=${result.generatedCSharp.take(2000)}",
    )
    assertFalse(
      result.kspWarnings.any { it.contains("Depot.flowReturnOnClass") },
      "warning about a member the consumer can still call is a false positive; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * ADR-160: the three cells this matrix row LOST when the per-call lambda parameter moved onto the
   * ADR-062 plan. The plan is keyed to the position rather than to the owner kind, so an object
   * member, a top-level function and an extension all bind the same shape a class method does, each
   * with its own non-`Unit` return. Asserted positively, so the row cannot quietly go back to
   * refusing them, and their names are gone from the two absence lists above for the same reason.
   */
  @Test
  fun `the object top-level and extension lambda parameters bind off the plan`() {
    val result: Tier1Result = run()

    listOf(
      "public static int CallbackParamOnObject(Action<int> cb)",
      "public static int CallbackParamOnTopLevel(Action<int> cb)",
      "int CallbackParamOnExtension(",
    ).forEach { member ->
      assertTrue(
        result.generatedCSharp.contains(member),
        "ADR-160 binds this position now; expected `$member`",
      )
    }
    listOf(
      "tier1.unrouted.DepotRegistry.callbackParamOnObject",
      "tier1.unrouted.callbackParamOnTopLevel",
      "tier1.unrouted.callbackParamOnExtension",
    ).forEach { symbol ->
      assertFalse(
        result.kspWarnings.any { warning -> warning.contains(symbol) },
        "a bound member must not be named as a skip; kspWarnings=${result.kspWarnings}",
      )
    }
  }

  @Test
  fun `the routed class lambda parameter still binds and is not named as a skip`() {
    val result: Tier1Result = run()

    assertTrue(
      result.generatedCSharp.contains("public void CallbackParamOnClass(Action<int> cb)"),
      "a lambda at a class-method parameter is re-emitted by the legacy callback route (with a " +
          "`Unit` return; the `Int`-returning form is the split-out forward-ABI mismatch); " +
          "generatedCSharp=${result.generatedCSharp.take(2000)}",
    )
    assertFalse(
      result.kspWarnings.any { it.contains("Depot.callbackParamOnClass") },
      "kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `the routed top-level lambda return still binds and is not named as a skip`() {
    val result: Tier1Result = run()

    assertTrue(
      result.generatedCSharp.contains(
        "public static KotlinFunc<string, string> CallbackReturnOnTopLevel()"
      ),
      "the top-level lambda-return route exists (and is the exact mirror of the class-method " +
          "lambda return that does not); generatedCSharp=${result.generatedCSharp.take(2000)}",
    )
    assertFalse(
      result.kspWarnings.any { it.contains("tier1.unrouted.callbackReturnOnTopLevel") },
      "kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `the routed top-level structural generic still binds and is not named as a skip`() {
    val result: Tier1Result = run()

    assertTrue(
      result.generatedCSharp.contains("public static T StructuralOnTopLevel<T>(T value)"),
      "`fun <T> f(value: T): T` at top level is re-emitted as per-type exports plus a generic " +
          "C# wrapper; generatedCSharp=${result.generatedCSharp.take(2000)}",
    )
    assertTrue(
      result.generatedCSharp.contains("StructuralOnTopLevel_string_native") &&
          result.generatedCSharp.contains("StructuralOnTopLevel_int_native"),
      "the per-type dispatch is what makes this cell routed; its absence would mean the wrapper " +
          "is a shell",
    )
    assertFalse(
      result.kspWarnings.any { it.contains("tier1.unrouted.structuralOnTopLevel") },
      "kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * The positions ADR-064's 2026-09-13 amendment left silent or unmeasured: (a) a member inherited
   * from an UNEXPORTED supertype (interface or base class), named once per inheriting class; (b) a
   * value-class member no route binds; (c) a class companion member; (d) a member of an interface's
   * `companion object`, which C# has no carrier for. Plus the companions of a sealed base, a sealed
   * arm and a value class, which the planner never walks.
   *
   * `tier1.residualshidden` is a sibling of the export root, not a subpackage, so its declarations
   * are out of scope however the fixture grows.
   */
  private val residualSources: Map<String, String> = mapOf(
    "Hidden.kt" to """
      package tier1.residualshidden

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      interface Hidden {
        fun <T> tally(item: T): Int = 1
        fun feedHidden(ticks: Flow<Int>): Int = 0
        fun okHidden(): Int = 1
        fun hiddenTicks(): Flow<Int> = flowOf(1)
        fun onHidden(cb: (Int) -> Unit) { cb(1) }
      }

      open class HiddenBase {
        fun <T> weigh(item: T): Int = 1
        fun drainBase(ticks: Flow<Int>): Int = 0
        fun okBase(): Int = 2
      }

      interface HiddenPacer {
        suspend fun pace(): Int
      }
    """.trimIndent(),
    "Residuals.kt" to """
      package tier1.residuals

      import kotlinx.coroutines.flow.Flow
      import tier1.residualshidden.Hidden
      import tier1.residualshidden.HiddenBase
      import tier1.residualshidden.HiddenPacer

      class Shelf : Hidden {
        fun okShelf(): Int = 1
      }

      class Rack : Hidden {
        fun okRack(): Int = 1
      }

      class Ledge : HiddenBase() {
        fun okLedge(): Int = 3
      }

      class Crate<T>(val item: T) : HiddenPacer {
        override suspend fun pace(): Int = 1
        fun okCrate(): Int = 4
      }

      @JvmInline
      value class Tag(val label: String) {
        fun okTag(): Int = 1
        fun tagOn(cb: (Int) -> Unit) { cb(1) }
        fun <T> tagPick(item: T): Int = 1
        fun tagFeed(ticks: Flow<Int>): Int = 0
        fun tagTicker(): (Int) -> Unit = {}
        suspend fun tagSettle(): Int = 1

        companion object {
          fun tagBlank(): Int = 0
        }
      }

      class Den {
        fun okDen(): Int = 1

        companion object {
          fun okDenCompanion(): Int = 1
          fun <T> denPick(item: T): Int = 1
          fun denFeed(ticks: Flow<Int>): Int = 0
          fun denTicker(): (Int) -> Unit = {}
        }
      }

      interface Keeper {
        fun greet(): String

        companion object {
          fun summon(): Int = 1
          val all: Int = 2
          const val LIMIT: Int = 3
        }
      }

      class Warden : Keeper {
        override fun greet(): String = "hi"
      }

      sealed class Job {
        fun okJob(): Int = 1

        companion object {
          fun hire(): Int = 1
        }

        class Cook(val dish: String) : Job() {
          companion object {
            fun hireCook(): Int = 2
          }
        }

        object Idle : Job()
      }
    """.trimIndent(),
  )

  private fun runResiduals(): Tier1Result = Tier1Harness.run(
    residualSources,
    processorOptions = mapOf("nuget.rootPackage" to "tier1.residuals"),
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  /** The warnings that name [member] as the skipped declaration, not merely mention it. */
  private fun Tier1Result.warningsNaming(member: String): List<String> {
    val declaration = Regex("Skipping ${Regex.escape(member)}[(:]")
    return kspWarnings.filter { warning -> declaration.containsMatchIn(warning) }
  }

  /** C# lines that are not comments: generated `///` remarks deliberately name skipped members. */
  private fun Tier1Result.csharpDeclarations(): List<String> =
    generatedCSharp.lines().filterNot { line -> line.trimStart().startsWith("//") }

  private val residualCells: List<Cell> = listOf(
    // (a) inherited from an unexported interface, once per inheriting class.
    structural("tier1.residuals.Shelf.tally"),
    input("tier1.residuals.Shelf.feedHidden"),
    structural("tier1.residuals.Rack.tally"),
    input("tier1.residuals.Rack.feedHidden"),
    // (a) inherited from an unexported base class. Its `fun <T> weigh` binds since ADR-197 (a class
    // member re-homed onto the exported subclass), asserted in the control test.
    input("tier1.residuals.Ledge.drainBase"),
    // (a) on a generic owner: an override of an unexported interface's `suspend` member.
    structural("tier1.residuals.Crate.pace"),
    // (b) value-class members no route binds. A lambda parameter is one too: the ADR-160 plan
    // binds it on ordinary owners, not on a value class.
    structural("tier1.residuals.Tag.tagPick"),
    input("tier1.residuals.Tag.tagFeed"),
    returns("tier1.residuals.Tag.tagTicker"),
    structural("tier1.residuals.Tag.tagSettle"),
    input("tier1.residuals.Tag.tagOn"),
    // (c) class companion members (already named before this cell existed; coverage only). Its
    // `fun <T> denPick` binds since ADR-197, asserted in the control test.
    input("tier1.residuals.Den.Companion.denFeed"),
    returns("tier1.residuals.Den.Companion.denTicker"),
    // (d) interface companion members, a function, a `val` and a `const val`.
    structural("tier1.residuals.Keeper.Companion.summon"),
    property("tier1.residuals.Keeper.Companion.all"),
    property("tier1.residuals.Keeper.Companion.LIMIT"),
    // The companions no route renders: a value class's, a sealed base's, a sealed arm's.
    structural("tier1.residuals.Tag.Companion.tagBlank"),
    structural("tier1.residuals.Job.Companion.hire"),
    structural("tier1.residuals.Job.Cook.Companion.hireCook"),
  )

  private fun property(member: String): Cell =
    Cell(member, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY)

  @Test
  fun `every residual unrouted member is named exactly once`() {
    val result: Tier1Result = runResiduals()

    residualCells.forEach { cell ->
      val matches: List<String> = result.warningsNaming(cell.member)
      assertEquals(
        1,
        matches.size,
        "${cell.member} is dropped from C# and must be named exactly once; " +
            "kspWarnings=${result.kspWarnings}",
      )
      assertTrue(
        matches.single().contains(cell.kind.name),
        "expected ${cell.member} to be named by ${cell.kind.name}; got=$matches",
      )
    }
  }

  @Test
  fun `the residual control members still bind and are not named`() {
    val result: Tier1Result = runResiduals()
    val declarations: List<String> = result.csharpDeclarations()

    listOf(
      "OkShelf(", "OkRack(", "OkLedge(", "OkHidden(", "OkBase(", "OkCrate(", "OkTag(", "OkDen(",
      "OkDenCompanion(", "Greet(", "OkJob(",
    ).forEach { member ->
      assertTrue(
        declarations.any { line -> line.contains(member) },
        "$member is a control member and must still bind; generatedCSharp=" +
            result.generatedCSharp.take(4000),
      )
    }
    // An unexported interface's Flow-return and lambda-parameter defaults are re-emitted by the
    // class legacy routes on every implementing class, so the new naming must leave them alone.
    listOf("public KotlinFlow<int> HiddenTicks()", "public void OnHidden(Action<int> cb)")
      .forEach { member ->
        assertEquals(
          2,
          declarations.count { line -> line.contains(member) },
          "`$member` binds on Shelf and on Rack through the class legacy route",
        )
      }
    // ADR-197: a class member's and a class companion member's own type parameter bind.
    listOf("public int Weigh<T>(T item)", "public static int DenPick<T>(T item)").forEach {
      member ->
      assertTrue(
        declarations.any { line -> line.contains(member) },
        "`$member` binds since ADR-197; got=${declarations.filter { it.contains("<T>") }}",
      )
    }
    listOf(
      "okShelf", "okHidden", "okBase", "okTag", "okDenCompanion", "greet", "hiddenTicks",
      "onHidden", "weigh", "denPick",
    ).forEach { name ->
      assertTrue(
        result.kspWarnings.none { warning -> warning.contains(".$name") },
        "$name binds, so it must not be named; kspWarnings=${result.kspWarnings}",
      )
    }
  }

  @Test
  fun `every residual unrouted member is absent from the generated C#`() {
    val result: Tier1Result = runResiduals()
    val declarations: List<String> = result.csharpDeclarations()

    listOf(
      "Tally", "FeedHidden", "DrainBase", "TagPick", "TagFeed", "TagTicker",
      "TagSettle", "TagOn", "DenFeed", "DenTicker", "Summon", "TagBlank", "Hire",
      "HireCook",
    ).forEach { member ->
      val leaks: List<String> = declarations.filter { line ->
        Regex("\\b${member}(Async)?\\s*[(<]").containsMatchIn(line)
      }
      assertTrue(leaks.isEmpty(), "$member is skipped, so no C# may declare it; got=$leaks")
    }
    // The two interface companion properties: no C# property or constant of either name.
    listOf("All", "LIMIT", "Limit").forEach { member ->
      val leaks: List<String> = declarations.filter { line ->
        Regex("\\b(int|var)\\s+$member\\b").containsMatchIn(line)
      }
      assertTrue(leaks.isEmpty(), "$member is skipped, so no C# may declare it; got=$leaks")
    }
  }

  /**
   * ADR-197: the two structural own-`<T>` cells this matrix lost when a member function's own type
   * parameter moved onto the ADR-062 plan. Asserted positively, as the ADR-160 cells above are, so
   * the row cannot quietly go back to refusing them.
   */
  @Test
  fun `the class and object structural generic members bind off the plan`() {
    val result: Tier1Result = run()

    listOf(
      "public T StructuralOnClass<T>(T value_)",
      "public static T StructuralOnObject<T>(T value_)",
    ).forEach { member ->
      assertTrue(
        result.generatedCSharp.contains(member),
        "ADR-197 binds this position now; expected `$member`",
      )
    }
    listOf(
      "tier1.unrouted.Depot.structuralOnClass",
      "tier1.unrouted.DepotRegistry.structuralOnObject",
    ).forEach { symbol ->
      assertFalse(
        result.kspWarnings.any { warning -> warning.contains(symbol) },
        "$symbol binds, so it must not be named as a skip; kspWarnings=${result.kspWarnings}",
      )
    }
  }
}
