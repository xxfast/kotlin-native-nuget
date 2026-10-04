package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * A generic data class's `equals` / `hashCode` / `toString` exports read the handle back as the
 * owner type. They spelled the bare qualified name (`asStableRef<pkg.Box>()`), which is not a type
 * for a generic class ("One type argument expected"), so the generated Kotlin did not compile for
 * any generic data class. They now read the owner as every other member does (ADR-147).
 */
class Tier1GenericDataClassTest {

  private val options: Map<String, String> = mapOf(
    "nuget.namespace" to "TestLibrary",
    "nuget.rootPackage" to "tier1",
  )

  @Test
  fun `a generic data class's equality members name the applied owner`() {
    val result = Tier1Harness.run(
      """
      package tier1.genericdata

      interface Pet { val name: String }

      data class Box<T>(val value: T)

      data class Pen<T : Pet>(val value: T)
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
    assertContains(
      result.generated,
      "handle.asStableRef<tier1.genericdata.Box<Any?>>().get().hashCode()",
    )
    assertContains(
      result.generated,
      "handle.asStableRef<tier1.genericdata.Pen<tier1.genericdata.Pet>>().get().toString()",
    )
  }
}
