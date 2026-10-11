package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A value class whose underlying no value-class wire carries has no C# `readonly record struct`
 * (`SKIPPED_UNSUPPORTED_TYPE` on the class). The classifier used to keep calling it a value class
 * anyway, and every position that only asks "can the component cross" spelled it: a `List`, `Set`
 * or `Map` component at a property, a method return, a parameter and a `suspend` return
 * (`IReadOnlyList<global::Interop.Initial>`, CS0234 in the consumer's build). The classifier now
 * refuses it on the set the renderer declares from, so no position can spell it and each member
 * is a named skip. This sweeps the positions: bare and collection, ordinary, `suspend`, generic
 * instantiation, lambda parameter and result, constructor, sealed arm, interface, top level and
 * extension receiver.
 *
 * The proof is a real `dotnet build`: the Kotlin half always compiled, which is how this hid.
 */
class Tier1ValueClassWithoutStructPositionsTest {

  private val source: String = """
    package tier1.vcpositions

    @JvmInline
    value class Initial(val letter: Char)

    @JvmInline
    value class Tag(val id: String)

    @JvmInline
    value class Nick(val name: String?)

    class Box<T>(val item: T)

    interface Feed {
      val all: List<Initial>
      fun first(): Initial
    }

    sealed class Pet {
      class Cat : Pet() {
        val all: List<Initial> = emptyList()
        fun first(): Initial = Initial('c')
      }
    }

    data class Chip(val initial: Initial, val others: List<Initial>, val tag: Tag)

    class Tracker(initial: Initial) {
      var one: Initial = initial
      val all: List<Initial> = emptyList()
      val unique: Set<Initial> = emptySet()
      val byName: Map<String, Initial> = emptyMap()
      val byInitial: Map<Initial, String> = emptyMap()
      val nested: List<List<Initial>> = emptyList()
      val maybe: List<Initial?> = emptyList()
      fun first(): Initial = one
      fun take(initial: Initial) {}
      fun everyone(): List<Initial> = all
      fun takeAll(initials: List<Initial>) {}
      fun boxed(): Box<Initial> = Box(one)
      fun takeBox(box: Box<Initial>) {}
      fun onInitial(pick: (Initial) -> Unit) {}
      fun pickInitial(pick: () -> Initial) {}
      fun onInitials(pick: (List<Initial>) -> Unit) {}
      fun onNick(pick: (Nick) -> Unit) {}
      fun onTag(pick: (Tag) -> Unit) {}
      suspend fun later(): Initial = one
      suspend fun laterAll(): List<Initial> = all
      suspend fun laterTake(initial: Initial): Int = 1
      suspend fun laterTakeAll(initials: List<Initial>): Int = 1
      fun tags(): List<Tag> = emptyList()
    }

    fun topFirst(): Initial = Initial('t')

    fun topAll(): List<Initial> = emptyList()

    fun Initial.shout(): String = letter.uppercase()

    val Initial.code: Int get() = letter.code
    """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))
  }

  /** The generated C# without its `///` remarks: a remark deliberately names what is skipped. */
  private val code: String by lazy {
    result.generatedCSharp.lines().filterNot { it.trimStart().startsWith("///") }.joinToString("\n")
  }

  private fun assertClean() {
    assertEquals(emptyList(), result.kspErrors, "KSP errors (an ADR-055 mismatch lands here)")
    assertTrue(result.compiledClean, "generated Kotlin must compile; got: ${result.compileErrors}")
  }

  @Test
  fun `the generated C# compiles and never spells the undeclared value class`() {
    assertClean()
    assertFalse(
      Regex("""\bInitial\b""").containsMatchIn(code.replace("Initials", "")),
      "expected no C# spelling of the undeclared Initial; got:\n" +
        code.lines().filter { Regex("""\bInitial\b""").containsMatchIn(it) }.joinToString("\n"),
    )
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      namespace Consumer
      {
          public static class Probe
          {
              public static object Run(global::Interop.Tracker tracker) => tracker.Tags();
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  @Test
  fun `the Kotlin half exports nothing that touches it`() {
    assertClean()
    assertFalse(
      "tier1.vcpositions.Initial" in result.generated,
      "expected no Kotlin export touching Initial; got:\n" +
        result.generated.lines().filter { "tier1.vcpositions.Initial" in it }.joinToString("\n"),
    )
  }

  @Test
  fun `every collection position is a named skip`() {
    assertClean()
    listOf(
      "Tracker.all:", "Tracker.unique:", "Tracker.byName:", "Tracker.byInitial:",
      "Tracker.nested:", "Tracker.maybe:", "Tracker.everyone:", "Tracker.takeAll:",
      "Tracker.laterAll:", "Tracker.laterTakeAll:", "topAll:", "Cat.all:",
    ).forEach(::assertNamed)
  }

  @Test
  fun `every bare, generic, lambda and receiver position is a named skip`() {
    assertClean()
    listOf(
      "Tracker.one:", "Tracker.first:", "Tracker.take:", "Tracker.boxed:", "Tracker.takeBox:",
      "Tracker.onInitial:", "Tracker.pickInitial:", "Tracker.onInitials:", "Tracker.later:",
      "Tracker.laterTake:", "topFirst:", "shout:", "code:", "Cat.first:",
    ).forEach(::assertNamed)
  }

  /**
   * The `suspend` return used to read "not the generic type Initial". It is not generic; it has
   * no record struct, and the skip says so, with the underlying that caused it.
   */
  @Test
  fun `a suspend return names the missing record struct, not a generic type`() {
    assertClean()
    val later: String = result.kspWarnings.single { it.contains("Tracker.later:") }
    assertContains(
      later,
      "its value class type `tier1.vcpositions.Initial` has no C# record struct, because it " +
        "wraps `kotlin.Char`, which no value-class wire carries",
    )
    assertFalse("generic type" in later, "a value class is not a generic type: $later")
  }

  /**
   * A lambda payload is read back through `NugetMarshal.Factories`, like a flow element. `Nick`
   * wraps a `String?`: a record struct but no ADR-171 box/unbox pair, so no entry, and
   * `FromHandle<Nick>` would throw inside the callback. Refused by name; `Tag`, which has the
   * pair, keeps binding.
   */
  @Test
  fun `a lambda payload with no boxed form is a named skip`() {
    assertClean()
    val named: List<String> = result.kspWarnings.filter { it.contains("Tracker.onNick:") }
    assertEquals(1, named.size, "expected onNick named once; got: ${result.kspWarnings}")
    assertContains(named.single(), "has no boxed form to cross a callback in")
    assertFalse(Regex("""Action<[\w.:]*Nick>""").containsMatchIn(code), "Nick payload spelled")
    assertContains(code, Regex("""public void OnTag\(Action<[\w.:]*Tag> pick\)"""))
  }

  @Test
  fun `a value class that has a record struct is untouched`() {

    assertClean()
    assertContains(result.generatedCSharp, "public readonly record struct Tag")
    assertContains(result.generatedCSharp, Regex("""IReadOnlyList<[\w.:]*Tag> Tags\(\)"""))
    assertFalse(
      result.kspWarnings.any { it.contains("Tracker.tags:") },
      "expected Tracker.tags to bind without a diagnostic; kspWarnings=${result.kspWarnings}",
    )
  }

  /** Named at least once, by a skip that is a warning about that member. */
  private fun assertNamed(member: String) {
    assertTrue(
      result.kspWarnings.any { warning ->
        warning.contains("[nuget:SKIPPED_") && warning.contains(member)
      },
      "expected $member to be a named skip; kspWarnings=\n" +
        result.kspWarnings.joinToString("\n") { it.lineSequence().first() },
    )
  }

  @Test
  fun `the value class itself is named once, with its underlying`() {
    assertClean()
    val declared: List<String> = result.kspWarnings.filter {
      it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE.name}]") &&
        it.contains("Skipping tier1.vcpositions.Initial:")
    }
    assertEquals(1, declared.size, "got: $declared")
    assertContains(declared.single(), "wraps `kotlin.Char`")
  }
}
