package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-164: one widened signature per callable, nullable means unset, `Optional<T>` for an already
 * nullable default, and a Kotlin-side `when (mask)` with one named-argument call per subset.
 *
 * The end-to-end half is `issue297/Issue297Sample.kt` / `Issue297Tests.cs`. What is pinned here is
 * the generated text both halves agree on: which parameters are optional, the `IsSet` slot, the arm
 * count, the cap diagnostic, and the trailing unroutable drop.
 */
class Tier1OptionalDefaultParameterTest {

  private val source: String = """
    package tier1.optionaldefaults

    enum class Mode { Never, Always }

    data class Config(val retries: Int = 3, val mode: Mode = Mode.Never, val owner: String? = "nobody")

    object Registry {
      fun describe(name: String, owner: String? = "nobody"): String = name + (owner ?: "(none)")
    }

    class Book(val title: String, val pages: Int = 100, val city: String)

    class Settings(val level: Int = 0)

    class Pad(val size: Int = 1, val tag: String = "x") {
      constructor(size: Int) : this(size, "y")
    }

    fun maybe(n: Int? = 3): Int? = n

    interface Greeter {
      fun greet(name: String, times: Int = 1): String
    }

    class Parrot : Greeter {
      override fun greet(name: String, times: Int): String = name
    }

    fun parrot(): Greeter = Parrot()

    fun notify(message: String, onDone: (String) -> Unit = {}): String = message

    class Button(val label: String = "ok", val onClick: () -> Unit = {})

    class Pinger {
      /**
       * Pings.
       *
       * @param times how many
       * @param done where to report
       */
      fun ping(times: Int, done: Sequence<Int> = emptySequence()): Int = times
    }

    class Wide(
      val p0: Int = 0, val p1: Int = 1, val p2: Int = 2, val p3: Int = 3, val p4: Int = 4,
      val p5: Int = 5, val p6: Int = 6, val p7: Int = 7, val p8: Int = 8, val p9: Int = 9,
    )
  """.trimIndent()

  private val result: Tier1Result by lazy { Tier1Harness.run(source) }

  @Test
  fun `the generated wrappers compile`() {
    assertTrue(result.compiledClean, "expected clean compile; got: ${result.compileErrors}")
  }

  @Test
  fun `only the trailing all-defaulted run is optional`() {
    val cs: String = result.generatedCSharp
    assertContains(
      cs, "public Config(int? retries = null, global::Interop.Mode? mode = null, " +
          "Optional<string?> owner = default)",
    )
    // A default before a required parameter is required-but-nullable.
    assertContains(cs, "public Book(string title, int? pages, string city)")
  }

  @Test
  fun `an already nullable default is an Optional behind its IsSet slot`() {
    val cs: String = result.generatedCSharp
    assertContains(cs, "public static string Describe(string name, Optional<string?> owner = default)")
    assertContains(cs, "bool ownerIsSet, [MarshalAs(UnmanagedType.LPUTF8Str)] string? owner")
    assertContains(cs, "var ownerValue = owner.Value;")
    assertContains(cs, "owner.HasValue, ownerValue")
    assertContains(cs, "public readonly struct Optional<T>")

    val kotlin: String = result.generated
    assertContains(kotlin, "if (ownerIsSet) mask = mask or 1")
    // An explicit null is a real argument, so the Optional arm never asserts non-null.
    assertContains(kotlin, "1 -> tier1.optionaldefaults.Registry.describe(name, owner = default_owner)")
  }

  @Test
  fun `the dispatch has one arm per subset of the widened parameters`() {
    val kotlin: String = result.generated
    val body: String = kotlin.substringAfter("export_library_tier1_optionaldefaults__config_create(")
      .substringBefore("@CName")
    assertEquals(8, Regex("""(?m)^\s+\d+ -> """).findAll(body).count(), body)
    assertContains(body, "0 -> tier1.optionaldefaults.Config()")
    assertContains(
      body,
      "7 -> tier1.optionaldefaults.Config(retries = default_retries!!, mode = default_mode!!, " +
          "owner = default_owner)",
    )
    assertContains(body, "else -> error(\"unreachable\")")
  }

  @Test
  fun `copy takes the same widened shape and dispatches on the receiver`() {
    assertContains(
      result.generatedCSharp,
      "public Config Copy(int? retries = null, global::Interop.Mode? mode = null, " +
          "Optional<string?> owner = default)",
    )
    assertContains(
      result.generated,
      "2 -> handle.asStableRef<tier1.optionaldefaults.Config>().get().copy(mode = default_mode!!)",
    )
  }

