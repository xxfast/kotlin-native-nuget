package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-074: the forward `expect`/`actual` reproduction matrix. Before Decision 1's `isExpect`
 * filter, `resolver.getAllFiles()` hands the `NugetProcessor` funnel both the `expect` header and
 * the `actual` body of every pair as two files of one compilation (Verified, ADR-074 spike
 * finding 1), so each pair is planned twice under one qualified name and trips the callable/
 * property catalog's duplicate-plan invariant, crashing the entire module's generation.
 *
 * [Tier1Harness.run]'s `commonSources` parameter (wired to `KSPConfig.Builder.commonSourceRoots`)
 * reproduces exactly that split for a plain JVM KSP2 run (ADR-074 spike finding 9), so these cells
 * need no native toolchain.
 */
class Tier1ExpectActualDeclarationsTest {

  private fun assertGeneratesCleanly(
    commonSources: Map<String, String>,
    sources: Map<String, String>,
  ) {
    val result = Tier1Harness.run(sources = sources, commonSources = commonSources)
    assertEquals("OK", result.kspExitCode, "kspErrors=${result.kspErrors}")
    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
  }

  // -- Regression cells: each of these crashed with "Forward callable/property catalog has
  // -- duplicate plans for <symbol>" before ADR-074's Decision 1 filter.

  @Test
  fun `expect class with explicit constructor generates cleanly`() {
    assertGeneratesCleanly(
      commonSources = mapOf(
        "Device.kt" to """
        package tier1.expectactual.device

        expect class Device(name: String) {
          fun describe(): String
          val id: String
        }
        """.trimIndent(),
      ),
      sources = mapOf(
        "DeviceActual.kt" to """
        package tier1.expectactual.device

        actual class Device actual constructor(private val name: String) {
          actual fun describe(): String = "device on macos: ${'$'}name"
          actual val id: String = "device-id"
        }
        """.trimIndent(),
      ),
    )
  }

  @Test
  fun `expect class with implicit constructor generates cleanly`() {
    assertGeneratesCleanly(
      commonSources = mapOf(
        "Sensor.kt" to """
        package tier1.expectactual.sensor

        expect class Sensor {
          fun reading(): Int
        }
        """.trimIndent(),
      ),
      sources = mapOf(
        "SensorActual.kt" to """
        package tier1.expectactual.sensor

        actual class Sensor {
          actual fun reading(): Int = 42
        }
        """.trimIndent(),
      ),
    )
  }

  @Test
  fun `expect fun generates cleanly`() {
    assertGeneratesCleanly(
      commonSources = mapOf(
        "Platform.kt" to """
        package tier1.expectactual.platformfn

        expect fun platformName(): String
        """.trimIndent(),
      ),
      sources = mapOf(
        "PlatformMacos.kt" to """
        package tier1.expectactual.platformfn

        actual fun platformName(): String = "macos"
        """.trimIndent(),
      ),
    )
  }

  @Test
  fun `expect object generates cleanly`() {
    assertGeneratesCleanly(
      commonSources = mapOf(
        "PlatformRegistry.kt" to """
        package tier1.expectactual.registryobj

        expect object PlatformRegistry {
          fun count(): Int
        }
        """.trimIndent(),
      ),
      sources = mapOf(
        "PlatformRegistryActual.kt" to """
        package tier1.expectactual.registryobj

        actual object PlatformRegistry {
          actual fun count(): Int = 1
        }
        """.trimIndent(),
      ),
    )
  }

  @Test
  fun `expect val generates cleanly`() {
    assertGeneratesCleanly(
      commonSources = mapOf(
        "Tag.kt" to """
        package tier1.expectactual.tagval

        expect val platformTag: String
        """.trimIndent(),
      ),
      sources = mapOf(
        "TagMacos.kt" to """
        package tier1.expectactual.tagval

        actual val platformTag: String = "osx-arm64"
        """.trimIndent(),
      ),
    )
  }

  // -- Structural cells: the generated Interop.cs carries exactly one declaration per name, with
  // -- the actual's members (not the expect's, which has none of a class's, and never a second,
  // -- degenerate copy).

