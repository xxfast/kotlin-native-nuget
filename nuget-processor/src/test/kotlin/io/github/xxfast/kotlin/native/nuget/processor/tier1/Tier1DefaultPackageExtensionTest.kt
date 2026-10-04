package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Declarations in the DEFAULT package (no `package` line). Their qualified name is the bare simple
 * name, which is how every route spells a type (`asStableRef<Leash>()`, `Leash()`), and the
 * generated file lives in its own package, so each such reference used to be
 * `Unresolved reference 'Leash'` and the consumer's compile failed. The fix imports every
 * root-package type the finished file mentions.
 */
class Tier1DefaultPackageExtensionTest {

  private val source: String = """
    class Leash {
      fun tug(): String = "member"
      val length: Int = 1
      fun addListener(listener: (Int) -> Unit) {}
      fun removeListener(listener: (Int) -> Unit) {}
      suspend fun fetch(): Int = 1

      class Clip(val size: Int)
    }

    @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
    fun Leash.tug(): String = "extension"

    fun Leash.yank(force: Int): String = "extension:${'$'}force"

    val Leash.slack: Int get() = 2

    val Leash?.length: Int get() = 3

    fun walk(leash: Leash): String = "walked"

    fun clip(size: Int): Leash.Clip = Leash.Clip(size)

    enum class Gait { TROT, CANTER }

    fun gait(): Gait = Gait.TROT

    object Kennel {
      fun count(): Int = 1
    }

    data class Collar(val tag: String)

    sealed interface Walk {
      data class Short(val minutes: Int) : Walk
    }

    fun shortWalk(): Walk = Walk.Short(5)

    @JvmInline
    value class Steps(val count: Int)

    fun steps(steps: Steps): Steps = steps

    interface Sniffer {
      fun sniff(): String
    }

    fun sniffWith(sniffer: Sniffer): String = sniffer.sniff()

    typealias Tether = Leash

    fun tether(): Tether = Leash()

    // Not referenced by any export: it must leave no import behind.
    internal class Hidden
  """.trimIndent()

  @Test
  fun `default-package types are imported so the generated Kotlin compiles`() {
    val result = Tier1Harness.run(source, fileName = "Leash.kt")

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    listOf("Leash", "Kennel", "Collar", "Walk", "Steps", "Sniffer").forEach { name ->
      assertContains(kotlin, "\nimport $name\n")
    }
    assertFalse(kotlin.contains("import Hidden"), "an unreferenced type must not be imported")
    assertFalse(kotlin.contains("import .Leash"), "no empty-package qualification")
  }

  @Test
  fun `the shadowed default-package extension keeps the aliased call and marked entry point`() {
    val result = Tier1Harness.run(source, fileName = "Leash.kt")

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"library_leash_tug\")")
    assertContains(kotlin, "@CName(\"library_leash_ext_tug\")")
    assertContains(kotlin, "@CName(\"library_leash_yank\")")
    assertContains(kotlin, "@CName(\"library_leash_ext_get_length\")")
    assertContains(kotlin, "import `tug` as nuget_ext_tug")
    assertContains(kotlin, ".get().nuget_ext_tug()")
  }
}
