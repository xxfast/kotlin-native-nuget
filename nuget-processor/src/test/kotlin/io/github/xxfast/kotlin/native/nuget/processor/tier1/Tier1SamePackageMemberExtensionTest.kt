package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A member and an extension of one name on one receiver, declared in ONE package. The member's
 * export prefix and the extension's package qualifier are then the same, so both used to derive
 * `<lib>_<pkg>__leash_tug` and the round failed with `ERROR_C_ENTRY_POINT_COLLISION`. The extension
 * now takes the `ext` role word only when its plain spelling is already taken, and is called
 * through the ADR-132 aliased import exactly like the cross-package case
 * (`Tier1ShadowedExtensionFunctionTest`).
 */
class Tier1SamePackageMemberExtensionTest {

  private val sources: Map<String, String> = mapOf(
    "Leash.kt" to """
      package tier1.samepackage

      class Leash {
        fun tug(): String = "member"
        fun yank(a: Int): String = "member"
        fun yank(a: String): String = "member"
      }

      @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
      fun Leash.tug(): String = "extension"
      fun Leash.yank(a: Long): String = "extension"
      fun Leash.yank(a: Boolean): String = "extension"
      fun Leash.sniff(): String = "extension"
    """.trimIndent(),
  )

  private val prefix: String = "library_tier1_samepackage__"

  private val alias: String = "nuget_ext_tier1__samepackage__"

