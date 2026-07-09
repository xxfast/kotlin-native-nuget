package io.github.xxfast.kotlin.native.nuget.rir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DllPathDerivationTest {
  private val singlePackageAssetsJson = """
    {
      "targets": {
        "net8.0": {
          "Newtonsoft.Json/13.0.3": {
            "type": "package",
            "runtime": {
              "lib/net8.0/Newtonsoft.Json.dll": {}
            }
          }
        }
      },
      "libraries": {
        "Newtonsoft.Json/13.0.3": {
          "sha512": "abc123",
          "type": "package",
          "path": "newtonsoft.json/13.0.3",
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
  fun `deriveDllPaths returns absolute path for a single bound package`() {
    val paths: Map<String, List<String>> = deriveDllPaths(
      assetsJson = singlePackageAssetsJson,
      packageIds = setOf("Newtonsoft.Json"),
    )

    assertEquals(1, paths.size)
    val dlls: List<String> = requireNotNull(paths["Newtonsoft.Json"])
    assertEquals(1, dlls.size)
    assertEquals(
      "/home/user/.nuget/packages/newtonsoft.json/13.0.3/lib/net8.0/Newtonsoft.Json.dll",
      dlls[0],
    )
  }

  @Test
  fun `deriveDllPaths skips packages not in the packageIds set`() {
    val paths: Map<String, List<String>> = deriveDllPaths(
      assetsJson = singlePackageAssetsJson,
      packageIds = setOf("Acme.Lib"),
    )

    assertTrue(paths.isEmpty())
  }

  @Test
  fun `deriveDllPaths handles package with multiple runtime dlls`() {
    val json = """
      {
        "targets": {
          "net8.0": {
            "Acme.Lib/2.0.0": {
              "type": "package",
              "runtime": {
                "lib/net8.0/Acme.Lib.dll": {},
                "lib/net8.0/Acme.Lib.Native.dll": {}
              }
            }
          }
        },
        "libraries": {
          "Acme.Lib/2.0.0": {
            "sha512": "def456",
            "type": "package",
            "path": "acme.lib/2.0.0",
            "files": []
          }
        },
        "project": {
          "restore": {
            "packagesPath": "/home/user/.nuget/packages"
          }
        }
      }
    """.trimIndent()

    val paths: Map<String, List<String>> = deriveDllPaths(
      assetsJson = json,
      packageIds = setOf("Acme.Lib"),
    )

    val dlls: List<String> = requireNotNull(paths["Acme.Lib"])
    assertEquals(2, dlls.size)
    assertTrue(dlls.any { it.endsWith("Acme.Lib.dll") })
    assertTrue(dlls.any { it.endsWith("Acme.Lib.Native.dll") })
  }

  @Test
  fun `deriveDllPaths returns empty map when packageIds is empty`() {
    val paths: Map<String, List<String>> = deriveDllPaths(
      assetsJson = singlePackageAssetsJson,
      packageIds = emptySet(),
    )

    assertTrue(paths.isEmpty())
  }

  @Test
  fun `deriveDllPaths prefixes packagesPath to libPath and dllRelPath`() {
    val json = """
      {
        "targets": {
          "net8.0": {
            "Serilog/3.1.1": {
              "type": "package",
              "runtime": {
                "lib/net8.0/Serilog.dll": {}
              }
            }
          }
        },
        "libraries": {
          "Serilog/3.1.1": {
            "sha512": "xyz789",
            "type": "package",
            "path": "serilog/3.1.1",
            "files": []
          }
        },
        "project": {
          "restore": {
            "packagesPath": "/custom/packages/root"
          }
        }
      }
    """.trimIndent()

    val paths: Map<String, List<String>> = deriveDllPaths(
      assetsJson = json,
      packageIds = setOf("Serilog"),
    )

    val dlls: List<String> = requireNotNull(paths["Serilog"])
    assertEquals(
      "/custom/packages/root/serilog/3.1.1/lib/net8.0/Serilog.dll",
      dlls[0],
    )
  }
}
