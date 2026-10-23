package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-064 amendment (the `ABSTRACT` / `SUSPEND` / `TYPE_PARAMETER` audit). `SUSPEND` was left out
 * of the unrouted-position reclassification on the assumption that "the suspend route is keyed to
 * every owner the planner reaches". It is not: the route walks top-level functions, classes and
 * sealed types and reachable interfaces only, so a `suspend fun` on an `object`, a class
 * `companion object` or an interface nothing returns used to vanish from the C# with no
 * diagnostic, and so did an `abstract fun` an abstract or sealed arm declares. Every owner the
 * route does not carry must now be named exactly once, and the routed owners must keep binding.
 * (`TYPE_PARAMETER`, the third reason audited, had no producer and is deleted.)
 */
class Tier1SuspendOwnerAuditTest {
  private val sources: Map<String, String> = mapOf(
    "Owners.kt" to """
      package tier1.suspendowners

      import kotlinx.coroutines.flow.Flow

      object Depot {
        suspend fun restock(): Int = 1
        fun count(): Int = 2
      }

      class Den {
        fun size(): Int = 1

        companion object {
          suspend fun open(): Int = 1
          fun make(): Int = 2
        }
      }

      @JvmInline
      value class Tag(val label: String) {
        suspend fun fetch(): Int = 1
        fun len(): Int = label.length
      }

      class Box<T>(val item: T) {
        suspend fun unbox(): Int = 1
        fun peek(): Int = 1
      }

      interface Pacer {
        suspend fun pace(): Int
        fun steps(): Int
      }

      class Kennel {
        suspend fun settle(): Int = 1
      }

      sealed class Nap {
        abstract class Deep : Nap() {
          abstract fun depth(): Int
          abstract suspend fun dream(): Int
          abstract fun ticks(): Flow<Int>
          abstract fun onNap(cb: (Int) -> Unit)
        }

        class Light : Nap()
      }

      sealed class Trip {
        sealed class Leg : Trip() {
          abstract fun miles(): Int

          class Walk : Leg() {
            override fun miles(): Int = 1
          }
        }
      }

      interface Carrier {
        suspend fun carry(): Int
      }

      fun carrier(): Carrier = object : Carrier {
        override suspend fun carry(): Int = 1
      }

      interface Holder<T> {
        suspend fun hold(): Int
      }

      suspend fun List<Int>.total(): Int = sum()
    """.trimIndent(),
  )

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      sources,
      processorOptions = mapOf("nuget.rootPackage" to "tier1.suspendowners"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  /** The warnings that name [member] as the skipped declaration, not merely mention it. */
  private fun warningsNaming(member: String): List<String> {
    val declaration = Regex("Skipping ${Regex.escape(member)}[(:]")
    return result.kspWarnings.filter { warning -> declaration.containsMatchIn(warning) }
  }

  /** C# lines that are not comments: generated `///` remarks deliberately name skipped members. */
  private fun declarations(): List<String> =
    result.generatedCSharp.lines().filterNot { line -> line.trimStart().startsWith("//") }

  @Test
  fun `a suspend member on an owner no suspend route carries is named exactly once`() {
    listOf(
      // A receiver the suspend route has no slot for: every owner binds now (ADR-229, ADR-232).
      "tier1.suspendowners.total",
    ).forEach { member ->
      val matches: List<String> = warningsNaming(member)
      assertEquals(1, matches.size, "$member must be named once; kspWarnings=${result.kspWarnings}")
      val warning: String = matches.single()
      assertTrue(
        ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_COMBINATION.name in warning,
        "$member is a structural suspend drop; got=$warning",
      )
      assertTrue(
        "as an extension on any other receiver" in warning,
        "$member must read the widened suspend sentence; got=$warning",
      )
    }
  }

  /**
   * ADR-229: an object's, a companion's and a value class's suspend members bind on the static
   * suspend route, one `_async` export each and a static (or, on the struct, instance) `XAsync`.
   */
  @Test
  fun `a suspend member on an object, a companion or a value class binds and is not named`() {
    listOf(
      "tier1.suspendowners.Depot.restock" to "public static Task<int> RestockAsync(",
      "tier1.suspendowners.Den.Companion.open" to "public static Task<int> OpenAsync(",
      "tier1.suspendowners.Tag.fetch" to "public Task<int> FetchAsync(",
    ).forEach { (member, declaration) ->
      assertEquals(0, warningsNaming(member).size, "$member binds; ${result.kspWarnings}")
      assertTrue(
        declarations().any { line -> declaration in line },
        "$member declares `$declaration`",
      )
    }
    listOf("_depot_restock_async", "_den_companion_open_async", "_tag_fetch_async")
      .forEach { export -> assertTrue(export in result.generated, "$export is exported") }
  }

  @Test
  fun `a suspend member on a generic class binds over the erased receiver and is not named`() {
    // ADR-219: the suspend route reads `Box<Any?>`, so it is no longer a generic-owner drop.
    assertEquals(0, warningsNaming("tier1.suspendowners.Box.unbox").size, "${result.kspWarnings}")
    assertTrue("asStableRef<tier1.suspendowners.Box<Any?>>().get()" in result.generated)
  }

  /**
   * The sealed route declares no abstract member on a nested `sealed` arm, so the ABSTRACT deferral
   * there used to be silent: it is a named SEALED_SUBCLASS_UNROUTED drop now. An `abstract` arm's
   * abstract members have a route since the backing wrapper (the next test).
   */
  @Test
  fun `an abstract member a sealed arm declares is named exactly once`() {
    listOf(
      "tier1.suspendowners.Trip.Leg.miles" to "Miles",
    ).forEach { (member, csharp) ->
      val declared: Boolean = declarations().any { line ->
        Regex("\\babstract\\b.*\\b$csharp\\s*\\(").containsMatchIn(line)
      }
      assertTrue(!declared, "$member is not declared abstract on the C# arm today")
      val matches: List<String> = warningsNaming(member)
      assertEquals(1, matches.size, "$member must be named once; kspWarnings=${result.kspWarnings}")
      assertTrue(
        ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_COMBINATION.name in matches.single() &&
            "member of a sealed subclass" in matches.single(),
        "$member is a sealed-arm drop; got=$matches",
      )
    }
  }

  /**
   * An `abstract` arm's plain and lambda-parameter abstract members are declared `abstract` on the
   * arm and overridden by the arm's backing wrapper over call-through exports, so they bind and are
   * never named.
   */
  @Test
  fun `an abstract arm's plain abstract members are declared and not named`() {
    listOf(
      "tier1.suspendowners.Nap.Deep.depth" to "public abstract int Depth();",
      "tier1.suspendowners.Nap.Deep.onNap" to "public abstract void OnNap(Action<int> cb);",
    ).forEach { (member, declaration) ->
      assertTrue(
        declarations().any { line -> declaration in line },
        "$member is declared abstract on the C# arm",
      )
      assertEquals(
        0, warningsNaming(member).size, "$member binds; kspWarnings=${result.kspWarnings}",
      )
    }
    listOf("public override int Depth()", "public override void OnNap(Action<int> cb)")
      .forEach { override ->
        assertTrue(
          declarations().any { line -> override in line },
          "the arm's backing wrapper declares `$override`",
        )
      }
  }

  /**
   * The false positive the ABSTRACT naming must not introduce: the arm suspend and Flow routes read
   * the declaration themselves and declare an abstract async member on the arm.
   */
  @Test
  fun `an abstract async member on an abstract arm stays declared and is not named`() {
    listOf(
      "tier1.suspendowners.Nap.Deep.dream" to "DreamAsync",
      "tier1.suspendowners.Nap.Deep.ticks" to "Ticks",
    ).forEach { (member, csharp) ->
      assertTrue(
        declarations().any { line -> Regex("\\b$csharp\\s*\\(").containsMatchIn(line) },
        "$member binds on the arm through its legacy route",
      )
      assertEquals(
        0,
        warningsNaming(member).size,
        "$member binds; kspWarnings=${result.kspWarnings}",
      )
    }
  }

  @Test
  fun `a suspend member on a reachable interface binds and a generic one is named once`() {
    assertTrue(
      declarations().any { line -> Regex("\\bCarryAsync\\s*\\(").containsMatchIn(line) },
      "Carrier is returned, so ADR-174 declares CarryAsync on ICarrier",
    )
    assertEquals(
      0,
      warningsNaming("tier1.suspendowners.Carrier.carry").size,
      "${result.kspWarnings}",
    )
    val hold: List<String> = result.kspWarnings.filter { "tier1.suspendowners.Holder.hold" in it }
    assertEquals(1, hold.size, "Holder.hold must be named once; kspWarnings=${result.kspWarnings}")
  }

  @Test
  fun `the suspend drops are absent from the C# and the controls still bind`() {
    val declarations: List<String> = declarations()
    // ADR-232: `Pacer` is an interface nothing returns, and `IPacer` still declares `PaceAsync`.
    assertTrue(
      declarations.any { line -> "Task<int> PaceAsync(" in line },
      "an interface nothing returns declares its suspend member",
    )
    listOf("Total", "Hold").forEach { member ->
      val leaks: List<String> = declarations.filter { line ->
        Regex("\\b${member}(Async)?\\s*[(<]").containsMatchIn(line)
      }
      assertTrue(leaks.isEmpty(), "$member is skipped, so no C# may declare it; got=$leaks")
    }
    listOf("Count(", "Size(", "Make(", "Len(", "Peek(", "SettleAsync(", "Steps(", "UnboxAsync(")
      .forEach { member ->
        assertTrue(
          declarations.any { line -> member in line },
          "$member is a control member and must still bind",
        )
      }
    listOf("count", "size", "make", "len", "peek", "settle", "steps").forEach { name ->
      assertTrue(
        result.kspWarnings.none { warning -> warning.contains(".$name") },
        "$name binds, so it must not be named; kspWarnings=${result.kspWarnings}",
      )
    }
  }
}
