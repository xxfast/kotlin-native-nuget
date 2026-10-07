package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A bare KDoc `[Odd]` resolves the way Kotlin resolves it at the documented declaration: its own
 * class scope and package first. Two packages each declaring `Odd` must each cref their own, a
 * third package that declares none must cref neither, and a package whose own `Odd` is not in the
 * generated file must not borrow another package's.
 */
class Tier1KdocLinkScopeTest {

  /** A root package, so each fixture package is its own C# namespace rather than all `Interop`. */
  private val rootOptions: Map<String, String> = mapOf("nuget.rootPackage" to "tier1")

  private val one: String = """
    package tier1.linkone

    /** The first package's odd cat. */
    class Odd(val n: Int)

    /** Counts the first [Odd] cat. */
    fun countOne(odd: Odd): Int = odd.n
  """.trimIndent()

  private val two: String = """
    package tier1.linktwo

    /** The second package's odd cat. */
    class Odd(val n: Int)

    /** Counts the second [Odd] cat. */
    fun countTwo(odd: Odd): Int = odd.n
  """.trimIndent()

  private val three: String = """
    package tier1.linkthree

    /** Names an [Odd] cat it never declares. */
    fun countThree(): Int = 3
  """.trimIndent()

  private val four: String = """
    package tier1.linkfour

    internal class Odd(val n: Int)

    /** Keeps its own [Odd] cat to itself. */
    fun countFour(): Int = 4

    class Holder {
      internal class Even(val n: Int)

      /** Peeks at the holder's own [Even] cat. */
      fun peek(): Int = 1
    }

    /** A shelf beside its own [Odd] cat. */
    class Shelf(val size: Int)

    /** A perch near its own [Odd] cat. */
    interface Perch {
      fun sit(): Int
    }

    /** A mood about its own [Odd] cat. */
    enum class Mood { CALM }

    /** A keeper of its own [Odd] cat. */
    object Keeper {
      fun keep(): Int = 1
    }

    /** A pounce at its own [Odd] cat. */
    sealed class Pounce {
      class High(val metres: Int) : Pounce()
    }
  """.trimIndent()

  private val five: String = """
    package tier1.linkfive

    /** The fifth package's even cat. */
    class Even(val n: Int)
  """.trimIndent()

  /** The one generated summary line whose text starts with [summary]. */
  private fun summaryLine(generated: String, summary: String): String = generated.lines()
    .singleOrNull { line -> line.contains("<summary>$summary") }
    ?: error("Not exactly one summary starting '$summary' in:\n$generated")

  /** Each link's namespace is read off the type's own declaration, not guessed. */
  private fun crefOf(generated: String, owner: String): String {
    val namespace: String = generated.lines()
      .filter { line -> line.startsWith("namespace ") }
      .map { line -> line.removePrefix("namespace ").trim().trimEnd(';', '{').trim() }
      .single { name -> name.endsWith(owner, ignoreCase = true) }
    return "<see cref=\"global::$namespace.Odd\"/>"
  }

  @Test
  fun `a bare link in each of two packages crefs its own package's type`() {
    val generated: String = Tier1Harness.run(
      sources = mapOf("One.kt" to one, "Two.kt" to two, "Three.kt" to three),
      processorOptions = rootOptions,
    ).generatedCSharp

    val first: String = summaryLine(generated, "Counts the first ")
    val second: String = summaryLine(generated, "Counts the second ")
    assertTrue(first.contains(crefOf(generated, "linkone")), first)
    assertTrue(second.contains(crefOf(generated, "linktwo")), second)
  }

  @Test
  fun `a bare link from a package declaring neither stays plain code`() {
    val generated: String = Tier1Harness.run(
      sources = mapOf("One.kt" to one, "Two.kt" to two, "Three.kt" to three),
      processorOptions = rootOptions,
    ).generatedCSharp

    val line: String = summaryLine(generated, "Names an ")
    assertTrue(line.contains("Names an <c>Odd</c> cat"), line)
  }

  @Test
  fun `a link to an own type the file does not declare never borrows another package's`() {
    val generated: String = Tier1Harness.run(
      sources = mapOf("One.kt" to one, "Four.kt" to four, "Five.kt" to five),
      processorOptions = rootOptions,
    ).generatedCSharp

    // `tier1.linkone.Odd` and `tier1.linkfive.Even` are each the only declared type of their
    // name, so a file-wide simple-name match would cref them; Kotlin resolves both links to the
    // internal types beside the documented declarations instead.
    val own: String = summaryLine(generated, "Keeps its own ")
    assertTrue(own.contains("<c>Odd</c>"), own)
    val nested: String = summaryLine(generated, "Peeks at the ")
    assertTrue(nested.contains("<c>Even</c>"), nested)
    // A type's own comment takes the same route through every declaration family.
    for (summary in listOf("A shelf ", "A perch ", "A mood ", "A keeper ", "A pounce ")) {
      val line: String = summaryLine(generated, summary)
      assertTrue(line.contains("<c>Odd</c>"), line)
    }
  }
}