  @Test
  fun `expect class with explicit constructor renders once with the actual's members`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "DeviceActual.kt" to """
        package tier1.expectactual.devicestructural

        actual class Device actual constructor(private val name: String) {
          actual fun describe(): String = "device on macos: ${'$'}name"
          actual val id: String = "device-id"
        }
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "Device.kt" to """
        package tier1.expectactual.devicestructural

        expect class Device(name: String) {
          fun describe(): String
          val id: String
        }
        """.trimIndent(),
      ),
    )

    val cs: String = result.generatedCSharp
    assertEquals(
      1,
      Regex("""\bclass Device\b""").findAll(cs).count(),
      "expected exactly one Device declaration in Interop.cs; got: $cs",
    )
    assertTrue(cs.contains("public class Device : IDisposable"))
    assertTrue(cs.contains("public Device(string name)"))
    assertTrue(cs.contains("internal Device(IntPtr handle)"))
    assertTrue(cs.contains("public string Describe()"))
    assertTrue(cs.contains("public string Id"))
  }

  @Test
  fun `expect class with implicit constructor renders once with a usable public constructor`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "SensorActual.kt" to """
        package tier1.expectactual.sensorstructural

        actual class Sensor {
          actual fun reading(): Int = 42
        }
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "Sensor.kt" to """
        package tier1.expectactual.sensorstructural

        expect class Sensor {
          fun reading(): Int
        }
        """.trimIndent(),
      ),
    )

    val cs: String = result.generatedCSharp
    assertEquals(
      1,
      Regex("""\bclass Sensor\b""").findAll(cs).count(),
      "expected exactly one Sensor declaration in Interop.cs; got: $cs",
    )
    assertTrue(cs.contains("public class Sensor : IDisposable"))
    assertTrue(cs.contains("public Sensor()"))
    assertTrue(cs.contains("internal Sensor(IntPtr handle)"))
    assertTrue(cs.contains("public int Reading()"))
  }

  @Test
  fun `expect fun renders once keeping camelCase and the actual's body`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "PlatformMacos.kt" to """
        package tier1.expectactual.platformfnstructural

        actual fun platformName(): String = "macos"
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "Platform.kt" to """
        package tier1.expectactual.platformfnstructural

        expect fun platformName(): String
        """.trimIndent(),
      ),
    )

    val cs: String = result.generatedCSharp
    assertEquals(
      1,
      Regex("""\bplatformName\(""").findAll(cs).count(),
      "expected exactly one platformName declaration in Interop.cs; got: $cs",
    )
    // Top-level functions keep Kotlin camelCase (ForwardCallablePlanner.kt topLevelEntry).
    assertTrue(cs.contains("public static string platformName()"))
  }

  @Test
  fun `expect object renders once with PascalCase members`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "PlatformRegistryActual.kt" to """
        package tier1.expectactual.registryobjstructural

        actual object PlatformRegistry {
          actual fun count(): Int = 1
        }
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "PlatformRegistry.kt" to """
        package tier1.expectactual.registryobjstructural

        expect object PlatformRegistry {
          fun count(): Int
        }
        """.trimIndent(),
      ),
    )

    val cs: String = result.generatedCSharp
    assertEquals(
      1,
      Regex("""\bclass PlatformRegistry\b""").findAll(cs).count(),
      "expected exactly one PlatformRegistry declaration in Interop.cs; got: $cs",
    )
    assertTrue(cs.contains("public static class PlatformRegistry"))
    assertTrue(cs.contains("public static int Count()"))
  }

  @Test
  fun `expect val renders once with PascalCase property`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "TagMacos.kt" to """
        package tier1.expectactual.tagvalstructural

        actual val platformTag: String = "osx-arm64"
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "Tag.kt" to """
        package tier1.expectactual.tagvalstructural

        expect val platformTag: String
        """.trimIndent(),
      ),
    )

    val cs: String = result.generatedCSharp
    assertEquals(
      1,
      Regex("""\bPlatformTag\b""").findAll(cs).count(),
      "expected exactly one PlatformTag declaration in Interop.cs; got: $cs",
    )
    assertTrue(cs.contains("public static string PlatformTag"))
  }

  /**
   * Decision 3: a top-level `actual fun`/`val` takes its C# static class name from the *expect's*
   * file, not the per-target file it happens to be declared in — otherwise `packNuget` packages
   * one target's `PlatformMacos` while shipping every target's binary, and the consumer gets a
   * class named after somebody else's platform.
   */
  @Test
  fun `decision 3 - static class name comes from the expect's file, not the actual's`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "PlatformMacos.kt" to """
        package tier1.expectactual.decision3

        actual fun platformName(): String = "macos"
        actual val platformTag: String = "osx-arm64"
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "Platform.kt" to """
        package tier1.expectactual.decision3

        expect fun platformName(): String
        expect val platformTag: String
        """.trimIndent(),
      ),
    )

    val cs: String = result.generatedCSharp
    assertTrue(
      cs.contains("class Platform"),
      "expected the expect-file-derived class Platform; got: $cs",
    )
    assertFalse(
      cs.contains("PlatformMacos"),
      "expected no trace of the per-target file name PlatformMacos; got: $cs",
    )
  }

  /**
   * Decision 2 (2a): an `actual typealias` whose target IS in the export set erases to that
   * target — the C# type is the target's, never the `expect`'s, at both a parameter position
   * (`labelOf`, declared in the common source referencing the still-unfiltered `expect` name) and
   * a return position (`defaultClock`, declared in the platform source).
   */
  @Test
  fun `actual typealias to an exported target erases to it at both positions`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "ClockActual.kt" to """
        package tier1.expectactual.aliasclock

        class SystemClock {
          fun label(): String = "system-clock"
        }

        actual typealias Clock = SystemClock

        fun defaultClock(): Clock = SystemClock()
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "AliasClock.kt" to """
        package tier1.expectactual.aliasclock

        expect class Clock {
          fun label(): String
        }

        fun labelOf(clock: Clock): String = clock.label()
        """.trimIndent(),
      ),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
    val cs: String = result.generatedCSharp
    assertFalse(
      Regex("""\bclass Clock\b""").containsMatchIn(cs),
      "expected no Clock type at all; got: $cs",
    )
    assertTrue(cs.contains("public class SystemClock : IDisposable"), "got: $cs")
    assertTrue(
      cs.contains("SystemClock defaultClock()"),
      "expected defaultClock's return position to erase to SystemClock; got: $cs",
    )
    assertTrue(
      cs.contains("labelOf(SystemClock clock)"),
      "expected labelOf's parameter position to erase to SystemClock; got: $cs",
    )
  }

  /**
   * Permanent Tier 1 cell (must never live in `test-library`'s build log): `SKIPPED_
   * ACTUAL_TYPEALIAS_TARGET` fires when an `actual typealias`'s erased target is not in the
   * forward export set — here a stdlib type, `kotlin.IllegalStateException`, which the forward
   * direction can never bring into scope.
   */
  @Test
  fun `actual typealias to an unexported stdlib target is skipped with its own diagnostic`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "FailureActual.kt" to """
        package tier1.expectactual.aliasfailure

        actual typealias Failure = kotlin.IllegalStateException
        """.trimIndent(),
      ),
      commonSources = mapOf(
        // Deliberately not named "Failure.kt": the ADR-007 per-file static class name would
        // otherwise coincide with the *type* name "Failure" for an unrelated reason (the file's
        // static wrapper class, empty because failureLabel is skipped, not any Failure type),
        // which would make the assertion below pass for the wrong reason.
        "AliasFailure.kt" to """
        package tier1.expectactual.aliasfailure

        expect class Failure

        fun failureLabel(f: Failure): String = f.toString()
        """.trimIndent(),
      ),
    )

    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_ACTUAL_TYPEALIAS_TARGET.name)
      },
      "expected a SKIPPED_ACTUAL_TYPEALIAS_TARGET diagnostic; kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains("Failure") && it.contains("IllegalStateException") },
      "expected the diagnostic to name both the expect (Failure) and the target " +
          "(IllegalStateException); kspWarnings=${result.kspWarnings}",
    )
    assertFalse(
      result.generatedCSharp.contains("class Failure"),
      "expected no Failure type at all in Interop.cs; got: ${result.generatedCSharp}",
    )
  }
}
