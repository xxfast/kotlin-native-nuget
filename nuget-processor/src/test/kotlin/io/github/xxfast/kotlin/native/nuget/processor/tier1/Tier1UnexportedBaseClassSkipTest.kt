package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-101's 2026-09-05 amendment / issue #42, base-class half: an exported class whose *base
 * class* is outside the export set used to render `public class Api : OutsideBase` for a type
 * nothing ever generates, so `Interop.cs` died on CS0246 — the same defect
 * [Tier1UnexportedSupertypeSkipTest] closed for interfaces, through the hole that ADR named and
 * deferred.
 *
 * The fix is deliberately *not* symmetric with the interface case, and these cells are written to
 * catch the asymmetry: an unexported interface carries nothing C# could have called, but an
 * unexported base class carries real members. Dropping the base must re-home them onto the
 * subclass, under the subclass's own export prefix, with no `override` (which would be CS0115
 * against a base that does not exist).
 *
 * Cell (a) crosses a real compilation-unit boundary via [Tier1DependencyLibrary] — the shape a
 * Gradle-module dependency has (`Origin.KOTLIN_LIB`, `containingFile == null`) — because the
 * load-bearing unverified claim in the amendment is precisely that KSP's `getAllFunctions()` /
 * `getAllProperties()` surface a *dependency-declared base class's* public members on a
 * module-local subclass. ADR-101 only ever measured that for a dependency-declared interface.
 */
class Tier1UnexportedBaseClassSkipTest {

  private val dependencyJar: File = Tier1DependencyLibrary.compile(
    """
    package dep.outside

    open class OutsideBase {
      val label: String = "base"

      fun greet(name: String): String = "hello ${'$'}name"
    }

    abstract class OutsideAbstractBase {
      abstract fun speak(): String
    }
    """.trimIndent(),
    fileName = "OutsideBase.kt",
  )

  private val fixture: String = """
    package tier1.issue42base

    import dep.outside.OutsideBase

    class Api(val port: Int) : OutsideBase() {
      fun ping(): String = "pong:${'$'}port"
    }
  """.trimIndent()

  @Test
  fun `a dependency-declared base class is dropped and its members re-home onto the subclass`() {
    val result = Tier1Harness.run(
      fixture,
      processorOptions = mapOf("nuget.rootPackage" to "tier1.issue42base"),
      libraries = listOf(dependencyJar),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      "export_api_ping" in result.generated,
      "dropping the base must not cost the class its own members; generated:\n${result.generated}",
    )
    assertTrue(
      "export_api_get_port" in result.generated,
      "dropping the base must not cost the class its own properties; " +
          "generated:\n${result.generated}",
    )
    // The amendment's red register: if KSP does not surface a klib/jar-declared base *class*'s
    // members on the subclass, these two are the assertions that say so, and the class would ship
    // silently missing everything it inherited.
    assertTrue(
      "export_api_greet" in result.generated,
      "the dropped base's concrete method must bind on the subclass itself — nothing else can " +
          "carry it once the C# base is gone; generated:\n${result.generated}",
    )
    assertTrue(
      "export_api_get_label" in result.generated,
      "the dropped base's property must bind on the subclass itself; " +
          "generated:\n${result.generated}",
    )
    assertTrue(
      "public class Api : IDisposable, INugetHandle" in result.generatedCSharp,
      "the class must render with no base at all — a dangling `: OutsideBase` is the CS0246 in " +
          "issue #42; generated C#:\n${result.generatedCSharp}",
    )
    assertFalse(
      "OutsideBase" in result.generatedCSharp,
      "no stand-in class may be emitted for the excluded base either; " +
          "generated C#:\n${result.generatedCSharp}",
    )

    val diagnostics: List<String> = result.kspWarnings.filter {
      it.contains(ForwardDiagnosticKind.SKIPPED_UNEXPORTED_SUPERTYPE.name)
    }
    assertEquals(
      1,
      diagnostics.size,
      "the base is dropped in one place (`translateClass`), so it must be reported exactly once; " +
          "kspWarnings=${result.kspWarnings}",
    )
    val diagnostic: String = diagnostics.single()
    assertTrue(
      diagnostic.contains("Api : OutsideBase"),
      "the diagnostic must name the declaration it skipped; got: $diagnostic",
    )
    assertTrue(
      diagnostic.contains("dep.outside.OutsideBase"),
      "the diagnostic must name the skipped base's qualified name; got: $diagnostic",
    )
    assertTrue(
      diagnostic.contains("base class"),
      "the message must say base class, not supertype — the interface wording (\"carries no " +
          "members the C# side could call\") is false here; got: $diagnostic",
    )
    assertFalse(
      diagnostic.contains("carries no members the C# side could call"),
      "the interface hint must not be reused for a base class, which does carry members; " +
          "got: $diagnostic",
    )
    assertTrue(
      diagnostic.contains("no inheritance relation"),
      "the hint must name what is actually lost (the type and the is-a relation); " +
          "got: $diagnostic",
    )
    assertTrue(
      result.generatedFiles.entries
        .single { it.key.endsWith("NugetDiagnostics.json") }
        .value.contains(ForwardDiagnosticKind.SKIPPED_UNEXPORTED_SUPERTYPE.name),
      "the warning must also reach NugetDiagnostics.json so nugetReportDiagnostics can re-emit it",
    )
  }

