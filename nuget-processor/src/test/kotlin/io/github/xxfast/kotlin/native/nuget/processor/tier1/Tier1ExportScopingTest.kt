package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-063: forward declaration-level export scoping. `NugetProcessor.process()` bridges every
 * public declaration in the module with no filter today; this pins the `include`/`exclude`
 * package-prefix filter and the `rootPackage`-derived default described in the ADR's Decision
 * section.
 *
 * One fixture (spread across four files, since a Kotlin file may declare only one `package`)
 * crosses every predicate branch:
 * - `com.app.api`: in scope, exact root match (`Widget`, top-level `makeWidget`)
 * - `com.app.api.detail`: in scope, strict subpackage of root (`Detail`)
 * - `com.app.internal`: excludable (`Secret`)
 * - `com.other`: out of root entirely (`Outsider`)
 *
 * Assertions read [Tier1Result.generatedCSharp] (structural mode, ADR-060): presence/absence of
 * the C# type names is enough to prove the filter without compiling either side.
 */
class Tier1ExportScopingTest {

  private val fixture: Map<String, String> = mapOf(
    "Api.kt" to """
      package com.app.api

      class Widget(val name: String)

      fun makeWidget(): Widget = Widget("widget")
    """.trimIndent(),
    "Detail.kt" to """
      package com.app.api.detail

      class Detail(val n: Int)
    """.trimIndent(),
    "Internal.kt" to """
      package com.app.internal

      class Secret(val n: Int)
    """.trimIndent(),
    "Other.kt" to """
      package com.other

      class Outsider(val n: Int)
    """.trimIndent(),
  )

  @Test
  fun `explicit include scopes to the named package subtree`() {
    val result = Tier1Harness.run(
      fixture,
      processorOptions = mapOf("nuget.includePackages" to "com.app.api"),
    )

    val generated: String = result.generatedCSharp
    assertTrue(generated.contains("Widget"), "expected Widget to be bridged; got: $generated")
    assertTrue(generated.contains("Detail"), "expected Detail to be bridged; got: $generated")
    assertFalse(
      generated.contains("Secret"),
      "expected Secret to be out of scope; got: $generated"
    )
    assertFalse(
      generated.contains("Outsider"),
      "expected Outsider to be out of scope; got: $generated"
    )
  }

  @Test
  fun `exclude wins over a broader include`() {
    val result = Tier1Harness.run(
      fixture,
      processorOptions = mapOf(
        "nuget.includePackages" to "com.app",
        "nuget.excludePackages" to "com.app.internal",
      ),
    )

    val generated: String = result.generatedCSharp
    assertTrue(generated.contains("Widget"), "expected Widget to be bridged; got: $generated")
    assertTrue(generated.contains("Detail"), "expected Detail to be bridged; got: $generated")
    assertFalse(
      generated.contains("Secret"),
      "expected excluded Secret to be dropped; got: $generated"
    )
  }

  @Test
  fun `rootPackage alone defaults the effective include set`() {
    val result = Tier1Harness.run(
      fixture,
      processorOptions = mapOf("nuget.rootPackage" to "com.app.api"),
    )

    val generated: String = result.generatedCSharp
    assertTrue(generated.contains("Widget"), "expected Widget to be bridged; got: $generated")
    assertTrue(generated.contains("Detail"), "expected Detail to be bridged; got: $generated")
    assertFalse(
      generated.contains("Secret"),
      "expected Secret to be out of the rootPackage subtree; got: $generated"
    )
    assertFalse(
      generated.contains("Outsider"),
      "expected Outsider to be out of the rootPackage subtree; got: $generated"
    )
  }

  @Test
  fun `no scoping configuration bridges every package (backward compat)`() {
    val result = Tier1Harness.run(fixture)

    val generated: String = result.generatedCSharp
    assertTrue(generated.contains("Widget"), "expected Widget to be bridged; got: $generated")
    assertTrue(generated.contains("Detail"), "expected Detail to be bridged; got: $generated")
    assertTrue(generated.contains("Secret"), "expected Secret to be bridged; got: $generated")
    assertTrue(generated.contains("Outsider"), "expected Outsider to be bridged; got: $generated")
  }

  /**
   * ADR-063 "Reverse-bound packages are always in scope": a bound package survives a
   * `rootPackage`-derived include set that would otherwise drop it, exactly the
   * `test-library`/`TestDependency` round-trip that broke `scripts/verify.sh` on the first cut of
   * this feature. `com.other` (`Outsider`) is bound, so it stays in scope even though it sits
   * outside `rootPackage = com.app.api`; `com.app.internal` (`Secret`) is not bound and stays out.
   */
  @Test
  fun `a bound package stays in scope outside the rootPackage-derived include set`() {
    val result = Tier1Harness.run(
      fixture,
      processorOptions = mapOf(
        "nuget.rootPackage" to "com.app.api",
        "nuget.boundPackages" to "com.other",
      ),
    )

    val generated: String = result.generatedCSharp
    assertTrue(generated.contains("Widget"), "expected Widget to be bridged; got: $generated")
    assertTrue(generated.contains("Detail"), "expected Detail to be bridged; got: $generated")
    assertTrue(
      generated.contains("Outsider"),
      "expected bound Outsider to stay in scope; got: $generated"
    )
    assertFalse(
      generated.contains("Secret"),
      "expected unbound Secret to stay out of the rootPackage subtree; got: $generated"
    )
  }

  /** ADR-063: the bound check runs before exclude, so exclude cannot remove a bound package. */
  @Test
  fun `exclude cannot remove a bound package`() {
    val result = Tier1Harness.run(
      fixture,
      processorOptions = mapOf(
        "nuget.excludePackages" to "com.other",
        "nuget.boundPackages" to "com.other",
      ),
    )

    val generated: String = result.generatedCSharp
    assertTrue(
      generated.contains("Outsider"),
      "expected bound Outsider to survive exclude; got: $generated"
    )
  }
}
