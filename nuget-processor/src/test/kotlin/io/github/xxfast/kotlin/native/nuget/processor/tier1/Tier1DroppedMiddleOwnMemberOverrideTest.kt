package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-101: an exported class overriding a member its *dropped* base introduces, one the kept base
 * does not declare. `Dinghy : Skiff(dropped) : Vessel` with `open fun tack()` on `Skiff` only.
 *
 * The nearest overridee sits on a class, so the C# `override` question used to answer yes, but the
 * class it sits on has no generated C# type: the C# base (`Vessel`) has no `Tack` to override, and
 * `public override string Tack()` is CS0115. The member is re-homed as the exported class's own,
 * so it renders without `override`. A member the dropped base itself overrides from the kept base
 * (`steer`) still renders `override`. Both a non-generic and a generic kept base are covered.
 */
class Tier1DroppedMiddleOwnMemberOverrideTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      mapOf(
        "Hidden.kt" to """
          package tier1.ownhidden

          import tier1.own.Crate
          import tier1.own.Vessel

          open class Skiff : Vessel() {
            open fun tack(): String = "skiff tack"
            open val draft: Int get() = 1
            override fun steer(): String = "skiff steer"
          }

          open class Keel : Crate<Int>(7) {
            open fun weigh(): String = "keel weigh"
            open val beam: Int get() = 2
            override fun steer(): String = "keel steer"
          }
        """.trimIndent(),
        "Boats.kt" to """
          package tier1.own

          import tier1.ownhidden.Keel
          import tier1.ownhidden.Skiff

          open class Vessel {
            open fun steer(): String = "vessel steer"
          }

          open class Crate<T>(val item: T) {
            open fun steer(): String = "crate steer"
          }

          class Dinghy : Skiff() {
            override fun tack(): String = "dinghy tack"
            override val draft: Int get() = 3
            override fun steer(): String = "dinghy steer"
          }

          class Barge : Keel() {
            override fun weigh(): String = "barge weigh"
            override val beam: Int get() = 4
            override fun steer(): String = "barge steer"
          }
        """.trimIndent(),
      ),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.own"),
    )
  }

  @Test
  fun `an override of a member only the dropped base declares is not a C# override`() {
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    val cs: String = result.generatedCSharp

    mapOf(
      "Dinghy" to listOf("string Tack()", "int Draft"),
      "Barge" to listOf("string Weigh()", "int Beam"),
    ).forEach { (name, members) ->
      val block: String = classBlock(cs, name)
      members.forEach { member ->
        assertTrue(
          Regex("public (virtual )?${Regex.escape(member)}").containsMatchIn(block),
          "expected $name to declare `$member`; got: $block",
        )
        assertFalse(
          block.contains("public override $member"),
          "expected $name's `$member` not to override (CS0115, the C# base has none); got: $block",
        )
      }
      assertContains(block, "public override string Steer()")
    }

    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop;
      class Consumer {
        static string Use() {
          using Dinghy dinghy = new Dinghy();
          using Barge barge = new Barge();
          Vessel vessel = dinghy;
          Crate<int> crate = barge;
          return dinghy.Tack() + dinghy.Draft + vessel.Steer() + barge.Weigh() + barge.Beam +
              crate.Steer();
        }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  /**
   * ADR-075's read-only-base guard, same rule: `override var` of a dropped base's `open val` that
   * the kept base lacks ([draft], [beam]) has no get-only C# base property to widen, so the setter
   * binds. When the kept base does carry the `val` ([rig], [hull]), the dropped hop changes
   * nothing: the C# override stays get-only (CS0546 otherwise), even when the dropped hop is the
   * one that widened it to `var` ([mast]).
   */
  private val setters: Tier1Result by lazy {
    Tier1Harness.run(
      mapOf(
        "Hidden.kt" to """
          package tier1.ownsetterhidden

          import tier1.ownsetter.Crate
          import tier1.ownsetter.Vessel

          open class Skiff : Vessel() {
            open val draft: Int = 1
            override val rig: String = "skiff rig"
            override var mast: Int = 2
          }

          open class Keel : Crate<Int>(7) {
            open val beam: Int = 2
            override val hull: String = "keel hull"
          }
        """.trimIndent(),
        "Boats.kt" to """
          package tier1.ownsetter

          import tier1.ownsetterhidden.Keel
          import tier1.ownsetterhidden.Skiff

          open class Vessel {
            open val rig: String = "vessel rig"
            open val mast: Int = 1
          }

          open class Crate<T>(val item: T) {
            open val hull: String = "crate hull"
          }

          class Dinghy : Skiff() {
            override var draft: Int = 3
            override var rig: String = "dinghy rig"
            override var mast: Int = 3
          }

          class Barge : Keel() {
            override var beam: Int = 4
            override var hull: String = "barge hull"
          }
        """.trimIndent(),
      ),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.ownsetter"),
    )
  }

  @Test
  fun `an override var of a val only the dropped base declares keeps its setter`() {
    assertTrue(setters.kspErrors.isEmpty(), "kspErrors=${setters.kspErrors}")
    assertTrue(setters.compiledClean, "compileErrors=${setters.compileErrors}")
    val cs: String = setters.generatedCSharp
    val kotlin: String = setters.generated

    listOf(
      Triple("Dinghy", "Draft", "Rig"),
      Triple("Barge", "Beam", "Hull"),
    ).forEach { (name, own, inherited) ->
      val block: String = classBlock(cs, name)
      val prefix: String = name.lowercase()
      assertTrue(
        Regex("public (virtual )?int $own\\b").containsMatchIn(block),
        "expected $name to declare `$own` as its own property; got: $block",
      )
      assertContains(kotlin, "@CName(\"library_${prefix}_set_${own.lowercase()}\")")
      assertContains(block, "public override string $inherited")
      assertFalse(
        kotlin.contains("@CName(\"library_${prefix}_set_${inherited.lowercase()}\")"),
        "expected $name.$inherited to stay get-only over the kept base's val (CS0546)",
      )
    }

    assertContains(classBlock(cs, "Dinghy"), "public override int Mast")
    assertFalse(
      kotlin.contains("@CName(\"library_dinghy_set_mast\")"),
      "expected Dinghy.Mast to stay get-only: the kept Vessel declares it a val (CS0546)",
    )

    Tier1CSharpCompile.assertCompiles(
      setters,
      """
      using Interop;
      class Consumer {
        static string Use() {
          using Dinghy dinghy = new Dinghy();
          using Barge barge = new Barge();
          dinghy.Draft = 5;
          barge.Beam = 6;
          Vessel vessel = dinghy;
          Crate<int> crate = barge;
          return vessel.Rig + crate.Hull + dinghy.Draft + barge.Beam;
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
