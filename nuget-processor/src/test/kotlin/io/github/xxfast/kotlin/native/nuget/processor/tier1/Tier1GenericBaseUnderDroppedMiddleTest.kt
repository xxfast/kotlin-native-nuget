package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-101: a kept *generic* base under a dropped middle class, `Barge : Keel : Crate<Int>` with
 * `Keel` outside the export root.
 *
 * Two things went wrong, and each fixture pins one of them:
 * - Membership. KSP parents a base member that mentions `T` to the first class that closes `T`, so
 *   `Crate<T>.item` and a non-open `describe(tag: T)` came back parented to the dropped `Keel` and
 *   were re-homed onto Barge as `public override int Item` (CS0506) and a second `Describe(int)`
 *   (CS0108). Only a member the dropped middle actually *declares* re-homes.
 * - Base spelling. The base list closes `Crate` through every dropped hop, substituting each hop's
 *   type arguments, so `Keel<U> : Crate<U>` under `Barge : Keel<Int>` spells `Crate<int>`, never
 *   `Crate<U>` (CS0246). An abstract leaf's backing restates the base's abstract member at the same
 *   closed type (`public override int Pick()`).
 *
 * Every fixture's generated C# is built with warnings as errors.
 */
class Tier1GenericBaseUnderDroppedMiddleTest {

  /**
   * Three dropped middles over one generic base: [Keel] overrides everything and its subclass is
   * silent, [Hold] is silent and its subclasses either override everything or nothing.
   */
  private val membership: Tier1Result by lazy {
    Tier1Harness.run(
      mapOf(
        "Hidden.kt" to """
          package tier1.underhidden

          import tier1.under.Crate

          open class Keel : Crate<Int>(7) {
            override fun tag(x: Int): String = "keel:${'$'}x"
            override fun weigh(): String = "keel"
            override val label: String get() = "keel"
            fun keelOwn(): Int = 1
          }

          open class Hold : Crate<Int>(8)
        """.trimIndent(),
        "Boats.kt" to """
          package tier1.under

          import tier1.underhidden.Hold
          import tier1.underhidden.Keel

          open class Crate<T>(val item: T) {
            fun describe(tag: T): String = "${'$'}tag:${'$'}item"
            fun plain(): String = "plain"
            open fun tag(x: T): String = "tag:${'$'}x"
            open fun weigh(): String = "crate"
            open val label: String get() = "crate"
            open val first: T get() = item
          }

          class Barge : Keel()

          class Scow : Hold() {
            override fun tag(x: Int): String = "scow:${'$'}x"
            override fun weigh(): String = "scow"
            override val label: String get() = "scow"
            override val first: Int get() = 9
          }

          class Punt : Hold()
        """.trimIndent(),
      ),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.under"),
    )
  }

  /** A generic dropped middle, and an abstract leaf whose backing restates the base's member. */
  private val genericMiddle: Tier1Result by lazy {
    Tier1Harness.run(
      mapOf(
        "Hidden.kt" to """
          package tier1.genericmiddlehidden

          import tier1.genericmiddle.Bin
          import tier1.genericmiddle.Crate

          open class Keel<U>(item: U) : Crate<U>(item)

          abstract class Hopper<U> : Bin<U>()

          abstract class Chute : Bin<Int>()
        """.trimIndent(),
        "Boats.kt" to """
          package tier1.genericmiddle

          import tier1.genericmiddlehidden.Chute
          import tier1.genericmiddlehidden.Hopper
          import tier1.genericmiddlehidden.Keel

          open class Crate<T>(val item: T) {
            fun describe(tag: T): String = "${'$'}tag:${'$'}item"
            open fun weigh(): String = "crate"
          }

          abstract class Bin<T> {
            abstract fun pick(): T
          }

          class Barge : Keel<Int>(1) {
            override fun weigh(): String = "barge"
          }

          abstract class Sled : Hopper<String>()

          abstract class Slide : Chute()
        """.trimIndent(),
      ),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.genericmiddle"),
    )
  }

  @Test
  fun `both fixtures generate without a processor error`() {
    listOf(membership, genericMiddle).forEach { result ->
      assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
      assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    }
  }

