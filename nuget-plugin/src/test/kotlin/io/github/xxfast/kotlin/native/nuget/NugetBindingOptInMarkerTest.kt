package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirAssembly
import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirConstructor
import io.github.xxfast.kotlin.native.nuget.rir.RirDelegateType
import io.github.xxfast.kotlin.native.nuget.rir.RirEnum
import io.github.xxfast.kotlin.native.nuget.rir.RirEnumEntry
import io.github.xxfast.kotlin.native.nuget.rir.RirEnumType
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirInterface
import io.github.xxfast.kotlin.native.nuget.rir.RirInterfaceType
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirNamespace
import io.github.xxfast.kotlin.native.nuget.rir.RirParameter
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirStruct
import io.github.xxfast.kotlin.native.nuget.rir.RirStructComponent
import io.github.xxfast.kotlin.native.nuget.rir.RirStructType
import io.github.xxfast.kotlin.native.nuget.rir.RirVoidType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * ADR-181: every consumer-facing declaration the reverse generator emits carries the ERROR-level
 * `@ExperimentalNugetBindingApi` marker, and every generated file opts in to it.
 *
 * Both layers are load-bearing: the declaration marker is what gates the consumer (the stubs
 * compile in the consumer's own module, so `internal` declarations are consumer surface too), and
 * the file-level opt-in is what keeps the unmarked glue (`*Bindings`, `*Handle`, the runtime)
 * compiling against the marked declarations it names.
 */
class NugetBindingOptInMarkerTest {

  private val marker: String =
    "@io.github.xxfast.kotlin.native.nuget.annotations.ExperimentalNugetBindingApi"

  private val markerOptIn: String =
    "io.github.xxfast.kotlin.native.nuget.annotations.ExperimentalNugetBindingApi::class"

  private val ns = "Kennel.Space"
  private val int = RirPrimitiveType("int")
  private val sizeType = RirEnumType(namespace = ns, name = "Size")
  private val pointType = RirStructType(namespace = ns, name = "Point")
  private val feedableType = RirInterfaceType(namespace = ns, name = "IFeedable")

  private val handler = RirDelegateType(
    definition = "$ns.Handler",
    parameters = listOf(int),
    returnType = int,
  )

  private val rir: RirFile = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "Kennel",
        assemblyName = "Kennel",
        namespaces = listOf(
          RirNamespace(
            name = ns,
            types = listOf(
              RirClass(
                name = "JsonConvert",
                isAbstract = true,
                isStatic = true,
                methods = listOf(
                  RirMethod(
                    name = "SerializeObject",
                    isStatic = true,
                    returnType = RirStringType(),
                    parameters = listOf(RirParameter(name = "value", type = int)),
                  ),
                  RirMethod(
                    name = "Apply",
                    isStatic = true,
                    returnType = int,
                    parameters = listOf(RirParameter(name = "handler", type = handler)),
                  ),
                  RirMethod(
                    name = "Translate",
                    isStatic = true,
                    returnType = pointType,
                    parameters = listOf(RirParameter(name = "p", type = pointType)),
                  ),
                ),
              ),
              RirClass(
                name = "Dog",
                constructors = listOf(RirConstructor()),
                methods = listOf(
                  RirMethod(name = "Size", returnType = sizeType),
                  RirMethod(
                    name = "Adopt",
                    returnType = RirVoidType,
                    parameters = listOf(RirParameter(name = "feedable", type = feedableType)),
                  ),
                ),
              ),
              RirStruct(
                name = "Point",
                components = listOf(
                  RirStructComponent(name = "x", readName = "X", type = int),
                  RirStructComponent(name = "y", readName = "Y", type = int),
                ),
              ),
              RirEnum(
                name = "Size",
                entries = listOf(RirEnumEntry("Small", 0), RirEnumEntry("Large", 1)),
              ),
              RirInterface(
                name = "IFeedable",
                methods = listOf(RirMethod(name = "Feed", returnType = RirVoidType)),
              ),
            ),
          ),
        ),
      ),
    ),
  )

  private val files: List<GeneratedFile> by lazy { generateKotlinStubs(rir) }

  private fun file(suffix: String): String = assertNotNull(
    files.singleOrNull { it.relativePath.endsWith(suffix) },
    "no generated file ends with $suffix; generated: ${files.map { it.relativePath }}",
  ).content

  @Test
  fun `every consumer facing declaration carries the marker`() {
    assertContains(file("/JsonConvert.kt"), "$marker\ninternal object JsonConvert {")
    assertContains(file("/Dog.kt"), "$marker\ninternal class Dog internal constructor(")
    assertContains(file("/Point.kt"), "$marker\ninternal data class Point(")
    assertContains(file("/Size.kt"), "$marker\nenum class Size {")
    assertContains(file("/IFeedable.kt"), "$marker\ninterface IFeedable")
    assertContains(file("/NugetDelegates.kt"), "$marker\ntypealias Handler = ")
  }

  @Test
  fun `generated glue is not marked`() {
    val glue: List<String> = listOf(
      "/JsonConvertBindings.kt", "/DogBindings.kt", "/PointBindings.kt",
      "/IFeedableHandle.kt", "/IFeedableBindings.kt", "/NugetRuntime.kt", "/NugetRegistry.kt",
    )
    glue.forEach { suffix ->
      files.filter { it.relativePath.endsWith(suffix) }.forEach { glueFile ->
        assertFalse(
          "$marker\n" in glueFile.content,
          "${glueFile.relativePath} is glue and must not be marked:\n${glueFile.content}",
        )
      }
    }
  }

  @Test
  fun `every generated Kotlin file opts in to the marker`() {
    files.filter { it.relativePath.endsWith(".kt") }.forEach { generated ->
      assertContains(
        generated.content.substringBefore("\npackage "),
        markerOptIn,
        message = "${generated.relativePath} does not opt in to the binding marker",
      )
    }
  }
}
