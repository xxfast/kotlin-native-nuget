package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-115 / issue #113: a declaration carrying a `@RequiresOptIn`-meta-annotated marker is not part
 * of the forward-exported C# surface, at any `RequiresOptIn.Level`.
 *
 * `IntegrationTests/Issue113Tests.cs` owns the absence assertions and the controls; from compiled
 * C# only the shape of the surface is visible. Everything here is what xunit cannot observe: the
 * diagnostic's kind, its `WARNING` severity, the marker's fully-qualified name in the text, that a
 * member typed with a marked class blames the **type** rather than the member, and that a
 * default-target marker on a constructor `val` warns **once** rather than once per synthesized
 * `copy`/`<init>` parameter.
 *
 * Shaped on [Tier1AnnotationClassSkipTest] (the closest declaration-level skip) and
 * [Tier1ExcludedDependencyTypeHintTest] (the closest per-hint one).
 */
class Tier1OptInMarkerSkipTest {

  private fun markerSource(level: String = "ERROR"): String =
    """
    @RequiresOptIn(level = RequiresOptIn.Level.$level, message = "internal")
    @Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
    annotation class InternalApi
    """.trimIndent()

  @Test
  fun `a marked member function skips with SKIPPED_OPT_IN_MARKER naming the marker`() {
    val result = Tier1Harness.run(
      """
      package tier1.optin

      ${markerSource()}

      class Cattery {
        fun plainName(): String = "Oreo"
        @InternalApi fun markedName(): String = "ledger"
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val diagnostics: List<String> = result.kspWarnings
      .filter { it.contains(ForwardDiagnosticKind.SKIPPED_OPT_IN_MARKER.name) }
    assertEquals(
      1,
      diagnostics.size,
      "one marked member, one warning; kspWarnings=${result.kspWarnings}",
    )
    // WARNING severity: `kspWarnings` is the harness's warning channel, and the kind's own
    // `SKIPPED_` prefix is checked against its severity at class-init time.
    assertEquals(
      ForwardDiagnosticKind.SKIPPED_OPT_IN_MARKER.severity.name,
      "WARNING",
      "ADR-115 keeps the first release at WARNING: the drop is a behaviour change to see",
    )
    assertTrue(
      diagnostics.single().contains("tier1.optin.Cattery.markedName") &&
          diagnostics.single().contains("tier1.optin.InternalApi"),
      "the message must name the declaration and the marker's FQN; got: ${diagnostics.single()}",
    )
    assertFalse(
      "MarkedName" in result.generatedCSharp,
      "a marked member has no C# projection; generated C#:\n${result.generatedCSharp}",
    )
    assertTrue(
      "PlainName" in result.generatedCSharp,
      "the unmarked sibling must survive; generated C#:\n${result.generatedCSharp}",
    )
  }

  @Test
  fun `a WARNING level marker is skipped exactly like an ERROR level one`() {
    val result = Tier1Harness.run(
      """
      package tier1.optin.level

      ${markerSource(level = "WARNING")}

      class Cattery {
        @InternalApi fun experimentalName(): String = "telemetry"
      }
      """.trimIndent(),
    )

    assertEquals(
      1,
      result.kspWarnings.count {
        it.contains(ForwardDiagnosticKind.SKIPPED_OPT_IN_MARKER.name)
      },
      "level is deliberately never consulted; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `a default-target marker on a constructor val warns once, not per synthesized parameter`() {
    // The marker also lands on the `KSValueParameter` and on the synthesized `copy`/`<init>`
    // parameters, so naive iteration double-reports. The constructor and `copy` are dropped too
    // (the marked declaration may never appear in a C# signature), and each says so once.
    val result = Tier1Harness.run(
      """
      package tier1.optin.ctor

      ${markerSource()}

      data class Litter(val name: String, @InternalApi val other: String)
      """.trimIndent(),
    )

    val diagnostics: List<String> = result.kspWarnings
      .filter { it.contains(ForwardDiagnosticKind.SKIPPED_OPT_IN_MARKER.name) }
    assertEquals(
      1,
      diagnostics.count { it.contains("tier1.optin.ctor.Litter.other") },
      "the property warns once; kspWarnings=${result.kspWarnings}",
    )
    assertEquals(
      1,
      diagnostics.count { it.contains("Litter.<init>") },
      "the constructor cannot drop a positional slot, so it is dropped once; " +
          "kspWarnings=${result.kspWarnings}",
    )
    assertEquals(
      1,
      diagnostics.count { it.contains("Litter.copy") },
      "copy follows the constructor; kspWarnings=${result.kspWarnings}",
    )
    assertFalse(
      "Other" in result.generatedCSharp,
      "generated C#:\n${result.generatedCSharp}",
    )
  }

  @Test
  fun `a trailing marked parameter with a default keeps the omitting overload`() {
    // ADR-096's machinery is the reuse: the shorter overload never names the marked parameter, so
    // C# can still construct one. The full-arity constructor is the only entry dropped.
    val result = Tier1Harness.run(
      """
      package tier1.optin.trailing

      ${markerSource()}

      class Litter(val name: String, @InternalApi val other: String = "vet")
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      "export_litter_create_2" in result.generated,
      "the omitting overload must still be exported; generated:\n${result.generated}",
    )
  }

