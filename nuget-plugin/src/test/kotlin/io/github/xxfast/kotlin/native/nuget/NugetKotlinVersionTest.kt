package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ADR-195: one plugin release supports Kotlin from a floor up to the last tested version. The
// decision is a pure function because `ProjectBuilder` cannot fake a second KGP version.
class NugetKotlinVersionTest {
  private val floor = "2.4.0"
  private val tested = "2.4.20"

  @Test
  fun kotlinSupport_atFloor_isSupported() {
    assertEquals(KotlinSupport.Supported, kotlinSupport(current = "2.4.0", floor = floor, tested = tested))
  }

  @Test
  fun kotlinSupport_atTested_isSupported() {
    assertEquals(KotlinSupport.Supported, kotlinSupport(current = "2.4.20", floor = floor, tested = tested))
  }

  @Test
  fun kotlinSupport_betweenFloorAndTested_isSupported() {
    assertEquals(KotlinSupport.Supported, kotlinSupport(current = "2.4.10", floor = floor, tested = tested))
  }

  @Test
  fun kotlinSupport_belowFloor_failsNamingTheKlibAbi() {
    val support: KotlinSupport = kotlinSupport(current = "2.3.21", floor = floor, tested = tested)

    val below: KotlinSupport.BelowFloor = assertIs<KotlinSupport.BelowFloor>(support)
    assertEquals(
      "[nuget] Kotlin 2.3.21 is not supported. kotlin-native-nuget $PLUGIN_VERSION needs Kotlin 2.4.0 or newer: " +
        "its nuget-runtime and nuget-annotations klibs are built at klib ABI 2.4, which an older Kotlin/Native " +
        "compiler cannot read. Update the Kotlin Gradle plugin to 2.4.0 or newer.",
      below.message,
    )
  }

  @Test
  fun kotlinSupport_aboveTested_warnsNamingTheTestedVersion() {
    val support: KotlinSupport = kotlinSupport(current = "2.5.0", floor = floor, tested = tested)

    val above: KotlinSupport.AboveTested = assertIs<KotlinSupport.AboveTested>(support)
    assertEquals(
      "[nuget] Kotlin 2.5.0 is newer than the last version kotlin-native-nuget $PLUGIN_VERSION was tested with " +
        "(2.4.20). It is expected to work. If it does not, update the plugin.",
      above.message,
    )
  }

  @Test
  fun kotlinSupport_patchAboveTested_warns() {
    assertIs<KotlinSupport.AboveTested>(kotlinSupport(current = "2.4.21", floor = floor, tested = tested))
  }

  @Test
  fun kotlinSupport_preReleaseOfFloor_isSupported() {
    assertEquals(KotlinSupport.Supported, kotlinSupport(current = "2.4.0-RC2", floor = floor, tested = tested))
  }

  @Test
  fun kotlinSupport_devBuildAboveTested_warns() {
    assertIs<KotlinSupport.AboveTested>(kotlinSupport(current = "2.5.0-dev-3513", floor = floor, tested = tested))
  }

  @Test
  fun kotlinSupport_unparseableVersion_isUnrecognised() {
    val support: KotlinSupport = kotlinSupport(current = "x", floor = floor, tested = tested)

    val unrecognised: KotlinSupport.Unrecognised = assertIs<KotlinSupport.Unrecognised>(support)
    assertEquals(
      "[nuget] Could not read the Kotlin version 'x'; kotlin-native-nuget $PLUGIN_VERSION supports Kotlin " +
        "2.4.0 to 2.4.20 and did not check this build.",
      unrecognised.message,
    )
  }

  // Not `KotlinVersion.CURRENT`: the Gradle test worker loads Gradle's embedded stdlib (2.2.0), not
  // the repo's. The KGP on the test classpath is the repo's own pin, the one that builds the klibs.
  @Test
  fun kotlinFloor_matchesTheMinorThisBuildCompilesWith() {
    val project: Project = ProjectBuilder.builder().build()
    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    val pin: String = project.plugins.withType(KotlinBasePlugin::class.java).first().pluginVersion
    val (major: String, minor: String) = pin.split(".")

    assertEquals("$major.$minor.0", KOTLIN_FLOOR)
  }

  @Test
  fun kotlinTested_isNotBelowFloor() {
    assertIs<KotlinSupport.Supported>(kotlinSupport(current = KOTLIN_TESTED, floor = KOTLIN_FLOOR, tested = KOTLIN_TESTED))
  }

  @Test
  fun apply_withKmpAtTheBuildsOwnKotlin_configuresWithoutError() {
    val project: Project = ProjectBuilder.builder().build()
    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")
    project.extensions.getByType(KotlinMultiplatformExtension::class.java).macosArm64()

    (project as ProjectInternal).evaluate()

    assertNotNull(project.plugins.findPlugin("com.google.devtools.ksp"))
  }

  @Test
  fun apply_withoutKmp_doesNotCheckKotlin() {
    val project: Project = ProjectBuilder.builder().build()
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")

    (project as ProjectInternal).evaluate()

    assertTrue(project.plugins.findPlugin("com.google.devtools.ksp") == null)
  }
}
