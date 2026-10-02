package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A pre-existing bug ADR-066's fixture flushed out, not caused by the reachability closure
 * itself: `ForwardCallablePlanValidator.validateType`'s `is BridgeType.Unsupported -> error(...)`
 * branch is reachable for a `List<Unsupported>` *result*, because nothing upstream marked the
 * callable `Skipped` before the full plan reached the validator — `handleResultShape`/
 * `inputSkipReason` built a `Collection` shape unconditionally, regardless of whether the element
 * was itself bridgeable. A plain (non-collection) unsupported return classifies straight to
 * `BridgeType.Unsupported` and skips cleanly; only the collection-element path crashed the whole
 * `packNuget` with an `IllegalStateException` instead of skipping the one member.
 *
 * Reproduced here with an ordinary `exclude(...)`-scoped element (no dependency module needed):
 * `Secret` is module-local but outside the export scope, so `Widget.archive(): List<Secret>`
 * classifies its element to `BridgeType.Unsupported` exactly as the `archive(): List<TopStory>`
 * fixture did before `TopStory`'s admission was fixed by the reachability closure.
 */
class Tier1CollectionElementSkipTest {

  @Test
  fun `a List result with an unsupported element skips the member instead of crashing generation`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "Widget.kt" to """
          package tier1.collectionelement.api

          import tier1.collectionelement.secret.Secret

          class Widget(val name: String) {
            fun archive(): List<Secret> = emptyList()
          }
        """.trimIndent(),
        "Secret.kt" to """
          package tier1.collectionelement.secret

          class Secret(val n: Int)
        """.trimIndent(),
      ),
      processorOptions = mapOf(
        "nuget.includePackages" to "tier1.collectionelement.api",
      ),
    )

    assertTrue(
      result.kspErrors.isEmpty(),
      "expected generation to skip the member, not crash; kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.compiledClean,
      "expected no broken source for Widget; got: ${result.compileErrors}",
    )
    assertFalse(
      "export_widget_archive" in result.generated,
      "expected Widget.archive() to be entirely absent from the generated CNameExports.kt " +
          "(its element type is out of scope); generated:\n${result.generated}",
    )
  }
}
