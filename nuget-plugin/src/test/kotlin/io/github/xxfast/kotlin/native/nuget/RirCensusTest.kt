package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.Census
import io.github.xxfast.kotlin.native.nuget.rir.RirAssembly
import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirDiagnostic
import io.github.xxfast.kotlin.native.nuget.rir.RirDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirNamespace
import io.github.xxfast.kotlin.native.nuget.rir.RirParameter
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirProperty
import io.github.xxfast.kotlin.native.nuget.rir.RirPublicSurface
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirVoidType
import io.github.xxfast.kotlin.native.nuget.rir.census
import io.github.xxfast.kotlin.native.nuget.rir.parseReverseIr
import io.github.xxfast.kotlin.native.nuget.rir.readerFailureCensus
import io.github.xxfast.kotlin.native.nuget.rir.toStableJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The PURE half of the reverse census: everything about the census that does not need nuget.org, so
 * it runs in the ordinary `test` task. The real-package run lives in `DogfoodCensusTest` (tagged
 * `dogfood`); this file pins the shape, the counting rules and the renderer, which is what a golden
 * diff is read against.
 */
class RirCensusTest {
  // `Unsupported` is NOT in this RIR: an unbound type reference is something the reader refused, so
  // on the Kotlin side it only ever arrives as a diagnostic. `Register` is bridgeable, `Track` takes
  // a handle to a type nothing declares and so is not.
  private val rir: RirFile = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "Acme.Lib",
        assemblyName = "Acme.Lib",
        namespaces = listOf(
          RirNamespace(
            name = "Acme.Lib",
            types = listOf(
              RirClass(
                name = "Registry",
                methods = listOf(
                  RirMethod(
                    name = "Register",
                    isStatic = true,
                    returnType = RirVoidType,
                    parameters = listOf(RirParameter("name", RirStringType())),
                  ),
                  RirMethod(
                    name = "Count",
                    isStatic = false,
                    returnType = RirPrimitiveType("int"),
                    parameters = emptyList(),
                  ),
                ),
                properties = listOf(
                  RirProperty(name = "Name", type = RirStringType(), isReadOnly = true),
                ),
              ),
              RirClass(name = "Helpers", isStatic = true),
            ),
          ),
        ),
        diagnostics = listOf(
          diagnostic(
            RirDiagnosticKind.SKIPPED_UNBOUND_TYPE_REFERENCE,
            member = "Track",
            reason = "parameter `System.TimeSpan` has no reverse mapping",
          ),
          diagnostic(
            RirDiagnosticKind.SKIPPED_UNBOUND_TYPE_REFERENCE,
            member = "Elapsed",
            reason = "property `System.TimeSpan` has no reverse mapping",
          ),
          diagnostic(
            RirDiagnosticKind.SKIPPED_INDEXER,
            member = "Item",
            reason = "indexers are deferred",
          ),
          diagnostic(
            RirDiagnosticKind.INFO_OBLIVIOUS_NULLABILITY,
            member = "Register",
            reason = "member is nullable-oblivious",
          ),
        ),
        publicSurface = RirPublicSurface(
          types = 3,
          nestedTypes = 1,
          structs = 1,
          methods = 6,
          constructors = 1,
          properties = 2,
          operators = 2,
          events = 1,
        ),
      ),
    ),
  )

  private fun diagnostic(
    kind: RirDiagnosticKind,
    member: String,
    reason: String,
  ): RirDiagnostic = RirDiagnostic(
    kind = kind,
    typeName = "Registry",
    memberName = member,
    memberSignature = "$member()",
    reason = reason,
    hint = "",
  )

  private fun censusOf(): Census = census(
    rir = rir,
    packageName = "Acme.Lib",
    version = "1.2.3",
    asset = "lib/net8.0/Acme.Lib.dll",
  )

  @Test
  fun `bound counts come off the bridgeable filters, not the registrable list`() {
    val census: Census = censusOf()
    // A property contributes ONE bound member. `bridgeableRegistrables` would count its getter and
    // its setter separately, which is how the spike saw `bound` exceed the RIR's own member count.
    assertEquals(1, census.members.getValue("property").bound)
    assertEquals(1, census.members.getValue("property").seen)
    assertEquals(1, census.members.getValue("staticMethod").bound)
    assertEquals(1, census.members.getValue("instanceMethod").bound)
  }

  @Test
  fun `the denominator is the reader's independent count, not the RIR's own members`() {
    val surface = requireNotNull(censusOf().publicSurface)
    // 6 methods + 1 ctor + 2 properties + 2 operators + 1 event. Operators and the nested type's
    // members are counted IN on purpose: they are dropped with no diagnostic, so the ratio must pay
    // for them rather than shrink its own denominator.
    assertEquals(12, surface.members)
    assertEquals(1, surface.nestedTypes)
    // The RIR sees far fewer than that, which is exactly the gap the denominator exists to expose.
    assertTrue(censusOf().members.values.sumOf { it.seen } < surface.members)
  }

  @Test
  fun `an oblivious island keeps per-member entries and is not an oblivious assembly`() {
    val census: Census = censusOf()
    // ADR-053 collapses the whole-assembly case into ONE entry naming no member. A per-member entry
    // is an island inside an annotated assembly, and the two must not read the same.
    assertEquals(false, census.nullability.assemblyOblivious)
    assertEquals(1, census.nullability.obliviousMembers)
  }

  @Test
  fun `diagnostics bucket by kind and the unbound histogram reads the backticked subject`() {
    val census: Census = censusOf()
    assertEquals(2, census.diagnostics.getValue("skipped_unbound_type_reference"))
    assertEquals(1, census.diagnostics.getValue("skipped_indexer"))
    assertEquals(mapOf("System.TimeSpan" to 2), census.unboundTypeReferences)
  }

  @Test
  fun `a reader failure is a row, not an abort`() {
    val census: Census = readerFailureCensus(
      packageName = "CsvHelper",
      version = "33.0.1",
      asset = "lib/net8.0/CsvHelper.dll",
      readerError = "error: type `CsvContext` contains duplicate canonical managed signature",
    )
    assertEquals("failed", census.reader)
    assertEquals("not_reached", census.generation)
    assertNull(census.publicSurface)
    assertTrue(census.toStableJson().contains("\"reader\": \"failed\""))
  }

  @Test
  fun `the rendered json has a fixed key order and is stable across runs`() {
    val first: String = censusOf().toStableJson()
    assertEquals(first, censusOf().toStableJson())
    val keys: List<String> = Regex("^  \"([A-Za-z]+)\":", RegexOption.MULTILINE)
      .findAll(first).map { it.groupValues[1] }.toList()
    assertEquals(
      listOf(
        "package", "version", "asset", "includes", "reader", "readerError", "generation",
        "generationError", "nullability", "publicSurface", "types", "members", "diagnostics",
        "unboundTypeReferences", "collapsedOverloads", "errors",
      ),
      keys,
    )
    // No absolute path and no timestamp may ever reach a golden: either makes every machine's run
    // a diff.
    assertTrue(!first.contains(":\\") && !first.contains("/home/") && !first.contains("/Users/"))
  }

  @Test
  fun `an older reverse-ir with no publicSurface still parses`() {
    // The field is optional precisely so a reverse-ir.json written before the reader learned to
    // count (or any hand-built fixture) is not a parse failure.
    val parsed: RirFile = parseReverseIr(
      """
      { "assemblies": [ { "packageId": "Old", "assemblyName": "Old", "namespaces": [] } ] }
      """.trimIndent(),
    )
    assertNull(parsed.assemblies.single().publicSurface)
    assertNull(
      census(parsed, packageName = "Old", version = "0.1.0", asset = "lib/net8.0/Old.dll")
        .publicSurface,
    )
  }

  @Test
  fun `allDiagnostics keeps the kind that formatDiagnostic throws away`() {
    // The refactor the census needs: the formatted strings are unchanged, and the structured list
    // carries the same entries with their kinds intact.
    val formatted: List<String> = diagnosticWarnings(rir)
    val structured: List<Pair<String, RirDiagnostic>> = allDiagnostics(rir)
    assertEquals(formatted.size, structured.size)
    assertTrue(structured.all { it.first == "Acme.Lib" })
    // The kind is what the rendered string drops and the census needs; every structured entry's
    // member must still be named by the formatted line at the same position.
    structured.forEachIndexed { index, (_, diagnostic) ->
      assertTrue(
        formatted[index].contains(diagnostic.memberName),
        "formatted[$index] must still name ${diagnostic.memberName}: ${formatted[index]}",
      )
    }
    assertEquals(
      setOf(
        RirDiagnosticKind.SKIPPED_UNBOUND_TYPE_REFERENCE,
        RirDiagnosticKind.SKIPPED_INDEXER,
        RirDiagnosticKind.INFO_OBLIVIOUS_NULLABILITY,
      ),
      structured.map { it.second.kind }.toSet(),
    )
  }
}
