package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The value-class refusals of `Tier1FlowElementValueClassWithoutStructTest` and
 * `Tier1ValueClassWithoutStructPositionsTest`, on the two surfaces that landed beside them:
 *
 *  - ADR-209: `SharedFlow` / `MutableSharedFlow` (`KotlinSharedFlow<T>`,
 *    `KotlinMutableSharedFlow<T>`) as a property, a held return and an awaited return;
 *  - ADR-208: a generic class instantiation at a member position (`Box<Initial>` as a property,
 *    return and parameter, inside a `List`, as a `MutableStateFlow` element) and a `Flow` or
 *    `StateFlow` as a generic type argument (`Box<Flow<Initial>>`, `Box<StateFlow<Nick>>`).
 *
 * `Initial` wraps a `Char`, so it has no C# record struct at all; `Nick` wraps a `String?`, so it
 * has a struct but no ADR-171 box/unbox pair, and nothing could read it back out of a flow or an
 * erased generic slot. Neither may be spelled at any of these positions; each member is a named
 * skip, and `Tag`, which has both, keeps binding. The proof is a real `dotnet build`.
 */
class Tier1ValueClassWithoutStructSharedFlowAndGenericTest {

  private val source: String = """
    package tier1.vcstack

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableSharedFlow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.SharedFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.emptyFlow

    @JvmInline
    value class Initial(val letter: Char)

    @JvmInline
    value class Nick(val name: String?)

    @JvmInline
    value class Tag(val id: String)

    class Box<T>(val item: T)

    class Tracker {
      val shared: SharedFlow<Initial> = MutableSharedFlow()
      val mutableShared: MutableSharedFlow<Initial> = MutableSharedFlow()
      val sharedInitials: SharedFlow<List<Initial>> = MutableSharedFlow()
      val sharedNick: SharedFlow<Nick> = MutableSharedFlow()
      val mutableSharedNick: MutableSharedFlow<Nick> = MutableSharedFlow()
      val sharedTag: MutableSharedFlow<Tag> = MutableSharedFlow()
      fun sharedRead(): SharedFlow<Initial> = shared
      fun sharedHeld(): MutableSharedFlow<Initial> = mutableShared
      fun sharedNickHeld(): MutableSharedFlow<Nick> = mutableSharedNick
      suspend fun awaitShared(): SharedFlow<Initial> = shared
      suspend fun awaitMutableShared(): MutableSharedFlow<Initial> = mutableShared
      suspend fun awaitSharedNick(): SharedFlow<Nick> = sharedNick

      val box: Box<Initial> = Box(Initial('a'))
      var boxVar: Box<Initial> = Box(Initial('a'))
      val boxes: List<Box<Initial>> = emptyList()
      val boxState: MutableStateFlow<Box<Initial>> = MutableStateFlow(box)
      val boxNick: Box<Nick> = Box(Nick(null))
      val boxTag: Box<Tag> = Box(Tag("a"))
      fun boxed(): Box<Initial> = box
      fun takeBox(box: Box<Initial>) {}

      val boxFlow: Box<Flow<Initial>> = Box(emptyFlow())
      val boxStateNick: Box<StateFlow<Nick>> = Box(MutableStateFlow(Nick(null)))
      val boxFlowTag: Box<Flow<Tag>> = Box(emptyFlow())
      fun boxFlowOf(): Box<Flow<Initial>> = boxFlow
    }
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
  fun `the generated C# compiles and spells neither value class at these positions`() {
    assertClean()
    assertFalse(
      Regex("""\bInitial\b""").containsMatchIn(code),
      "expected no C# spelling of the undeclared Initial; got:\n" +
        code.lines().filter { Regex("""\bInitial\b""").containsMatchIn(it) }.joinToString("\n"),
    )
    // `Nick` is declared (its record struct and constructor), but never as a flow element or a
    // generic type argument.
    assertFalse(
      Regex("""<[\w.:<]*\bNick\b""").containsMatchIn(code),
      "expected no flow holder or generic instantiation over Nick; got:\n" +
        code.lines().filter { Regex("""<[\w.:<]*\bNick\b""").containsMatchIn(it) }
          .joinToString("\n"),
    )
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      namespace Consumer
      {
          public static class Probe
          {
              public static object[] Run(global::Interop.Tracker tracker) => new object[]
              {
                  tracker.SharedTag, tracker.BoxTag, tracker.BoxFlowTag,
              };
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  @Test
  fun `the Kotlin half exports nothing that touches the struct-less value class`() {
    assertClean()
    assertFalse(
      "tier1.vcstack.Initial" in result.generated,
      "expected no Kotlin export touching Initial; got:\n" +
        result.generated.lines().filter { "tier1.vcstack.Initial" in it }.joinToString("\n"),
    )
  }

  @Test
  fun `a SharedFlow or MutableSharedFlow element is a named skip on every route`() {
    assertClean()
    listOf(
      "Tracker.shared:", "Tracker.mutableShared:", "Tracker.sharedInitials:",
      "Tracker.sharedRead:", "Tracker.sharedHeld:", "Tracker.awaitShared:",
      "Tracker.awaitMutableShared:",
    ).forEach { member ->
      assertContains(namedOnce(member), "`tier1.vcstack.Initial` has no C# record struct")
    }
    listOf(
      "Tracker.sharedNick:", "Tracker.mutableSharedNick:", "Tracker.sharedNickHeld:",
      "Tracker.awaitSharedNick:",
    ).forEach { member ->
      assertContains(namedOnce(member), "those are the underlyings it has a boxed form for")
    }
  }

  @Test
  fun `a generic instantiation over either value class is a named skip at every position`() {
    assertClean()
    listOf(
      "Tracker.box:", "Tracker.boxVar:", "Tracker.boxes:", "Tracker.boxState:",
      "Tracker.boxNick:", "Tracker.boxed:", "Tracker.takeBox:",
    ).forEach(::namedOnce)
  }

  @Test
  fun `a Flow or StateFlow type argument over either value class is a named skip`() {
    assertClean()
    listOf("Tracker.boxFlow:", "Tracker.boxStateNick:", "Tracker.boxFlowOf:").forEach(::namedOnce)
    // No per-instantiation collect export was generated for either refused flow argument.
    assertFalse(
      Regex("""Flow<[\w.:]*(Initial|Nick)>""").containsMatchIn(result.generated),
      "expected no flow-argument export over Initial or Nick; got:\n" +
        result.generated.lines()
          .filter { Regex("""Flow<[\w.:]*(Initial|Nick)>""").containsMatchIn(it) }
          .joinToString("\n"),
    )
  }

  @Test
  fun `a value class with a record struct and a boxed form binds at the same positions`() {
    assertClean()
    val csharp: String = result.generatedCSharp
    assertContains(csharp, Regex("""public KotlinMutableSharedFlow<[\w.:]*Tag> SharedTag\b"""))
    assertContains(csharp, Regex("""public [\w.:]*Box<[\w.:]*Tag> BoxTag\b"""))
    assertContains(
      csharp,
      Regex("""public [\w.:]*Box<[\w.:]*KotlinFlow<[\w.:]*Tag>> BoxFlowTag\b"""),
    )
    listOf("Tracker.sharedTag:", "Tracker.boxTag:", "Tracker.boxFlowTag:").forEach { member ->
      assertFalse(
        result.kspWarnings.any { it.contains(member) },
        "expected $member to bind without a diagnostic; kspWarnings=${result.kspWarnings}",
      )
    }
  }

  /** The one skip warning that names [member]. */
  private fun namedOnce(member: String): String {
    val named: List<String> = result.kspWarnings.filter { warning ->
      warning.contains("[nuget:SKIPPED_") && warning.contains(member)
    }
    assertEquals(
      1, named.size,
      "expected $member named once; kspWarnings=\n" +
        result.kspWarnings.joinToString("\n") { it.lineSequence().first() },
    )
    return named.single()
  }
}
