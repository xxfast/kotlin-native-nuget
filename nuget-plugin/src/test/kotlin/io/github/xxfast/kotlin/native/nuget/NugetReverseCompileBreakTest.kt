package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirAssembly
import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirInterface
import io.github.xxfast.kotlin.native.nuget.rir.RirInterfaceType
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirNamespace
import io.github.xxfast.kotlin.native.nuget.rir.RirParameter
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirProperty
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirStruct
import io.github.xxfast.kotlin.native.nuget.rir.RirStructComponent
import io.github.xxfast.kotlin.native.nuget.rir.RirStructType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * The generated reverse bindings that did not compile on a real package: an interface-typed
 * parameter with a struct return (a receiverless `handleOf(...)`), a cross-namespace interface in a
 * read position (an unimported `nuget{Name}Value(...)`), and a settable string interface property
 * whose `{Name}Handle` used `memScoped`/`cstr`/`ptr` without importing them.
 *
 * Every fixture here is deliberately MINIMAL in the direction that matters: the class whose only
 * interface position is a method parameter, and the interface whose only string INPUT is a property
 * setter. Both are shapes the existing fixtures mask by also having a string-parameter method or an
 * interface-typed setter.
 */
class NugetReverseCompileBreakTest {

  private val collar = RirStruct(
    name = "Collar",
    components = listOf(
      RirStructComponent(name = "girth", readName = "Girth", type = RirPrimitiveType("int")),
      RirStructComponent(name = "colour", readName = "Colour", type = RirStringType(false)),
    ),
  )

  private val collarType = RirStructType(namespace = "Test.Structs", name = "Collar")

  private val iFeedable = RirInterface(
    name = "IFeedable",
    methods = listOf(RirMethod(name = "Describe", returnType = RirStringType(nullable = false))),
    properties = listOf(RirProperty(name = "Legs", type = RirPrimitiveType("int"), isReadOnly = true)),
  )

  private val iFeedableType = RirInterfaceType(namespace = "Test.Menagerie", name = "IFeedable")

  // The class the two defects need: its ONLY interface position is `Fit`'s parameter (so the
  // nugetTransferScope import is not smuggled in by some other member), and it returns/exposes the
  // cross-namespace interface in both read positions (method return and nullable property).
  private val kennel = RirClass(
    name = "Kennel",
    methods = listOf(
      RirMethod(
        name = "Fit", returnType = collarType,
        parameters = listOf(RirParameter("guest", iFeedableType)),
      ),
      RirMethod(name = "Resident", returnType = iFeedableType),
    ),
    properties = listOf(
      RirProperty(
        name = "Favourite", type = iFeedableType.copy(nullable = true), isReadOnly = false,
      ),
    ),
  )

  private val rir = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "TestDependency",
        assemblyName = "TestDependency",
        namespaces = listOf(
          RirNamespace(name = "Test.Menagerie", types = listOf(iFeedable)),
          RirNamespace(name = "Test.Structs", types = listOf(collar)),
          RirNamespace(name = "Test.Kennel", types = listOf(kennel)),
        ),
      ),
    ),
  )

  // Without the aliases every namespace of one package lands in ONE Kotlin package, where the
  // resolver call needs no import at all and the E assertion would pass vacuously. The key is the
  // fixture's own packageId.
  private val aliases: Map<String, Map<String, String>> = mapOf(
    "TestDependency" to mapOf(
      "Test.Kennel" to "test.kennel",
      "Test.Menagerie" to "test.menagerie",
      "Test.Structs" to "test.structs",
    ),
  )

  private fun kennelKt(): String = generateKotlinStubs(rir, namespaceAliases = aliases)
    .single { it.relativePath.endsWith("test/kennel/Kennel.kt") }
    .content

  @Test
  fun `a struct return with an interface parameter opens the transfer scope`() {
    val kennelKt: String = kennelKt()

    // `handleOf` is a MEMBER of NugetTransferScope: the struct-return branch used to emit it bare.
    assertContains(kennelKt, "nugetCall { err -> nugetTransferScope { fn.invoke(")
    assertContains(kennelKt, "handleOf(guest, \"Test.Menagerie.IFeedable\")")
  }

  @Test
  fun `the transfer scope import is emitted when the only interface position is a method parameter`() {
    assertContains(
      kennelKt(),
      "import io.github.xxfast.kotlin.native.nuget.internal.nugetTransferScope",
    )
  }

  @Test
  fun `a cross-package interface read position imports its resolver as well as its type`() {
    val kennelKt: String = kennelKt()

    assertContains(kennelKt, "import test.menagerie.IFeedable")
    assertContains(kennelKt, "import test.menagerie.nugetIFeedableValue")
    // Both emit sites: the method return (requireNotNull) and the nullable property getter (?.let).
    assertContains(kennelKt, "nugetIFeedableValue(requireNotNull(")
    assertContains(kennelKt, "ptr?.let { nugetIFeedableValue(it) }")
  }

  @Test
  fun `a parameter-only interface reference does not import the resolver`() {
    val fitterOnly = RirClass(
      name = "Fitter",
      methods = listOf(
        RirMethod(
          name = "Fit", returnType = collarType,
          parameters = listOf(RirParameter("guest", iFeedableType)),
        ),
      ),
    )
    val file: String = generateKotlinStubs(
      RirFile(
        assemblies = listOf(
          RirAssembly(
            packageId = "TestDependency",
            assemblyName = "TestDependency",
            namespaces = listOf(
              RirNamespace(name = "Test.Menagerie", types = listOf(iFeedable)),
              RirNamespace(name = "Test.Structs", types = listOf(collar)),
              RirNamespace(name = "Test.Kennel", types = listOf(fitterOnly)),
            ),
          ),
        ),
      ),
      namespaceAliases = aliases,
    ).single { it.relativePath.endsWith("test/kennel/Fitter.kt") }.content

    assertContains(file, "import test.menagerie.IFeedable")
    assertFalse(
      file.contains("nugetIFeedableValue"),
      "the argument path uses handleOf, never the resolver, so an unused import would be noise",
    )
  }

  @Test
  fun `a settable string interface property imports memScoped cstr and ptr`() {
    // The pre-existing gap: the import collector counted string METHOD PARAMETERS only, so an
    // interface whose only string input is a property setter generated a `{Name}Handle.kt` that
    // used `.cstr.ptr` inside `memScoped` with none of the three imported.
    val iCollar = RirInterface(
      name = "ICollar",
      methods = listOf(RirMethod(name = "Size", returnType = RirPrimitiveType("int"))),
      properties = listOf(
        RirProperty(name = "Colour", type = RirStringType(nullable = false), isReadOnly = false),
      ),
    )
    val file: String = generateKotlinStubs(
      RirFile(
        assemblies = listOf(
          RirAssembly(
            packageId = "TestDependency",
            assemblyName = "TestDependency",
            namespaces = listOf(RirNamespace(name = "Test.Menagerie", types = listOf(iCollar))),
          ),
        ),
      ),
    ).single { it.relativePath.endsWith("/ICollarHandle.kt") }.content

    assertContains(file, "memScoped {")
    assertContains(file, "import kotlinx.cinterop.cstr")
    assertContains(file, "import kotlinx.cinterop.memScoped")
    assertContains(file, "import kotlinx.cinterop.ptr")
  }
}
