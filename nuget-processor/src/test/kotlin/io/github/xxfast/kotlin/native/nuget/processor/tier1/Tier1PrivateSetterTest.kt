package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-075 amendment (ROADMAP "a public `var` with `private set`"): a `var` whose setter is
 * narrower than public (`private set`, `protected set`, `internal set`) binds get-only on every
 * owner shape the property planner serves. Before the gate, the planner keyed on KSP's
 * `isMutable` alone (true for every `var`), so the generated Kotlin called a setter it could not
 * see ("Cannot access '...': it is private"), and `internal set` leaked a public C# setter.
 *
 * The widened override (`protected set` on the base, `public set` on the override) is the second
 * edit site: the base binds get-only, so the override must drop its setter too (CS0546) and says
 * so with the ADR-075 dropped-setter diagnostic.
 */
class Tier1PrivateSetterTest {

  private val fixture: String = """
    package tier1.privatesetter

    class Counter {
      var clicks: Int = 0
        private set
      var maybe: Int? = null
        private set
      var tags: List<String> = emptyList()
        private set
      var label: String = ""
        internal set
      var plain: Int = 0
    }

    var Counter.nick: String
      get() = "n"
      private set(value) {}

    var topLevelName: String = ""
      private set

    open class Gauge {
      open var level: Int = 0
        protected set
    }

    class Dial : Gauge() {
      override var level: Int = 1
        public set
    }

    abstract class Meter {
      abstract var reading: Int
        protected set
    }

    class Ammeter : Meter() {
      override var reading: Int = 3
        public set
    }

    object Registry {
      var count: Int = 0
        private set
    }

    class Factory {
      companion object {
        var instances: Int = 0
          private set
      }
    }

    sealed class Shape {
      class Circle : Shape() {
        var radius: Double = 1.0
          private set
      }
    }

    interface Named {
      val name: String
    }

    class Person : Named {
      override var name: String = ""
        private set
    }

    data class Point(val x: Int) {
      var visits: Int = 0
        private set
    }

    class Box<T>(initial: T) {
      var item: T = initial
        private set
    }
  """.trimIndent()

  private val result: Tier1Result by lazy { Tier1Harness.run(fixture) }

  /** The property names behind every `_set_` symbol in [text], each once (a Kotlin export appears
   * under both its `@CName` and its function name). */
  private fun setterExports(text: String): List<String> =
    Regex("""\w+_set_(\w+)""").findAll(text).map { it.groupValues[1] }.distinct().toList()

  @Test
  fun `a var with a narrower setter compiles on every owner shape`() {
    assertTrue(
      result.compiledClean,
      "every narrowed setter must be absent from the generated Kotlin; " +
          "got: ${result.compileErrors}",
    )
  }

  @Test
  fun `only the public setters survive as set exports on both sides`() {
    val kotlinSetters: List<String> = setterExports(result.generated)
    val csharpSetters: List<String> = setterExports(result.generatedCSharp)

    // `Counter.plain` is the control: a default (public) setter still binds.
    assertTrue(
      "counter_set_plain" in result.generated,
      "Counter.plain keeps its setter; kotlinSetters=$kotlinSetters",
    )
    // `Ammeter.reading` widens an abstract `protected set` to public; the abstract base binds
    // get-only, so the override must drop it too (asserted below). Nothing else may carry a setter.
    assertEquals(
      listOf("plain"),
      kotlinSetters,
      "only Counter.plain may export a setter; kotlinSetters=$kotlinSetters\n${result.generated}",
    )
    assertEquals(
      listOf("plain"),
      csharpSetters,
      "only Counter.plain may import a setter; csharpSetters=$csharpSetters",
    )
  }

  @Test
  fun `narrowed properties still bind their getters`() {
    val kotlin: String = result.generated
    listOf(
      "clicks", "maybe", "tags", "label", "nick", "topLevelName", "level", "reading", "count",
      "instances", "radius", "name", "visits", "item",
    ).forEach { name ->
      assertTrue(
        Regex("""_get_$name\b""").containsMatchIn(kotlin),
        "expected a getter export for $name; generated:\n$kotlin",
      )
    }
  }

  @Test
  fun `the C# side renders narrowed properties get-only`() {
    val csharp: String = result.generatedCSharp.withoutDocComments()
    assertTrue(
      "public abstract int Reading { get; }" in csharp,
      "abstract protected-set property binds get-only on the abstract class; csharp:\n$csharp",
    )
    // No `set` accessor may appear anywhere but Counter.Plain's.
    val setAccessors: Int = Regex("""\bset\b\s*[{=;]""").findAll(csharp).count()
    assertEquals(1, setAccessors, "only Counter.Plain renders a set accessor; csharp:\n$csharp")
  }

  @Test
  fun `a widened override drops its setter with the ADR-075 diagnostic`() {
    val dropped: List<String> = result.kspWarnings.filter { "CS0546" in it }
    assertTrue(
      dropped.any { "level" in it && "Gauge" in it && "no public setter" in it },
      "Dial.level widens Gauge.level's protected setter; expected the CS0546 drop naming Gauge; " +
          "kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      dropped.any { "reading" in it && "Meter" in it },
      "Ammeter.reading widens Meter.reading's protected setter; kspWarnings=${result.kspWarnings}",
    )
    assertEquals(2, dropped.size, "only the two widened overrides warn; kspWarnings=$dropped")
  }

  @Test
  fun `a narrowed generic property raises no ADR-147 setter warning`() {
    assertFalse(
      result.kspWarnings.any {
        ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name in it && "item" in it
      },
      "Box.item's setter is private, not an unsupported type-parameter setter; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `a dependency owner's narrowed setters bind get-only`() {
    val dependencyJar: File = Tier1DependencyLibrary.compile(
      """
        package dep.pset

        class Meter {
          var reading: Int = 0
            private set
          var inner: String = ""
            internal set
          var open: Int = 0
        }
      """.trimIndent(),
    )
    val dependencyResult: Tier1Result = Tier1Harness.run(
      """
        package tier1.privatesetterdep

        import dep.pset.Meter

        class Panel {
          fun meter(): Meter = Meter()
        }
      """.trimIndent(),
      processorOptions = mapOf("nuget.admit" to "dep.pset"),
      libraries = listOf(dependencyJar),
    )

    assertTrue(
      dependencyResult.compiledClean,
      "expected clean compile; got: ${dependencyResult.compileErrors}",
    )
    assertEquals(
      listOf("open"),
      setterExports(dependencyResult.generated),
      "only Meter.open may export a setter; generated:\n${dependencyResult.generated}",
    )
  }
}
