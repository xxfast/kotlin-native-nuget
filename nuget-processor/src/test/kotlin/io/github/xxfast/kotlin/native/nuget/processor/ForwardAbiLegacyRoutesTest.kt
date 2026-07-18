package io.github.xxfast.kotlin.native.nuget.processor

import io.github.xxfast.kotlin.native.nuget.processor.cir.CirClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirGenericClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirNamespace
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirProperty
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirSealedClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirStaticClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirTypeParameter
import kotlin.test.Test
import kotlin.test.assertEquals

class ForwardAbiLegacyRoutesTest {
  @Test
  fun `legacy route names are explicit and total`() {
    assertEquals(
      setOf(
        ForwardAbiLegacyRoute.GENERIC_FUNCTION,
        ForwardAbiLegacyRoute.GENERIC_EXTENSION_FUNCTION,
        ForwardAbiLegacyRoute.GENERIC_CLASS,
        ForwardAbiLegacyRoute.SEALED_CLASS,
        ForwardAbiLegacyRoute.SUSPEND_FUNCTION,
        ForwardAbiLegacyRoute.SUSPEND_METHOD,
        ForwardAbiLegacyRoute.FLOW_PROPERTY,
        ForwardAbiLegacyRoute.FLOW_METHOD,
        ForwardAbiLegacyRoute.LAMBDA_PROPERTY,
        ForwardAbiLegacyRoute.SUSPEND_LAMBDA_PROPERTY,
        ForwardAbiLegacyRoute.LAMBDA_PARAMETER_METHOD,
        ForwardAbiLegacyRoute.STORED_CALLBACK_METHOD,
        ForwardAbiLegacyRoute.INTERFACE_BRIDGE_METHOD,
      ),
      ForwardAbiLegacyRoute.entries.toSet(),
    )
  }

  @Test
  fun `collector names specialized declarations without an unknown route`() {
    val file = CirFile(
      namespaces = listOf(
        CirNamespace(
          name = "Sample",
          declarations = listOf(
            CirStaticClass(
              name = "Functions",
              members = listOf(
                CirMethod(
                  name = "Load",
                  returnType = "Task<int>",
                  parameters = emptyList(),
                  body = "",
                  isExtension = true,
                  typeParameters = listOf(CirTypeParameter("T")),
                  isAsync = true,
                )
              ),
            ),
            CirClass(
              name = "SampleClass",
              libraryName = "sample",
              nativePrefix = "sample",
              constructor = null,
              properties = listOf(
                CirProperty(
                  name = "Events",
                  type = "IAsyncEnumerable<int>",
                  nativeReturnType = "IntPtr",
                  nativeName = "events",
                  getter = "",
                  isFlow = true,
                ),
                CirProperty(
                  name = "Handler",
                  type = "KotlinFunc<int>",
                  nativeReturnType = "IntPtr",
                  nativeName = "handler",
                  getter = "",
                ),
              ),
              methods = listOf(
                CirMethod(
                  name = "Watch",
                  returnType = "IAsyncEnumerable<int>",
                  parameters = emptyList(),
                  body = "",
                  isFlow = true,
                )
              ),
            ),
            CirGenericClass(
              name = "Box",
              typeParameters = listOf(CirTypeParameter("T")),
              libraryName = "sample",
              nativePrefix = "box",
              properties = emptyList(),
            ),
            CirSealedClass(
              name = "Result",
              libraryName = "sample",
              nativePrefix = "result",
              subclasses = emptyList(),
            ),
          ),
        )
      ),
    )

    assertEquals(
      setOf(
        ForwardAbiLegacyRoute.GENERIC_EXTENSION_FUNCTION,
        ForwardAbiLegacyRoute.SUSPEND_FUNCTION,
        ForwardAbiLegacyRoute.FLOW_PROPERTY,
        ForwardAbiLegacyRoute.LAMBDA_PROPERTY,
        ForwardAbiLegacyRoute.FLOW_METHOD,
        ForwardAbiLegacyRoute.GENERIC_CLASS,
        ForwardAbiLegacyRoute.SEALED_CLASS,
      ),
      ForwardAbiLegacyRoutes.collect(file),
    )
  }
}
