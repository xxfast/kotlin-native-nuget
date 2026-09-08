package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue [#112](https://github.com/xxfast/kotlin-native-nuget/issues/112) / ADR-113: `IFoo` is
 * projected from Kotlin simple names (`mapParamType` is
 * `KOTLIN_TO_CSHARP_PARAM[kotlinType] ?: "IntPtr"`) with no bridgeability filter, while every
 * implementation of it is projected from the forward plan. The two disagree, so nothing can
 * implement the generated interface: CS0102, CS0738 and CS0535 all at once.
 *
 * Tier 1 never compiles the generated C# (ADR-060), so the *compile* proof lives in
 * `IntegrationTests/Issue112Tests.cs`. What lives here is everything Tier 1 can see and that
 * cannot: the rendered `Interop.cs` text, the generated `CNameExports.kt`, and the diagnostics,
 * including one cell that must FAIL the build and therefore cannot ship in `test-library`.
 */
class Tier1Issue112InterfaceProjectionTest {

  /**
   * The reported shape, all three sub-problems in one interface.
   *
   * - `collarTag: CollarTag?` is reference-typed, so `mapInterfacePropertyType` falls to `IntPtr?`
   *   while the class renders the wrapper: CS0738.
   * - `codes: Collection<String>` is skipped by the class route, so `IntPtr Codes` on the
   *   interface has no implementer: CS0535.
   * - `fun collarTag(code: Int)` is skipped too, and additionally claims the property's C# name:
   *   CS0102 plus a second CS0535.
   *
   * The interface is deliberately **non-reachable** (nothing returns an `Advertisement`), which is
   * the issue's own shape: the reporter implements the interface, never returns it.
   */
  @Test
  fun `interface projects the same C# types the implementing class does`() {
    val result = Tier1Harness.run(ADVERTISEMENT_FIXTURE, fileName = "Issue112Sample.kt")

    assertTrue(result.kspErrors.isEmpty(), "expected no failure; kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "got: ${result.compileErrors}")

    val iface: String = result.generatedCSharp.interfaceBlock("IAdvertisement")

    // Sub-problem 1: reference-typed members carry the class's wrapper type, never a raw pointer.
    assertFalse(
      "IntPtr" in iface,
      "expected no raw IntPtr member on IAdvertisement; block=$iface",
    )
    // ADR-113 Decision A only *inferred* that the interface's `plan.type.csharpType()` and the
    // class route's property projection render identical strings for the same Kotlin member, and
    // its stated failure symptom is CS0738 again. So the expected spelling is READ OFF the class
    // rather than hardcoded: a literal here would keep passing while the two sides drifted.
    val onClass: String = result.generatedCSharp.classPropertyType("CollarTag")
    assertContains(iface, "$onClass CollarTag { get; }")
    assertFalse(
      onClass == "IntPtr" || onClass == "IntPtr?",
      "the class route itself lost the wrapper type, so this cell no longer proves anything; " +
          "onClass=$onClass",
    )

    // Sub-problem 2: members the class route skipped are absent, and silently so.
    assertFalse("Codes" in iface, "expected Codes to be absent from IAdvertisement; block=$iface")
    assertFalse(
      "CollarTag(int" in iface,
      "expected the unbridgeable collarTag(code) method to be absent; block=$iface",
    )
    assertContains(iface, "string Identifier { get; }")
    assertContains(iface, "string Describe(string prefix);")
  }

  /**
   * Decision C: the omission is silent. The class route already fired `SKIPPED_UNSUPPORTED_*` for
   * `codes` and `collarTag(code)`, so a second diagnostic naming the same Kotlin declaration is
   * duplicate noise. Asserted as "exactly the class's own skips, no more".
   */
  @Test
  fun `skipped interface members are omitted without a second diagnostic`() {
    val result = Tier1Harness.run(ADVERTISEMENT_FIXTURE, fileName = "Issue112Sample.kt")

    val codesSkips: List<String> = result.kspWarnings.filter { "codes" in it }
    assertEquals(
      1,
      codesSkips.size,
      "expected exactly one skip naming `codes`, the class route's own; kspWarnings=$codesSkips",
    )
    assertTrue(
      codesSkips.single().contains("BleAdvertisement.codes"),
      "expected the surviving skip to name the class, not the interface; got=${codesSkips.single()}",
    )

    val methodSkips: List<String> = result.kspWarnings.filter { "collarTag" in it }
    assertEquals(
      1,
      methodSkips.size,
      "expected exactly one skip naming `collarTag`; kspWarnings=$methodSkips",
    )
  }

  /**
   * ADR-040's promise, and the regression this feature is most likely to cause. Interface *plans*
   * are computed only for `reachableInterfaces` (`NugetProcessor.kt`), while `translateInterface`
   * runs for every exported interface, so threading today's `callableCatalog` in would empty every
   * interface that is only implemented and never returned. `IMicrochipped` must keep all five
   * members.
   *
   * Non-reachability is asserted here rather than assumed: no backing class, no `microchipped_*`
   * dispatch export. That second half also guards ADR-113 Decision B's *inferred* claim that
   * planning a non-reachable interface has no side effect beyond the returned lists, whose stated
   * symptom is exactly "new `@CName` exports for interfaces that have no backing class".
   */
  @Test
  fun `non-reachable interface keeps every member and gains no exports`() {
    val result = Tier1Harness.run(MICROCHIPPED_FIXTURE, fileName = "Issue112Sample.kt")

    assertTrue(result.kspErrors.isEmpty(), "expected no failure; kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "got: ${result.compileErrors}")

    val iface: String = result.generatedCSharp.interfaceBlock("IMicrochipped")
    assertContains(iface, "string Label { get; }")
    assertContains(iface, "int Lives { get; }")
    assertContains(iface, "string? Nickname { get; }")
    assertContains(iface, "string Describe(string prefix);")
    assertContains(iface, "void Nap();")

    assertFalse(
      "class Microchipped :" in result.generatedCSharp,
      "expected no ADR-040 backing class, i.e. a genuinely non-reachable interface; " +
          "cs=${result.generatedCSharp}",
    )
    assertFalse(
      "microchipped_" in result.generated,
      "expected no interface-dispatch exports for a non-reachable interface; " +
          "kotlin=${result.generated}",
    )
  }

  /**
   * The reachable half of the same route: `prowl()` returns a `Prowling`, so ADR-040 generates the
   * implementation and `IProwling` has to agree with a class the generator wrote. Its `codes` is
   * planned into the export catalog today and, after ADR-113, into the declaration catalog too, so
   * Decision D's "the second planner's drop channels are not merged" is asserted here as a single
   * diagnostic rather than two.
   */
  @Test
  fun `reachable interface agrees with its generated backing class and reports one skip`() {
    val result = Tier1Harness.run(PROWLING_FIXTURE, fileName = "Issue112Sample.kt")

    assertTrue(result.kspErrors.isEmpty(), "expected no failure; kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "got: ${result.compileErrors}")

    val iface: String = result.generatedCSharp.interfaceBlock("IProwling")
    assertFalse("IntPtr" in iface, "expected no raw IntPtr member on IProwling; block=$iface")
    assertFalse("Codes" in iface, "expected Codes to be absent from IProwling; block=$iface")
    assertContains(iface, "${result.generatedCSharp.classPropertyType("CollarTag")} CollarTag { get; }")
    assertContains(iface, "string Describe(string prefix);")

    assertContains(result.generatedCSharp, "public sealed class Prowling : IProwling")
    assertContains(result.generated, "prowling_get_collarTag")

    val codesSkips: List<String> = result.kspWarnings.filter { "Prowling.codes" in it }
    assertEquals(
      1,
      codesSkips.size,
      "expected the reachable interface's `codes` skip exactly once, not once per planner; " +
          "kspWarnings=$codesSkips",
    )
  }

  /**
   * Decision E: `val tag` + `fun tag(n)` both survive the plan, so both claim the C# member name
   * `Tag` on `ITagged`, which is CS0102. Following ADR-110's settled precedent this is fatal with
   * no rename, because renaming either member would be a silently different API.
   *
   * This cell cannot live in `test-library`: it must fail the build.
   */
  @Test
  fun `interface property colliding with a same-named method fails and names both`() {
    val result = Tier1Harness.run(
      """
      package tier1.issue112collision

      interface Tagged {
        val tag: String
        fun tag(n: Int): String
      }
      """.trimIndent(),
      fileName = "Tagged.kt",
    )

    val error: String? = result.kspErrors.firstOrNull {
      it.contains(ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION.name)
    }
    assertTrue(error != null, "expected the named collision failure; kspErrors=${result.kspErrors}")
    assertContains(error, "ITagged.Tag")
    assertContains(error, "property")
    assertContains(error, "CS0102")
    assertContains(error, "rename the Kotlin function 'tag'")
  }

  /**
   * The post-filter half of Decision E, spelled out on its own: issue #112's literal Kotlin has
   * `val collarTag` next to `fun collarTag(code: Int): ByteArray?`, and that method is
   * unbridgeable. The collision guard runs over the *projected* member lists, so the method has
   * already dropped out and this build stays green. A pre-filter guard would turn a real
   * reporter's working library into a build failure.
   */
  @Test
  fun `collision guard does not fire when the colliding method is unbridgeable`() {
    val result = Tier1Harness.run(ADVERTISEMENT_FIXTURE, fileName = "Issue112Sample.kt")

    assertTrue(
      result.kspErrors.none {
        it.contains(ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION.name)
      },
      "expected no collision error when only one CollarTag member survives the plan; " +
          "kspErrors=${result.kspErrors}",
    )
    assertContains(result.generatedCSharp.interfaceBlock("IAdvertisement"), "CollarTag { get; }")
  }

  /**
   * The ADR-113 carve-out. A member whose signature names the interface's own class type parameter
   * has no plan entry, but it is not unbridgeable either: `T Read()` is valid C# inside
   * `interface IReadable<T>` and rendered correctly before the plan became the source of truth, so
   * projecting `IFoo` purely from the plan silently deleted it. Issue #111's rule is that a type
   * parameter stays BARE, which is exactly why the pre-plan spelling was already right here and
   * nowhere else.
   *
   * Written so it fails BOTH ways: absent members fail the `assertContains`, and a revert that
   * brings the members back as raw pointers fails the `IntPtr` assertions. The carve-out stays
   * narrow, so the same fixture's unplannable non-generic member must still drop.
   */
  @Test
  fun `interface members over the interface's own type parameter keep rendering bare`() {
    val result = Tier1Harness.run(VARIANCE_FIXTURE, fileName = "Variance.kt")

    assertTrue(result.kspErrors.isEmpty(), "expected no failure; kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "got: ${result.compileErrors}")

    val readable: String = result.generatedCSharp.interfaceBlock("IReadable<out T>")
    assertContains(readable, "T Read();")
    assertContains(readable, "T Head { get; }")
    assertFalse("IntPtr" in readable, "expected a bare type parameter, not a pointer; block=$readable")
    // Narrowness: `codes` is unplannable for a reason that has nothing to do with T, so the
    // carve-out must not readmit it.
    assertFalse("Codes" in readable, "carve-out widened past type parameters; block=$readable")

    val writable: String = result.generatedCSharp.interfaceBlock("IWritable<in T>")
    assertContains(writable, "void Write(T value);")
    assertFalse("IntPtr" in writable, "expected a bare type parameter, not a pointer; block=$writable")
  }

  /**
   * Slices `public interface IFoo : IDisposable { ... }` out of the generated `Interop.cs` so an
   * assertion about "no `IntPtr` member on this interface" cannot be satisfied (or defeated) by
   * text belonging to some other declaration in the same file.
   */
  private fun String.interfaceBlock(name: String): String {
    val start: Int = indexOf("public interface $name :")
    assertTrue(start >= 0, "no `public interface $name` in the generated C#; cs=$this")
    val end: Int = indexOf("\n    }", start)
    assertTrue(end > start, "unterminated `$name` block in the generated C#; cs=$this")
    return substring(start, end)
  }

  /**
   * The C# type the *class* route renders for the property called [name], read out of the same
   * `Interop.cs`. This is what the interface's projection of the same Kotlin member is compared
   * against, so neither side can be pinned to a literal that outlives the other.
   */
  private fun String.classPropertyType(name: String): String {
    val match = Regex("""public (?:virtual )?(\S+) $name\r?\n\s*\{""").find(this)
    assertTrue(match != null, "no class-route property named `$name` in the generated C#; cs=$this")
    return match.groupValues[1]
  }
}

private val ADVERTISEMENT_FIXTURE: String = """
  package tier1.issue112

  class CollarTag(val label: String)

  interface Advertisement {
    val identifier: String
    val collarTag: CollarTag?
    val codes: Collection<String>
    fun collarTag(code: Int): ByteArray?
    fun describe(prefix: String): String
  }

  class BleAdvertisement(
    override val identifier: String,
    override val collarTag: CollarTag?,
  ) : Advertisement {
    override val codes: Collection<String> get() = listOf(identifier)
    override fun collarTag(code: Int): ByteArray? = null
    override fun describe(prefix: String): String = "${'$'}prefix${'$'}identifier"
  }
""".trimIndent()

private val MICROCHIPPED_FIXTURE: String = """
  package tier1.issue112

  interface Microchipped {
    val label: String
    val lives: Int
    val nickname: String?
    fun describe(prefix: String): String
    fun nap()
  }

  class MicrochippedCat(
    override val label: String,
    override val lives: Int,
    override val nickname: String?,
  ) : Microchipped {
    override fun describe(prefix: String): String = "${'$'}prefix${'$'}label"
    override fun nap() = Unit
  }
""".trimIndent()

private val PROWLING_FIXTURE: String = """
  package tier1.issue112

  class CollarTag(val label: String)

  interface Prowling {
    val collarTag: CollarTag?
    val codes: Collection<String>
    fun describe(prefix: String): String
  }

  fun prowl(): Prowling = object : Prowling {
    override val collarTag: CollarTag? = CollarTag("Mylo")
    override val codes: Collection<String> get() = listOf("brown", "creamy")
    override fun describe(prefix: String): String = "${'$'}prefix roams the hallway"
  }
""".trimIndent()

private val VARIANCE_FIXTURE: String = """
  package tier1.issue112variance

  interface Readable<out T> {
    val head: T
    val codes: Collection<String>
    fun read(): T
  }

  interface Writable<in T> {
    fun write(value: T)
  }
""".trimIndent()
