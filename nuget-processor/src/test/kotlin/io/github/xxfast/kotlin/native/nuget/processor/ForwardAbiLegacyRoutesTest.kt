package io.github.xxfast.kotlin.native.nuget.processor

import io.github.xxfast.kotlin.native.nuget.processor.cir.CirCallbackMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDeclaration
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDllImport
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirInterface
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirInterfaceBridgeMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMember
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirNamespace
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirObject
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirProperty
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirSealedClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirSealedSubclass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirStaticClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirStoredCallbackMethod
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
            // ADR-147: a generic class is an ordinary CirClass now, on no legacy route at all.
            CirClass(
              name = "Box",
              typeParameters = listOf(CirTypeParameter("T")),
              libraryName = "sample",
              nativePrefix = "box",
              constructor = null,
              properties = emptyList(),
              methods = emptyList(),
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

  /**
   * ADR-174: the interface owner kind. The backing wrapper carries the interface's async members
   * exactly where an ordinary class carries its own (`companionMembers`, flow `properties`), and a
   * generic implementer's explicit implementations ride the same slots, so both are recognized
   * structurally as the same three routes rather than by entry-point name.
   */
  @Test
  fun `an interface wrapper's and a generic implementer's async members are the legacy routes`() {
    val fetch = CirMethod(
      name = "FetchAsync",
      nativeName = "FeedNative.Native_FetchAsync",
      returnType = "Task<string>",
      parameters = emptyList(),
      body = "",
      isAsync = true,
      asyncReturnType = "string",
    )
    val ticks = CirMethod(
      name = "Ticks",
      nativeName = "FeedNative.Native_TicksCollect",
      returnType = "KotlinFlow<int>",
      parameters = emptyList(),
      body = "",
      isFlow = true,
      flowElementType = "int",
    )
    val level = CirProperty(
      name = "Level",
      type = "KotlinStateFlow<int>",
      nativeReturnType = "IntPtr",
      nativeName = "level",
      getter = "",
      isFlow = true,
      isStateFlow = true,
      flowElementType = "int",
    )
    fun owner(name: String, explicitInterface: String?): CirClass = CirClass(
      name = name,
      libraryName = "sample",
      nativePrefix = "feed",
      constructor = null,
      properties = listOf(level.copy(explicitInterface = explicitInterface)),
      methods = emptyList(),
      companionMembers = listOf(
        fetch.copy(explicitInterface = explicitInterface),
        ticks.copy(explicitInterface = explicitInterface),
      ),
      nativeCarrier = "FeedNative".takeIf { explicitInterface == null },
    )
    val file = CirFile(
      namespaces = listOf(
        CirNamespace(
          name = "Sample",
          declarations = listOf(owner("Feed", null), owner("Crate", "IFeed")),
        ),
      ),
    )

    assertEquals(
      setOf(
        ForwardAbiLegacyRoute.SUSPEND_METHOD,
        ForwardAbiLegacyRoute.FLOW_METHOD,
        ForwardAbiLegacyRoute.FLOW_PROPERTY,
      ),
      ForwardAbiLegacyRoutes.collect(file),
    )
  }

  /**
   * The three callback member kinds on an ordinary class: a per-call lambda parameter (ADR-036), a
   * stored-callback add/remove pair and an interface-bridge pair, each on its own route.
   */
  @Test
  fun `an ordinary class's per-call callback member is the lambda parameter route`() {
    assertEquals(
      setOf(ForwardAbiLegacyRoute.LAMBDA_PARAMETER_METHOD),
      ForwardAbiLegacyRoutes.collect(fileOf(owner(callbackMethods = listOf(perCallCallback)))),
    )
  }

  @Test
  fun `an ordinary class's stored callback pair is the stored callback route`() {
    assertEquals(
      setOf(ForwardAbiLegacyRoute.STORED_CALLBACK_METHOD),
      ForwardAbiLegacyRoutes.collect(fileOf(owner(storedCallbackMethods = listOf(storedCallback)))),
    )
  }

  @Test
  fun `an ordinary class's interface bridge pair is the interface bridge route`() {
    assertEquals(
      setOf(ForwardAbiLegacyRoute.INTERFACE_BRIDGE_METHOD),
      ForwardAbiLegacyRoutes.collect(
        fileOf(owner(interfaceBridgeMethods = listOf(interfaceBridge))),
      ),
    )
  }

  /**
   * ADR-116 amendments (2026-09-11, 2026-09-13): an arm carries all three callback kinds in one
   * [CirSealedSubclass.callbackMembers] list, each preceded by its [CirDllImport]s, so the arm's
   * list is walked and dispatched by member type rather than by which list it came from.
   */
  @Test
  fun `a sealed arm's per-call callback member is the lambda parameter route`() {
    assertEquals(
      setOf(ForwardAbiLegacyRoute.SEALED_CLASS, ForwardAbiLegacyRoute.LAMBDA_PARAMETER_METHOD),
      ForwardAbiLegacyRoutes.collect(fileOf(arm(perCallCallback))),
    )
  }

  @Test
  fun `a sealed arm's stored callback pair is the stored callback route`() {
    assertEquals(
      setOf(ForwardAbiLegacyRoute.SEALED_CLASS, ForwardAbiLegacyRoute.STORED_CALLBACK_METHOD),
      ForwardAbiLegacyRoutes.collect(fileOf(arm(storedCallback))),
    )
  }

  @Test
  fun `a sealed arm's interface bridge pair is the interface bridge route`() {
    assertEquals(
      setOf(ForwardAbiLegacyRoute.SEALED_CLASS, ForwardAbiLegacyRoute.INTERFACE_BRIDGE_METHOD),
      ForwardAbiLegacyRoutes.collect(fileOf(arm(interfaceBridge))),
    )
  }

  /**
   * ADR-133/ADR-134: a type declared inside another is rendered inside its owner's block and
   * carries its own legacy members, so the collector descends into every owner's
   * `nestedDeclarations` exactly as it walks a top-level declaration.
   */
  @Test
  fun `a type nested in a sealed base registers its own routes`() {
    val base: CirSealedClass = arm(perCallCallback).copy(nestedDeclarations = listOf(nested))
    assertEquals(
      setOf(ForwardAbiLegacyRoute.SEALED_CLASS, ForwardAbiLegacyRoute.LAMBDA_PARAMETER_METHOD) +
        nestedRoutes,
      ForwardAbiLegacyRoutes.collect(fileOf(base)),
    )
  }

  @Test
  fun `a type nested in a sealed arm registers its own routes`() {
    val base: CirSealedClass = arm(perCallCallback)
    val withNested: CirSealedClass = base.copy(
      subclasses = base.subclasses.map { it.copy(nestedDeclarations = listOf(nested)) },
    )
    assertEquals(
      setOf(ForwardAbiLegacyRoute.SEALED_CLASS, ForwardAbiLegacyRoute.LAMBDA_PARAMETER_METHOD) +
        nestedRoutes,
      ForwardAbiLegacyRoutes.collect(fileOf(withNested)),
    )
  }

  @Test
  fun `a type nested in an ordinary class registers its own routes`() {
    val outer: CirClass = owner().copy(name = "Outer", nestedDeclarations = listOf(nested))
    assertEquals(nestedRoutes, ForwardAbiLegacyRoutes.collect(fileOf(outer)))
  }

  @Test
  fun `a type nested in an object registers its own routes`() {
    val outer = CirObject(
      name = "Registry",
      libraryName = "sample",
      nativePrefix = "registry",
      methods = emptyList(),
      nestedDeclarations = listOf(nested),
    )
    assertEquals(nestedRoutes, ForwardAbiLegacyRoutes.collect(fileOf(outer)))
  }

  @Test
  fun `a type nested in an interface registers its own routes`() {
    val outer = CirInterface(
      name = "IFeed",
      properties = emptyList(),
      methods = emptyList(),
      nestedDeclarations = listOf(nested),
    )
    assertEquals(nestedRoutes, ForwardAbiLegacyRoutes.collect(fileOf(outer)))
  }

  /** Two levels deep, so the descent is recursive rather than one level. */
  @Test
  fun `a type nested two levels deep registers its own routes`() {
    val middle: CirClass = owner().copy(name = "Middle", nestedDeclarations = listOf(nested))
    val outer: CirClass = owner().copy(name = "Outer", nestedDeclarations = listOf(middle))
    assertEquals(nestedRoutes, ForwardAbiLegacyRoutes.collect(fileOf(outer)))
  }

  private val perCallCallback: CirCallbackMethod = CirCallbackMethod(
    csMethodName = "OnTick",
    nativeEntryPoint = "job_idle_onTick",
    libraryName = "sample",
    nativeImportReturnType = "void",
    lambdaParamName = "block",
    delegateName = "NugetIntVoidCallback",
    delegateParamList = "(int arg0, IntPtr _)",
    csReturnType = "void",
    csParamType = "Action<int>",
    callbackBody = "",
    wrapperBody = "",
  )

  private val storedCallback: CirStoredCallbackMethod = CirStoredCallbackMethod(
    csMethodName = "AddTickListener",
    csRemoveNativeName = "Native_RemoveTickListener",
    subscribeEntryPoint = "job_idle_addTickListener",
    removeEntryPoint = "job_idle_removeTickListener",
    libraryName = "sample",
    delegateName = "NugetIntVoidCallback",
    delegateParamList = "(int arg0, IntPtr _)",
    csParamType = "Action<int>",
    nativeCallbackBody = "",
    className = "Idle",
  )

  private val interfaceBridge: CirInterfaceBridgeMethod = CirInterfaceBridgeMethod(
    csMethodName = "AddListener",
    csRemoveNativeName = "Native_RemoveListener",
    subscribeEntryPoint = "job_idle_addListener",
    removeEntryPoint = "job_idle_removeListener",
    libraryName = "sample",
    interfaceCsName = "IJobListener",
    className = "Idle",
    entries = emptyList(),
  )

  // Declared after the callback fixtures it reads, so they are initialized first.
  private val nested: CirClass = owner(storedCallbackMethods = listOf(storedCallback)).copy(
    name = "Trace",
    nativePrefix = "job_trace",
    companionMembers = listOf(
      CirMethod(
        name = "FlushAsync",
        returnType = "Task<int>",
        parameters = emptyList(),
        body = "",
        isAsync = true,
        asyncReturnType = "int",
      ),
    ),
  )

  private val nestedRoutes: Set<ForwardAbiLegacyRoute> = setOf(
    ForwardAbiLegacyRoute.SUSPEND_METHOD,
    ForwardAbiLegacyRoute.STORED_CALLBACK_METHOD,
  )

  private fun owner(
    callbackMethods: List<CirCallbackMethod> = emptyList(),
    storedCallbackMethods: List<CirStoredCallbackMethod> = emptyList(),
    interfaceBridgeMethods: List<CirInterfaceBridgeMethod> = emptyList(),
  ): CirClass = CirClass(
    name = "Job",
    libraryName = "sample",
    nativePrefix = "job",
    constructor = null,
    properties = emptyList(),
    methods = emptyList(),
    callbackMethods = callbackMethods,
    storedCallbackMethods = storedCallbackMethods,
    interfaceBridgeMethods = interfaceBridgeMethods,
  )

  private fun arm(member: CirMember): CirSealedClass = CirSealedClass(
    name = "Job",
    libraryName = "sample",
    nativePrefix = "job",
    subclasses = listOf(
      CirSealedSubclass(
        name = "Idle",
        nativePrefix = "job_idle",
        properties = emptyList(),
        callbackMembers = listOf(
          CirDllImport(
            libraryName = "sample",
            entryPoint = "job_idle_native",
            returnType = "void",
            name = "Native_Idle",
            parameters = emptyList(),
          ),
          member,
        ),
      ),
    ),
  )

  private fun fileOf(declaration: CirDeclaration): CirFile = CirFile(
    namespaces = listOf(CirNamespace(name = "Sample", declarations = listOf(declaration))),
  )
}
