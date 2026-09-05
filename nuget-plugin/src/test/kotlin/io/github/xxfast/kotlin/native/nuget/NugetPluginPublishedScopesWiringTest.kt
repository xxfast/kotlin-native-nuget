package io.github.xxfast.kotlin.native.nuget

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-109: the duplicate-type hazard is only visible to a KSP run that knows the export scope of
 * every OTHER forward publisher in the same Gradle build. That knowledge crosses as one KSP
 * option, `nuget.publishedScopes`, registered as a lazy `Provider<String>` (KSP's
 * `arg(String, Provider<String>)` overload) so its cross-project walk runs when KSP resolves its
 * options — after every project is evaluated — instead of inside `afterEvaluate`, where seeing
 * a not-yet-evaluated sibling would need the mutually-circular `evaluationDependsOn`.
 *
 * Encoding (ADR-109 Decision 2): entries `;`-separated, fields `:`-separated, lists `|`-separated,
 * `<packageId>:<include1|include2>:<exclude1|exclude2>`, sorted for a stable configuration-cache
 * input. `include` is the publisher's *effective* include (the explicit `include(...)` list when
 * non-empty, else `[rootPackage]`), mirroring `effectiveInclude` in `NugetProcessor.kt`.
 *
 * Modelled on [NugetPluginKspArgsWiringTest]'s ProjectBuilder wiring; this cell needs a
 * multi-project build, so it builds children `withParent(root)`. `KspExtension.arguments` is
 * `apOptions.get()` (verified in KSP 2.3.10's own source), so it resolves a Provider-registered
 * option like any other.
 */
class NugetPluginPublishedScopesWiringTest {
  private fun buildProjectWithSharedLib(name: String, parent: Project? = null): Project {
    val builder: ProjectBuilder = ProjectBuilder.builder().withName(name)
    if (parent != null) builder.withParent(parent)
    val project: Project = builder.build()
    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")

    val kotlin: KotlinMultiplatformExtension =
      project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    kotlin.mingwX64 {
      binaries {
        sharedLib {
          baseName = name
        }
      }
    }

    return project
  }

  private fun Project.evaluate() {
    (this as ProjectInternal).evaluate()
  }

  private fun Project.publish(id: String, configure: NugetPublishConfig.() -> Unit = {}) {
    extensions.getByType(NugetExtension::class.java).publish {
      packageId = id
      version = "1.0.0"
      authors = "Test Author"
      description = "Test description"
      configure()
    }
  }

  @Test
  fun `every publisher in the build is encoded, self included, and non-publishers are not`() {
    val root: Project = ProjectBuilder.builder().withName("root").build()
    // The ADR-066 dependency shape: a module both publishers admit types from, which itself
    // publishes nothing. It applies the plugin (so the extension exists) but has no `publish {}`,
    // which is exactly the `publish == null` case the walk must drop.
    val models: Project = buildProjectWithSharedLib("models", root)
    val libA: Project = buildProjectWithSharedLib("lib-a", root)
    val libB: Project = buildProjectWithSharedLib("lib-b", root)

    libA.publish("LibA") {
      rootPackage = "com.acme"
      include("com.acme", "com.acme.models")
    }
    libB.publish("LibB") {
      rootPackage = "com.acme"
      include("com.acme", "com.acme.models")
      exclude("com.acme.internal")
    }

    listOf(models, libA, libB).forEach { it.evaluate() }

    val ksp: KspExtension = libA.extensions.getByType(KspExtension::class.java)
    val encoded: String = requireNotNull(ksp.arguments["nuget.publishedScopes"]) {
      "expected the publisher to register nuget.publishedScopes; got ${ksp.arguments.keys}"
    }

    assertEquals(
      listOf("LibA:com.acme|com.acme.models:", "LibB:com.acme|com.acme.models:com.acme.internal"),
      encoded.split(";"),
      "expected both publishers' effective scopes, sorted, and nothing for the non-publishing " +
          "models module; got: $encoded",
    )
    // The equality above already pins the whole value; this names *why* it has two entries and
    // not three (the packageId field of the non-publishing module would be empty, so a third
    // entry would be a leading ":" one). `"models" in encoded` would be a false positive: the
    // publishers' own `com.acme.models` include contains it.
    assertFalse(
      encoded.split(";").any { entry -> entry.substringBefore(":").isBlank() },
      "a module with no publish {} must contribute no entry at all; got: $encoded",
    )
  }

  /** A publisher with no explicit `include(...)` falls back to `[rootPackage]`, exactly as
   *  `effectiveInclude` does on the processor side. */
  @Test
  fun `a lone publisher encodes its own rootPackage-derived scope`() {
    val project: Project = buildProjectWithSharedLib("solo")
    project.publish("Solo") { rootPackage = "com.contoso.api" }

    project.evaluate()

    val ksp: KspExtension = project.extensions.getByType(KspExtension::class.java)
    assertEquals("Solo:com.contoso.api:", ksp.arguments["nuget.publishedScopes"])
  }

  /** A consume-only module (`dependencies { bind {} }`, no `publish {}`) has no export scope of
   *  its own, so it registers no option at all rather than an empty one. */
  @Test
  fun `a project with no publish block registers no publishedScopes option`() {
    val project: Project = buildProjectWithSharedLib("consumer")

    project.evaluate()

    val ksp: KspExtension = project.extensions.getByType(KspExtension::class.java)
    assertTrue(
      "nuget.publishedScopes" !in ksp.arguments,
      "expected no nuget.publishedScopes for a project with no publish {}; got ${ksp.arguments}",
    )
  }
}
