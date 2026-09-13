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
 *  - the two interface-default PART cells (`flowReturnOnInterface`, `callbackParamOnInterface`):
 *    they ARE re-emitted, on the *implementing class*, and are missing only from the C# interface;
 *  - the cross-namespace generic return (`fun f(): Box<Int>` at top level) — emitted unqualified,
 *    `CS0246`;
 *  - `fun callbackParamOnClass(cb: (Int) -> Unit): Int` (non-`Unit` return) — a hard forward-ABI
 *    mismatch, so the `Unit` form is the one mirrored below.
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
   * The 29 individually-named silent cells. The three `Dock` secondary constructors are the 30th
   * to 32nd: they all render the one symbol `Dock.<init>`, so they are counted rather than
   * matched one by one ([constructorCells]).
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
    // row 17 / extra cell X2: structural own-`<T>` on an ordinary class. Only the top-level
    // structural route exists (`GenericFunctionExports.kt`), so this is a combination skip.
    structural("tier1.unrouted.Depot.structuralOnClass"),
    // extra cell X1: `SUSPEND_CALLBACK_PROTOCOL` — a `suspend` lambda parameter. Fully silent
    // today even on the one owner the ordinary lambda route does serve.
    input("tier1.unrouted.Depot.suspendCallbackParamOnClass"),
    // rows 7 / 14 / 23: the reason is carried by the collection *element*; the position is the
    // collection's own, which here is a return.
    returns("tier1.unrouted.Depot.flowElementOnClass"),
    returns("tier1.unrouted.Depot.callbackElementOnClass"),
    returns("tier1.unrouted.Depot.genericElementOnClass"),

    // --- object owner (`DepotRegistry`) -------------------------------------------------------
    // rows 3a / 13a / 22a / 18a: no legacy route is keyed to an object owner at all, so even the
    // shapes that bind on a class vanish here.
    returns("tier1.unrouted.DepotRegistry.flowReturnOnObject"),
    input("tier1.unrouted.DepotRegistry.flowParamOnObject"),
    input("tier1.unrouted.DepotRegistry.callbackParamOnObject"),
    returns("tier1.unrouted.DepotRegistry.callbackReturnOnObject"),
    returns("tier1.unrouted.DepotRegistry.genericReturnOnObject"),
    input("tier1.unrouted.DepotRegistry.genericParamOnObject"),
    structural("tier1.unrouted.DepotRegistry.structuralOnObject"),

    // --- interface-default owner (`Manifest`) -------------------------------------------------
    // `flowParamOnInterface` is not a row of its own in the observed matrix, but it is measured:
    // the C# `IManifest` declared only `int OkOnInterface();` and the only two members re-emitted
    // on the implementing class were `flowReturnOnInterface` / `callbackParamOnInterface` (the
    // PART pair, split out). So a Flow *parameter* on an interface default is silent, like
    // everywhere else a Flow sits at a parameter.
    input("tier1.unrouted.Manifest.flowParamOnInterface"),
    returns("tier1.unrouted.Manifest.genericReturnOnInterface"),
    structural("tier1.unrouted.Manifest.structuralOnInterface"),

    // --- extension owner (receiver `Depot`) ---------------------------------------------------
    // The extension planner's symbol carries no receiver: `{package}.{function}` (verified in
    // `ForwardCallablePlanner.extensionEntry`), so these names have no `Depot.` segment.
    returns("tier1.unrouted.flowReturnOnExtension"),
    input("tier1.unrouted.flowParamOnExtension"),
    input("tier1.unrouted.callbackParamOnExtension"),
    returns("tier1.unrouted.genericReturnOnExtension"),
    structural("tier1.unrouted.structuralOnExtension"),

    // --- top-level owner ----------------------------------------------------------------------
    // row 4: today this is the LIE cell — both halves emit and the C# renders `Flow<int>`, a type
    // that exists nowhere in `Interop.cs` (the class route spells the same thing `KotlinFlow<int>`)
    // -> `CS0246` in `GeneratedBindingsCheck`. There is no top-level Flow route, so the fix is to
    // name it as a skip and stop emitting, not to invent one.
    returns("tier1.unrouted.flowReturnOnTopLevel"),
    input("tier1.unrouted.flowParamOnTopLevel"),
    input("tier1.unrouted.callbackParamOnTopLevel"),
    input("tier1.unrouted.genericParamOnTopLevel"),
    // row 16: `fun <T> f(): List<T>` — the structural top-level route refuses it internally
    // (`paramIndex == -1 -> return`), and that refusal is total for the declaration, so the
    // declaration is unrouted at its return.
    returns("tier1.unrouted.structuralRefusedOnTopLevel"),
  )

  /** Row 24: three secondary constructors, one per reason, beside a good primary. */
  private val constructorCells: Int = 3

  /**
   * The two interface-default cells that ARE re-emitted on the implementing class. They are
   * excluded from every assertion here (including the count) because whether they should be
   * declared on the C# interface or named as a skip is the split-out decision, not this item's.
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
    // The PART pair binds through the class-owner routes, so this reclassification must not sweep
    // it up: a skip naming a member the consumer can still call (through `ManifestDesk`) is a
    // false positive. Asserted against every warning, not just [named] — they are not in [cells],
    // so filtering first would make this vacuous. If the split-out later decides to declare them
    // on the C# interface *and* name the interface half, this assertion moves with that decision.
    partMembers.forEach { member ->
      assertFalse(
        result.kspWarnings.any { it.contains(member) },
        "$member is re-emitted on ManifestDesk (the split-out PART bug), so it must not become a " +
            "skip here; kspWarnings=${result.kspWarnings}",
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
      "StructuralOnClass(",
      "SuspendCallbackParamOnClass(",
      "FlowElementOnClass(",
      "CallbackElementOnClass(",
      "GenericElementOnClass(",
      "FlowReturnOnObject(",
      "FlowParamOnObject(",
      "CallbackParamOnObject(",
      "CallbackReturnOnObject(",
      "GenericReturnOnObject(",
      "GenericParamOnObject(",
      "StructuralOnObject(",
      "FlowParamOnInterface(",
      "GenericReturnOnInterface(",
      "StructuralOnInterface(",
      "FlowReturnOnExtension(",
      "FlowParamOnExtension(",
      "CallbackParamOnExtension(",
      "GenericReturnOnExtension(",
      "StructuralOnExtension(",
      "FlowReturnOnTopLevel(",
      "FlowParamOnTopLevel(",
      "CallbackParamOnTopLevel(",
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
}
