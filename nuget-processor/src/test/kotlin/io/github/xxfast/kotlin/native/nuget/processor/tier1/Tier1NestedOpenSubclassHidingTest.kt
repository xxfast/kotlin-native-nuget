package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-116 amendment (2026-11-09, issue #613): a subclass nested inside its own `open` base sees the
 * base's `private static extern`s, so each extern it redeclares with the SAME signature is CS0108
 * and renders `new`. One with the same name over different parameters is an overload, where `new`
 * is CS0109, and a top-level subclass sees none of them. Both are fatal under
 * `TreatWarningsAsErrors`, so [Tier1CSharpCompile.assertCompiles] is the arbiter; the line checks
 * below pin which externs carry the modifier.
 */
class Tier1NestedOpenSubclassHidingTest {

  @Test
  fun `a nested open subclass marks only the externs whose signature hides an enclosing base's`() {
    val result = Tier1Harness.run(
      """
      package tier1.orchard

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      open class Fruit(val name: String? = null) {
        open fun ripen(): String = "fruit"

        open val colour: String get() = "green"

        open val ticks: Flow<Int> get() = flowOf(1)

        open fun visit(block: (String) -> Unit) = block("fruit")

        class Apple(name: String? = "apple") : Fruit(name) {
          override fun ripen(): String = "apple"

          override val colour: String get() = "red"

          override fun visit(block: (String) -> Unit) = block("apple")
        }

        class Pear(val ripeness: Int) : Fruit("pear")

        open class Stone(name: String? = "stone") : Fruit(name) {
          override fun ripen(): String = "stone"

          class Plum(name: String? = "plum") : Stone(name) {
            override fun ripen(): String = "plum"
          }
        }

        class Pear2(name: String? = "pear2") : Stone(name) {
          override fun ripen(): String = "pear2"
        }
      }

      class Quince(name: String? = "quince") : Fruit(name) {
        override fun ripen(): String = "quince"
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected the orchard to compile; got: ${result.compileErrors}")

    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop.Orchard;
      static class Consumer
      {
          static string Run()
          {
              using Fruit.Apple apple = new Fruit.Apple();
              using Fruit.Pear pear = new Fruit.Pear(3);
              using Fruit.Stone.Plum plum = new Fruit.Stone.Plum();
              using Fruit.Pear2 pear2 = new Fruit.Pear2();
              using Quince quince = new Quince();
              return apple.Ripen() + apple.Colour + pear.Ripeness + plum.Ripen() + pear2.Ripen() +
                  quince.Ripen();
          }
      }
      """.trimIndent(),
    )

    val code: String = result.generatedCSharp.withoutDocComments()

    val apple: List<String> = code.hidingExterns("Apple")
    assertTrue(apple.any { "Native_Create(" in it }, "Apple hides Fruit's create: $apple")
    assertTrue(apple.any { "Native_Dispose(" in it }, "Apple hides Fruit's dispose: $apple")
    assertTrue(apple.any { "Native_Get_colour(" in it }, "Apple hides the getter: $apple")
    assertTrue(apple.any { "Ripen" in it || "ripen" in it }, "Apple hides the method: $apple")
    assertTrue(apple.any { "isit" in it }, "Apple hides the raw callback route: $apple")

    // A different constructor shape is an overload: only the dispose hides.
    val pear: List<String> = code.hidingExterns("Pear")
    assertTrue(pear.none { "Native_Create(" in it }, "Pear's create is an overload: $pear")
    assertTrue(pear.any { "Native_Dispose(" in it }, "Pear hides Fruit's dispose: $pear")

    // Two levels in: Plum is inside Stone and Fruit, and hides through both.
    val plum: List<String> = code.hidingExterns("Plum")
    assertTrue(plum.any { "Native_Create(" in it }, "Plum hides the create: $plum")
    assertTrue(plum.any { "Native_Dispose(" in it }, "Plum hides the dispose: $plum")

    // Nested in Fruit but not in Stone: Stone's privates are out of reach, Fruit's are not.
    val pear2: List<String> = code.hidingExterns("Pear2")
    assertTrue(pear2.any { "Native_Dispose(" in it }, "Pear2 hides Fruit's dispose: $pear2")

    assertEquals(emptyList(), code.hidingExterns("Quince"), "a top-level subclass hides nothing")
  }

  /** The `new static extern` lines of class [name]'s own block, nested classes excluded. */
  private fun String.hidingExterns(name: String): List<String> {
    val lines: List<String> = lines()
    val start: Int = lines.indexOfFirst { line -> Regex("""\bclass $name\b""").containsMatchIn(line) }
    check(start >= 0) { "no class $name in the generated C#" }
    val indent: Int = lines[start].indexOfFirst { !it.isWhitespace() }
    val body: List<String> = lines.drop(start + 1)
      .takeWhile { line -> !(line.isNotBlank() && line.indexOfFirst { !it.isWhitespace() } == indent && line.trim() == "}") }
    val member: String = " ".repeat(indent + 4)
    return body
      .filter { line -> line.startsWith(member) && !line.startsWith("$member ") }
      .filter { line -> "new static extern" in line }
  }
}