  @Test
  fun `a same-module base outside rootPackage is dropped the same way`() {
    // Measured while writing this cell: `rootPackage` admits *sub*packages, so a base in
    // `tier1.issue42base.outside` is in the export set and renders as a real C# base. The base
    // has to sit outside the prefix entirely to be out of scope.
    val result = Tier1Harness.run(
      mapOf(
        "Base.kt" to """
          package tier1outside.base

          open class LocalBase {
            fun tag(): String = "local"
          }
        """.trimIndent(),
        "Api.kt" to """
          package tier1.issue42base

          import tier1outside.base.LocalBase

          class Api : LocalBase() {
            fun ping(): String = "pong"
          }
        """.trimIndent(),
      ),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.issue42base"),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      "export_api_ping" in result.generated && "export_api_tag" in result.generated,
      "a same-round base outside `rootPackage` is just as absent from the export set as a " +
          "dependency one, so its member re-homes identically; generated:\n${result.generated}",
    )
    assertTrue(
      "public class Api : IDisposable, INugetHandle" in result.generatedCSharp,
      "generated C#:\n${result.generatedCSharp}",
    )
    val diagnostic: String = requireNotNull(
      result.kspWarnings.firstOrNull {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNEXPORTED_SUPERTYPE.name)
      },
    ) { "expected the base-class skip to fire in-module too; kspWarnings=${result.kspWarnings}" }
    assertTrue(
      diagnostic.contains("include(\"tier1outside.base\")"),
      "for a same-module base, include(...) genuinely does admit it, and the hint names the " +
          "package to add; got: $diagnostic",
    )
  }

  @Test
  fun `an override of a dropped abstract base member renders virtual, never override`() {
    val result = Tier1Harness.run(
      """
      package tier1.issue42base

      import dep.outside.OutsideAbstractBase

      class Api : OutsideAbstractBase() {
        override fun speak(): String = "meow"
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.issue42base"),
      libraries = listOf(dependencyJar),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      "export_api_speak" in result.generated,
      "the override is the only implementation there is once the base is dropped; " +
          "generated:\n${result.generated}",
    )
    // Scoped to this member: the generated file legitimately contains other `override`s
    // (the boilerplate's own `ToString`/`Equals`/`Dispose`), so a bare "override " would pass or
    // fail for reasons unrelated to the dropped base.
    assertFalse(
      "override string Speak()" in result.generatedCSharp,
      "with no generated base there is nothing to override — `public override string Speak()` " +
          "is CS0115; generated C#:\n${result.generatedCSharp}",
    )
    assertTrue(
      "public virtual string Speak()" in result.generatedCSharp,
      "the existing open-member rule still applies: a non-final Kotlin `override` with no " +
          "forward base renders `virtual`; generated C#:\n${result.generatedCSharp}",
    )
  }
}
