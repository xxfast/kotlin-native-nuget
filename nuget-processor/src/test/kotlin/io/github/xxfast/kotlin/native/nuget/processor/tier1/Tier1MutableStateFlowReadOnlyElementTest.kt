package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-071: the element kinds a declared `MutableStateFlow` deliberately does NOT write. A
 * collection (`List` / `Set` / `Map`) and a `ByteArray` cross as a wire container the write seam
 * has no arm to build, and an interface element has no forward arm that carries a C#
 * implementation into a Kotlin flow. The member binds the read-only `KotlinStateFlow<T>`. That
 * used to be silent: the author wrote `MutableStateFlow` and got a holder with no setter and no
 * word why. It is declined by name now, on the path the value-class element's refusal already
 * takes (`SKIPPED_UNSUPPORTED_INPUT`, the property keeps its getter).
 *
 * Mylo's nap log can be read from C#, and only Kotlin may rewrite it.
 */
class Tier1MutableStateFlowReadOnlyElementTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.readonly

      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow

      interface Animal { val name: String }

      class Cat(override val name: String) : Animal

      class Journal {
        val naps: MutableStateFlow<List<String>> = MutableStateFlow(listOf("noon"))
        val tags: MutableStateFlow<Set<Int>> = MutableStateFlow(setOf(1))
        val scores: MutableStateFlow<Map<String, Int>> = MutableStateFlow(mapOf("oreo" to 1))
        val photo: MutableStateFlow<ByteArray> = MutableStateFlow(byteArrayOf(1))
        fun napDial(): MutableStateFlow<List<String>> = naps
        fun photoDial(): MutableStateFlow<ByteArray> = photo
        val mascot: MutableStateFlow<Animal> = MutableStateFlow(Cat("Oreo"))
        fun mascotDial(): MutableStateFlow<Animal> = mascot
        suspend fun awaitMascot(): MutableStateFlow<Animal> = mascot

        // Declared read-only: nothing was dropped, so nothing is named.
        val readOnlyNaps: StateFlow<List<String>> = naps
        // Settable: the control that the walk names only what it refuses.
        val count: MutableStateFlow<Int> = MutableStateFlow(0)
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  private fun setterSkip(member: String): List<String> = result.kspWarnings.filter {
    it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name}]") &&
      it.contains("Journal.$member:")
  }

  @Test
  fun `a collection or ByteArray element binds the read-only holder`() {
    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp.withoutDocComments()
    listOf(
      "public KotlinStateFlow<IReadOnlyList<string>> Naps",
      "public KotlinStateFlow<IReadOnlySet<int>> Tags",
      "public KotlinStateFlow<IReadOnlyDictionary<string, int>> Scores",
      "public KotlinStateFlow<byte[]> Photo",
      "public KotlinStateFlow<IReadOnlyList<string>> NapDial()",
      "public KotlinStateFlow<byte[]> PhotoDial()",
      "public KotlinStateFlow<global::Interop.IAnimal> Mascot",
      "public KotlinStateFlow<global::Interop.IAnimal> MascotDial()",
      "Task<KotlinStateFlow<global::Interop.IAnimal>> AwaitMascotAsync(",
      "public KotlinMutableStateFlow<int> Count",
    ).forEach { signature ->
      assertContains(cs, signature, message = "expected `$signature` in Interop.cs")
    }
    val unwritten: List<String> =
      listOf("naps", "tags", "scores", "photo", "napDial", "photoDial", "mascot", "mascotDial")
    unwritten.forEach { member ->
      assertFalse(
        Regex("""_set_${member}_value"|_${member}_set_value"""").containsMatchIn(result.generated),
        "expected no setter export for $member",
      )
    }
  }

  @Test
  fun `the dropped setter is named once, with the element kind`() {
    mapOf(
      "naps" to "a List element",
      "tags" to "a Set element",
      "scores" to "a Map element",
      "photo" to "a ByteArray element",
      "napDial" to "a List element",
      "photoDial" to "a ByteArray element",
      "mascot" to "an interface element",
      "mascotDial" to "an interface element",
      "awaitMascot" to "an interface element",
    ).forEach { (member, kind) ->
      val named: List<String> = setterSkip(member)
      assertEquals(1, named.size, "expected one setter skip for $member; got: $named")
      assertContains(named.single(), kind, message = "expected the element kind for $member")
      assertContains(named.single(), "has no write arm")
      assertContains(named.single(), "read-only KotlinStateFlow")
    }
  }

  @Test
  fun `a declared StateFlow and a settable element are not named`() {
    listOf("readOnlyNaps", "count").forEach { member ->
      assertTrue(
        result.kspWarnings.none { it.contains("[nuget:") && it.contains("Journal.$member:") },
        "expected no diagnostic for $member; kspWarnings=${result.kspWarnings}",
      )
    }
  }

  /**
   * An awaited `MutableStateFlow` of a collection or `ByteArray` is refused whole on the suspend
   * route (awaiting it to a read-only holder would drop the settable `.Value`), so it carries
   * that one return diagnostic and no setter line claiming a holder exists.
   */
  @Test
  fun `a member refused whole is named once, with no setter line`() {
    val awaited: Tier1Result = Tier1Harness.run(
      """
      package tier1.readonly

      import kotlinx.coroutines.flow.MutableStateFlow

      class Journal {
        suspend fun awaitNaps(): MutableStateFlow<List<String>> = MutableStateFlow(listOf())
        suspend fun awaitPhoto(): MutableStateFlow<ByteArray> = MutableStateFlow(byteArrayOf())
        val maybeNaps: MutableStateFlow<List<String>?> = MutableStateFlow(null)
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    val cs: String = awaited.generatedCSharp.withoutDocComments()
    listOf("awaitNaps", "awaitPhoto").forEach { member ->
      val csName: String = member.replaceFirstChar { it.uppercase() }
      assertFalse(cs.contains(" ${csName}Async("), "expected no ${csName}Async declaration")
      val named: List<String> =
        awaited.kspWarnings.filter { it.contains("[nuget:") && it.contains("Journal.$member:") }
      assertEquals(1, named.size, "expected one diagnostic for $member; got: $named")
      assertContains(named.single(), ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN.name)
    }
    // A nullable collection element is refused whole on the Flow route: the property is absent,
    // so its one diagnostic is that refusal, never a setter line about a read-only holder.
    assertFalse(cs.contains(" MaybeNaps"), "expected no MaybeNaps property")
    val named: List<String> =
      awaited.kspWarnings.filter { it.contains("[nuget:") && it.contains("Journal.maybeNaps:") }
    assertEquals(1, named.size, "expected one diagnostic for maybeNaps; got: $named")
    assertContains(named.single(), ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name)
  }

  /**
   * ADR-174: an interface's flow members bind on `I<Name>`, and the implementer may not be
   * exported at all (here it is `private`), so the interface is the only owner that can name a
   * holder left read-only. Every read-only member is named on it, once, and the settable ones
   * beside them are not.
   */
  @Test
  fun `an interface-owned read-only holder is named on the interface`() {
    val owned: Tier1Result = Tier1Harness.run(
      """
      package tier1.readonly

      import kotlinx.coroutines.flow.MutableStateFlow

      interface Animal { val name: String }

      interface Feed {
        val level: MutableStateFlow<Int>
        val naps: MutableStateFlow<List<Int>>
        val photo: MutableStateFlow<ByteArray>
        val mascot: MutableStateFlow<Animal>
        fun dial(): MutableStateFlow<Int>
        fun napDial(): MutableStateFlow<List<Int>>
        fun maybeDial(): MutableStateFlow<Int>?
        suspend fun awaitDial(): MutableStateFlow<Int>
        suspend fun awaitMaybe(): MutableStateFlow<Int?>
        suspend fun awaitMember(): MutableStateFlow<Int>?
      }

      private class Quiet : Animal { override val name: String = "Oreo" }

      private class LoudFeed : Feed {
        override val level: MutableStateFlow<Int> = MutableStateFlow(0)
        override val naps: MutableStateFlow<List<Int>> = MutableStateFlow(listOf())
        override val photo: MutableStateFlow<ByteArray> = MutableStateFlow(byteArrayOf())
        override val mascot: MutableStateFlow<Animal> = MutableStateFlow(Quiet())
        override fun dial(): MutableStateFlow<Int> = level
        override fun napDial(): MutableStateFlow<List<Int>> = naps
        override fun maybeDial(): MutableStateFlow<Int>? = null
        override suspend fun awaitDial(): MutableStateFlow<Int> = level
        override suspend fun awaitMaybe(): MutableStateFlow<Int?> = MutableStateFlow(null)
        override suspend fun awaitMember(): MutableStateFlow<Int>? = null
      }

      fun feed(): Feed = LoudFeed()
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    assertTrue(owned.kspErrors.isEmpty(), "expected no KSP errors; got: ${owned.kspErrors}")
    val cs: String = owned.generatedCSharp.withoutDocComments()
    listOf(
      " KotlinMutableStateFlow<int> Level { get; }",
      " KotlinStateFlow<IReadOnlyList<int>> Naps { get; }",
      " KotlinStateFlow<byte[]> Photo { get; }",
      " KotlinStateFlow<global::Interop.IAnimal> Mascot { get; }",
      " KotlinMutableStateFlow<int> Dial();",
      " KotlinStateFlow<IReadOnlyList<int>> NapDial();",
      " KotlinStateFlow<int>? MaybeDial();",
      " Task<KotlinMutableStateFlow<int>> AwaitDialAsync(",
      " Task<KotlinStateFlow<int?>> AwaitMaybeAsync(",
      " Task<KotlinStateFlow<int>?> AwaitMemberAsync(",
    ).forEach { signature ->
      assertContains(cs, signature, message = "expected `$signature` on IFeed")
    }
    mapOf(
      "naps" to "a List element of a MutableStateFlow has no write arm",
      "photo" to "a ByteArray element of a MutableStateFlow has no write arm",
      "mascot" to "an interface element of a MutableStateFlow has no write arm",
      "napDial" to "a List element of a MutableStateFlow has no write arm",
      "maybeDial" to "a nullable MutableStateFlow method return has no write arm",
      "awaitMaybe" to "an awaited MutableStateFlow of a nullable element has no write arm",
      "awaitMember" to "an awaited nullable MutableStateFlow has no write arm",
    ).forEach { (member, reason) ->
      val named: List<String> =
        owned.kspWarnings.filter { it.contains("[nuget:") && it.contains(" Feed.$member:") }
      assertEquals(1, named.size, "expected one diagnostic for Feed.$member; got: $named")
      assertContains(named.single(), ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name)
      assertContains(named.single(), reason)
      assertContains(named.single(), "read-only KotlinStateFlow")
    }
    listOf("level", "dial", "awaitDial").forEach { member ->
      assertTrue(
        owned.kspWarnings.none { it.contains("[nuget:") && it.contains(" Feed.$member:") },
        "expected no diagnostic for Feed.$member; kspWarnings=${owned.kspWarnings}",
      )
    }
  }

  /**
   * ADR-209: a `MutableSharedFlow` emit rides this write slot, so the same three element kinds
   * leave it read-only, for the same reason. The two refusals are one sentence with the holder's
   * name in it, compared here member against member so neither can be reworded alone.
   */
  @Test
  fun `a MutableSharedFlow of the same element is refused in the same words`() {
    val twins: Tier1Result = Tier1Harness.run(
      """
      package tier1.readonly

      import kotlinx.coroutines.flow.MutableSharedFlow
      import kotlinx.coroutines.flow.MutableStateFlow

      interface Animal { val name: String }

      class Cat(override val name: String) : Animal

      class Journal {
        val naps: MutableStateFlow<List<String>> = MutableStateFlow(listOf("noon"))
        val napFeed: MutableSharedFlow<List<String>> = MutableSharedFlow(replay = 1)
        val tags: MutableStateFlow<Set<Int>> = MutableStateFlow(setOf(1))
        val tagFeed: MutableSharedFlow<Set<Int>> = MutableSharedFlow(replay = 1)
        val scores: MutableStateFlow<Map<String, Int>> = MutableStateFlow(mapOf("oreo" to 1))
        val scoreFeed: MutableSharedFlow<Map<String, Int>> = MutableSharedFlow(replay = 1)
        val photo: MutableStateFlow<ByteArray> = MutableStateFlow(byteArrayOf(1))
        val photoFeed: MutableSharedFlow<ByteArray> = MutableSharedFlow(replay = 1)
        val mascot: MutableStateFlow<Animal> = MutableStateFlow(Cat("Oreo"))
        val mascotFeed: MutableSharedFlow<Animal> = MutableSharedFlow(replay = 1)
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    assertTrue(twins.kspErrors.isEmpty(), "expected no KSP errors; got: ${twins.kspErrors}")
    val sentence = Regex("""because (an? \w+ element of a \w+ has no write arm \([^)]*\))""")
    fun refusal(member: String): String {
      val named: List<String> = twins.kspWarnings.filter {
        it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name}]") &&
          it.contains("Journal.$member:")
      }
      assertEquals(1, named.size, "expected one refusal for $member; got: $named")
      val match: MatchResult? = sentence.find(named.single())
      return requireNotNull(match) { "no refusal sentence in ${named.single()}" }.groupValues[1]
    }
    mapOf(
      "naps" to "napFeed",
      "tags" to "tagFeed",
      "scores" to "scoreFeed",
      "photo" to "photoFeed",
      "mascot" to "mascotFeed",
    ).forEach { (state, shared) ->
      val stateSentence: String = refusal(state)
      assertContains(stateSentence, " of a MutableStateFlow has no write arm (")
      assertEquals(
        stateSentence.replace("MutableStateFlow", "MutableSharedFlow"), refusal(shared),
        "expected $shared to be refused in $state's words",
      )
    }
    val cs: String = twins.generatedCSharp.withoutDocComments()
    assertFalse(
      Regex("""public KotlinMutable(State|Shared)Flow<""").containsMatchIn(cs),
      "expected every member to bind a read-only holder",
    )
  }

  /**
   * The routes a declared `MutableStateFlow` return is read-only on whatever its element is. Each
   * binds the read-only holder and says which route has no write arm; the settable routes beside
   * them stay unnamed.
   */
  @Test
  fun `a read-only route is named once, with the route`() {
    val routes: Tier1Result = Tier1Harness.run(
      mapOf(
        "Routes.kt" to """
          package tier1.routes

          import kotlinx.coroutines.flow.MutableStateFlow

          class Journal {
            private val count: MutableStateFlow<Int> = MutableStateFlow(0)
            fun maybeDial(): MutableStateFlow<Int>? = null
            suspend fun awaitMaybe(): MutableStateFlow<Int?> = MutableStateFlow(null)
            suspend fun awaitMember(): MutableStateFlow<Int>? = null

            // Settable: the held return and the awaited return of a non-null member and element.
            fun dial(): MutableStateFlow<Int> = count
            suspend fun awaitDial(): MutableStateFlow<Int> = count
          }

          suspend fun topAwait(): MutableStateFlow<Int> = MutableStateFlow(0)
        """.trimIndent(),
      ),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    assertTrue(routes.kspErrors.isEmpty(), "expected no KSP errors; got: ${routes.kspErrors}")
    val cs: String = routes.generatedCSharp.withoutDocComments()
    listOf(
      "public KotlinStateFlow<int>? MaybeDial()",
      "public Task<KotlinStateFlow<int?>> AwaitMaybeAsync(",
      "public Task<KotlinStateFlow<int>?> AwaitMemberAsync(",
      "public static Task<KotlinStateFlow<int>> TopAwaitAsync(",
      "public KotlinMutableStateFlow<int> Dial()",
      "public Task<KotlinMutableStateFlow<int>> AwaitDialAsync(",
    ).forEach { signature ->
      assertContains(cs, signature, message = "expected `$signature` in Interop.cs")
    }
    mapOf(
      "Journal.maybeDial:" to "a nullable MutableStateFlow method return has no write arm",
      "Journal.awaitMaybe:" to "an awaited MutableStateFlow of a nullable element has no write arm",
      "Journal.awaitMember:" to "an awaited nullable MutableStateFlow has no write arm",
      "topAwait:" to "a top-level suspend function's MutableStateFlow has no write arm",
    ).forEach { (member, route) ->
      val named: List<String> =
        routes.kspWarnings.filter { it.contains("[nuget:") && it.contains(" $member") }
      assertEquals(1, named.size, "expected one diagnostic for $member got: $named")
      assertContains(named.single(), ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name)
      assertContains(named.single(), "setter is not generated because $route")
      assertContains(named.single(), "read-only KotlinStateFlow")
    }
    listOf("Journal.dial:", "Journal.awaitDial:").forEach { member ->
      assertTrue(
        routes.kspWarnings.none { it.contains("[nuget:") && it.contains(" $member") },
        "expected no diagnostic for $member kspWarnings=${routes.kspWarnings}",
      )
    }
  }
}
