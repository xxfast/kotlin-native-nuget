package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A value class whose underlying no value-class wire carries (`Char`, `Instant`, another value
 * class, a collection) gets no C# `readonly record struct` at all (`SKIPPED_UNSUPPORTED_TYPE`).
 * As a `Flow`/`StateFlow`/`MutableStateFlow` ELEMENT it used to be spelled anyway
 * (`KotlinStateFlow<global::Interop.Initial>`), so the consumer's build failed with CS0234 on a
 * type nothing declares. The member is now a named skip on every flow route and owner, decided on
 * the same record-struct set the renderer declares from, so the two cannot disagree.
 *
 * The proof is a real `dotnet build`: the Kotlin half always compiled, which is how this hid.
 *
 * A class and a sealed arm name the member with the value-class reason. An `object` owner has no
 * flow property route at all (the member is skipped for the owner's own reason); it and the
 * `interface` member are in the fixture to prove the build stays clean there too.
 */
class Tier1FlowElementValueClassWithoutStructTest {

  private val source: String = """
    package tier1.flowvc

    import kotlin.time.Instant
    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.emptyFlow

    @JvmInline
    value class Initial(val letter: Char)

    @JvmInline
    value class Stamp(val at: Instant)

    @JvmInline
    value class Inner(val id: String)

    @JvmInline
    value class Outer(val inner: Inner)

    @JvmInline
    value class Tags(val ids: List<String>)

    @JvmInline
    value class Nick(val name: String?)

    class Tracker {
      val initial: MutableStateFlow<Initial> = MutableStateFlow(Initial('a'))
      val readInitial: StateFlow<Initial> = initial
      val maybeInitial: StateFlow<Initial?> = MutableStateFlow(null)
      val initials: StateFlow<List<Initial>> = MutableStateFlow(emptyList())
      val byName: StateFlow<Map<String, Initial>> = MutableStateFlow(emptyMap())
      val stamp: StateFlow<Stamp> = MutableStateFlow(Stamp(Instant.fromEpochSeconds(0)))
      val outer: StateFlow<Outer> = MutableStateFlow(Outer(Inner("a")))
      val tags: StateFlow<Tags> = MutableStateFlow(Tags(emptyList()))
      val inner: MutableStateFlow<Inner> = MutableStateFlow(Inner("a"))
      fun initialFlow(): Flow<Initial> = emptyFlow()
      fun initialState(): StateFlow<Initial> = initial
      fun initialHeld(): MutableStateFlow<Initial> = initial
      fun initialLists(): Flow<List<Initial>> = emptyFlow()
      suspend fun awaitInitial(): StateFlow<Initial> = initial
      suspend fun awaitInitialHeld(): MutableStateFlow<Initial> = initial
      suspend fun awaitInitialFlow(): Flow<Initial> = emptyFlow()

      val nick: StateFlow<Nick> = MutableStateFlow(Nick(null))
      val maybeNick: MutableStateFlow<Nick?> = MutableStateFlow(null)
      val nicks: StateFlow<List<Nick>> = MutableStateFlow(emptyList())
      fun nickFlow(): Flow<Nick> = emptyFlow()
      suspend fun awaitNick(): StateFlow<Nick> = nick
    }

    sealed class Pet {
      class Cat : Pet() {
        val initial: StateFlow<Initial> = MutableStateFlow(Initial('c'))
      }
    }

    object Desk {
      val initial: StateFlow<Initial> = MutableStateFlow(Initial('d'))
    }

    interface Feed {
      val initial: StateFlow<Initial>
    }

    class Holder(val feed: Feed)
    """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))
  }

  private fun assertClean() {
    assertEquals(emptyList(), result.kspErrors, "KSP errors (an ADR-055 mismatch lands here)")
    assertTrue(result.compiledClean, "generated Kotlin must compile; got: ${result.compileErrors}")
  }

  /** The generated C# without its `///` remarks: a remark deliberately names what is skipped. */
  private val code: String by lazy {
    result.generatedCSharp.lines().filterNot { it.trimStart().startsWith("///") }.joinToString("\n")
  }

  @Test
  fun `the generated C# compiles and never spells a value class nothing declares`() {
    assertClean()
    listOf("Initial", "Stamp", "Outer", "Tags").forEach { name ->
      assertFalse(
        Regex("""Interop\.$name\b""").containsMatchIn(code),
        "expected no C# spelling of the undeclared $name; got:\n" +
          code.lines().filter { "Interop.$name" in it }.joinToString("\n"),
      )
    }
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      namespace Consumer
      {
          public static class Probe
          {
              public static object Run(global::Interop.Tracker tracker) => tracker.Inner;

              // A component is projected, not unboxed, so this read is real.
              public static string? First(global::Interop.Tracker tracker)
                  => tracker.Nicks.Value[0].Name;
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  @Test
  fun `the Kotlin half exports nothing for a dropped member`() {
    assertClean()
    listOf("Initial", "Stamp", "Outer", "Tags").forEach { name ->
      assertFalse(
        "tier1.flowvc.$name" in result.generated,
        "expected no Kotlin export touching $name; got:\n" +
          result.generated.lines().filter { "tier1.flowvc.$name" in it }.joinToString("\n"),
      )
    }
  }

  @Test
  fun `every flow property over a struct-less value class is skipped by name`() {
    assertClean()
    mapOf(
      "Tracker.initial:" to "tier1.flowvc.Initial",
      "Tracker.readInitial:" to "tier1.flowvc.Initial",
      "Tracker.maybeInitial:" to "tier1.flowvc.Initial",
      "Tracker.initials:" to "tier1.flowvc.Initial",
      "Tracker.byName:" to "tier1.flowvc.Initial",
      "Tracker.stamp:" to "tier1.flowvc.Stamp",
      "Tracker.outer:" to "tier1.flowvc.Outer",
      "Tracker.tags:" to "tier1.flowvc.Tags",
      "Cat.initial:" to "tier1.flowvc.Initial",
    ).forEach { (member, valueClass) ->
      assertSkipped(member, valueClass)
    }
  }

  @Test
  fun `every flow method over a struct-less value class is skipped by name`() {
    assertClean()
    listOf(
      "Tracker.initialFlow:", "Tracker.initialState:", "Tracker.initialHeld:",
      "Tracker.initialLists:", "Tracker.awaitInitial:", "Tracker.awaitInitialHeld:",
      "Tracker.awaitInitialFlow:",
    ).forEach { member ->
      assertSkipped(member, "tier1.flowvc.Initial")
    }
  }

  @Test
  fun `the reason says why the value class has no record struct`() {
    assertClean()
    val initial: String = result.kspWarnings.single { warning ->
      warning.contains("Tracker.readInitial:")
    }
    assertContains(
      initial,
      "its value class type `tier1.flowvc.Initial` has no C# record struct, because it wraps " +
        "`kotlin.Char`, which no value-class wire carries",
    )
    assertFalse("generic type" in initial, "a value class is not a generic type: $initial")
  }

  @Test
  fun `a dropped MutableStateFlow member is not also called a read-only KotlinStateFlow`() {
    assertClean()
    assertFalse(
      result.kspWarnings.any { it.contains("is a read-only KotlinStateFlow") },
      "a member the flow route dropped has no C# property to call read-only; " +
        "kspWarnings=${result.kspWarnings}",
    )
    assertFalse(
      result.kspWarnings.any { it.contains("returns a read-only KotlinStateFlow") },
      "a method the flow route dropped returns nothing; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * `Nick` wraps a `String?`: it HAS a record struct, but no ADR-171 box/unbox pair, so no
   * `NugetMarshal.Factories` entry reads it back and `FromHandle<Nick>` would throw
   * `NotSupportedException` at the first `.Value`. Tier 1 compiles the C# but cannot execute it
   * (no native library is linked), so the cell pins what makes the read impossible (no factory
   * entry) and that the bare element is refused by name instead. Inside a `List` it is projected
   * per component, with no box, so that member binds and its read compiles.
   */
  @Test
  fun `a value class with a record struct but no boxed form is refused as a bare element`() {
    assertClean()
    assertContains(result.generatedCSharp, "public readonly record struct Nick")
    assertFalse(
      "[typeof(global::Interop.Nick)]" in result.generatedCSharp,
      "Nick has no box/unbox pair, so no Factories entry can read a bare element back",
    )
    listOf("Tracker.nick:", "Tracker.maybeNick:", "Tracker.nickFlow:", "Tracker.awaitNick:")
      .forEach { member ->
        val named: List<String> = result.kspWarnings.filter { it.contains(member) }
        assertEquals(1, named.size, "expected $member named once; got: $named")
        assertContains(named.single(), "Nick")
        assertContains(named.single(), "those are the underlyings it has a boxed form for")
      }
    assertFalse(
      Regex("""Flow<[\w.:]*Nick\??>""").containsMatchIn(code),
      "expected no flow holder of a bare Nick element",
    )
    assertContains(
      result.generatedCSharp,
      Regex("""public KotlinStateFlow<IReadOnlyList<[\w.:]*Nick>> Nicks\b"""),
    )
  }

  @Test
  fun `a value class that has a record struct still binds as a flow element`() {
    assertClean()
    assertContains(
      result.generatedCSharp,
      Regex("""public KotlinMutableStateFlow<[\w.:]*Inner> Inner\b"""),
    )
    assertFalse(
      result.kspWarnings.any { it.contains("Tracker.inner:") },
      "expected Tracker.inner to bind without a diagnostic; kspWarnings=${result.kspWarnings}",
    )
  }

  /** Named exactly once, as the one undeclarable type it is at every position. */
  private fun assertSkipped(member: String, valueClass: String) {
    val kind: String = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE.name
    val named: List<String> = result.kspWarnings.filter { warning -> warning.contains(member) }
    assertEquals(1, named.size, "expected $member named once; got: $named")
    assertTrue(
      named.single().contains("[nuget:$kind]") &&
        named.single().contains("value class type `$valueClass` has no C# record struct"),
      "expected $member to name $valueClass and its missing record struct; got: $named",
    )
  }
}
