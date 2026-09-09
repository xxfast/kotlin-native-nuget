package io.github.xxfast.kotlin.native.nuget.processor

import io.github.xxfast.kotlin.native.nuget.processor.cir.CirClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirGenericClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirNamespace
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirProperty
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirSealedClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirSealedSubclass
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
        ForwardAbiLegacyRoute.INTERFACE_BRIDGE_FACTORY,
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

  /**
   * ADR-118: a `suspend fun` declared on a sealed **arm** is the legacy suspend route under a new
   * owner, so the arm's async members have to be walked. Recognition stays structural
   * (`CirMethod.isAsync`), never by the `job_running_pause_async` entry-point shape.
   */
  @Test
  fun `a sealed arm's async member is recognized as the suspend method route`() {
    val file = CirFile(
      namespaces = listOf(
        CirNamespace(
          name = "Sample",
          declarations = listOf(
            CirSealedClass(
              name = "Job",
              libraryName = "sample",
              nativePrefix = "job",
              subclasses = listOf(
                CirSealedSubclass(
                  name = "Running",
                  nativePrefix = "job_running",
                  properties = emptyList(),
                  asyncMembers = listOf(
                    CirMethod(
                      name = "PauseAsync",
                      nativeName = "Native_PauseAsync",
                      returnType = "Task<int>",
                      parameters = emptyList(),
                      body = "",
                      isAsync = true,
                      asyncReturnType = "int",
                    ),
                  ),
                  hasSuspendMethods = true,
                ),
              ),
            ),
          ),
        ),
      ),
    )

    assertEquals(
      setOf(ForwardAbiLegacyRoute.SEALED_CLASS, ForwardAbiLegacyRoute.SUSPEND_METHOD),
      ForwardAbiLegacyRoutes.collect(file),
    )
  }

  /**
   * ADR-124: the flow twin of the cell above. An arm's `Flow`-returning method rides
   * [CirSealedSubclass.flowMembers] and its `StateFlow` property rides `properties`, two different
   * carriers for one route, so both are walked and both are recognized structurally
   * (`CirMethod.isFlow`, `CirProperty.isFlow`) rather than by the arm's entry-point shape.
   */
  @Test
  fun `a sealed arm's flow member and flow property are recognized as the flow routes`() {
    val file = CirFile(
      namespaces = listOf(
        CirNamespace(
          name = "Sample",
          declarations = listOf(
            CirSealedClass(
              name = "Job",
              libraryName = "sample",
              nativePrefix = "job",
              subclasses = listOf(
                CirSealedSubclass(
                  name = "Watching",
                  nativePrefix = "job_watching",
                  properties = listOf(
                    CirProperty(
                      name = "Ticks",
                      type = "KotlinStateFlow<int>",
                      nativeReturnType = "IntPtr",
                      nativeName = "ticks",
                      getter = "",
                      isFlow = true,
                      isStateFlow = true,
                      flowElementType = "int",
                    ),
                  ),
                  flowMembers = listOf(
                    CirMethod(
                      name = "Labels",
                      nativeName = "Native_LabelsCollect",
                      returnType = "KotlinFlow<string>",
                      parameters = emptyList(),
                      body = "",
                      isFlow = true,
                      flowElementType = "string",
                    ),
                  ),
                  hasSuspendMethods = true,
                ),
              ),
            ),
          ),
        ),
      ),
    )

    assertEquals(
      setOf(
        ForwardAbiLegacyRoute.SEALED_CLASS,
        ForwardAbiLegacyRoute.FLOW_METHOD,
        ForwardAbiLegacyRoute.FLOW_PROPERTY,
      ),
      ForwardAbiLegacyRoutes.collect(file),
    )
  }
}
