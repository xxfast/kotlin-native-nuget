package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-159: two branches of the scope-ownership logic that were written to the design but never
 * reached by a fixture.
 *
 * Arm cells: an `open` sealed arm is the base of an ordinary exported class, the sealed base
 * declares no async member, and the arm does. `forwardScopeOwner`'s walk from the subclass asks the
 * arm, and `forwardDeclaresScopeMember` answers through the arm route's own selectors. The Flow
 * method (`Nap.Curled.purrs`) and the StateFlow property (`Perch.High.view`) reach the
 * `forwardArmFlowMethods` / `forwardArmFlowProperties` branch; the suspend member
 * (`Stretch.Long.reach`) answers earlier, through `forwardSuspendRouteMethods(isArm = true)`. In
 * each, the arm owns the scope and declares the member once, and the subclass inherits both.
 *
 * Middle cells: a concrete `open` class between an abstract owner and a further subclass, on the
 * suspend route (`Groomer : Brusher : SoftBrusher`) and the Flow route
 * (`Tracker : PawTracker : NightPawTracker`). The abstract owner declares `DisposeAsync`
 * abstractly; the middle class and the leaf each carry the body as an `override`, and neither
 * declares a second scope or re-projects the member.
 *
 * Each fixture's generated C# is built with warnings as errors, so a CS0108 or CS0114 fails the
 * cell. `libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore)` is load-bearing: without it the
 * async members resolve to nothing and every absence assertion passes vacuously.
 */
class Tier1ScopeOwnerChainBranchesTest {

  private val armResult: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.armowner

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow
      import kotlinx.coroutines.flow.flowOf

      sealed class Nap {
        open class Curled(val minutes: Int) : Nap() {
          open fun purrs(): Flow<Int> = flowOf(minutes)
        }
        data class Done(val code: Int) : Nap()
      }

      class Snore(minutes: Int) : Nap.Curled(minutes) {
        override fun purrs(): Flow<Int> = flowOf(minutes, minutes + 1)
      }

      sealed class Perch {
        open class High(val height: Int) : Perch() {
          val view: StateFlow<Int> = MutableStateFlow(height)
        }
        data class Low(val code: Int) : Perch()
      }

      class Shelf(height: Int) : Perch.High(height)

      sealed class Stretch {
        open class Long(val reach: Int) : Stretch() {
          open suspend fun reach(): Int = reach
        }
        data class Short(val code: Int) : Stretch()
      }

      class Yawn(reach: Int) : Stretch.Long(reach) {
        override suspend fun reach(): Int = reach + 1
      }
      """.trimIndent(),
      fileName = "ArmOwner.kt",
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  private val middleResult: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.middleowner

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      abstract class Groomer {
        abstract suspend fun groom(): String
      }

      open class Brusher : Groomer() {
        override suspend fun groom(): String = "brushed"
      }

      class SoftBrusher : Brusher() {
        override suspend fun groom(): String = "softly brushed"
      }

      abstract class Tracker {
        abstract fun steps(): Flow<Int>
      }

      open class PawTracker : Tracker() {
        override fun steps(): Flow<Int> = flowOf(1)
      }

      class NightPawTracker : PawTracker() {
        override fun steps(): Flow<Int> = flowOf(2)
      }
      """.trimIndent(),
      fileName = "MiddleOwner.kt",
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  @Test
  fun `both fixtures generate without a processor error`() {
    listOf(armResult, middleResult).forEach { result ->
      assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
      assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    }
  }

  /** One scope per chain, on the arm; neither the sealed base nor the subclass declares one. */
  @Test
  fun `an open arm owns the scope for the ordinary class below it`() {
    val cs: String = armResult.generatedCSharp

    assertEquals(
      3,
      Regex("internal NugetScopeHandle\\? _scopeHandle;").findAll(cs).count(),
      "expected one scope field per chain, three chains; got: " +
          cs.lines().filter { "_scopeHandle;" in it || "class " in it }.map(String::trim),
    )
    listOf("Curled : Nap", "High : Perch", "Long : Stretch").forEach { arm ->
      assertContains(cs, "public class $arm, IAsyncDisposable")
    }
    listOf("Nap", "Perch", "Stretch").forEach { base ->
      assertContains(cs, "public abstract class $base : IDisposable, INugetHandle\n")
    }
    listOf("Snore : Nap.Curled", "Shelf : Perch.High", "Yawn : Stretch.Long").forEach { sub ->
      assertContains(cs, "public class $sub\n")
      val block: String = classBlock(cs, sub.substringBefore(" :"))
      assertFalse(
        Regex("ValueTask DisposeAsync[(]").containsMatchIn(block),
        "expected $sub to inherit the arm's DisposeAsync; got: $block",
      )
      assertTrue(
        block.contains("public override void Dispose()") &&
            block.contains("Interlocked.Exchange(ref _scopeHandle, null)?.Dispose();"),
        "expected $sub's Dispose to still clean up the inherited scope; got: $block",
      )
    }
  }

