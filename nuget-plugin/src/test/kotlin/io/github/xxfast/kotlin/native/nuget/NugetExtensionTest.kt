package io.github.xxfast.kotlin.native.nuget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NugetExtensionTest {
  private val extension = NugetExtension()

  @Test
  fun `publish block populates the model`() {
    extension.publish {
      packageId = "MyLib"
      version = "1.0.0"
      authors = "Test Author"
      description = "Test description"
      rootPackage = "io.github.test"
    }

    val pub = extension.publish!!
    assertEquals("MyLib", pub.packageId)
    assertEquals("1.0.0", pub.version)
    assertEquals("Test Author", pub.authors)
    assertEquals("Test description", pub.description)
    assertEquals("io.github.test", pub.rootPackage)
  }

  @Test
  fun `publish include and exclude populate package prefix lists`() {
    extension.publish {
      packageId = "MyLib"
      version = "1.0.0"
      authors = "Test Author"
      description = "Test description"
      rootPackage = "io.github.test"
      include("a.b")
      include("a.c")
      exclude("a.b.internal")
    }

    val pub = extension.publish!!
    assertEquals(listOf("a.b", "a.c"), pub.include)
    assertEquals(listOf("a.b.internal"), pub.exclude)
  }

  @Test
  fun `packNuget fails fast with clear message when publish is missing`() {
    assertNull(extension.publish)

    val error = assertFailsWith<IllegalArgumentException> {
      requireNotNull(extension.publish) {
        "nuget { publish { ... } } block is required to run packNuget"
      }
    }

    assertEquals("nuget { publish { ... } } block is required to run packNuget", error.message)
  }

  @Test
  fun `dependency without bind is resolve-only`() {
    extension.dependencies {
      dependency("Serilog") {
        version = "3.1.1"
      }
    }

    val dep: NugetDependency = extension.dependencies.single()
    assertEquals("Serilog", dep.id)
    assertEquals("3.1.1", dep.version)
    assertNull(dep.bind)
  }

  @Test
  fun `dependency with bind captures packageName include exclude alias`() {
    extension.dependencies {
      dependency("Acme.Utilities") {
        version = "2.0.0"
        source = "https://pkgs.dev.azure.com/myorg"
        bind {
          packageName = "acme"
          include("Acme.Utilities.Core")
          include("Acme.Utilities.Math")
          exclude("Acme.Utilities.Internal")
          alias("Acme.Utilities.Core", kotlinPackage = "acme.core")
          alias("Acme.Utilities.Math", kotlinPackage = "acme.math")
        }
      }
    }

    val dep: NugetDependency = extension.dependencies.single()
    assertEquals("Acme.Utilities", dep.id)
    assertEquals("2.0.0", dep.version)
    assertEquals("https://pkgs.dev.azure.com/myorg", dep.source)

    val bind = dep.bind!!
    assertEquals("acme", bind.packageName)
    assertEquals(listOf("Acme.Utilities.Core", "Acme.Utilities.Math"), bind.include)
    assertEquals(listOf("Acme.Utilities.Internal"), bind.exclude)
    assertEquals(
      mapOf(
        "Acme.Utilities.Core" to "acme.core",
        "Acme.Utilities.Math" to "acme.math",
      ),
      bind.aliases,
    )
  }

  @Test
  fun `dependency shorthand sets version without configure block`() {
    extension.dependencies {
      dependency("Microsoft.Extensions.Logging", version = "8.0.0")
    }

    val dep: NugetDependency = extension.dependencies.single()
    assertEquals("Microsoft.Extensions.Logging", dep.id)
    assertEquals("8.0.0", dep.version)
    assertNull(dep.bind)
  }

  @Test
  fun `multiple dependencies are preserved in declaration order`() {
    extension.dependencies {
      dependency("First") { version = "1.0.0" }
      dependency("Second") { version = "2.0.0" }
      dependency("Third") { version = "3.0.0" }
    }

    val ids: List<String> = extension.dependencies.map { it.id }
    assertEquals(listOf("First", "Second", "Third"), ids)
  }
}