  @Test
  fun `a set-targeted marker skips the whole property`() {
    // `@set:` is the only accessor position that compiles, and the marker is invisible on the
    // `KSPropertyDeclaration`, so an implementation reading only `declaration.annotations` leaves
    // this property fully exported and nothing else notices.
    val result = Tier1Harness.run(
      """
      package tier1.optin.setter

      @RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "internal")
      annotation class InternalApi

      class Litter {
        @set:InternalApi var viaSetter: String = "unset"
        var mutablePlain: String = "loaf"
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertEquals(
      1,
      result.kspWarnings.count {
        it.contains(ForwardDiagnosticKind.SKIPPED_OPT_IN_MARKER.name) &&
            it.contains("Litter.viaSetter")
      },
      "kspWarnings=${result.kspWarnings}",
    )
    assertFalse(
      "ViaSetter" in result.generatedCSharp,
      "the whole property goes, not just its setter; generated C#:\n${result.generatedCSharp}",
    )
    assertTrue(
      "MutablePlain" in result.generatedCSharp,
      "the unmarked sibling keeps both accessors; generated C#:\n${result.generatedCSharp}",
    )
  }

  @Test
  fun `a member whose type is a marked class blames the type, not the member`() {
    val result = Tier1Harness.run(
      """
      package tier1.optin.type

      ${markerSource()}

      @InternalApi
      class HouseRules(val motto: String)

      class Shelter {
        fun address(): String = "12 Sunbeam Lane"
        @OptIn(InternalApi::class)
        fun rules(): HouseRules = HouseRules("no zoomies")
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val blamed: String = result.kspWarnings
      .single {
        it.contains(ForwardDiagnosticKind.SKIPPED_OPT_IN_MARKER.name) &&
            it.contains("Shelter.rules")
      }
    assertTrue(
      blamed.contains("tier1.optin.type.HouseRules") &&
          blamed.contains("tier1.optin.type.InternalApi"),
      "the message must name the marked TYPE and the marker; got: $blamed",
    )
    assertFalse(
      blamed.contains("include("),
      "SKIPPED_UNEXPORTED_DEPENDENCY_TYPE's include(...) hint is actively wrong here: no export " +
          "scope can admit a marked type; got: $blamed",
    )
    assertFalse(
      "HouseRules" in result.generatedCSharp,
      "a marked class is never declared; generated C#:\n${result.generatedCSharp}",
    )
    assertTrue(
      "Address" in result.generatedCSharp,
      "the unmarked sibling survives; generated C#:\n${result.generatedCSharp}",
    )
  }

  @Test
  fun `an OptIn consumer is not a marker member and stays exported`() {
    val result = Tier1Harness.run(
      """
      package tier1.optin.consumer

      ${markerSource()}

      class Cattery {
        @InternalApi fun markedName(): String = "ledger"
        @OptIn(InternalApi::class)
        fun consumesMarked(): String = "opted-in:" + markedName()
      }
      """.trimIndent(),
    )

    assertTrue(
      "ConsumesMarked" in result.generatedCSharp,
      "`kotlin.OptIn` carries no RequiresOptIn meta-annotation, so a consumer is not a member; " +
          "generated C#:\n${result.generatedCSharp}",
    )
  }

  @Test
  fun `a SubclassOptInRequired type is not a marker and stays exported`() {
    // Verified Finding 6, and deliberate: its semantic is "you may use this, you may not subclass
    // it", and the forward direction never generates a C# subclass of an exported Kotlin class.
    val result = Tier1Harness.run(
      """
      package tier1.optin.subclass

      ${markerSource()}

      @SubclassOptInRequired(InternalApi::class)
      interface OpenForSubclass {
        fun describe(): String
      }

      class Shelter {
        fun address(): String = "12 Sunbeam Lane"
      }
      """.trimIndent(),
    )

    assertTrue(
      result.kspWarnings.none {
        it.contains(ForwardDiagnosticKind.SKIPPED_OPT_IN_MARKER.name) &&
            it.contains("OpenForSubclass")
      },
      "kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `a marker declared in a dependency module is resolved across the boundary`() {
    // ADR-115's one Inferred claim was that two-hop marker resolution survives a *klib* read; the
    // spike only had a JVM jar. `scripts/verify.sh` retires it for real (the `:test-models`
    // `CatteryInternalApi` cell). This is the same assertion at Tier 1 speed, over a jar.
    val dependency: File = Tier1DependencyLibrary.compile(
      """
      package tier1.optin.dep

      @RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "dep internal")
      @Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
      annotation class DepInternalApi
      """.trimIndent(),
    )

    val result = Tier1Harness.run(
      sources = mapOf(
        "Cattery.kt" to """
        package tier1.optin.crossmodule

        import tier1.optin.dep.DepInternalApi

        class Cattery {
          fun plainName(): String = "Oreo"
          @DepInternalApi fun crossModuleName(): String = "cattery"
        }
        """.trimIndent(),
      ),
      libraries = listOf(dependency),
    )

    assertEquals(
      1,
      result.kspWarnings.count {
        it.contains(ForwardDiagnosticKind.SKIPPED_OPT_IN_MARKER.name) &&
            it.contains("tier1.optin.dep.DepInternalApi")
      },
      "a dependency-module marker must resolve, or every declaration behind one keeps leaking " +
          "silently; kspWarnings=${result.kspWarnings}",
    )
    assertFalse(
      "CrossModuleName" in result.generatedCSharp,
      "generated C#:\n${result.generatedCSharp}",
    )
  }
}
