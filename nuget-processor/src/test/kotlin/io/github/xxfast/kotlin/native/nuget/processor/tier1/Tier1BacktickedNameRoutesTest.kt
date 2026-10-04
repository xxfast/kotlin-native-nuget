package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Backticked names on every route, not only the plan route `Tier1BacktickedNameTest` covers: the
 * legacy `suspend`, `Flow` (method, property, sealed arm), per-call lambda, stored-callback and
 * interface-bridge pair, generic-function and companion-property routes, plus value-class, enum and
 * object members, and keyword or backticked PARAMETER names on each. Every cell compiles both the
 * generated Kotlin and the generated C#.
 *
 * A keyword (`in`, `object`, `class`) used to be spelled bare at each legacy call site
 * (`obj.in()`, `tier1.kw.when()`, `pull(in)`); it binds now, backticked in Kotlin. A name with a
 * space or symbol is a named `NON_IDENTIFIER_NAME` skip on every route unless `@CSharpName`
 * declares the C# name, and then binds with every symbol and extern cleaned through `asCSymbol`.
 */
class Tier1BacktickedNameRoutesTest {

  private val identifier: Regex = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

  private fun run(source: String): Tier1Result = Tier1Harness.run(
    source,
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore, csharpNameLibrary),
  )

  private fun Tier1Result.assertBuildsOnBothHalves() {
    assertTrue(kspErrors.isEmpty(), "kspErrors=$kspErrors")
    assertTrue(compiledClean, "expected no broken Kotlin; got: $compileErrors")
    Regex("""@CName\("([^"]*)"\)""").findAll(generated).forEach { match ->
      assertTrue(identifier.matches(match.groupValues[1]), "bad @CName ${match.value}")
    }
    Regex("EntryPoint = \"([^\"]*)\"").findAll(generatedCSharp).forEach { match ->
      assertTrue(identifier.matches(match.groupValues[1]), "bad EntryPoint ${match.value}")
    }
    Tier1CSharpCompile.assertCompiles(this, "class Consumer {}", allowUnsafe = true)
  }

  private val keywords: String = """
    package tier1.routes.keywords

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flowOf

    interface Walker {
      fun walk(steps: Int)
    }

    class Leash {
      suspend fun `in`(): Int = 1
      fun `object`(): Flow<Int> = flowOf(1)
      fun `class`(onLetter: (Char) -> Unit) {}
      fun `is`(onPull: (Int) -> Unit) {}
      fun addListener(`fun`: (Int) -> Unit) {}
      fun removeListener(`fun`: (Int) -> Unit) {}
      fun addWalker(`val`: Walker) {}
      fun removeWalker(`val`: Walker) {}
      fun pull(`in`: Int, `object`: String = "x"): Int = 1
      suspend fun fetch(`in`: Int): Int = `in`
      fun stream(`in`: Int): Flow<Int> = flowOf(`in`)
      fun step(`step count`: Int, `a+b`: String = "x"): Int = `step count`
      suspend fun stepLater(`step count`: Int): Int = `step count`
      fun stepFlow(`step count`: Int): Flow<Int> = flowOf(`step count`)
      fun onStep(`step count`: Int, cb: (Char) -> Unit) {}
      val `as`: Flow<Int> = flowOf(1)

      companion object {
        val `while`: Int = 1
        fun `do`(): Int = 1
      }
    }

    suspend fun `when`(): Int = 1
    fun <T> `return`(t: T): T = t

    sealed class Walk {
      class Brisk : Walk() {
        suspend fun `in`(): Int = 1
        fun `object`(): Flow<Int> = flowOf(1)
        fun `class`(onLetter: (Char) -> Unit) {}
      }
    }

    @JvmInline
    value class Steps(val n: Int) {
      fun `in`(): Int = n
    }

    enum class Gait {
      TROT;

      fun `in`(): Int = 1
    }

    object Kennel {
      fun `in`(): Int = 1
      val `object`: Int = 1
    }
  """.trimIndent()

  @Test
  fun `keyword names and parameters bind on every route with backticked Kotlin calls`() {
    val result = run(keywords)

    result.assertBuildsOnBothHalves()
    val kotlin: String = result.generated
    listOf(
      "obj.`in`(", // suspend member
      "obj.`object`()", // Flow member
      ".get().`class` {", // per-call lambda (legacy, `Char` payload)
      "obj.`as`", // Flow property
      "tier1.routes.keywords.`when`()", // top-level suspend
      "tier1.routes.keywords.`return`(", // generic function
      "obj.fetch(`in`)", // keyword parameter, suspend route
      "obj.stream(`in`)", // keyword parameter, Flow route
      ".pull(`in`", // keyword parameter, plan route with a default
    ).forEach { spelling -> assertContains(kotlin, spelling) }
    val owner: String = "library_tier1_routes_keywords__"
    listOf(
      "leash_in_async", "leash_object_collect", "leash_class", "leash_get_as_collect",
      "when_async", "walk_brisk_in_async", "walk_brisk_object_collect", "steps_in",
      "gait_in", "kennel_in", "leash_companion_get_while",
    ).forEach { symbol -> assertContains(kotlin, "@CName(\"$owner$symbol\")") }
    assertContains(result.generatedCSharp, "Native_LeashCompanionGetWhile(")
  }

  private val spaces: String = """
    package tier1.routes.spaces

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flowOf

    interface Walker {
      fun walk(steps: Int)
    }

    class Leash {
      suspend fun `tug hard`(): Int = 1
      fun `a+b`(): Flow<Int> = flowOf(1)
      fun `pull back`(onLetter: (Char) -> Unit) {}
      fun `yank it`(onPull: (Int) -> Unit) {}
      fun `add listener`(listener: (Int) -> Unit) {}
      fun `remove listener`(listener: (Int) -> Unit) {}
      val `trail end`: Flow<Int> = flowOf(1)
      fun walk(): Int = 1

      companion object {
        val `slack line`: Int = 1
        fun `sit down`(): Int = 1
      }
    }

    suspend fun `come here`(): Int = 1
    fun <T> `give back`(t: T): T = t

    sealed class Walk {
      class Brisk : Walk() {
        suspend fun `tug hard`(): Int = 1
        fun `a+b`(): Flow<Int> = flowOf(1)
        fun `pull back`(onLetter: (Char) -> Unit) {}
      }
    }

    @JvmInline
    value class Steps(val n: Int) {
      fun `tug hard`(): Int = n
    }

    enum class Gait {
      TROT;

      fun `tug hard`(): Int = 1
    }

    object Kennel {
      fun `tug hard`(): Int = 1
      val `kennel size`: Int = 1
    }
  """.trimIndent()

  @Test
  fun `a name with a space or symbol is a named skip on every route without @CSharpName`() {
    val result = run(spaces)

    result.assertBuildsOnBothHalves()
    assertContains(result.generated, "@CName(\"library_tier1_routes_spaces__leash_walk\")")
    listOf(
      "Leash.tug hard", "Leash.a+b", "Leash.pull back", "Leash.yank it", "Leash.add listener",
      "Leash.remove listener", "Leash.trail end", "Companion.slack line", "Companion.sit down",
      "spaces.come here", "spaces.give back", "Brisk.tug hard", "Brisk.a+b", "Brisk.pull back",
      "Steps.tug hard", "Gait.tug hard", "Kennel.tug hard", "Kennel.kennel size",
    ).forEach { declaration ->
      assertTrue(
        result.kspWarnings.any { warning ->
          warning.contains(declaration) && warning.contains("is not an identifier") &&
            warning.contains("@CSharpName")
        },
        "expected a NON_IDENTIFIER_NAME skip for $declaration; kspWarnings=${result.kspWarnings}",
      )
    }
    // Nothing of a skipped member reaches either half, under its raw or its cleaned spelling.
    listOf(
      "tug_hard", "a_b", "pull_back", "yank_it", "add_listener", "remove_listener", "trail_end",
      "slack_line", "sit_down", "come_here", "give_back", "kennel_size",
    ).forEach { stem ->
      assertFalse(result.generated.contains(stem), "`$stem` must not be exported")
      assertFalse(result.generatedCSharp.contains("_$stem\""), "`$stem` must not be imported")
    }
  }

  private val named: String = """
    package tier1.routes.named

    import io.github.xxfast.kotlin.native.nuget.annotations.CSharpName
    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flowOf

    class Leash {
      @CSharpName("TugHard")
      suspend fun `tug hard`(): Int = 1
      @CSharpName("AddB")
      fun `a+b`(): Flow<Int> = flowOf(1)
      @CSharpName("PullBack")
      fun `pull back`(onLetter: (Char) -> Unit) {}
      @CSharpName("YankIt")
      fun `yank it`(onPull: (Int) -> Unit) {}
      @CSharpName("AddListener")
      fun `add listener`(listener: (Int) -> Unit) {}
      @CSharpName("RemoveListener")
      fun `remove listener`(listener: (Int) -> Unit) {}
      @CSharpName("TrailEnd")
      val `trail end`: Flow<Int> = flowOf(1)

      companion object {
        @CSharpName("SlackLine")
        val `slack line`: Int = 1
        @CSharpName("SitDown")
        fun `sit down`(): Int = 1
      }
    }

    @CSharpName("ComeHere")
    suspend fun `come here`(): Int = 1
    @CSharpName("GiveBack")
    fun <T> `give back`(t: T): T = t

    sealed class Walk {
      class Brisk : Walk() {
        @CSharpName("TugHard")
        suspend fun `tug hard`(): Int = 1
        @CSharpName("AddB")
        fun `a+b`(): Flow<Int> = flowOf(1)
        @CSharpName("PullBack")
        fun `pull back`(onLetter: (Char) -> Unit) {}
      }
    }

    @JvmInline
    value class Steps(val n: Int) {
      @CSharpName("TugHard")
      fun `tug hard`(): Int = n
    }

    enum class Gait {
      TROT;

      @CSharpName("TugHard")
      fun `tug hard`(): Int = 1
    }

    object Kennel {
      @CSharpName("TugHard")
      fun `tug hard`(): Int = 1
      @CSharpName("KennelSize")
      val `kennel size`: Int = 1
    }
  """.trimIndent()

  @Test
  fun `a @CSharpName binds a backticked name on every route through cleaned symbols`() {
    val result = run(named)

    result.assertBuildsOnBothHalves()
    val owner: String = "library_tier1_routes_named__"
    listOf(
      "leash_tug_hard_async", "leash_a_b_collect", "leash_pull_back", "leash_yank_it",
      "leash_add_listener", "leash_remove_listener", "leash_get_trail_end_collect",
      "leash_companion_get_slack_line", "leash_companion_sit_down", "come_here_async",
      "walk_brisk_tug_hard_async", "walk_brisk_a_b_collect", "walk_brisk_pull_back",
      "steps_tug_hard", "gait_tug_hard", "kennel_tug_hard", "kennel_get_kennel_size",
    ).forEach { symbol -> assertContains(result.generated, "@CName(\"$owner$symbol\")") }
    assertContains(result.generated, "obj.`tug hard`()")
    assertContains(result.generated, ".get().`pull back` {")
    val csharp: String = result.generatedCSharp
    listOf(
      "Task<int> TugHard(", "KotlinFlow<int> AddB(", "void PullBack(", "AddListener(",
      "KotlinFlow<int> TrailEnd", "int SlackLine", "Task<int> ComeHere(", "int KennelSize",
    ).forEach { spelling -> assertContains(csharp, spelling) }
    assertContains(csharp, "Native_LeashCompanionGetSlackLine(")
  }

  /**
   * `tug hard` and `tug_hard` on one owner both clean to `leash_tug_hard`. The C# names differ, so
   * only the entry point meets, and ADR-117's fatal diagnostic names both owners rather than one
   * overwriting the other.
   */
  @Test
  fun `two names that clean to one entry point are ADR-117's collision naming both`() {
    val result = run(
      """
      package tier1.routes.unique

      import io.github.xxfast.kotlin.native.nuget.annotations.CSharpName

      class Leash {
        @CSharpName("TugHard")
        fun `tug hard`(): Int = 1

        fun tug_hard(): Int = 2
      }
      """.trimIndent(),
    )

    assertTrue(
      result.kspErrors.any { message ->
        message.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name) &&
          message.contains("library_tier1_routes_unique__leash_tug_hard") &&
          message.contains("Leash.tug hard()") &&
          message.contains("Leash.tug_hard()")
      },
      "expected a collision naming both owners; kspErrors=${result.kspErrors}",
    )
  }

  /**
   * The routes added beside this item: a `Result` member's `TryX` twin (with authored parameters
   * spelled like the twin's own `value` / `failure` out slots), a generic member read back through
   * its checked bound, an abstract member the backing wrapper overrides, a nested type on a
   * generic owner's holder, an ADR-039 listener (its `val` getter slot and a function slot) and a
   * lambda-typed property. [a] names the functions, [b] the properties, [c] a generic function.
   */
  private fun stackRoutes(pkg: String, a: String, b: String, c: String, named: Boolean): String {
    fun declared(name: String): String = if (named) "@CSharpName(\"$name\") " else ""
    return """
      package tier1.routes.$pkg

      import io.github.xxfast.kotlin.native.nuget.annotations.CSharpName

      class Service {
        ${declared("Load")}fun $a(`object`: Int): Result<Int> = Result.success(`object`)
        fun reload(`value`: Int, `failure`: String, `in`: Int): Result<Int> = Result.success(1)
      }

      class Crate<T : Comparable<T>>(val item: T) {
        ${declared("Pick")}fun $a(`object`: T): T = `object`
      }

      fun <T : Comparable<T>> $c(`in`: T): T = `in`

      abstract class Pet {
        ${declared("Legs")}abstract fun $a(`object`: Int): Int
        ${declared("Tag")}abstract val $b: Int
      }

      fun adopt(): Pet = object : Pet() {
        override fun $a(`object`: Int): Int = `object`
        override val $b: Int = 1
      }

      class Box<T>(val item: T) {
        class Lid {
          ${declared("Open")}fun $a(`object`: Int): Int = `object`
        }
      }

      interface BarkListener {
        ${declared("Loud")}val $b: Int
        ${declared("Bark")}fun $a(`object`: Int)
      }

      class Dog {
        fun addBarkListener(listener: BarkListener) {}
        fun removeBarkListener(listener: BarkListener) {}
        ${declared("OnPurr")}val $b: (Int) -> Unit = {}
      }
    """.trimIndent()
  }

  @Test
  fun `keyword names bind on the Try, bounded-read, backing, nested, listener, lambda routes`() {
    val result = run(stackRoutes("stackkeywords", "`in`", "`is`", "`return`", named = false))

    result.assertBuildsOnBothHalves()
    val kotlin: String = result.generated
    listOf(
      ".get().`in`(`object`).also", // the Result member the Try twin shares
      "tier1.routes.stackkeywords.`return`((`in`.asStableRef<Any>()", // generic function read
      ".get().`in`((`object`.asStableRef<Any>()", // generic member's checked bounded read
      "override val `is`: Int", // the listener getter slot
      "override fun `in`(`object`: Int)", // the listener function slot
      ".get().`is`)", // the lambda-typed property
    ).forEach { spelling -> assertContains(kotlin, spelling) }
    val csharp: String = result.generatedCSharp
    listOf(
      "public bool TryIn(int @object, out int value",
      "public bool TryReload(int value_, string failure, int @in, out int value",
      "public abstract int In(int @object);",
      "public override int In(int @object)",
      "public T In(T @object)",
    ).forEach { spelling -> assertContains(csharp, spelling) }
  }

  @Test
  fun `a name with a space is a named skip on those routes, and refuses a listener pair`() {
    val result =
      run(stackRoutes("stackspaces", "`tug hard`", "`slack line`", "`give back`", named = false))

    result.assertBuildsOnBothHalves()
    listOf(
      "Service.tug hard", "Crate.tug hard", "Pet.tug hard", "Pet.slack line", "Lid.tug hard",
      "Dog.slack line", "BarkListener.tug hard", "BarkListener.slack line", "stackspaces.give back",
    ).forEach { declaration ->
      assertTrue(
        result.kspWarnings.any { it.contains(declaration) && it.contains("is not an identifier") },
        "expected a NON_IDENTIFIER_NAME skip for $declaration; kspWarnings=${result.kspWarnings}",
      )
    }
    // The bridge must override every listener member, so the pair is refused by name.
    assertTrue(
      result.kspWarnings.any { warning ->
        warning.contains("Dog.addBarkListener") &&
          warning.contains("whose name is not an identifier")
      },
      "expected the listener pair refused; kspWarnings=${result.kspWarnings}",
    )
    assertFalse(result.generated.contains("addBarkListener"), "the refused pair must not export")
  }

  @Test
  fun `a @CSharpName binds a backticked name on those routes through cleaned slots`() {
    val result =
      run(stackRoutes("stacknamed", "`tug hard`", "`slack line`", "`give back`", named = true))

    result.assertBuildsOnBothHalves()
    val owner: String = "library_tier1_routes_stacknamed__"
    listOf(
      "service_tug_hard", "crate_tug_hard", "pet_tug_hard", "pet_get_slack_line",
      "box_lid_tug_hard", "dog_get_slack_line", "dog_addBarkListener",
    ).forEach { symbol -> assertContains(result.generated, "@CName(\"$owner$symbol\")") }
    assertContains(result.generated, "override fun `tug hard`(`object`: Int)")
    assertContains(result.generated, "tug_hardPtr: COpaquePointer")
    val csharp: String = result.generatedCSharp
    listOf(
      "public bool TryLoad(int @object, out int value", "public override int Tag",
      "public int Open(int @object)", "KotlinAction<int> OnPurr", "int Loud { get; }",
      "listener.Bark(arg0)", "IntPtr tug_hardPtr",
    ).forEach { spelling -> assertContains(csharp, spelling) }
  }
}
