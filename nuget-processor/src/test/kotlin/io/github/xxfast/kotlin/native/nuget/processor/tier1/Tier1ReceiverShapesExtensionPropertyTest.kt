package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-132 receiver shapes, one slot to the right: the same lowering at the extension **property**
 * position that `Tier1ReceiverShapesExtensionTest` pins at the extension function position. An
 * INTERFACE receiver takes the ADR-084 stage-3 lifecycle (`HandleOf(receiver, out receiverOwned)`
 * on the C# side, a borrowed `StableRef` read on the Kotlin side, an ADR-135-guarded `Dispose` in
 * the `finally`), and a NULLABLE HANDLE receiver crosses as a null-or-pointer wire. The getter is
 * the new surface here: it used to be a flat body with no scope, so a minted receiver handle had
 * nowhere to be released.
 *
 * ADR-132 amendment (2026-09-20): the receiver set reached extension-FUNCTION parity. `Enum`,
 * `Uuid`, `Instant`, `Duration`, `String?`, `Uuid?`, a nullable value class over a `String` or
 * object-handle underlying, and the two handle-MINTING receivers (`Collection`, `BoundInterface`)
 * all bind here now; the cells for them are in the second half of this class. What stays a named
 * skip is exactly the has-value fan-out class, because a receiver is one ABI slot and those need
 * two -- the `fan-out receivers are still named skips` cell is the control for all four spellings.
 */
class Tier1ReceiverShapesExtensionPropertyTest {

  private val source: String = """
    package tier1.receivershapesprop

    interface Pet {
      val name: String
      val legs: Int
      fun speak(): String
    }

    class Cat(val title: String) : Pet {
      override val name: String = title
      override val legs: Int = 4
      override fun speak(): String = "Meow"
    }

    private val tags: MutableMap<String, String> = mutableMapOf()

    val Pet.summary: String get() = "${'$'}name/${'$'}legs/${'$'}{speak()}"

    val Cat?.nameOrStray: String get() = this?.title ?: "stray"

    var Pet.tag: String
      get() = tags[name] ?: "untagged"
      set(value) { tags[name] = value }

    private val quotas: MutableMap<String, Int> = mutableMapOf()

    var Pet.napQuota: Int?
      get() = quotas[name]
      set(value) { if (value == null) quotas.remove(name) else quotas[name] = value }
  """.trimIndent()