  @Test
  fun `a same-package member and extension bind under distinct entry points`() {
    val result: Tier1Result = Tier1Harness.run(sources)

    assertTrue(
      result.kspErrors.none { message ->
        message.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name)
      },
      "the pair must not collide; kspErrors=${result.kspErrors}",
    )
    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    // The members keep the spelling they always had.
    assertContains(kotlin, "@CName(\"${prefix}leash_tug\")")
    assertContains(kotlin, "@CName(\"${prefix}leash_yank\")")
    assertContains(kotlin, "@CName(\"${prefix}leash_yank_2\")")
    // Only the colliding extensions move, each overload to its own marked symbol.
    assertContains(kotlin, "@CName(\"${prefix}leash_ext_tug\")")
    assertContains(kotlin, "@CName(\"${prefix}leash_ext_yank\")")
    assertContains(kotlin, "@CName(\"${prefix}leash_ext_yank_2\")")
    // And each calls the extension through its alias, never the member by its simple name.
    assertContains(kotlin, "import tier1.samepackage.`tug` as ${alias}tug")
    assertContains(kotlin, ".get().${alias}tug()")
    assertContains(kotlin, ".get().${alias}yank(a)")
  }

  @Test
  fun `an extension no member shadows keeps its unmarked symbol`() {
    val result: Tier1Result = Tier1Harness.run(sources)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertContains(result.generated, "@CName(\"${prefix}leash_sniff\")")
    assertFalse(
      result.generated.contains("leash_ext_sniff"),
      "a non-colliding extension must not move; generated=${result.generated}",
    )
  }

  /**
   * Value-class members are planned on a route that runs after the class walk, which is why the
   * extensions are planned last: their entry points have to be known before an extension is. (An
   * enum member beside a same-package extension is a named ADR-034 C# signature collision
   * instead, because both render into `{Enum}Extensions`.)
   */
  @Test
  fun `a value-class member and a same-package extension bind too`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.samepackage.valueclass

      @JvmInline
      value class Kennel(val size: Int) {
        fun bark(): String = "member"
      }

      @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
      fun Kennel.bark(): String = "extension"
      """.trimIndent(),
    )

    assertTrue(
      result.kspErrors.none { message ->
        message.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name)
      },
      "the pair must not collide; kspErrors=${result.kspErrors}",
    )
    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val owners: String = "library_tier1_samepackage_valueclass__"
    assertContains(result.generated, "@CName(\"${owners}kennel_bark\")")
    assertContains(result.generated, "@CName(\"${owners}kennel_ext_bark\")")
  }

  @Test
  fun `C# carries the member on the class and the extension on its extensions class`() {
    val result: Tier1Result = Tier1Harness.run(sources)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val csharp: String = result.generatedCSharp
    assertContains(csharp, "EntryPoint = \"${prefix}leash_tug\"")
    assertContains(csharp, "EntryPoint = \"${prefix}leash_ext_tug\"")
    assertContains(csharp, "EntryPoint = \"${prefix}leash_ext_yank_2\"")
    assertContains(csharp, "public string Tug()")
    assertContains(csharp, "class LeashExtensions")
    assertContains(csharp, "string Tug(this global::Interop.Leash receiver)")
    assertContains(csharp, "string Yank(this global::Interop.Leash receiver, long a)")
    assertContains(csharp, "string Yank(this global::Interop.Leash receiver, bool a)")
  }

  private fun assertNoCollision(result: Tier1Result) {
    assertTrue(
      result.kspErrors.isEmpty(),
      "expected no KSP error; kspErrors=${result.kspErrors}",
    )
    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
  }

  /**
   * The hand-written callback routes mint `<owner>_<name>` outside the catalog: a stored-callback
   * pair (ADR-037), an interface-bridge pair (ADR-039) and a per-call lambda member the plan does
   * not own (a `Char` payload, ADR-036). A same-package extension of the same name used to meet
   * each of them on one entry point.
   */
  @Test
  fun `a legacy callback member and a same-package extension bind under distinct entry points`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.samepackage.legacy

      interface Walker {
        fun walk(steps: Int)
      }

      class Leash {
        fun addListener(listener: (Int) -> Unit) {}
        fun removeListener(listener: (Int) -> Unit) {}
        fun addWalker(walker: Walker) {}
        fun removeWalker(walker: Walker) {}
        fun spell(onLetter: (Char) -> Unit) {}
      }

      fun Leash.addListener(): String = "extension"
      fun Leash.addWalker(): String = "extension"
      fun Leash.spell(): String = "extension"
      """.trimIndent(),
    )

    assertNoCollision(result)
    val legacy: String = "library_tier1_samepackage_legacy__"
    listOf("addListener", "removeListener", "addWalker", "removeWalker", "spell").forEach { name ->
      assertContains(result.generated, "@CName(\"${legacy}leash_$name\")")
    }
    listOf("addListener", "addWalker", "spell").forEach { name ->
      assertContains(result.generated, "@CName(\"${legacy}leash_ext_$name\")")
    }
  }

  /**
   * The shapes that never collided, pinned so they stay unmarked: a planned per-call lambda member
   * already takes a catalog entry point (so its extension is marked like any planned member's),
   * while a `suspend` member exports `_async` and a `Flow` member `_collect`, leaving the
   * extension's plain spelling free.
   */
  @Test
  fun `planned lambda, suspend and Flow members keep their own spellings`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.samepackage.routes

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      class Leash {
        fun tug(onPull: (Int) -> Unit): String = "member"
        suspend fun sniff(): String = "member"
        fun trail(): Flow<Int> = flowOf(1)
      }

      fun Leash.tug(): String = "extension"
      fun Leash.sniff(scent: Int): String = "extension"
      @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
      fun Leash.trail(): String = "extension"
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertNoCollision(result)
    val routes: String = "library_tier1_samepackage_routes__"
    assertContains(result.generated, "@CName(\"${routes}leash_tug\")")
    assertContains(result.generated, "@CName(\"${routes}leash_ext_tug\")")
    assertContains(result.generated, "@CName(\"${routes}leash_sniff_async\")")
    assertContains(result.generated, "@CName(\"${routes}leash_sniff\")")
    assertContains(result.generated, "@CName(\"${routes}leash_trail_collect\")")
    assertContains(result.generated, "@CName(\"${routes}leash_trail\")")
    assertFalse(result.generated.contains("leash_ext_sniff"), "sniff must not move")
    assertFalse(result.generated.contains("leash_ext_trail"), "trail must not move")
  }

  /**
   * A member property and a same-package extension property of one name are keyed by one plan
   * symbol (`pkg.Leash.x`). The shadowed extension stays ADR-132's named skip, as it is across
   * packages; the lookup used to hand the member's plan to the extension route and fail the round
   * with ERROR_INTERNAL_GENERATOR_FAILURE.
   */
  @Test
  fun `a same-package member-shadowed extension property is the named skip, not a crash`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.samepackage.shadowedprop

      class Leash {
        val x: String = "member"
        var y: Int = 1
      }

      @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
      val Leash.x: String get() = "extension"

      @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
      var Leash.y: Int
        get() = 2
        set(value) {}
      """.trimIndent(),
    )

    assertNoCollision(result)
    val owner: String = "library_tier1_samepackage_shadowedprop__"
    assertContains(result.generated, "@CName(\"${owner}leash_get_x\")")
    assertContains(result.generated, "@CName(\"${owner}leash_set_y\")")
    assertFalse(result.generated.contains("leash_ext_"), "a shadowed extension exports nothing")
    listOf("`Leash.x` shadows it", "`Leash.y` shadows it").forEach { text ->
      assertTrue(
        result.kspWarnings.any { it.contains("SHADOWED_BY_MEMBER") && it.contains(text) },
        "expected the named skip ($text); kspWarnings=${result.kspWarnings}",
      )
    }
  }

  /**
   * A nullable-receiver extension property is never shadowed (ADR-132), so beside a same-package
   * member it binds, and its accessors take the `ext` role word only where the plain one is taken:
   * a `val` member leaves the extension `var`'s setter its plain spelling.
   */
  @Test
  fun `a same-package nullable-receiver extension property takes the marked accessors`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.samepackage.nullableprop

      class Leash {
        val x: String = "member"
        var y: Int = 1
        val z: Int = 1
      }

      val Leash?.x: String get() = "extension"

      var Leash?.y: Int
        get() = 2
        set(value) {}

      var Leash?.z: Int
        get() = 3
        set(value) {}
      """.trimIndent(),
    )

    assertNoCollision(result)
    val owner: String = "library_tier1_samepackage_nullableprop__"
    listOf("get_x", "get_y", "set_y", "get_z").forEach { accessor ->
      assertContains(result.generated, "@CName(\"${owner}leash_$accessor\")")
      assertContains(result.generated, "@CName(\"${owner}leash_ext_$accessor\")")
    }
    assertContains(result.generated, "@CName(\"${owner}leash_set_z\")")
    assertFalse(result.generated.contains("leash_ext_set_z"), "an untaken setter must not move")
  }

  /**
   * A member FUNCTION spelled like a property accessor (`get_x`) mints `<owner>_get_x` on the
   * callable route, the very spelling a same-package `val Leash?.x` derives for its getter. The
   * callable route's entry points are in the extension property's taken set, so the accessor moves.
   */
  @Test
  fun `a member function named like an accessor moves the extension property accessor`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.samepackage.accessorfunction

      class Leash {
        fun get_x(): Int = 1
      }

      val Leash?.x: String get() = "extension"
      """.trimIndent(),
    )

    assertNoCollision(result)
    val owner: String = "library_tier1_samepackage_accessorfunction__"
    assertContains(result.generated, "@CName(\"${owner}leash_get_x\")")
    assertContains(result.generated, "@CName(\"${owner}leash_ext_get_x\")")
  }

  /**
   * A member lambda-typed property's getter is the hand-written legacy export `<owner>_get_<name>`,
   * outside both name sets the planners built, so a same-package `val Leash?.onTap` derived the
   * same entry point. It joins the extension property's taken set, so the accessor moves.
   */
  @Test
  fun `a member lambda property moves a same-package extension property accessor`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.samepackage.lambdaprop

      class Leash {
        val onTap: () -> Unit = {}
      }

      val Leash?.onTap: String get() = "extension"
      """.trimIndent(),
    )

    assertNoCollision(result)
    val owner: String = "library_tier1_samepackage_lambdaprop__"
    assertContains(result.generated, "@CName(\"${owner}leash_get_onTap\")")
    assertContains(result.generated, "@CName(\"${owner}leash_ext_get_onTap\")")
  }
}