  @Test
  fun `a trailing unroutable default is dropped and never named`() {
    assertContains(result.generatedCSharp, "public int Ping(int times)")
    assertTrue(
      result.kspWarnings.none { it.contains("Pinger.ping") },
      "a dropped trailing default is not a skip; kspWarnings=${result.kspWarnings}",
    )
    assertContains(result.generated, ".get().ping(times)")
    // Its `@param` goes with it, or the C# doc names a parameter that does not exist (CS1572).
    assertContains(result.generatedCSharp, "<param name=\"times\">how many</param>")
    assertFalse(result.generatedCSharp.contains("<param name=\"done\">"))
  }

  @Test
  fun `more than eight defaults keep the earliest required and warn`() {
    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      "public Wide(int p0, int p1, int? p2 = null, int? p3 = null, int? p4 = null, int? p5 = null, " +
          "int? p6 = null, int? p7 = null, int? p8 = null, int? p9 = null)",
    )
    val warning: String? = result.kspWarnings.singleOrNull {
      it.contains(ForwardDiagnosticKind.WARNING_DEFAULT_PARAMETER_CAP_EXCEEDED.name)
    }
    assertTrue(
      warning != null && "Wide.<init>" in warning && "`p0`, `p1`" in warning,
      "kspWarnings=${result.kspWarnings}",
    )
    val body: String = result.generated
      .substringAfter("export_library_tier1_optionaldefaults__wide_create(")
      .substringBefore("@CName")
    assertEquals(256, Regex("""(?m)^\s+\d+ -> """).findAll(body).count())
  }

  @Test
  fun `a widened integral sole parameter gets an exact overload over the handle constructor`() {
    val cs: String = result.generatedCSharp
    assertContains(cs, "public Settings(int? level = null)")
    assertContains(cs, "public Settings(int level) : this((int?)level)")
    assertFalse(cs.contains("public Book(string title) :"), "only a one-argument call needs it")
  }

  @Test
  fun `a declared exact one-parameter constructor suppresses the extra overload`() {
    val cs: String = result.generatedCSharp
    assertContains(cs, "public Pad(int? size = null, string? tag = null)")
    assertEquals(1, Regex("""public Pad\(int size\)""").findAll(cs).count(), cs)
  }

  @Test
  fun `the top-level two-call route unwraps an Optional on both calls`() {
    val cs: String = result.generatedCSharp
    assertContains(cs, "public static int? Maybe(Optional<int?> n = default)")
    assertContains(cs, "var nValue = n.Value;")
    assertContains(cs, "Maybe_has_value(n.HasValue, nValue.HasValue, nValue.GetValueOrDefault(), ")
    assertContains(result.generated, "1 -> tier1.optionaldefaults.maybe(n = default_n)")
  }

  @Test
  fun `an interface member and its implementer declare one widened signature`() {
    val cs: String = result.generatedCSharp
    assertContains(cs, "string Greet(string name, int? times = null);")
    assertContains(cs, "public string Greet(string name, int? times = null)")
    assertTrue(result.compiledClean, "expected clean compile; got: ${result.compileErrors}")
    assertContains(result.generated, "0 -> handle.asStableRef<tier1.optionaldefaults.Greeter>().get().greet(name)")
  }

  @Test
  fun `a defaulted per-call lambda is a nullable delegate behind its IsSet slot`() {
    val cs: String = result.generatedCSharp
    assertContains(cs, "Notify(string message, ")
    assertContains(cs, "? onDone = null)")
    assertContains(cs, "if (onDone is not null) onDoneCtx = NugetThunks.RegisterCtx(onDoneNative);")
    assertContains(cs, "onDone is not null, NugetThunks.")

    val kotlin: String = result.generated
    assertContains(kotlin, "onDoneIsSet: Boolean")
    assertContains(kotlin, "if (onDoneIsSet) mask = mask or 1")
    // No local: the lambda is lowered inside the one arm that sets it.
    assertFalse(kotlin.contains("default_onDone"), kotlin)
    assertContains(kotlin, "1 -> tier1.optionaldefaults.notify(message, onDone = ")
  }

  @Test
  fun `a stored constructor lambda is still dropped`() {
    assertContains(result.generatedCSharp, "public Button(string? label = null)")
  }
}
