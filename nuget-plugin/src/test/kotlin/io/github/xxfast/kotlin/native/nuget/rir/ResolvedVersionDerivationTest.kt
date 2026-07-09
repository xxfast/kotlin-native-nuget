package io.github.xxfast.kotlin.native.nuget.rir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for `deriveResolvedVersions()` (ADR-050 Alternative 5): given `project.assets.json`
 * and a set of bound package ids, returns the exact version NuGet actually resolved for each id, by
 * parsing the `libraries` keys of the form `"{id}/{version}"` — mirrors the existing
 * `deriveDllPaths()` (see [DllPathDerivationTest]).
 */
class ResolvedVersionDerivationTest {
  private val mimeMappingAssetsJson = """
    {
      "targets": {
        "net8.0": {
          "MimeMapping/4.0.0": {
            "type": "package",
            "runtime": {
              "lib/netstandard2.0/MimeMapping.dll": {}
            }
          }
        }
      },
      "libraries": {
        "MimeMapping/4.0.0": {
          "sha512": "abc123",
          "type": "package",
          "path": "mimemapping/4.0.0",
          "files": []
        }
      },
      "project": {
        "version": "1.0.0",
        "restore": {
          "packagesPath": "/home/user/.nuget/packages",
          "outputPath": "/tmp/obj"
        }
      }
    }
  """.trimIndent()

  @Test
  fun `deriveResolvedVersions returns exact resolved version for a bound package`() {
    val versions: Map<String, String> = deriveResolvedVersions(
      assetsJson = mimeMappingAssetsJson,
      packageIds = setOf("MimeMapping"),
    )

    assertEquals(mapOf("MimeMapping" to "4.0.0"), versions)
  }

  @Test
  fun `deriveResolvedVersions omits an id that is not bound in the assets file`() {
    val versions: Map<String, String> = deriveResolvedVersions(
      assetsJson = mimeMappingAssetsJson,
      packageIds = setOf("SomeOtherPackage"),
    )

    assertFalse(versions.containsKey("SomeOtherPackage"))
    assertTrue(versions.isEmpty())
  }

  @Test
  fun `deriveResolvedVersions returns empty map when packageIds is empty`() {
    val versions: Map<String, String> = deriveResolvedVersions(
      assetsJson = mimeMappingAssetsJson,
      packageIds = emptySet(),
    )

    assertTrue(versions.isEmpty())
  }
}