  /** The member projects once, on the arm: one C# declaration, one export, none on the subclass. */
  @Test
  fun `an arm-declared async member is declared once, on the arm`() {
    val cs: String = armResult.generatedCSharp
    val kotlin: String = armResult.generated

    mapOf(
      "public (virtual |override )?KotlinFlow<int> Purrs[(]" to "__nap_curled_purrs_collect",
      "public (virtual |override )?KotlinStateFlow<int> View\\b" to
          "__perch_high_get_view_collect",
      "public (virtual |override )?Task<int> ReachAsync[(]" to "__stretch_long_reach_async",
    ).forEach { (declaration, export) ->
      assertEquals(
        1,
        Regex(declaration).findAll(cs).count(),
        "expected exactly one `$declaration`; got: " +
            cs.lines().filter { Regex(declaration).containsMatchIn(it) }.map(String::trim),
      )
      assertEquals(
        1,
        Regex("@CName\\(\"library_tier1_armowner$export\"\\)").findAll(kotlin).count(),
        "expected exactly one export $export",
      )
    }
    listOf("snore", "shelf", "yawn").forEach { sub ->
      assertFalse(
        Regex("@CName\\(\"library_tier1_armowner__${sub}_(purrs|get_view|reach)")
          .containsMatchIn(kotlin),
        "expected no re-projection on $sub; got: " +
            kotlin.lines().filter { "__${sub}_" in it }.map(String::trim),
      )
    }

    Tier1CSharpCompile.assertCompiles(
      armResult,
      """
      using System.Threading.Tasks;
      using Interop;
      class Consumer {
        static async Task<int> Reach() {
          await using Yawn yawn = new Yawn(3);
          return await yawn.ReachAsync();
        }
        static async Task Purrs() {
          await using Snore snore = new Snore(2);
          await foreach (int purr in snore.Purrs()) { }
        }
        static async Task<int> View() {
          await using Shelf shelf = new Shelf(4);
          return shelf.View.Value;
        }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  /**
   * The abstract owner declares the drain; the concrete middle class is NOT the owner, so it never
   * renders `DisposeAsync` as the owner's non-virtual body, and both it and the leaf override the
   * abstract slot. Both levels repeat the scope cleanup in `Dispose()`.
   */
  @Test
  fun `a concrete open class between an abstract owner and a leaf overrides the drain`() {
    val cs: String = middleResult.generatedCSharp

    listOf(
      Triple("Groomer", "Brusher", "SoftBrusher"),
      Triple("Tracker", "PawTracker", "NightPawTracker"),
    ).forEach { (owner, middle, leaf) ->
      assertContains(cs, "public abstract class $owner : IDisposable, IAsyncDisposable")
      val ownerBlock: String = classBlock(cs, owner)
      assertTrue(
        ownerBlock.contains("public abstract ValueTask DisposeAsync();") &&
            ownerBlock.contains("internal NugetScopeHandle? _scopeHandle;"),
        "expected $owner to own the scope and declare the drain abstractly; got: $ownerBlock",
      )
      assertContains(cs, "public class $middle : $owner\n")
      assertContains(cs, "public class $leaf : $middle\n")
      listOf(middle, leaf).forEach { level ->
        val block: String = classBlock(cs, level)
        assertEquals(
          listOf("public override ValueTask DisposeAsync()"),
          Regex("public [a-z ]*ValueTask DisposeAsync[(][)]").findAll(block)
            .map { it.value }.toList(),
          "expected $level to carry the drain body as an override (CS0114 otherwise); " +
              "got: $block",
        )
        assertTrue(
          block.contains("public override void Dispose()") &&
              block.contains("Interlocked.Exchange(ref _scopeHandle, null)?.Dispose();"),
          "expected $level's Dispose to override and clean up the scope; got: $block",
        )
        assertFalse(
          block.contains("_scopeHandle;") || block.contains("NugetScopeHandle GetOrCreateScope()"),
          "expected $level to reuse $owner's scope (CS0108 otherwise); got: $block",
        )
      }
    }

    assertEquals(1, Regex("public Task<string> GroomAsync[(]").findAll(cs).count())
    assertEquals(1, Regex("public KotlinFlow<int> Steps[(]").findAll(cs).count())
    val kotlin: String = middleResult.generated
    assertContains(kotlin, "__groomer_groom_async\")")
    assertContains(kotlin, "__tracker_steps_collect\")")
    listOf("brusher", "softbrusher", "pawtracker", "nightpawtracker").forEach { level ->
      assertFalse(
        Regex("__${level}_(groom|steps)").containsMatchIn(kotlin),
        "expected no re-projection on $level",
      )
    }

    Tier1CSharpCompile.assertCompiles(
      middleResult,
      """
      using System.Threading.Tasks;
      using Interop;
      class Consumer {
        static async Task<string> Groom() {
          await using Brusher middle = new Brusher();
          await using SoftBrusher leaf = new SoftBrusher();
          return await middle.GroomAsync() + await leaf.GroomAsync();
        }
        static async Task Steps() {
          await using PawTracker middle = new PawTracker();
          await using NightPawTracker leaf = new NightPawTracker();
          await foreach (int step in middle.Steps()) { }
          await foreach (int step in leaf.Steps()) { }
        }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  /**
   * `reProjectsKeptBaseMember`'s dropped-base return, the outcome where the override is KEPT: the
   * nearest overridee sits on a base outside the export root (ADR-101) and no kept base carries the
   * member, so the exported class is the only carrier. Two shapes:
   * - `Canoe : Paddle(dropped) : Hull(kept)`: the kept base lacks the member (the final scan).
   * - `Raft : Plank(dropped)`, no kept base: `baseClassOverridee(null)` is null, so the function
   *   answers before the dropped-base walk; the guard's `superClass == null` half is unreachable.
   * Each projects its override on itself, on the suspend route and the Flow route, and owns the
   * scope. The guard's generic-kept-base half is the next cell,
   * `Barge : Keel(dropped) : Crate<Int>`.
   */
  @Test
  fun `an override whose only overridee is on a dropped base is projected on the exported class`() {
    val result: Tier1Result = Tier1Harness.run(
      mapOf(
        "Hidden.kt" to """
          package tier1.droppedhidden

          import kotlinx.coroutines.flow.Flow
          import kotlinx.coroutines.flow.flowOf
          import tier1.dropped.Hull

          open class Plank {
            open suspend fun drift(): String = "plank drifted"
            open fun ripples(): Flow<Int> = flowOf(1)
          }

          open class Paddle : Hull() {
            open suspend fun drift(): String = "paddle drifted"
            open fun ripples(): Flow<Int> = flowOf(2)
          }
        """.trimIndent(),
        "Boats.kt" to """
          package tier1.dropped

          import kotlinx.coroutines.flow.Flow
          import kotlinx.coroutines.flow.flowOf
          import tier1.droppedhidden.Paddle
          import tier1.droppedhidden.Plank

          open class Hull {
            fun weight(): Int = 3
          }

          class Raft : Plank() {
            override suspend fun drift(): String = "raft drifted"
            override fun ripples(): Flow<Int> = flowOf(3)
          }

          class Canoe : Paddle() {
            override suspend fun drift(): String = "canoe drifted"
            override fun ripples(): Flow<Int> = flowOf(4)
          }

        """.trimIndent(),
      ),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.dropped"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    val cs: String = result.generatedCSharp
    assertFalse(Regex("class (Plank|Paddle)\\b").containsMatchIn(cs), "outside the root")
    assertContains(cs, "public class Canoe : Hull, IAsyncDisposable")
    assertEquals(
      0,
      Regex("public [a-z ]*(Task<string> DriftAsync|KotlinFlow<int> Ripples)[(]")
        .findAll(classBlock(cs, "Hull")).count(),
      "expected the kept Hull to carry neither member",
    )
    listOf("Raft", "Canoe").forEach { boat ->
      val block: String = classBlock(cs, boat)
      assertEquals(
        1,
        Regex("public Task<string> DriftAsync[(]").findAll(block).count(),
        "expected $boat to project its kept override of drift; got: $block",
      )
      assertEquals(
        1,
        Regex("public KotlinFlow<int> Ripples[(]").findAll(block).count(),
        "expected $boat to project its kept override of ripples; got: $block",
      )
      assertTrue(
        block.contains("internal NugetScopeHandle? _scopeHandle;"),
        "expected $boat to own the scope; got: $block",
      )
      val prefix: String = boat.lowercase()
      assertContains(result.generated, "_${prefix}_drift_async\")")
      assertContains(result.generated, "_${prefix}_ripples_collect\")")
    }
    assertEquals(2, Regex("public Task<string> DriftAsync[(]").findAll(cs).count())
    assertEquals(2, Regex("public KotlinFlow<int> Ripples[(]").findAll(cs).count())

    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System.Threading.Tasks;
      using Interop;
      class Consumer {
        static async Task<string> Drift() {
          await using Raft raft = new Raft();
          await using Canoe canoe = new Canoe();
          await foreach (int ripple in raft.Ripples()) { }
          await foreach (int ripple in canoe.Ripples()) { }
          return await raft.DriftAsync() + await canoe.DriftAsync();
        }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  /**
   * `reProjectsKeptBaseMember`'s generic-kept-base half: `Barge : Keel(dropped) : Crate<Int>`, with
   * the generic `Crate<T>` declaring an `open suspend fun` and an `open` Flow method that both
   * `Keel` and `Barge` override. A generic class projects neither (ADR-147 refusal), so the nearest
   * overridee being on the dropped `Keel` leaves Barge as the only carrier: it projects both on
   * itself, plain (no `override`), and owns the scope. The base list closes `Crate` through the
   * dropped hop, and the generic base's `item: T` (which KSP parents to `Keel`, the class that
   * closes `T`) is not re-homed onto Barge.
   */
  @Test
  fun `an override under a dropped middle of a generic base is projected on the exported class`() {
    val result: Tier1Result = Tier1Harness.run(
      mapOf(
        "Hidden.kt" to """
          package tier1.bargehidden

          import kotlinx.coroutines.flow.Flow
          import kotlinx.coroutines.flow.flowOf
          import tier1.barge.Crate

          open class Keel : Crate<Int>(1) {
            override suspend fun load(): String = "keel loaded"
            override fun ripples(): Flow<Int> = flowOf(1)
          }
        """.trimIndent(),
        "Boats.kt" to """
          package tier1.barge

          import kotlinx.coroutines.flow.Flow
          import kotlinx.coroutines.flow.flowOf
          import tier1.bargehidden.Keel

          open class Crate<T>(val item: T) {
            open suspend fun load(): String = "crate loaded"
            open fun ripples(): Flow<Int> = flowOf(0)
          }

          class Barge : Keel() {
            override suspend fun load(): String = "barge loaded"
            override fun ripples(): Flow<Int> = flowOf(2)
          }
        """.trimIndent(),
      ),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.barge"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    val cs: String = result.generatedCSharp
    assertFalse(Regex("class Keel\\b").containsMatchIn(cs), "outside the root")
    assertContains(cs, "public class Barge : Crate<int>, IAsyncDisposable\n")
    assertEquals(
      0,
      Regex("public [a-z ]*(Task<string> LoadAsync|KotlinFlow<int> Ripples)[(]")
        .findAll(classBlock(cs, "Crate<T>")).count(),
      "expected the generic Crate to project neither member (ADR-147)",
    )
    val block: String = classBlock(cs, "Barge")
    assertEquals(
      listOf("public Task<string> LoadAsync("),
      Regex("public [a-z ]*Task<string> LoadAsync[(]").findAll(block).map { it.value }.toList(),
      "expected Barge to project its kept override of load, not as a C# override; got: $block",
    )
    assertEquals(
      listOf("public KotlinFlow<int> Ripples("),
      Regex("public [a-z ]*KotlinFlow<int> Ripples[(]").findAll(block).map { it.value }.toList(),
      "expected Barge to project its kept override of ripples; got: $block",
    )
    assertTrue(
      block.contains("internal NugetScopeHandle? _scopeHandle;"),
      "expected Barge to own the scope; got: $block",
    )
    assertFalse(
      Regex("public [a-z ]*\\w+ Item\\b").containsMatchIn(block),
      "expected Barge to inherit Item from Crate<int>, not restate it (CS0506); got: $block",
    )
    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"library_barge_load_async\")")
    assertContains(kotlin, "@CName(\"library_barge_ripples_collect\")")
    assertContains(kotlin, "@CName(\"library_crate_get_item\")")
    assertFalse(
      kotlin.contains("@CName(\"library_barge_get_item\")"),
      "expected no export for the phantom item on Barge",
    )

    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System.Threading.Tasks;
      using Interop;
      class Consumer {
        static async Task<string> Load() {
          await using Barge barge = new Barge();
          Crate<int> crate = barge;
          await foreach (int ripple in barge.Ripples()) { }
          return await barge.LoadAsync() + crate.Item;
        }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  /** The C# class body, from its declaration line to the next top-level class declaration. */
  private fun classBlock(csharp: String, name: String): String {
    val lines: List<String> = csharp.lines()
    val start: Int =
      lines.indexOfFirst { it.contains("class $name ") || it.endsWith("class $name") }
    if (start < 0) return "<no class $name in generated C#>"
    val end: Int = lines.drop(start + 1)
      .indexOfFirst { it.startsWith("    public ") && it.contains("class ") }
    return lines.drop(start).take(if (end < 0) lines.size else end + 1).joinToString("\n")
  }
}
