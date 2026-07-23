package io.github.xxfast.kotlin.native.nuget.processor

import io.github.xxfast.kotlin.native.nuget.processor.cir.CirAsyncHelper
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirCallbackDelegateHelper
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirCallbackMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirConst
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDeclaration
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDllImport
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirEnum
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirErrorHelper
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFlowHelper
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFuncHelper
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFuncNativeHelper
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirGenericClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirInterface
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirInterfaceBridgeMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirJobHelper
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirListHelper
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMapHelper
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMarshalHelper
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMember
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirObject
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirProperty
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirScopeHelper
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirSealedClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirSetHelper
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirStateFlowHandleHelper
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirStaticClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirStoredCallbackMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirSubscriptionHelper
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirSuspendFuncHelper
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirSuspendFuncNativeHelper
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirValueClass

internal enum class ForwardAbiLegacyRoute {
  GENERIC_FUNCTION,
  GENERIC_EXTENSION_FUNCTION,
  GENERIC_CLASS,
  SEALED_CLASS,
  SUSPEND_FUNCTION,
  SUSPEND_METHOD,
  FLOW_PROPERTY,
  FLOW_METHOD,
  LAMBDA_PROPERTY,
  SUSPEND_LAMBDA_PROPERTY,
  LAMBDA_PARAMETER_METHOD,
  STORED_CALLBACK_METHOD,
  INTERFACE_BRIDGE_METHOD,
}

internal object ForwardAbiLegacyRoutes {
  fun collect(file: CirFile): Set<ForwardAbiLegacyRoute> = buildSet {
    file.namespaces
      .flatMap { namespace -> namespace.declarations }
      .forEach { declaration -> add(declaration) }
  }

  private fun MutableSet<ForwardAbiLegacyRoute>.add(declaration: CirDeclaration) {
    when (declaration) {
      is CirClass -> {
        declaration.properties.forEach { property -> add(property) }
        declaration.methods.forEach { method -> add(method, ForwardAbiLegacyRoute.SUSPEND_METHOD) }
        declaration.companionMembers.forEach { member ->
          add(member, ForwardAbiLegacyRoute.SUSPEND_METHOD)
        }
        if (declaration.callbackMethods.isNotEmpty()) {
          add(ForwardAbiLegacyRoute.LAMBDA_PARAMETER_METHOD)
        }
        if (declaration.storedCallbackMethods.isNotEmpty()) {
          add(ForwardAbiLegacyRoute.STORED_CALLBACK_METHOD)
        }
        if (declaration.interfaceBridgeMethods.isNotEmpty()) {
          add(ForwardAbiLegacyRoute.INTERFACE_BRIDGE_METHOD)
        }
      }

      is CirStaticClass -> declaration.members.forEach { member ->
        add(member, ForwardAbiLegacyRoute.SUSPEND_FUNCTION)
      }

      is CirObject -> declaration.methods.forEach { member ->
        add(member, ForwardAbiLegacyRoute.SUSPEND_METHOD)
      }

      is CirGenericClass -> add(ForwardAbiLegacyRoute.GENERIC_CLASS)
      is CirSealedClass -> add(ForwardAbiLegacyRoute.SEALED_CLASS)
      is CirAsyncHelper,
      is CirCallbackDelegateHelper,
      is CirEnum,
      is CirErrorHelper,
      is CirFlowHelper,
      is CirFuncHelper,
      is CirFuncNativeHelper,
      is CirInterface,
      is CirJobHelper,
      is CirListHelper,
      is CirMapHelper,
      is CirMarshalHelper,
      is CirScopeHelper,
      is CirSetHelper,
      is CirStateFlowHandleHelper,
      is CirSubscriptionHelper,
      is CirSuspendFuncHelper,
      is CirSuspendFuncNativeHelper,
      is CirValueClass,
        -> Unit
    }
  }

  private fun MutableSet<ForwardAbiLegacyRoute>.add(
    member: CirMember,
    suspendRoute: ForwardAbiLegacyRoute,
  ) {
    when (member) {
      is CirMethod -> add(member, suspendRoute)
      is CirProperty -> add(member)
      is CirCallbackMethod -> add(ForwardAbiLegacyRoute.LAMBDA_PARAMETER_METHOD)
      is CirStoredCallbackMethod -> add(ForwardAbiLegacyRoute.STORED_CALLBACK_METHOD)
      is CirInterfaceBridgeMethod -> add(ForwardAbiLegacyRoute.INTERFACE_BRIDGE_METHOD)
      is CirConst, is CirDllImport -> Unit
    }
  }

  private fun MutableSet<ForwardAbiLegacyRoute>.add(
    method: CirMethod,
    suspendRoute: ForwardAbiLegacyRoute,
  ) {
    if (method.typeParameters.isNotEmpty()) {
      val route: ForwardAbiLegacyRoute = if (method.isExtension) {
        ForwardAbiLegacyRoute.GENERIC_EXTENSION_FUNCTION
      } else {
        ForwardAbiLegacyRoute.GENERIC_FUNCTION
      }
      add(route)
    }
    if (method.isAsync) add(suspendRoute)
    if (method.isFlow) add(ForwardAbiLegacyRoute.FLOW_METHOD)
  }

  private fun MutableSet<ForwardAbiLegacyRoute>.add(property: CirProperty) {
    if (property.isFlow) add(ForwardAbiLegacyRoute.FLOW_PROPERTY)
    if (property.type.startsWith("KotlinFunc<")) {
      add(ForwardAbiLegacyRoute.LAMBDA_PROPERTY)
    }
    val isSuspendLambda: Boolean = property.type.startsWith("KotlinSuspendFunc<") ||
        property.type.startsWith("KotlinSuspendAction")
    if (isSuspendLambda) add(ForwardAbiLegacyRoute.SUSPEND_LAMBDA_PROPERTY)
  }
}
