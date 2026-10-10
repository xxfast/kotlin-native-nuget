package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.nonNullStringOrThrow
import io.github.xxfast.kotlin.native.nuget.processor.valueClassUnderlyingOrThrow
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
 * all bind here now; the cells for them are in the second half of this class.
 *
 * ADR-132 amendment (2026-10-04): the has-value fan-out class (`Int?`, `Char?`, `Mood?`,
 * `Instant?`, `Duration?`, a nullable value class over a primitive or enum) and a bare `Char` bind
 * too, on the extension-FUNCTION route's two-slot `receiverHasValue` + value wire.
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

    assertContains(
      cs,
      "extension(global::Interop.IPet receiver)\n        {\n            public string Summary\n",
    )
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
      "extension(global::Interop.Cat? receiver)\n        {\n" +
        "            public string NameOrStray\n",
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
    assertContains(
      cs,
      "extension(global::Interop.IPet receiver)\n        {\n            public string Tag\n",
    )
    // ADR-188: one C# 14 property with both accessors; the setter's `value` is implicit.
    assertContains(cs, Regex("""public string Tag\n\s+\{\n\s+get\n[\s\S]*?\n\s+set\n\s+\{"""))
    assertFalse(cs.contains(" SetTag("), "the ADR-013 setter method must be gone: $cs")
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

    assertContains(result.generated, "@CName(\"library_tier1_propreceiverreach__sitter_bridge_create\")")
    assertContains(
      result.generatedCSharp,
      "internal sealed class Tier1PropreceiverreachSitterBridgeState : NugetBridgeState",
    )
    // ROADMAP line 28: this fixture used to carry an unrelated `fun frontDoor(): String` purely to
    // open `CirTranslator.needsCoreMarshal`. Without it the module rendered `NugetMarshal.HandleOf`
    // calls and declared none of the helpers behind them.
    assertContains(result.generatedCSharp, "internal static class NugetMarshal")
    assertContains(result.generatedCSharp, "internal static class NugetErrorNative")
  }

  /**
   * ADR-132 amendment (2026-10-04): a has-value fan-out receiver binds at the property position on
   * the extension-FUNCTION route's wire, `(bool receiverHasValue, <value> receiver, out IntPtr
   * error)`. The Kotlin export must READ the flag: lowering off the value slot alone compiles
   * clean on both halves and hands the nullable extension `0` where C# passed `null`.
   */
  @Test
  fun `a fan-out receiver binds on its nullable struct and reads the flag`() {
    val result = Tier1Harness.run(
      """
      package tier1.propreceiverfanout

      val Int?.orZero: Int get() = this ?: 0
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(
      result.kspWarnings.none { warning -> warning.contains("orZero") },
      "a fan-out receiver must bind, not warn; got: ${result.kspWarnings}",
    )
    val kotlin: String = result.generated
    // The value slot is the INNER type: an `Int?` parameter would be a boxed pointer, not an int.
    assertContains(kotlin, "receiverHasValue: Boolean,\n  `receiver`: Int,")
    assertContains(kotlin, "(if (receiverHasValue) receiver else null).orZero")
    val cs: String = result.generatedCSharp
    assertContains(cs, "extension(int? receiver)\n        {\n            public int OrZero\n")
    assertContains(cs, "Native_IntGetOrZero(receiver.HasValue, receiver.GetValueOrDefault()")
    // The flag once, one byte wide (ADR-069), then the value, then the error slot: the function
    // route's own spelling.
    assertContains(
      cs,
      "Native_IntGetOrZero([MarshalAs(UnmanagedType.I1)] bool receiverHasValue, int receiver, " +
          "out IntPtr error)",
    )
    // No handle is minted on either side for a value-type receiver, so there is nothing to leak.
    assertFalse(Regex("\\bHandleOf\\w*\\(receiver").containsMatchIn(cs))
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop;
      public static class Consumer
      {
          public static int Run()
          {
              int? none = null;
              int? some = 5;
              return none.OrZero + some.OrZero;
          }
      }
      """.trimIndent(),
    )
  }

  /**
   * `val Int.label` beside `val Int?.label` does what `fun Int.f()` beside `fun Int?.f()` does on
   * the function route (`Tier1ReceiverShapesExtensionTest`): both bind. C# declares an
   * `extension(int)` and an `extension(int?)` block of one member name in one class (no CS0102,
   * unlike a reference receiver's twin), and the nullable twin's accessors take the `ext` role
   * word because the non-null twin already spells the plain ones.
   */
  @Test
  fun `a non-null and a nullable fan-out receiver of one name both bind`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.propreceiverfanouttwin

      val Int.label: String get() = "nonnull:${'$'}this"

      val Int?.label: String get() = "nullable:${'$'}this"
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(result.kspWarnings.isEmpty(), "expected no warning; got: ${result.kspWarnings}")
    val owner = "library_tier1_propreceiverfanouttwin__"
    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"${owner}int_get_label\")")
    assertContains(kotlin, "@CName(\"${owner}int_ext_get_label\")")
    assertContains(kotlin, "try {\n  receiver.label\n")
    assertContains(kotlin, "(if (receiverHasValue) receiver else null).label")
    val cs: String = result.generatedCSharp
    assertContains(cs, "extension(int receiver)\n        {\n            public string Label\n")
    assertContains(cs, "extension(int? receiver)\n        {\n            public string Label\n")
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop;
      public static class Consumer
      {
          public static string Run()
          {
              int? none = null;
              int? some = 5;
              return 7.Label + none.Label + some.Label;
          }
      }
      """.trimIndent(),
    )
  }

  /**
   * The twin rule is about the receiver being a C# VALUE type, not about the fan-out wire: a
   * `Uuid?` (`Guid?`) and a nullable value class over a `String` (a `record struct`) ride their
   * null in-band on one slot, and C# still declares `extension(Guid)` beside `extension(Guid?)`.
   * Both bind, the nullable twin under the `ext` accessors, exactly like `val Int.x` / `Int?.x`.
   */
  @Test
  fun `a non-fan-out struct receiver and its nullable twin both bind`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.propreceiverstructtwin

      import kotlin.uuid.Uuid

      @JvmInline
      value class CatId(val id: String)

      val Uuid.tag: String get() = "chip"

      // The Tier 1 harness compiles on the JVM, where the pair erases to one signature.
      @get:JvmName("tagOrNull")
      val Uuid?.tag: String get() = if (this == null) "no chip" else "chip?"

      val CatId.badge: String get() = id

      @get:JvmName("badgeOrNull")
      val CatId?.badge: String get() = this?.id ?: "stray"

      fun frontDoor(): String = "open"
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(result.kspWarnings.isEmpty(), "expected no warning; got: ${result.kspWarnings}")
    val owner = "library_tier1_propreceiverstructtwin__"
    listOf("uuid_get_tag", "uuid_ext_get_tag", "catid_get_badge", "catid_ext_get_badge")
      .forEach { export -> assertContains(result.generated, "@CName(\"$owner$export\")") }
    val cs: String = result.generatedCSharp
    assertContains(cs, "extension(global::System.Guid receiver)")
    assertContains(cs, "extension(global::System.Guid? receiver)")
    assertContains(cs, "extension(global::Interop.CatId receiver)")
    assertContains(cs, "extension(global::Interop.CatId? receiver)")
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop;
      public static class Consumer
      {
          public static string Run()
          {
              global::System.Guid chip = global::System.Guid.NewGuid();
              global::System.Guid? none = null;
              CatId id = new CatId("Oreo");
              CatId? stray = null;
              return chip.Tag + none.Tag + id.Badge + stray.Badge;
          }
      }
      """.trimIndent(),
    )
  }

  /**
   * ADR-188's property/function refusal is C#'s member lookup, and for a VALUE-type receiver that
   * lookup tells `int` from `int?` (no implicit nullable conversion applies to an extension
   * receiver). `fun Int.label()` beside `val Int?.label` is therefore not ambiguous for any caller,
   * and both bind; the same nullability on both sides is still the refusal.
   */
  @Test
  fun `a property and a function on value receivers of differing nullability both bind`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.propreceivernullabilitysplit

      fun Int.label(): String = "fun"

      val Int?.label: String get() = "val?"

      fun Int?.mark(): String = "fun?"

      val Int.mark: String get() = "val"

      fun Int?.same(): String = "fun?"

      val Int?.same: String get() = "val?"
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    listOf("label", "mark").forEach { name ->
      assertTrue(
        result.kspWarnings.none { warning -> warning.contains(".$name") },
        "$name must bind on both sides; got: ${result.kspWarnings}",
      )
    }
    assertTrue(
      result.kspWarnings.any { warning ->
        warning.contains("SHADOWED_BY_EXTENSION_FUNCTION") && warning.contains("same")
      },
      "one receiver, both shapes, is still refused; got: ${result.kspWarnings}",
    )
    val cs: String = result.generatedCSharp
    assertContains(cs, "public static string Label(this int receiver)")
    assertContains(cs, "extension(int? receiver)\n        {\n            public string Label\n")
    assertContains(cs, "public static string Mark(this int? receiver)")
    assertContains(cs, "extension(int receiver)\n        {\n            public string Mark\n")
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop;
      public static class Consumer
      {
          public static string Run()
          {
              int? none = null;
              return 7.Label() + none.Label + none.Mark() + 7.Mark + none.Same();
          }
      }
      """.trimIndent(),
    )
  }

  /**
   * A `var` over a fan-out receiver, with a fan-out TYPE too: the getter is ADR-002's two-call
   * pair and the setter ADR-002's `set`/`set_null` dispatch, and all four exports carry the
   * receiver's two slots in front of everything else.
   */
  @Test
  fun `a var over a fan-out receiver carries the receiver pair on all four exports`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.propreceiverfanoutvar

      private val quotas: MutableMap<Int?, Int> = mutableMapOf()

      var Int?.quota: Int?
        get() = quotas[this]
        set(value) { if (value == null) quotas.remove(this) else quotas[this] = value }
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    val owner = "library_tier1_propreceiverfanoutvar__"
    listOf("get_quota", "get_quota_value", "set_quota", "set_quota_null").forEach { export ->
      val function: String = Regex(
        """@CName\("${owner}int_$export"\)\s*public fun [^(]+\(([^)]*)\)""",
      ).find(kotlin)?.groupValues?.get(1) ?: error("missing $export in:\n$kotlin")
      assertTrue(
        function.trimStart().startsWith("receiverHasValue: Boolean"),
        "$export must lead with the receiver flag; parameters=$function",
      )
    }
    assertContains(kotlin, "(if (receiverHasValue) receiver else null).quota = null")
    val cs: String = result.generatedCSharp
    assertContains(cs, "extension(int? receiver)\n        {\n            public int? Quota\n")
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop;
      public static class Consumer
      {
          public static int? Run()
          {
              int? none = null;
              none.Quota = 3;
              none.Quota = null;
              return none.Quota;
          }
      }
      """.trimIndent(),
    )
  }

  /**
   * `Char` and `Char?` property receivers: the property route's lowerings already carry CHAR16 (a
   * `Char` setter value, a `Char?` property's `_value` getter), so a receiver rides the same arms
   * the function route's `Char` / `Char?` receivers do. The native slot must be the two-byte U2
   * marshal: a bare `char` P/Invoke parameter is one ANSI byte.
   */
  @Test
  fun `a Char and a nullable Char receiver both bind on the two-byte wire`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.propreceiverchar

      val Char.shout: String get() = uppercase()

      val Char?.orSpace: Char get() = this ?: ' '
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(result.kspWarnings.isEmpty(), "expected no warning; got: ${result.kspWarnings}")
    assertContains(result.generated, "(if (receiverHasValue) receiver else null).orSpace")
    val cs: String = result.generatedCSharp
    assertContains(cs, "extension(char receiver)\n        {\n            public string Shout\n")
    assertContains(cs, "extension(char? receiver)\n        {\n            public char OrSpace\n")
    assertContains(cs, "Native_CharGetShout([MarshalAs(UnmanagedType.U2)] char receiver")
    assertContains(cs, "[MarshalAs(UnmanagedType.U2)] char receiver, out IntPtr error)")
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop;
      public static class Consumer
      {
          public static string Run()
          {
              char? none = null;
              return 'a'.Shout + none.OrSpace;
          }
      }
      """.trimIndent(),
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
      |            public string Summary
      |            {
      |                get
      |                {
      |                    IntPtr receiverHandle = IntPtr.Zero;
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

    // The has-value fan-out receivers: two slots, the flag then the value.
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
      "extension(global::Interop.Mood receiver)\n        {\n            public string Emoji\n",
    )
    assertContains(result.generatedCSharp, "Native_MoodGetEmoji(int receiver")
  }

  @Test
  fun `a Uuid receiver crosses as hex-dash text on a UTF-8 pinned string slot`() {
    val result = Tier1Harness.run(paritySource)

    assertContains(result.generated, "kotlin.uuid.Uuid.parse(receiver).shortForm")
    assertContains(
      result.generatedCSharp,
      "extension(global::System.Guid receiver)\n        {\n            public string ShortForm\n",
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
      "extension(global::System.DateTimeOffset receiver)\n        {\n" +
        "            public long EpochDay\n",
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

    assertContains(
      cs,
      "extension(string? receiver)\n        {\n            public string OrPlaceholder\n",
    )
    assertContains(
      cs,
      "extension(global::System.Guid? receiver)\n        {\n            public bool IsMissing\n",
    )
    assertContains(
      cs,
      "extension(global::Interop.CatId? receiver)\n        {\n            public string Display\n",
    )

    assertContains(
      cs,
      "Native_StringGetOrPlaceholder([MarshalAs(UnmanagedType.LPUTF8Str)] string? receiver",
    )
    assertContains(
      cs,
      "Native_UuidGetIsMissing([MarshalAs(UnmanagedType.LPUTF8Str)] string? receiver",
    )
    assertContains(
      cs,
      "Native_CatidGetDisplay([MarshalAs(UnmanagedType.LPUTF8Str)] string? receiver",
    )

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
      "extension(global::Interop.ChartRef? receiver)\n        {\n" +
        "            public string PatientName\n",
    )
    assertContains(result.generatedCSharp, "Native_ChartrefGetPatientName(NugetKotlinHandle receiver")
    assertContains(result.generatedCSharp, "receiver.HasValue ? " +
      valueClassUnderlyingOrThrow("receiver.Value", "Chart", "ChartRef", "receiver") +
      "._handle : NugetKotlinHandle.Null")
  }

  /**
   * Widened from the single `Int?` cell above: every has-value fan-out receiver kind binds, and
   * each converting one lowers its value off `GetValueOrDefault()`. The shared setter-value
   * spelling (`(int)receiver`) would throw `InvalidOperationException` on a null `Mood?` before
   * the call ever reached Kotlin.
   */
  @Test
  fun `every fan-out receiver kind binds and converts off GetValueOrDefault`() {
    val result = Tier1Harness.run(paritySource)

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    listOf("orZero", "orGrumpy", "epochOrNever", "countOrZero").forEach { name ->
      assertTrue(
        result.kspWarnings.none { warning -> warning.contains(name) },
        "$name must bind, not warn; got: ${result.kspWarnings}",
      )
    }
    val kotlin: String = result.generated
    assertContains(
      kotlin,
      "(if (receiverHasValue) tier1.propreceiverparity.Mood.entries[receiver] else null).orGrumpy",
    )
    assertContains(
      kotlin,
      "(if (receiverHasValue) instantFromDotNetTicks(receiver) else null).epochOrNever",
    )
    assertContains(
      kotlin,
      "(if (receiverHasValue) tier1.propreceiverparity.Paws(receiver) else null).countOrZero",
    )
    val cs: String = result.generatedCSharp
    assertContains(cs, "extension(global::Interop.Mood? receiver)")
    assertContains(cs, "extension(global::System.DateTimeOffset? receiver)")
    assertContains(cs, "extension(global::Interop.Paws? receiver)")
    assertContains(cs, "receiver.HasValue, (int)receiver.GetValueOrDefault()")
    assertContains(cs, "receiver.HasValue, receiver.GetValueOrDefault().UtcTicks")
    assertContains(cs, "receiver.HasValue, receiver.GetValueOrDefault().Count")
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop;
      public static class Consumer
      {
          public static string Run()
          {
              Mood? mood = null;
              global::System.DateTimeOffset? never = null;
              Paws? paws = null;
              return mood.OrGrumpy + never.EpochOrNever + paws.CountOrZero;
          }
      }
      """.trimIndent(),
    )
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
      "extension(IReadOnlyList<string> receiver)\n        {\n" +
        "            public string LongestName\n",
    )
    assertContains(
      cs,
      "receiverHandle = NugetMarshal.CreateList(global::System.Linq.Enumerable.Select(" +
        "receiver, x => ${nonNullStringOrThrow("x", "receiver")}));",
    )
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
      "extension(global::Test.Menagerie.IFeedable receiver)\n        {\n" +
        "            public string FeedingNote\n",
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

    assertContains(
      cs,
      "extension(global::Interop.IPet receiver)\n        {\n            public int? NapQuota\n",
    )
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
    assertContains(cs, "public static string Description(this global::Interop.Mood mood)")
    assertContains(cs, "RallyCry(this global::Interop.Mood receiver)")
    assertContains(
      cs,
      "extension(global::Interop.Mood receiver)\n        {\n            public string Emoji\n",
    )
  }
}
