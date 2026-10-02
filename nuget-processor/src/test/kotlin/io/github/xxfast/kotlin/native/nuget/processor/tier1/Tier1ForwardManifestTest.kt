package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Tier1ForwardManifestTest {
  @Test fun `real processor emits canonical ABI next to original bindings`() {
    val result = Tier1Harness.run("package fixture\nfun answer(value: Int): Int = value\nfun écho(value: Int): Int = value", processorOptions = mapOf("nuget.namespace" to "Fixture"))
    assertTrue(result.kspSucceeded, result.kspErrors.toString())
    assertTrue(result.compiledClean, result.compileErrors.toString())
    val manifest = assertNotNull(result.generatedFiles["ForwardAbi.json"])
    val source = assertNotNull(result.generatedFiles["Interop.cs"])
    assertTrue(manifest.contains("\"schemaVersion\": 1"), manifest)
    val symbol = Regex("EntryPoint = \"([^\"]*answer[^\"]*)\"").find(source)!!.groupValues[1]
    assertTrue(manifest.contains("$symbol(in int, out pointer) -> int"), manifest)
    val imports = Regex("EntryPoint = \"([^\"]+)\"").findAll(source).map { it.groupValues[1] }.toSet()
    val signatures = Regex(""""([^"]+\([^"]*\) -> [^"]+)"""" ).findAll(manifest).map { it.groupValues[1] }.toList()
    assertTrue(signatures.any { it.contains("écho") }, manifest)
    assertEquals(imports, signatures.map { it.substringBefore('(') }.toSet())
    assertEquals(signatures.sorted(), signatures)
  }
}
