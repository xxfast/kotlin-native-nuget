package io.github.xxfast.kotlin.native.nuget

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NugetExtensionTest {
  private val extension: NugetExtension = ProjectBuilder.builder().build()
    .objects.newInstance(NugetExtension::class.java)

  @Test
  fun `publish block populates the model`() {
    extension.publish {
      it.packageId.set("MyLib")
      it.version.set("1.0.0")
      it.authors.set("Test Author")
      it.description.set("Test description")
      it.rootPackage.set("io.github.test")
    }

    val pub: NugetPublishConfig = extension.publish
    assertEquals("MyLib", pub.packageId.get())
    assertEquals("1.0.0", pub.version.get())
    assertEquals("Test Author", pub.authors.get())
    assertEquals("Test description", pub.description.get())
    assertEquals("io.github.test", pub.rootPackage.get())
  }

  @Test
  fun `publish include and exclude populate package prefix lists`() {
    extension.publish {
      it.packageId.set("MyLib")
      it.include("a.b")
      it.include("a.c")
      it.exclude("a.b.internal")
    }

    val pub: NugetPublishConfig = extension.publish
    assertEquals(listOf("a.b", "a.c"), pub.include.get())
    assertEquals(listOf("a.b.internal"), pub.exclude.get())
  }

  @Test
  fun `publish is undeclared until the block is called`() {
    assertFalse(extension.publishDeclared)
    assertFalse(extension.publish.packageId.isPresent)

    extension.publish { }

    assertTrue(extension.publishDeclared)
  }

  @Test
  fun `a second publish block merges into the first`() {
    extension.publish {
      it.packageId.set("MyLib")
      it.include("a.b")
    }
    extension.publish {
      it.version.set("2.0.0")
      it.include("a.c")
    }

    val pub: NugetPublishConfig = extension.publish
    assertEquals("MyLib", pub.packageId.get())
    assertEquals("2.0.0", pub.version.get())
    assertEquals(listOf("a.b", "a.c"), pub.include.get())
  }

  @Test
  fun `snapshot and strictDependencyTypes default to false`() {
    assertFalse(extension.publish.snapshot.get())
    assertFalse(extension.publish.strictDependencyTypes.get())
  }

  @Test
  fun `strictCompileCheck defaults to false`() {
    assertFalse(extension.publish.strictCompileCheck.get())
  }

  @Test
  fun `dependency without bind is resolve-only`() {
    extension.dependencies {
      it.dependency("Serilog") { dep ->
        dep.version.set("3.1.1")
      }
    }

    val dep: NugetDependency = extension.dependencies.single()
    assertEquals("Serilog", dep.id)
    assertEquals("3.1.1", dep.version.get())
    assertFalse(dep.bound)
  }

  @Test
  fun `dependency with bind captures packageName include exclude alias`() {
    extension.dependencies {
      it.dependency("Acme.Utilities") { dep ->
        dep.version.set("2.0.0")
        dep.source.set("https://pkgs.dev.azure.com/myorg")
        dep.bind { bind ->
          bind.packageName.set("acme")
          bind.includeNamespaces("Acme.Utilities.Core")
          bind.includeNamespaces("Acme.Utilities.Math")
          bind.excludeNamespaces("Acme.Utilities.Internal")
          bind.alias("Acme.Utilities.Core", kotlinPackage = "acme.core")
          bind.alias("Acme.Utilities.Math", kotlinPackage = "acme.math")
        }
      }
    }

    val dep: NugetDependency = extension.dependencies.single()
    assertEquals("Acme.Utilities", dep.id)
    assertEquals("2.0.0", dep.version.get())
    assertEquals("https://pkgs.dev.azure.com/myorg", dep.source.get())
    assertTrue(dep.bound)

    val bind: NugetBindConfig = dep.bind
    assertEquals("acme", bind.packageName.get())
    assertEquals(listOf("Acme.Utilities.Core", "Acme.Utilities.Math"), bind.includeNamespaces.get())
    assertEquals(listOf("Acme.Utilities.Internal"), bind.excludeNamespaces.get())
    assertEquals(
      mapOf(
        "Acme.Utilities.Core" to "acme.core",
        "Acme.Utilities.Math" to "acme.math",
      ),
      bind.aliases.get(),
    )
  }

  @Test
  fun `dependency shorthand sets version without configure block`() {
    extension.dependencies {
      it.dependency("Microsoft.Extensions.Logging", version = "8.0.0")
    }

    val dep: NugetDependency = extension.dependencies.single()
    assertEquals("Microsoft.Extensions.Logging", dep.id)
    assertEquals("8.0.0", dep.version.get())
    assertFalse(dep.bound)
  }

  @Test
  fun `multiple dependencies are kept, keyed by id`() {
    extension.dependencies {
      it.dependency("Second") { dep -> dep.version.set("2.0.0") }
      it.dependency("First") { dep -> dep.version.set("1.0.0") }
      it.dependency("Third") { dep -> dep.version.set("3.0.0") }
    }

    val ids: Set<String> = extension.dependencies.map { it.id }.toSet()
    assertEquals(setOf("First", "Second", "Third"), ids)
  }

  // ADR-180 gate decision 1: a repeated id is one PackageReference, not two.
  @Test
  fun `a second dependency with the same id merges into one entry`() {
    extension.dependencies { it.dependency("Acme", version = "1.0.0") }
    extension.dependencies {
      it.dependency("Acme") { dep -> dep.bind { bind -> bind.includeNamespaces("Acme.Core") } }
    }

    val dep: NugetDependency = extension.dependencies.single()
    assertEquals("1.0.0", dep.version.get())
    assertTrue(dep.bound)
    assertEquals(listOf("Acme.Core"), dep.bind.includeNamespaces.get())
  }

  @Test
  fun `a second bind block merges into the first`() {
    extension.dependencies {
      it.dependency("Acme") { dep ->
        dep.bind { bind -> bind.includeNamespaces("Acme.Core") }
        dep.bind { bind -> bind.includeNamespaces("Acme.Math") }
      }
    }

    val bind: NugetBindConfig = extension.dependencies.single().bind
    assertEquals(listOf("Acme.Core", "Acme.Math"), bind.includeNamespaces.get())
  }

  // ADR-192: a second bind block does not open a second namespace group; packageName is last-wins.
  @Test
  fun `a second bind block with its own packageName overrides the first`() {
    extension.dependencies {
      it.dependency("Acme") { dep ->
        dep.bind { bind -> bind.packageName.set("acme.core") }
        dep.bind { bind -> bind.packageName.set("acme.math") }
      }
    }

    val bind: NugetBindConfig = extension.dependencies.single().bind
    assertEquals("acme.math", bind.packageName.get())
  }

  @Test
  fun `aliasing the same csharp namespace twice keeps only the last kotlin package`() {
    extension.dependencies {
      it.dependency("Acme.Utilities") { dep ->
        dep.bind { bind ->
          bind.alias("Foo", kotlinPackage = "a.b")
          bind.alias("Foo", kotlinPackage = "a.c")
        }
      }
    }

    val bind: NugetBindConfig = extension.dependencies.single().bind
    assertEquals(mapOf("Foo" to "a.c"), bind.aliases.get())
  }

  @Test
  fun `shared sources default to empty and a second call appends`() {
    assertEquals(emptyList(), extension.sources.get())

    extension.sources("https://feed.example/v3/index.json", "../local-feed")
    extension.sources("../other-feed")

    assertEquals(
      listOf("https://feed.example/v3/index.json", "../local-feed", "../other-feed"),
      extension.sources.get(),
    )
  }
}
