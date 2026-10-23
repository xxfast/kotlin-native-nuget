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
  fun `a collection, ByteArray or interface element binds the settable holder`() {
    // ADR-240: the write seam mints each element's handle, so every one of these is settable.
    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp.withoutDocComments()
    listOf(
      "public KotlinMutableStateFlow<IReadOnlyList<string>> Naps",
      "public KotlinMutableStateFlow<IReadOnlySet<int>> Tags",
      "public KotlinMutableStateFlow<IReadOnlyDictionary<string, int>> Scores",
      "public KotlinMutableStateFlow<byte[]> Photo",
      "public KotlinMutableStateFlow<IReadOnlyList<string>> NapDial()",
      "public KotlinMutableStateFlow<byte[]> PhotoDial()",
      "public KotlinMutableStateFlow<global::Interop.IAnimal> Mascot",
      "public KotlinMutableStateFlow<global::Interop.IAnimal> MascotDial()",
      "public KotlinMutableStateFlow<int> Count",
    ).forEach { signature ->
      assertContains(cs, signature, message = "expected `$signature` in Interop.cs")
    }
    assertContains(cs, "NugetMarshal.CreateBytes(v)")
    assertContains(cs, "NugetMarshal.HandleOf(v, out bool vOwned)")
  }


  @Test
  fun `a minted element is not named`() {
    listOf("naps", "tags", "scores", "photo", "napDial", "photoDial", "mascot", "mascotDial")
      .forEach { member ->
        assertEquals(emptyList(), setterSkip(member), "expected no setter skip for $member")
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
   * An awaited `MutableStateFlow` of a collection or `ByteArray` awaits a settable holder, and a
   * nullable collection element binds settable too, null crossing as a null handle. None of them
   * is named.
   */
  @Test
  fun `awaited and nullable collection holders bind settable, unnamed`() {
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
    // ADR-240: an awaited `MutableStateFlow` of a collection or `ByteArray` awaits settable.
    assertContains(cs, "Task<KotlinMutableStateFlow<IReadOnlyList<string>>> AwaitNapsAsync(")
    assertContains(cs, "Task<KotlinMutableStateFlow<byte[]>> AwaitPhotoAsync(")
    listOf("awaitNaps", "awaitPhoto").forEach { member ->
      assertTrue(
        awaited.kspWarnings.none { it.contains("[nuget:") && it.contains("Journal.$member:") },
        "expected no diagnostic for $member; got: ${awaited.kspWarnings}",
      )
    }
    // A nullable collection element binds settable: null crosses as a null handle.
    assertContains(cs, "public KotlinMutableStateFlow<IReadOnlyList<string>?> MaybeNaps")
    assertTrue(
      awaited.kspWarnings.none { it.contains("[nuget:") && it.contains("Journal.maybeNaps:") },
      "expected no diagnostic for maybeNaps; got: ${awaited.kspWarnings}",
    )
  }

  /**
   * ADR-174: an interface's flow members bind on `I<Name>`, and the implementer may not be
   * exported at all (here it is `private`), so the interface is the only owner that can name a
   * holder left read-only. Every read-only member is named on it, once, and the settable ones
   * beside them are not.
   */
  @Test
  fun `an interface-owned holder binds settable and its read-only routes are named`() {
    val owned: Tier1Result = Tier1Harness.run(
      """
      package tier1.readonly

      import kotlinx.coroutines.flow.MutableStateFlow

      interface Animal { val name: String }

      interface Feed {
        val level: MutableStateFlow<Int>
        val naps: MutableStateFlow<List<Int>>
        val photo: MutableStateFlow<ByteArray>
        fun maybeDial(): MutableStateFlow<Int>?
        suspend fun awaitMaybe(): MutableStateFlow<Int?>
      }

      private class LoudFeed : Feed {
        override val level: MutableStateFlow<Int> = MutableStateFlow(0)
        override val naps: MutableStateFlow<List<Int>> = MutableStateFlow(listOf())
        override val photo: MutableStateFlow<ByteArray> = MutableStateFlow(byteArrayOf())
        override fun maybeDial(): MutableStateFlow<Int>? = null
        override suspend fun awaitMaybe(): MutableStateFlow<Int?> = MutableStateFlow(null)
      }

      fun feed(): Feed = LoudFeed()
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    assertTrue(owned.kspErrors.isEmpty(), "expected no KSP errors; got: ${owned.kspErrors}")
    val cs: String = owned.generatedCSharp.withoutDocComments()
    listOf(
      " KotlinMutableStateFlow<int> Level { get; }",
      " KotlinMutableStateFlow<IReadOnlyList<int>> Naps { get; }",
      " KotlinMutableStateFlow<byte[]> Photo { get; }",
      " KotlinStateFlow<int>? MaybeDial();",
      " Task<KotlinMutableStateFlow<int?>> AwaitMaybeAsync(",
    ).forEach { signature ->
      assertContains(cs, signature, message = "expected `$signature` on IFeed")
    }
    mapOf(
      "maybeDial" to "a nullable MutableStateFlow method return has no write arm",
    ).forEach { (member, reason) ->
      val named: List<String> =
        owned.kspWarnings.filter { it.contains("[nuget:") && it.contains(" Feed.$member:") }
      assertEquals(1, named.size, "expected one diagnostic for Feed.$member; got: $named")
      assertContains(named.single(), reason)
    }
    listOf("level", "naps", "photo", "awaitMaybe").forEach { member ->
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
  fun `a MutableSharedFlow of the same element is settable too`() {
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
    // ADR-240: both holders write the same minted handle, so neither is refused.
    assertTrue(
      twins.kspWarnings.none { "no write arm" in it },
      "expected no write refusal; got: ${twins.kspWarnings}",
    )
    val cs: String = twins.generatedCSharp.withoutDocComments()
    listOf("Naps", "Tags", "Scores", "Photo", "Mascot").forEach { member ->
      assertContains(cs, Regex("""public KotlinMutableStateFlow<[^\n]*> $member\b"""))
      assertContains(cs, Regex("""public KotlinMutableSharedFlow<[^\n]*> ${member.removeSuffix("s")}\w*Feed\b"""))
    }
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
      "public Task<KotlinMutableStateFlow<int?>> AwaitMaybeAsync(",
      "public Task<KotlinStateFlow<int>?> AwaitMemberAsync(",
      // ADR-230: the top-level route writes an awaited MutableStateFlow too.
      "public static Task<KotlinMutableStateFlow<int>> TopAwaitAsync(",
      "public KotlinMutableStateFlow<int> Dial()",
      "public Task<KotlinMutableStateFlow<int>> AwaitDialAsync(",
    ).forEach { signature ->
      assertContains(cs, signature, message = "expected `$signature` in Interop.cs")
    }
    mapOf(
      "Journal.maybeDial:" to "a nullable MutableStateFlow method return has no write arm",
      "Journal.awaitMember:" to "an awaited nullable MutableStateFlow has no write arm",
    ).forEach { (member, route) ->
      val named: List<String> =
        routes.kspWarnings.filter { it.contains("[nuget:") && it.contains(" $member") }
      assertEquals(1, named.size, "expected one diagnostic for $member got: $named")
      assertContains(named.single(), ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name)
      assertContains(named.single(), "setter is not generated because $route")
      assertContains(named.single(), "read-only KotlinStateFlow")
    }
    listOf(
      "Journal.dial:",
      "Journal.awaitDial:",
      "Journal.awaitMaybe:",
      "topAwait:",
    ).forEach { member ->
      assertTrue(
        routes.kspWarnings.none { it.contains("[nuget:") && it.contains(" $member") },
        "expected no diagnostic for $member kspWarnings=${routes.kspWarnings}",
      )
    }
  }
}