  @Test
  fun `an interface receiver reads a borrowed StableRef and compiles`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.compiledClean,
      "expected the interface and nullable handle receivers to compile; got: ${result.compileErrors}",
    )
    // Identical to the extension *function* receiver: the ADR-084 bridge object backing a
    // C#-implemented pet is itself a Kotlin `Pet`, so one read serves both implementations.
    assertContains(
      result.generated,
      "receiver.asStableRef<tier1.receivershapesprop.Pet>().get().summary",
    )
  }

  @Test
  fun `an interface receiver binds as a C# extension on the interface and disposes its transfer handle`() {
    val result = Tier1Harness.run(source)
    val cs: String = result.generatedCSharp

    assertContains(cs, "public static string GetSummary(this global::Interop.IPet receiver)")
    assertContains(cs, "NugetMarshal.HandleOf(receiver, out receiverOwned)")
    // ADR-135's guard: the mint itself can throw, and this `finally` is then reached with the
    // handle still Zero. Without the scope at all, the getter leaked one StableRef per read.
    assertContains(
      cs,
      "if (receiverOwned && receiverHandle != IntPtr.Zero) { NugetMarshal.Dispose(receiverHandle); }",
    )
  }

  @Test
  fun `a nullable handle receiver binds as a C# extension on the nullable wrapper`() {
    val result = Tier1Harness.run(source)

    assertContains(
      result.generatedCSharp,
      "public static string GetNameOrStray(this global::Interop.Cat? receiver)",
    )
    // Parenthesised: `a?.b()?.c().d` binds `.d` to the safe-called result, which is not what the
    // `Cat?` extension declares its receiver to be.
    assertContains(
      result.generated,
      "(receiver?.asStableRef<tier1.receivershapesprop.Cat>()?.get()).nameOrStray",
    )
  }

  /**
   * COMPILE PIN ONLY: no runtime fixture sets a `var` over an interface receiver, so this cell is
   * where the setter half is exercised at all. Both accessors share one receiver slot, so the
   * setter's receiver mint and its value lowering have to live in the same handle scope: the
   * receiver's `Dispose` belongs in the same `finally` the value's would.
   */
  @Test
  fun `a var over an interface receiver exports both accessors through one receiver slot`() {
    val result = Tier1Harness.run(source)
    val cs: String = result.generatedCSharp

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertContains(cs, "public static string GetTag(this global::Interop.IPet receiver)")
    assertContains(
      cs,
      "public static void SetTag(this global::Interop.IPet receiver, string value)",
    )
    assertContains(
      result.generated,
      "receiver.asStableRef<tier1.receivershapesprop.Pet>().get().tag = value",
    )
  }

  /**
   * ROADMAP line 22 / ADR-135: an interface that appears **only** as an extension property's
   * receiver still has to reach the bridge factory. That arm of `reachableInterfaceNames` reads
   * the property plan's RECEIVER slot and nothing else in the fixture set exercises it.
   */
  @Test
  fun `a receiver-only interface is reachable through the property plan's RECEIVER slot`() {
    val result = Tier1Harness.run(
      """
      package tier1.propreceiverreach

      interface Sitter {
        fun house(): String
      }

      val Sitter.address: String get() = "at ${'$'}{house()}"
      """.trimIndent(),
    )

    assertContains(result.generated, "@CName(\"sitter_bridge_create\")")
    assertContains(
      result.generatedCSharp,
      "internal sealed class SitterBridgeState : NugetBridgeState",
    )
    // ROADMAP line 28: this fixture used to carry an unrelated `fun frontDoor(): String` purely to
    // open `CirTranslator.needsCoreMarshal`. Without it the module rendered `NugetMarshal.HandleOf`
    // calls and declared none of the helpers behind them.
    assertContains(result.generatedCSharp, "internal static class NugetMarshal")
    assertContains(result.generatedCSharp, "internal static class NugetErrorNative")
  }

  /**
   * The control: a has-value fan-out receiver is unrepresentable on this route (one
   * `valueParameter` mints exactly one slot), so it stays a named `SKIPPED_UNSUPPORTED_PROPERTY`
   * with no export on either side, never a half-rendered one.
   */
  @Test
  fun `a fan-out receiver is a named skip, not an export`() {
    val result = Tier1Harness.run(
      """
      package tier1.propreceiverfanout

      val Int?.orZero: Int get() = this ?: 0
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertFalse(
      result.generated.contains("orZero"),
      "a fan-out receiver must not render an export at all",
    )
    assertFalse(
      // Word-bounded, accessor prefixes included (this route would render `GetOrZero`): the marshal
      // helper is declared even for a module that exports nothing (ADR-129 amendment), and it
      // declares `HandleOfOrZero`, which a bare `contains("OrZero")` now matches.
      Regex("\\b(Get|Set)?OrZero\\b").containsMatchIn(result.generatedCSharp),
      "a fan-out receiver must not render a C# binding at all",
    )
    assertTrue(
      result.kspWarnings.any { warning -> warning.contains("orZero") },
      "the drop must name itself; got: ${result.kspWarnings}",
    )
  }

  /**
   * ROADMAP line 29: an accessor body opens on its own line, like every other generated body. The
   * projection used to pass `leadingNewline = false`, so the first statement shared the brace line
   * (`{            IntPtr receiverHandle = ...`). The assertion is whole-file because the helper
   * that produced it is shared by every planned property accessor and by the custom constructor
   * body, so an accessor left behind anywhere in this fixture fails here too.
   */
  @Test
  fun `no generated statement shares a line with an opening brace`() {
    val result = Tier1Harness.run(source)

    // `{ NugetMarshal.Dispose(handle); }` is the one legitimate same-line body and it carries
    // exactly one space; two or more spaces before a statement is this defect's signature.
    val offenders: List<String> =
      Regex("""\{ {2,}\S.*""").findAll(result.generatedCSharp).map { match -> match.value }.toList()

    assertTrue(offenders.isEmpty(), "statements sharing an opening-brace line: $offenders")
    assertContains(
      result.generatedCSharp,
      """
      |        public static string GetSummary(this global::Interop.IPet receiver)
      |        {
      |            IntPtr receiverHandle = IntPtr.Zero;
      """.trimMargin(),
    )
  }

  // ---- ADR-132 amendment (2026-09-20): receiver parity with the extension FUNCTION route ----

  /**
   * The fixture for the newly admitted by-value receivers, plus the refused controls beside them.
   * Each admitted shape crosses a *different* wire (`int` ordinal, hex-dash text, `long` ticks,
   * nullable text, nullable handle); each refused one is a has-value fan-out, which a receiver
   * slot cannot carry because there is exactly one of it.
   */
  private val paritySource: String = """
    package tier1.propreceiverparity

    import kotlin.time.Duration
    import kotlin.time.Instant
    import kotlin.uuid.Uuid

    enum class Mood { HAPPY, GRUMPY }

    class Chart(val patient: String)

    @JvmInline
    value class CatId(val id: String)

    @JvmInline
    value class ChartRef(val chart: Chart)

    @JvmInline
    value class Paws(val count: Int)

    val Mood.emoji: String get() = if (this == Mood.HAPPY) "=^.^=" else ">:("

    val Uuid.shortForm: String get() = toString().substringBefore('-')

    val Instant.epochDay: Long get() = epochSeconds.floorDiv(86400L)

    val Duration.wholeHours: Long get() = inWholeHours

    val String?.orPlaceholder: String get() = this ?: "(no cat)"

    val Uuid?.isMissing: Boolean get() = this == null

    val CatId?.display: String get() = this?.id ?: "anonymous"

    val ChartRef?.patientName: String get() = this?.chart?.patient ?: "(unfiled)"

    // Refused, all four: one receiver slot, two slots needed.
    val Int?.orZero: Int get() = this ?: 0

    val Mood?.orGrumpy: String get() = (this ?: Mood.GRUMPY).name

    val Instant?.epochOrNever: Long get() = this?.epochSeconds ?: -1L

    val Paws?.countOrZero: Int get() = this?.count ?: 0

    // `NugetMarshal` itself is emitted off a top-level function/class being present
    // (`CirTranslator.needsCoreMarshal`); a file of extensions alone calls helpers it never
    // declares.
    fun frontDoor(): String = "open"
  """.trimIndent()

  @Test
  fun `an enum receiver crosses as its int ordinal`() {
    val result = Tier1Harness.run(paritySource)
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")

    assertContains(result.generated, "tier1.propreceiverparity.Mood.entries[receiver].emoji")
    assertContains(
      result.generatedCSharp,
      "public static string GetEmoji(this global::Interop.Mood receiver)",
    )
    assertContains(result.generatedCSharp, "Native_MoodGetEmoji(int receiver")
  }

  @Test
  fun `a Uuid receiver crosses as hex-dash text on a UTF-8 pinned string slot`() {
    val result = Tier1Harness.run(paritySource)

    assertContains(result.generated, "kotlin.uuid.Uuid.parse(receiver).shortForm")
    assertContains(
      result.generatedCSharp,
      "public static string GetShortForm(this global::System.Guid receiver)",
    )
    // The receiver is a `string` slot like any other input text, so the whole-file UTF-8 pass has
    // to have annotated it: an unannotated `string` marshals as the Windows code page.
    assertContains(
      result.generatedCSharp,
      "[MarshalAs(UnmanagedType.LPUTF8Str)] string receiver",
    )
  }

  @Test
  fun `Instant and Duration receivers cross as 64-bit ticks`() {
    val result = Tier1Harness.run(paritySource)

    assertContains(result.generated, "instantFromDotNetTicks(receiver).epochDay")
    assertContains(result.generated, "durationFromDotNetTicks(receiver).wholeHours")
    assertContains(
      result.generatedCSharp,
      "public static long GetEpochDay(this global::System.DateTimeOffset receiver)",
    )
    // ADR-076: `UtcTicks`, never the wall-clock `Ticks`, at the receiver position too.
    assertContains(result.generatedCSharp, "Native_InstantGetEpochDay(receiver.UtcTicks")
    assertContains(result.generatedCSharp, "Native_DurationGetWholeHours(receiver.Ticks")
    assertContains(result.generatedCSharp, "Native_InstantGetEpochDay(long receiver")
  }

  /**
   * The gap the 2026-09-14 amendment's "the shared lowering has an arm for each" did not cover: the
   * receiver's own `DllImport` parameter was spelled from the bare wire (`STRING -> "string"`), so
   * a nullable receiver passed a `string?` into a `string` slot -- CS8604 under the generated
   * file's `<Nullable>enable</Nullable>` + `<TreatWarningsAsErrors>`. One shared predicate
   * (`isNullableStringWire`) now spells it on both routes.
   */
  @Test
  fun `the three nullable string-wire receivers declare a nullable import parameter`() {
    val result = Tier1Harness.run(paritySource)
    val cs: String = result.generatedCSharp

    assertContains(cs, "public static string GetOrPlaceholder(this string? receiver)")
    assertContains(cs, "public static bool GetIsMissing(this global::System.Guid? receiver)")
    assertContains(cs, "public static string GetDisplay(this global::Interop.CatId? receiver)")

    assertContains(cs, "Native_StringGetOrPlaceholder([MarshalAs(UnmanagedType.LPUTF8Str)] string? receiver")
    assertContains(cs, "Native_UuidGetIsMissing([MarshalAs(UnmanagedType.LPUTF8Str)] string? receiver")
    assertContains(cs, "Native_CatidGetDisplay([MarshalAs(UnmanagedType.LPUTF8Str)] string? receiver")

    // Kotlin side: the null rides the wire in-band, and the lowering is parenthesised so `.display`
    // binds to the nullable extension rather than to a safe-called result.
    assertContains(result.generated, "(receiver?.let(kotlin.uuid.Uuid::parse)).isMissing")
    assertContains(
      result.generated,
      "(receiver?.let { tier1.propreceiverparity.CatId(it) }).display",
    )
  }

  /** The other nullable value-class underlying: an object handle, so the wire stays `IntPtr` and
   *  `IntPtr.Zero` is the null. */
  @Test
  fun `a nullable handle-underlying value class receiver stays on the pointer wire`() {
    val result = Tier1Harness.run(paritySource)

    assertContains(
      result.generatedCSharp,
      "public static string GetPatientName(this global::Interop.ChartRef? receiver)",
    )
    assertContains(result.generatedCSharp, "Native_ChartrefGetPatientName(IntPtr receiver")
    assertContains(result.generatedCSharp, "receiver?.Chart._handle ?? IntPtr.Zero")
  }

  /**
   * The control, widened from the single `Int?` cell above: every has-value fan-out receiver is
   * still a named skip with no export on either side. Asserted on the KIND and the declaration
   * name only -- the sentence is the next item's to change.
   */
  @Test
  fun `the fan-out receivers are still named skips`() {
    val result = Tier1Harness.run(paritySource)

    listOf("orZero", "orGrumpy", "epochOrNever", "countOrZero").forEach { name ->
      assertFalse(
        result.generated.contains(name),
        "$name must not render a Kotlin export at all",
      )
      assertTrue(
        result.kspWarnings.any { warning ->
          warning.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name) &&
              warning.contains(name)
        },
        "expected a named skip for $name; got: ${result.kspWarnings}",
      )
    }
  }

  /**
   * ADR-132 (2026-09-20), folded in: a COLLECTION receiver, the first receiver shape on either
   * route whose C# side *builds* a Kotlin object for the crossing. The mint is unconditional, so a
   * missing `finally` leaks one StableRef per read while every value assertion still passes.
   *
   * The `NugetListNative` pin is not decoration: the file's collection-helper tracker read the
   * property's declared TYPE only, so a receiver-only list disposed its handle with a class the
   * file never emitted (CS0103), which is also why `compiledClean` alone would not catch it.
   */
  @Test
  fun `a collection receiver mints and releases a list handle`() {
    val result = Tier1Harness.run(
      """
      package tier1.propreceivercollection

      val List<String>.longestName: String get() = maxByOrNull { it.length } ?: "(empty basket)"

      fun frontDoor(): String = "open"
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp

    assertContains(
      cs,
      "public static string GetLongestName(this IReadOnlyList<string> receiver)",
    )
    assertContains(cs, "receiverHandle = NugetMarshal.CreateList(receiver);")
    assertContains(
      cs,
      "if (receiverHandle != IntPtr.Zero) { NugetListNative.Dispose(receiverHandle); }",
    )
    assertContains(cs, "class NugetListNative")
    assertContains(cs, "Native_ListGetLongestName(IntPtr receiver")
  }

  /**
   * ADR-088 at the receiver position: a bound C# interface crosses as a fresh transfer GCHandle
   * that KOTLIN owns, so there is deliberately no `finally` disposing it on the C# side.
   *
   * Not `compiledClean`, by the same rule `Tier1BoundInterfacePositionTest` states: an admitted
   * bound-interface position emits Kotlin calling the reverse pipeline's own `nugetIFeedableValue`,
   * which no Tier 1 fixture has. The runtime half is `IntegrationTests` /`LeakTests` row 6k.
   */
  @Test
  fun `a bound interface receiver crosses as a transfer GCHandle Kotlin owns`() {
    val manifest: File = Files.createTempFile("nuget-bound-types-", ".json").toFile()
    manifest.deleteOnExit()
    manifest.writeText(
      """
      {
        "interfaces": [
          { "kotlinName": "bound.menagerie.IFeedable", "csharpName": "Test.Menagerie.IFeedable", "implementable": true }
        ]
      }
      """.trimIndent(),
    )
    val result = Tier1Harness.run(
      sources = mapOf(
        "Bound.kt" to """
          package bound.menagerie

          interface IFeedable {
            fun describe(): String
          }
        """.trimIndent(),
        "Fixture.kt" to """
          package tier1.propreceiverbound

          import bound.menagerie.IFeedable

          val IFeedable.feedingNote: String get() = "${'$'}{describe()} needs a bowl"

          fun frontDoor(): String = "open"
        """.trimIndent(),
      ),
      processorOptions = mapOf("nuget.boundTypesManifest" to manifest.absolutePath),
    )

    assertContains(result.generated, "bound.menagerie.nugetIFeedableValue(receiver).feedingNote")
    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      "public static string GetFeedingNote(this global::Test.Menagerie.IFeedable receiver)",
    )
    assertContains(cs, "IntPtr receiverHandle = GCHandle.ToIntPtr(GCHandle.Alloc(receiver));")
    assertContains(cs, "Native_IfeedableGetFeedingNote(IntPtr receiver")
    // The receiving side owns it: a `Dispose` here would double-free against the ADR-070 cleaner.
    assertFalse(
      cs.contains("NugetMarshal.Dispose(receiverHandle)"),
      "a bound-interface receiver handle must not be disposed on the C# side; csharp=$cs",
    )
  }

  /**
   * The `NullableDispatch` setter arm used to build its body directly instead of through the
   * handle scope, so an interface receiver -- whose argument IS the local `receiverHandle` -- named
   * a local the arm never declared (CS0103), and would have leaked the ADR-084 transfer handle per
   * set if it had compiled. Tier 1 compiles the Kotlin half only, so this text pin, not
   * `compiledClean`, is what holds it: the consumer C# compile in `verify.sh` is the other half.
   */
  @Test
  fun `a fan-out setter over an interface receiver declares and releases its receiver handle`() {
    val result = Tier1Harness.run(source)
    val cs: String = result.generatedCSharp

    assertContains(cs, "public static void SetNapQuota(this global::Interop.IPet receiver, int? value)")
    assertContains(cs, "receiverHandle = NugetMarshal.HandleOf(receiver, out receiverOwned);")
    assertContains(
      cs,
      "if (receiverOwned && receiverHandle != IntPtr.Zero) { NugetMarshal.Dispose(receiverHandle); }",
    )
    // Both fan-out arms take the receiver slot, the null one included.
    assertContains(cs, "Native_PetSetNapQuota(receiverHandle, value.Value")
    assertContains(cs, "Native_PetSetNapQuotaNull(receiverHandle")
  }

  /**
   * The enum defect this item surfaced, pinned where it is cheapest: an enum with a property of its
   * own renders a `{Enum}Extensions` class here, and any extension over that enum merges into a
   * static class of the same name, which renders `partial`. One of the two not being `partial` is
   * CS0260 on the whole generated file. Tier 1 does not compile C#, so the NEGATIVE assertion is
   * what would catch a regression.
   */
  @Test
  fun `an enum with a property renders a partial extension class the extensions can merge into`() {
    val result = Tier1Harness.run(
      """
      package tier1.propreceiverenumpartial

      enum class Mood(val description: String) {
        HAPPY("happy"),
        GRUMPY("grumpy"),
      }

      fun Mood.rallyCry(): String = "${'$'}{name.lowercase()} cats unite"

      val Mood.emoji: String get() = if (this == Mood.HAPPY) ":)" else ":("

      fun frontDoor(): String = "open"
      """.trimIndent(),
    )
    val cs: String = result.generatedCSharp

    assertContains(cs, "public static partial class MoodExtensions")
    assertFalse(
      cs.contains("public static class MoodExtensions"),
      "a non-partial MoodExtensions collides with the merged extension class (CS0260); csharp=$cs",
    )
    // All three members land in that one class: the enum's own property, the extension function,
    // and the extension property.
    assertContains(cs, "public static string Description(this Mood mood)")
    assertContains(cs, "RallyCry(this global::Interop.Mood receiver)")
    assertContains(cs, "GetEmoji(this global::Interop.Mood receiver)")
  }

  /**
   * ROADMAP Phase 4 / ADR-064 amendment: the property route reads the same fan-out sentence and
   * hint the extension-FUNCTION route reads, under its own position kind
   * (`SKIPPED_UNSUPPORTED_PROPERTY`, which still names where the drop happened). Its shipped pair
   * could not explain why `Int?` is refused while "primitive" and "nullable class" are both on the
   * supported list it printed, and never mentioned that `Int?` is perfectly fine as a parameter.
   */
  @Test
  fun `a fan-out property receiver names the receiver type, its shape, and both remedies`() {
    val result = Tier1Harness.run(
      """
      package tier1.propreceiverfanoutmessage

      val Int?.orZero: Int get() = this ?: 0
      """.trimIndent(),
    )

    val warning: String = result.kspWarnings.single { it.contains("orZero") }
    assertContains(
      warning,
      "[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name}] Skipping " +
          "tier1.propreceiverfanoutmessage.Int.orZero: " +
          "its extension receiver `Int?` crosses the bridge as a has-value flag plus a value " +
          "(two slots), and an extension receiver can carry only one (RECEIVER_FAN_OUT). " +
          "`Int?` binds as an ordinary parameter, so declare a top-level function that takes it " +
          "as a parameter instead of as the receiver; or declare the extension on the non-null " +
          "receiver `Int`",
    )
    // The shipped receiver pair, which every NON-fan-out refused receiver still keeps
    // (`Tier1NamedSkipDiagnosticsTest`'s `Box<Int>.label`).
    assertFalse(
      warning.contains("is not a supported extension-property receiver"),
      "a fan-out receiver must not fall back to the generic receiver sentence; got: $warning",
    )
    assertFalse(
      warning.contains("or expose a top-level getter function instead"),
      "a fan-out receiver must not fall back to the generic receiver hint; got: $warning",
    )
  }

  /**
   * The non-primitive spellings, same as the extension-function cell: the receiver name in the
   * sentence and in the non-null clause is rendered off the receiver's own type. The non-null
   * clause is truthful on this route only since the 2026-09-20 receiver-parity amendment admitted
   * a bare `Enum` receiver here.
   */
  @Test
  fun `a fan-out enum and value-class property receiver each name themselves`() {
    val result = Tier1Harness.run(
      """
      package tier1.propreceiverfanoutkinds

      enum class Mood { HAPPY, SAD }

      @JvmInline
      value class Dosage(val mg: Int)

      val Mood?.loud: String get() = if (this == Mood.HAPPY) "!" else "."

      val Dosage?.orZero: Int get() = this?.mg ?: 0
      """.trimIndent(),
    )

    val mood: String = result.kspWarnings.single { it.contains("Mood.loud") }
    assertContains(
      mood,
      "its extension receiver `Mood?` crosses the bridge as a has-value flag plus a value " +
          "(two slots), and an extension receiver can carry only one (RECEIVER_FAN_OUT). " +
          "`Mood?` binds as an ordinary parameter, so declare a top-level function that takes it " +
          "as a parameter instead of as the receiver; or declare the extension on the non-null " +
          "receiver `Mood`",
    )

    val dosage: String = result.kspWarnings.single { it.contains("Dosage.orZero") }
    assertContains(
      dosage,
      "its extension receiver `Dosage?` crosses the bridge as a has-value flag plus a value " +
          "(two slots), and an extension receiver can carry only one (RECEIVER_FAN_OUT). " +
          "`Dosage?` binds as an ordinary parameter, so declare a top-level function that takes " +
          "it as a parameter instead of as the receiver; or declare the extension on the " +
          "non-null receiver `Dosage`",
    )
  }
}
