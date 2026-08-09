package io.github.xxfast.kotlin.native.nuget.processor.forward

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ADR-088: the reader half of the cross-pipeline manifest. The writer is
 * `NugetGenerateBindingsTask.boundTypesManifest`, pinned character-for-character on the plugin
 * side, so this only has to hold up its end of that one shape and fail loudly on anything else.
 */
class ForwardBoundTypesTest {

  @Test
  fun `parses the manifest the plugin writes`() {
    val json = """
      {
        "interfaces": [
          { "kotlinName": "test.menagerie.IFeedable", "csharpName": "Test.Menagerie.IFeedable", "implementable": true },
          { "kotlinName": "test.menagerie.ICrowded", "csharpName": "Test.Menagerie.ICrowded", "implementable": false }
        ]
      }
    """.trimIndent()

    assertEquals(
      listOf(
        ForwardBoundInterface("test.menagerie.IFeedable", "Test.Menagerie.IFeedable", true),
        ForwardBoundInterface("test.menagerie.ICrowded", "Test.Menagerie.ICrowded", false),
      ),
      parseBoundTypesManifest(json),
    )
  }

  @Test
  fun `an empty manifest yields no bound interfaces`() {
    assertTrue(parseBoundTypesManifest("{\n  \"interfaces\": []\n}\n").isEmpty())
    assertTrue(parseBoundTypesManifest("").isEmpty())
  }

  /**
   * A silently-empty parse would degrade every bound interface at a forward position back into
   * `SKIPPED_UNSUPPORTED_TYPE`, which is exactly the pre-ADR-088 bug: a wrong-shaped manifest must
   * be loud, not lossy.
   */
  @Test
  fun `a malformed entry fails fast naming the offending text`() {
    val failure = assertFailsWith<IllegalArgumentException> {
      parseBoundTypesManifest("""{ "interfaces": [ { "kotlinName": "a.B" } ] }""")
    }
    assertTrue(failure.message.orEmpty().contains("csharpName"), failure.message.orEmpty())
  }
}