  /**
   * The generic base keeps every member it declares, and no subclass restates one it does not
   * override: not the non-open `Item`, `Describe(T)` or `Plain()`, whichever middle sits between.
   */
  @Test
  fun `a member the dropped middle does not declare stays on the generic base`() {
    val cs: String = membership.generatedCSharp
    val kotlin: String = membership.generated

    listOf("Barge", "Scow", "Punt").forEach { name ->
      assertContains(cs, "public class $name : Crate<int>\n")
      val block: String = classBlock(cs, name)
      listOf("\\w+ Item\\b", "string Describe[(]", "string Plain[(]").forEach { member ->
        assertFalse(
          Regex("public [a-z ]*$member").containsMatchIn(block),
          "expected $name to inherit `$member` from Crate<int>; got: $block",
        )
      }
      val prefix: String = name.lowercase()
      listOf("get_item", "describe", "plain").forEach { export ->
        assertFalse(
          kotlin.contains("@CName(\"library_${prefix}_$export\")"),
          "expected no phantom export ${prefix}_$export",
        )
      }
    }

    val crate: String = classBlock(cs, "Crate<T>")
    listOf(
      "public T Item",
      "public string Describe(T tag)",
      "public string Plain()",
      "public virtual string Tag(T x)",
      "public virtual string Weigh()",
      "public virtual string Label",
      "public virtual T First",
    ).forEach { member -> assertContains(crate, member) }
  }

  /**
   * A member the dropped middle declares is re-homed, whether the exported class is silent
   * ([Barge] over [Keel], including `Keel`'s override of the `T`-taking `tag`) or overrides it
   * itself ([Scow] over the silent [Hold]). [Punt] declares nothing of its own.
   */
  @Test
  fun `an override on the dropped middle or the exported class projects at the closed type`() {
    val cs: String = membership.generatedCSharp

    val barge: String = classBlock(cs, "Barge")
    listOf(
      "public override string Tag(int x)",
      "public override string Weigh()",
      "public override string Label",
      "public int KeelOwn()",
    ).forEach { member -> assertContains(barge, member) }
    assertFalse(Regex("public [a-z ]*\\w+ First\\b").containsMatchIn(barge), barge)

    val scow: String = classBlock(cs, "Scow")
    listOf(
      "public override string Tag(int x)",
      "public override string Weigh()",
      "public override string Label",
      "public override int First",
    ).forEach { member -> assertContains(scow, member) }

    val punt: String = classBlock(cs, "Punt")
    assertFalse(
      Regex("public [a-z ]*\\w+ (Tag|Weigh|Label|First|KeelOwn)\\b").containsMatchIn(punt),
      "expected Punt to declare nothing beyond its constructor; got: $punt",
    )

    Tier1CSharpCompile.assertCompiles(
      membership,
      """
      using Interop;
      class Consumer {
        static string Use() {
          using Barge barge = new Barge();
          using Scow scow = new Scow();
          using Punt punt = new Punt();
          Crate<int> crate = barge;
          return crate.Tag(1) + crate.Weigh() + crate.Label + crate.Item + crate.Describe(2) +
              barge.KeelOwn() + scow.First + scow.Tag(3) + punt.Plain() + punt.Item;
        }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  /**
   * `Keel<U> : Crate<U>` under `Barge : Keel<Int>` closes the base as `Crate<int>`, and an abstract
   * leaf below a dropped middle, generic ([Sled] over `Hopper<U>`) or not ([Slide] over `Chute`),
   * restates the base's abstract `Pick()` on its backing at the closed type.
   */
  @Test
  fun `the base list and an abstract leaf's backing close the base through every dropped hop`() {
    val cs: String = genericMiddle.generatedCSharp

    assertContains(cs, "public class Barge : Crate<int>\n")
    assertContains(classBlock(cs, "Barge"), "public override string Weigh()")
    assertContains(cs, "public abstract class Sled : Bin<string>\n")
    assertContains(cs, "public abstract class Slide : Bin<int>\n")
    assertContains(classBlock(cs, "Sled"), "public override string Pick()")
    assertContains(classBlock(cs, "Slide"), "public override int Pick()")
    assertFalse(Regex("\\bU\\b").containsMatchIn(cs), "an unsubstituted middle parameter leaked")
    assertEquals(
      0,
      Regex("public [a-z ]*\\w+ Item\\b").findAll(classBlock(cs, "Barge")).count(),
      "expected Barge to inherit Item",
    )

    Tier1CSharpCompile.assertCompiles(
      genericMiddle,
      """
      using Interop;
      class Consumer {
        static string Use(Sled sled, Slide slide) {
          using Barge barge = new Barge();
          Crate<int> crate = barge;
          Bin<string> bin = sled;
          return crate.Weigh() + crate.Item + bin.Pick() + slide.Pick();
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
