package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.exports.findInterfaceBridgePairs
import io.github.xxfast.kotlin.native.nuget.processor.exports.findStoredCallbackPairs
import io.github.xxfast.kotlin.native.nuget.processor.toCName

internal enum class ForwardPlanSkipReason {
  ABSTRACT,
  CALLBACK_PROTOCOL,
  CHAR,
  COLLECTION,
  ENUM,
  FLOW_PROTOCOL,
  GENERIC,
  HANDLE,
  NULLABLE,
  OBJECT,
  SEALED_PROTOCOL,
  STRING,
  SUSPEND,
  SUSPEND_CALLBACK_PROTOCOL,
  TYPE_PARAMETER,
  UNSUPPORTED,
  VALUE_CLASS,
}

internal sealed interface ForwardCallableCatalogEntry {
  val symbol: String

  data class Planned(
    val plan: ForwardCallablePlan,
  ) : ForwardCallableCatalogEntry {
    override val symbol: String = plan.invocation.symbol
  }

  data class Skipped(
    override val symbol: String,
    val reason: ForwardPlanSkipReason,
  ) : ForwardCallableCatalogEntry
}

/**
 * Complete planning result for the first migration slice. Every callable inspected by this
 * planner is either [ForwardCallableCatalogEntry.Planned] or explicitly [Skipped]; no raw KSP
 * type or implicit fallback reaches the emission phase.
 */
internal data class ForwardCallablePlanCatalog(
  val entries: List<ForwardCallableCatalogEntry>,
  val propertyPlans: List<ForwardPropertyPlan> = emptyList(),
) {
  val plans: List<ForwardCallablePlan> = entries.mapNotNull { entry ->
    (entry as? ForwardCallableCatalogEntry.Planned)?.plan
  }

  fun propertyFor(symbol: String): ForwardPropertyPlan? {
    val matches: List<ForwardPropertyPlan> = propertyPlans.filter { plan -> plan.symbol == symbol }
    require(matches.size <= 1) { "Forward property catalog has duplicate plans for $symbol" }
    return matches.singleOrNull()
  }
}

/**
 * Builds the shadow plan for ordinary synchronous class methods and primitive-receiver extension
 * functions. This phase intentionally does not hand its plans to either renderer.
 */
internal class ForwardCallablePlanner(
  private val classifier: ForwardBridgeTypeClassifier,
) {
  fun catalog(
    classes: List<KSClassDeclaration>,
    functions: List<KSFunctionDeclaration>,
    extensionFunctions: List<KSFunctionDeclaration>,
    objects: List<KSClassDeclaration>,
    properties: List<KSPropertyDeclaration>,
    extensionProperties: List<KSPropertyDeclaration>,
    valueClasses: List<KSClassDeclaration> = emptyList(),
  ): ForwardCallablePlanCatalog {
    val entries: List<ForwardCallableCatalogEntry> = buildList {
      classes.forEach { cls -> addAll(classEntries(cls)) }
      classes.forEach { cls -> addAll(constructorEntries(cls)) }
      functions.forEach { function -> add(topLevelEntry(function)) }
      extensionFunctions.forEach { function -> add(extensionEntry(function)) }
      objects.forEach { obj -> addAll(objectEntries(obj)) }
      classes.forEach { cls -> addAll(companionEntries(cls)) }
      valueClasses.forEach { cls -> addAll(valueClassEntries(cls)) }
    }
    val propertyPlans: List<ForwardPropertyPlan> = ForwardPropertyPlanner(classifier).catalog(
      classes, properties, extensionProperties,
    )
    return ForwardCallablePlanCatalog(entries, propertyPlans)
  }

  /**
   * Value-class constructors (ADR-035 primary/secondary numbering), non-underlying public
   * properties, and public methods. Members keep the shipped no-errorOut ABI; only constructors
   * carry an error slot. The receiver is always the *underlying* wire value (primitive/String as
   * a Value named `value`, reference as a Handle named `handle`).
   */
  private fun valueClassEntries(cls: KSClassDeclaration): List<ForwardCallableCatalogEntry> {
    val owner: String = cls.qualifiedName?.asString() ?: return emptyList()
    val prefix: String = cls.simpleName.asString().lowercase()
    val underlyingParam = cls.primaryConstructor?.parameters?.firstOrNull() ?: return emptyList()
    val underlyingPropName: String = underlyingParam.name?.asString() ?: return emptyList()
    val classifiedUnderlying: BridgeType = classifier.classify(underlyingParam.type.resolve())
    // Sealed (and other handle-shaped specialized) underlyings still cross as StableRef handles
    // on the shipped ABI — same as ObjectHandle. Map them to a Handle receiver so methods like
    // ObservationResult.describe keep working after ordinary legacy deletion.
    val underlyingType: BridgeType = if (
      classifiedUnderlying is BridgeType.SpecializedProtocol &&
      classifiedUnderlying.name.startsWith("sealed helper ")
    ) {
      BridgeType.ObjectHandle(classifiedUnderlying.name.removePrefix("sealed helper "))
    } else {
      classifiedUnderlying
    }
    val isReferenceUnderlying: Boolean = underlyingType is BridgeType.ObjectHandle

    val receiver: ForwardReceiver = if (isReferenceUnderlying) {
      ForwardReceiver.Handle(underlyingType, name = "handle")
    } else {
      ForwardReceiver.Value(underlyingType, name = "value")
    }

    return buildList {
      addAll(
        valueClassConstructorEntries(
          cls,
          owner,
          prefix,
          underlyingPropName,
          underlyingType,
          isReferenceUnderlying
        )
      )
      addAll(valueClassPropertyEntries(cls, owner, prefix, underlyingPropName, receiver))
      addAll(valueClassMethodEntries(cls, owner, prefix, receiver))
    }
  }

  private fun valueClassConstructorEntries(
    cls: KSClassDeclaration,
    owner: String,
    prefix: String,
    underlyingPropName: String,
    underlyingType: BridgeType,
    isReferenceUnderlying: Boolean,
  ): List<ForwardCallableCatalogEntry> {
    // Constructor exports return the *underlying* value (ADR-014 unwrapped bridge), not a
    // StableRef of the value class. Reference-underlying primaries are deferred (ADR-035).
    val secondaryConstructors: List<KSFunctionDeclaration> = cls.declarations
      .filterIsInstance<KSFunctionDeclaration>()
      .filter { it.simpleName.asString() == "<init>" }
      .filter { it != cls.primaryConstructor }
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .toList()

    // Reference-underlying constructors stay on the legacy path: ADR-035 defers the primary, and
    // any secondary still uses the historical export numbering. Planning them would force the
    // ObjectHandle/StableRef result shape, which is not the shipped (rare) secondary ABI.
    if (isReferenceUnderlying) return emptyList()

    val exports: List<Pair<KSFunctionDeclaration, Pair<String, String>>> = buildList {
      val primary = cls.primaryConstructor
      if (primary != null && primary.getVisibility() == Visibility.PUBLIC) {
        add(primary to ("${prefix}_create" to ""))
      }
      secondaryConstructors.forEachIndexed { index, ctor ->
        val number: Int = index + 2
        add(ctor to ("${prefix}_create_$number" to "_$number"))
      }
    }

    return exports.map { (ctor, names) ->
      val (export, suffix) = names
      planOrSkip(
        symbol = "$owner.<init>$suffix",
        publicName = "Create$suffix",
        exportName = export,
        receiver = ForwardReceiver.Static,
        parameters = ctor.parameters.map { parameter ->
          (parameter.name?.asString() ?: "_") to classifier.classify(parameter.type.resolve())
        },
        result = underlyingType,
        origin = ForwardCallableOrigin.VALUE_CLASS,
        target = owner,
        // Underlying property name used by the Kotlin emitter to unbox: `Owner(args).prop`.
        invocationReceiver = underlyingPropName,
        includeError = true,
      )
    }
  }

  private fun valueClassPropertyEntries(
    cls: KSClassDeclaration,
    owner: String,
    prefix: String,
    underlyingPropName: String,
    receiver: ForwardReceiver,
  ): List<ForwardCallableCatalogEntry> = cls.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() != underlyingPropName }
    .map { prop ->
      val name: String = prop.simpleName.asString()
      planOrSkip(
        symbol = "$owner.$name",
        publicName = name.replaceFirstChar { it.uppercase() },
        exportName = "${prefix}_get_$name",
        receiver = receiver,
        parameters = emptyList(),
        result = classifier.classify(prop.type.resolve()),
        origin = ForwardCallableOrigin.VALUE_CLASS,
        target = owner,
        includeError = false,
        // Property getter: export name contains `_get_`; emitter uses bare member access.
        valueClassProperty = true,
      )
    }
    .toList()

  private fun valueClassMethodEntries(
    cls: KSClassDeclaration,
    owner: String,
    prefix: String,
    receiver: ForwardReceiver,
  ): List<ForwardCallableCatalogEntry> {
    val excluded: Set<String> = setOf(
      "equals", "hashCode", "toString", "<init>",
      "box-impl", "unbox-impl", "constructor-impl",
      "hashCode-impl", "equals-impl", "equals-impl0", "toString-impl",
    )
    return cls.getAllFunctions()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.simpleName.asString() !in excluded }
      .map { method ->
        val name: String = method.simpleName.asString()
        val structuralReason: ForwardPlanSkipReason? = when {
          method.modifiers.contains(Modifier.SUSPEND) -> ForwardPlanSkipReason.SUSPEND
          method.typeParameters.isNotEmpty() -> ForwardPlanSkipReason.GENERIC
          else -> null
        }
        if (structuralReason != null) {
          ForwardCallableCatalogEntry.Skipped("$owner.$name", structuralReason)
        } else {
          planOrSkip(
            symbol = "$owner.$name",
            publicName = name.replaceFirstChar { it.uppercase() },
            exportName = "${prefix}_$name",
            receiver = receiver,
            parameters = method.parameters.map { parameter ->
              (parameter.name?.asString() ?: "_") to classifier.classify(parameter.type.resolve())
            },
            result = method.returnType?.resolve()?.let(classifier::classify) ?: BridgeType.Unit,
            origin = ForwardCallableOrigin.VALUE_CLASS,
            target = owner,
            includeError = false,
          )
        }
      }
      .toList()
  }

  private fun classEntries(cls: KSClassDeclaration): List<ForwardCallableCatalogEntry> {
    val className: String = cls.simpleName.asString()
    val prefix: String = className.lowercase()
    val receiverType: BridgeType = BridgeType.ObjectHandle(
      requireNotNull(cls.qualifiedName?.asString()) {
        "Forward class planner cannot create a handle for local ${className}"
      }
    )
    val methods: List<KSFunctionDeclaration> = cls.getAllFunctions()
      .filter { method -> method.getVisibility() == Visibility.PUBLIC }
      .filter { method ->
        val name: String = method.simpleName.asString()
        val isDataClassMethod: Boolean = cls.modifiers.contains(Modifier.DATA) &&
            (name == "copy" || name.startsWith("component"))
        name !in setOf("equals", "hashCode", "toString", "<init>") && !isDataClassMethod
      }
      .filter { method -> method.parentDeclaration == cls }
      .toList()
    val interfaceBridgeMethods: Set<KSFunctionDeclaration> = findInterfaceBridgePairs(methods)
      .flatMap { pair -> listOf(pair.first, pair.second) }
      .toSet()
    val storedCallbackMethods: Set<KSFunctionDeclaration> = findStoredCallbackPairs(methods)
      .flatMap { pair -> listOf(pair.first, pair.second) }
      .toSet()

    return methods.map { method ->
      val symbol: String = "${cls.qualifiedName?.asString() ?: className}.${method.simpleName.asString()}"
      val structuralReason: ForwardPlanSkipReason? = when {
        method.modifiers.contains(Modifier.ABSTRACT) -> ForwardPlanSkipReason.ABSTRACT
        method.modifiers.contains(Modifier.SUSPEND) -> ForwardPlanSkipReason.SUSPEND
        method.typeParameters.isNotEmpty() -> ForwardPlanSkipReason.GENERIC
        method in interfaceBridgeMethods || method in storedCallbackMethods -> ForwardPlanSkipReason.CALLBACK_PROTOCOL
        else -> null
      }
      if (structuralReason != null) {
        ForwardCallableCatalogEntry.Skipped(symbol, structuralReason)
      } else {
        planOrSkip(
          symbol = symbol,
          publicName = method.simpleName.asString().replaceFirstChar { it.uppercase() },
          exportName = "${prefix}_${method.simpleName.asString()}",
          receiver = ForwardReceiver.Handle(receiverType),
          parameters = method.parameters.map { parameter ->
            (parameter.name?.asString() ?: "_") to classifier.classify(parameter.type.resolve())
          },
          result = method.returnType?.resolve()?.let(classifier::classify) ?: BridgeType.Unit,
          origin = ForwardCallableOrigin.CLASS,
        )
      }
    }
  }

  private fun constructorEntries(cls: KSClassDeclaration): List<ForwardCallableCatalogEntry> {
    if (cls.modifiers.contains(Modifier.ABSTRACT)) return emptyList()
    val owner: String = cls.qualifiedName?.asString() ?: return emptyList()
    val prefix: String = cls.simpleName.asString().lowercase()
    val result = BridgeType.ObjectHandle(owner)
    val constructors: List<KSFunctionDeclaration> = cls.getConstructors()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .toList()
    val primary = cls.primaryConstructor
    return buildList {
      if (primary != null) add(constructorEntry(primary, owner, "${prefix}_create", "Create", result, ""))
      constructors
        .filter { constructor -> constructor != primary }
        .forEachIndexed { index, constructor ->
          add(
            constructorEntry(
              constructor,
              owner,
              "${prefix}_create_${index + 2}",
              "Create",
              result,
              "_${index + 2}",
            )
          )
        }
      if (cls.modifiers.contains(Modifier.DATA) && primary != null) {
        val receiver = ForwardReceiver.Handle(result)
        add(
          planOrSkip(
            symbol = "$owner.copy",
            publicName = "Copy",
            exportName = "${prefix}_copy",
            receiver = receiver,
            parameters = primary.parameters.map { parameter ->
              (parameter.name?.asString() ?: "_") to classifier.classify(parameter.type.resolve())
            },
            result = result,
            origin = ForwardCallableOrigin.COPY,
          )
        )
      }
    }
  }

  private fun constructorEntry(
    constructor: KSFunctionDeclaration,
    owner: String,
    export: String,
    publicName: String,
    result: BridgeType.ObjectHandle,
    suffix: String,
  ): ForwardCallableCatalogEntry = planOrSkip(
    symbol = "$owner.<init>$suffix",
    publicName = publicName,
    exportName = export,
    receiver = ForwardReceiver.Static,
    parameters = constructor.parameters.map { parameter ->
      (parameter.name?.asString() ?: "_") to classifier.classify(parameter.type.resolve())
    },
    result = result,
    origin = ForwardCallableOrigin.CONSTRUCTOR,
    target = owner,
  )

  private fun topLevelEntry(function: KSFunctionDeclaration): ForwardCallableCatalogEntry = staticEntry(
    function = function,
    symbol = "${function.packageName.asString()}.${function.simpleName.asString()}",
    publicName = toCName(function.simpleName.asString()).csharpIdentifier(),
    exportName = toCName(function.simpleName.asString()),
    origin = ForwardCallableOrigin.TOP_LEVEL,
    target = null,
  )

  private fun objectEntries(obj: KSClassDeclaration): List<ForwardCallableCatalogEntry> {
    val owner: String = obj.qualifiedName?.asString() ?: return emptyList()
    val prefix: String = obj.simpleName.asString().lowercase()
    return obj.getAllFunctions()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.parentDeclaration == obj }
      .filter { it.simpleName.asString() !in setOf("equals", "hashCode", "toString", "<init>") }
      .map { function ->
        val name: String = function.simpleName.asString()
        staticEntry(
          function = function,
          symbol = "$owner.$name",
          publicName = name.replaceFirstChar { it.uppercase() },
          exportName = "${prefix}_${toCName(name)}",
          origin = ForwardCallableOrigin.OBJECT,
          target = owner,
        )
      }.toList()
  }

  private fun companionEntries(cls: KSClassDeclaration): List<ForwardCallableCatalogEntry> {
    val owner: String = cls.qualifiedName?.asString() ?: return emptyList()
    val companion: KSClassDeclaration = cls.declarations.filterIsInstance<KSClassDeclaration>()
      .firstOrNull { it.isCompanionObject } ?: return emptyList()
    val prefix: String = cls.simpleName.asString().lowercase()
    return companion.getAllFunctions()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.simpleName.asString() !in setOf("equals", "hashCode", "toString", "<init>") }
      .map { function ->
        val name: String = function.simpleName.asString()
        staticEntry(
          function = function,
          symbol = "$owner.Companion.$name",
          publicName = name.replaceFirstChar { it.uppercase() },
          exportName = "${prefix}_companion_${toCName(name)}",
          origin = ForwardCallableOrigin.COMPANION,
          target = owner,
        )
      }.toList()
  }

  private fun staticEntry(
    function: KSFunctionDeclaration,
    symbol: String,
    publicName: String,
    exportName: String,
    origin: ForwardCallableOrigin,
    target: String?,
  ): ForwardCallableCatalogEntry {
    val structuralReason: ForwardPlanSkipReason? = when {
      function.modifiers.contains(Modifier.SUSPEND) -> ForwardPlanSkipReason.SUSPEND
      function.typeParameters.isNotEmpty() -> ForwardPlanSkipReason.GENERIC
      else -> null
    }
    if (structuralReason != null) return ForwardCallableCatalogEntry.Skipped(symbol, structuralReason)
    val result: BridgeType = function.returnType?.resolve()?.let(classifier::classify) ?: BridgeType.Unit
    val parameters: List<Pair<String, BridgeType>> = function.parameters.map { parameter ->
      (parameter.name?.asString() ?: "_") to classifier.classify(parameter.type.resolve())
    }
    // ADR-002 / MIGRATION: top-level nullable primitives keep the shipped two-call ABI.
    if (origin == ForwardCallableOrigin.TOP_LEVEL &&
      result is BridgeType.Nullable &&
      result.type is BridgeType.Primitive
    ) {
      return topLevelNullablePrimitivePlan(
        symbol = symbol,
        publicName = publicName,
        exportName = exportName,
        parameters = parameters,
        result = result,
      )
    }
    return planOrSkip(
      symbol = symbol,
      publicName = publicName,
      exportName = exportName,
      receiver = ForwardReceiver.Static,
      parameters = parameters,
      result = result,
      origin = origin,
      target = target,
    )
  }

  /**
   * Plans a top-level `fun f(...): Primitive?` as [ForwardEvaluation.LEGACY_TWO_CALL]:
   * `${export}_has_value` (BOOLEAN) + `${export}_value` (primitive wire), matching ADR-002.
   * Method/extension nullable primitives stay on the ADR-061 single-call `valueOut` shape.
   */
  private fun topLevelNullablePrimitivePlan(
    symbol: String,
    publicName: String,
    exportName: String,
    parameters: List<Pair<String, BridgeType>>,
    result: BridgeType.Nullable,
  ): ForwardCallableCatalogEntry {
    val primitive: BridgeType.Primitive = result.type as BridgeType.Primitive
    val ineligible: BridgeType? = parameters.map { it.second }.firstOrNull { type ->
      type.inputSkipReason() != null
    }
    if (ineligible != null) {
      return ForwardCallableCatalogEntry.Skipped(symbol, requireNotNull(ineligible.inputSkipReason()))
    }

    val error: ForwardAbiParameter = errorParameter()
    val nativeInputs: List<ForwardAbiParameter> = parameters.flatMap { (name, type) ->
      nativeInputParameters(name, type)
    }
    val presence = ForwardNativeCall(
      exportName = "${exportName}_has_value",
      result = ForwardAbiWireType.BOOLEAN,
      parameters = nativeInputs + error,
    )
    val value = ForwardNativeCall(
      exportName = "${exportName}_value",
      result = primitive.wireType(),
      parameters = nativeInputs + error,
    )
    val helpers: Set<ForwardHelperRequirement> = buildSet {
      add(ForwardHelperRequirement.STABLE_REF)
      if (parameters.any { (_, type) -> type.unwrapNullable() == BridgeType.String }) {
        add(ForwardHelperRequirement.UTF8)
      }
      if (parameters.any { (_, type) -> type.unwrapNullable() is BridgeType.Enum }) {
        add(ForwardHelperRequirement.ENUM_ORDINAL)
      }
      if (parameters.any { (_, type) -> type.unwrapNullable() is BridgeType.Collection }) {
        add(ForwardHelperRequirement.COLLECTION)
      }
    }
    val plan = ForwardCallablePlan(
      invocation = ForwardInvocation(
        symbol = symbol,
        origin = ForwardCallableOrigin.TOP_LEVEL,
        target = null,
      ),
      publicSignature = ForwardPublicSignature(
        name = publicName,
        parameters = parameters.map { (name, type) -> ForwardPublicParameter(name, type) },
        result = result,
      ),
      evaluation = ForwardEvaluation.LEGACY_TWO_CALL,
      nativeExports = listOf(presence, value),
      nativeImports = listOf(presence, value),
      result = ForwardResultConvention(
        wireType = ForwardAbiWireType.BOOLEAN,
        transfer = transfer("result", result, ForwardFlow.OUT_OF_KOTLIN),
      ),
      errorSlot = error,
      helperRequirements = helpers,
    ).validate()
    return ForwardCallableCatalogEntry.Planned(plan)
  }

  private fun extensionEntry(function: KSFunctionDeclaration): ForwardCallableCatalogEntry {
    val receiver: KSType = requireNotNull(function.extensionReceiver) {
      "Forward extension planner received a non-extension function ${function.simpleName.asString()}"
    }.resolve()
    val receiverType: BridgeType = classifier.classify(receiver)
    val functionName: String = function.simpleName.asString()
    val symbol: String = "${function.packageName.asString()}.$functionName"
    val structuralReason: ForwardPlanSkipReason? = when {
      function.modifiers.contains(Modifier.SUSPEND) -> ForwardPlanSkipReason.SUSPEND
      function.typeParameters.isNotEmpty() -> ForwardPlanSkipReason.GENERIC
      else -> null
    }
    if (structuralReason != null) return ForwardCallableCatalogEntry.Skipped(symbol, structuralReason)

    return planOrSkip(
      symbol = symbol,
      publicName = toCName(functionName).replaceFirstChar { it.uppercase() },
      exportName = "${receiver.declaration.simpleName.asString().lowercase()}_${toCName(functionName)}",
      receiver = ForwardReceiver.Value(receiverType),
      parameters = function.parameters.map { parameter ->
        (parameter.name?.asString() ?: "_") to classifier.classify(parameter.type.resolve())
      },
      result = function.returnType?.resolve()?.let(classifier::classify) ?: BridgeType.Unit,
      origin = ForwardCallableOrigin.EXTENSION,
    )
  }

  private fun planOrSkip(
    symbol: String,
    publicName: String,
    exportName: String,
    receiver: ForwardReceiver,
    parameters: List<Pair<String, BridgeType>>,
    result: BridgeType,
    origin: ForwardCallableOrigin,
    target: String? = null,
    invocationReceiver: String? = null,
    includeError: Boolean = true,
    valueClassProperty: Boolean = false,
  ): ForwardCallableCatalogEntry {
    val inputTypes: List<BridgeType> = buildList {
      when (receiver) {
        is ForwardReceiver.Value -> add(receiver.type)
        is ForwardReceiver.Handle -> add(receiver.type)
        ForwardReceiver.Static -> Unit
      }
      addAll(parameters.map { it.second })
    }
    val ineligible: BridgeType? = inputTypes
      .firstOrNull { type -> type.inputSkipReason() != null }
    if (ineligible != null) {
      return ForwardCallableCatalogEntry.Skipped(symbol, requireNotNull(ineligible.inputSkipReason()))
    }

    val resultShape: ForwardResultShape? = result.shapeOrNull()
    if (resultShape == null) {
      return ForwardCallableCatalogEntry.Skipped(symbol, requireNotNull(result.skipReason()))
    }

    val error: ForwardAbiParameter? = if (includeError) errorParameter() else null
    val nativeParameters: List<ForwardAbiParameter> = receiverParameter(receiver) +
        parameters.flatMap { (name, type) -> nativeInputParameters(name, type) } +
        resultShape.extraParameters + listOfNotNull(error)
    val nativeCall = ForwardNativeCall(
      exportName = exportName,
      result = resultShape.wireType,
      parameters = nativeParameters,
    )
    val helpers: Set<ForwardHelperRequirement> = buildSet {
      add(ForwardHelperRequirement.STABLE_REF)
      if (origin == ForwardCallableOrigin.VALUE_CLASS) add(ForwardHelperRequirement.VALUE_CLASS)
      addAll(resultShape.helperRequirements)
      if (inputTypes.any { type -> type.unwrapNullable() == BridgeType.String }) {
        add(ForwardHelperRequirement.UTF8)
      }
      if (inputTypes.any { type -> type.unwrapNullable() is BridgeType.Enum }) {
        add(ForwardHelperRequirement.ENUM_ORDINAL)
      }
      if (inputTypes.any { type -> type.unwrapNullable() is BridgeType.Collection }) {
        add(ForwardHelperRequirement.COLLECTION)
      }
    }
    val plan = ForwardCallablePlan(
      invocation = ForwardInvocation(
        symbol = symbol,
        receiver = invocationReceiver,
        origin = origin,
        target = if (valueClassProperty) "$target#property" else target,
      ),
      publicSignature = ForwardPublicSignature(
        name = publicName,
        parameters = parameters.map { (name, type) -> ForwardPublicParameter(name, type) },
        result = result,
      ),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(nativeCall),
      nativeImports = listOf(nativeCall),
      result = ForwardResultConvention(
        wireType = resultShape.wireType,
        transfer = resultShape.transfer,
      ),
      errorSlot = error,
      cleanup = resultShape.cleanup,
      helperRequirements = helpers,
    ).validate()
    return ForwardCallableCatalogEntry.Planned(plan)
  }

  private fun errorParameter(): ForwardAbiParameter = ForwardAbiParameter(
    name = "errorOut",
    wireType = ForwardAbiWireType.POINTER,
    direction = ForwardAbiDirection.OUT,
    transfer = ForwardTransfer(
      subject = "error",
      type = BridgeType.ObjectHandle("kotlin.Throwable"),
      flow = ForwardFlow.OUT_OF_KOTLIN,
      passing = ForwardPassing.OUT,
      ownership = ForwardOwnership.BORROWED,
      conversion = ForwardConversion.STABLE_REF_TO_HANDLE,
    ),
  )

  private fun valueParameter(
    name: String,
    type: BridgeType,
    flow: ForwardFlow,
  ): ForwardAbiParameter = ForwardAbiParameter(
    name = name,
    wireType = type.wireType(),
    direction = ForwardAbiDirection.IN,
    transfer = transfer(name, type, flow),
  )

  /**
   * The native ABI shape for one declared input parameter. Almost every [BridgeType] fans out to
   * exactly one native parameter; a nullable primitive is the sole exception, fanning out to two
   * *adjacent* native parameters (`${name}HasValue` then `name`) in place of the single public
   * parameter, so callers must `flatMap` over the declared parameter list rather than `map`.
   */
  private fun nativeInputParameters(name: String, type: BridgeType): List<ForwardAbiParameter> = when (type) {
    is BridgeType.Primitive, BridgeType.Char, BridgeType.String -> listOf(
      valueParameter(name, type, ForwardFlow.INTO_KOTLIN),
    )

    is BridgeType.Enum -> listOf(
      ForwardAbiParameter(
        name = name,
        wireType = ForwardAbiWireType.INT32,
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.ORDINAL_TO_ENUM,
        ),
      )
    )

    is BridgeType.ObjectHandle -> listOf(
      ForwardAbiParameter(
        name = name,
        wireType = ForwardAbiWireType.POINTER,
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF,
        ),
      )
    )

    is BridgeType.Collection -> {
      require(type.kind == CollectionKind.LIST || type.kind == CollectionKind.MUTABLE_LIST) {
        "Forward planner cannot build an input parameter for collection kind ${type.kind}"
      }
      listOf(
        ForwardAbiParameter(
          name = name,
          wireType = ForwardAbiWireType.POINTER,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_COLLECTION,
          ),
        )
      )
    }

    is BridgeType.Nullable -> when (val inner = type.type) {
      BridgeType.String -> listOf(
        ForwardAbiParameter(
          name = name,
          wireType = ForwardAbiWireType.STRING,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.STRING_TO_UTF8,
          ),
        )
      )

      is BridgeType.ObjectHandle -> listOf(
        ForwardAbiParameter(
          name = name,
          wireType = ForwardAbiWireType.POINTER,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF,
          ),
        )
      )

      is BridgeType.Primitive -> listOf(
        ForwardAbiParameter(
          name = "${name}HasValue",
          wireType = ForwardAbiWireType.BOOLEAN,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            "${name}HasValue", BridgeType.Primitive(PrimitiveKind.BOOLEAN), ForwardFlow.INTO_KOTLIN,
            ForwardPassing.VALUE, ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
          ),
        ),
        ForwardAbiParameter(
          name = name,
          wireType = inner.wireType(),
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            name, inner, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
          ),
        ),
      )

      else -> error("Forward planner cannot build an input parameter for nullable $inner")
    }

    else -> error("Forward planner cannot build an input parameter for $type")
  }

  private fun transfer(subject: String, type: BridgeType, flow: ForwardFlow): ForwardTransfer = ForwardTransfer(
    subject = subject,
    type = type,
    flow = flow,
    passing = ForwardPassing.VALUE,
    ownership = ForwardOwnership.BORROWED,
    conversion = if (type == BridgeType.String && flow == ForwardFlow.INTO_KOTLIN) {
      ForwardConversion.STRING_TO_UTF8
    } else {
      ForwardConversion.DIRECT
    },
  )

  private fun BridgeType.shapeOrNull(): ForwardResultShape? = when (this) {
    BridgeType.Unit, is BridgeType.Primitive, BridgeType.Char -> ForwardResultShape(
      wireType = wireType(),
      transfer = transfer("result", this, ForwardFlow.OUT_OF_KOTLIN),
    )

    // String results cross as a native pointer (Kotlin String / C# IntPtr + PtrToStringUTF8),
    // matching property getters and the shipped value-class method ABI.
    BridgeType.String -> ForwardResultShape(
      wireType = ForwardAbiWireType.POINTER,
      transfer = ForwardTransfer(
        subject = "result",
        type = this,
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.MATERIALIZED,
        conversion = ForwardConversion.UTF8_TO_STRING,
      ),
      helperRequirements = setOf(ForwardHelperRequirement.UTF8),
    )

    is BridgeType.Enum -> ForwardResultShape(
      wireType = ForwardAbiWireType.INT32,
      transfer = ForwardTransfer(
        subject = "result",
        type = this,
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.BORROWED,
        conversion = ForwardConversion.ENUM_TO_ORDINAL,
      ),
      helperRequirements = setOf(ForwardHelperRequirement.ENUM_ORDINAL),
    )

    is BridgeType.ObjectHandle -> handleResultShape(this)
    is BridgeType.Collection -> handleResultShape(this, ForwardHelperRequirement.COLLECTION)

    is BridgeType.Nullable -> nullableResultShape(type)
    else -> null
  }

  private fun nullableResultShape(type: BridgeType): ForwardResultShape? = when (type) {
    BridgeType.String -> ForwardResultShape(
      wireType = ForwardAbiWireType.POINTER,
      transfer = ForwardTransfer(
        subject = "result",
        type = BridgeType.Nullable(type),
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.MATERIALIZED,
        conversion = ForwardConversion.UTF8_TO_STRING,
      ),
      helperRequirements = setOf(ForwardHelperRequirement.UTF8),
    )

    is BridgeType.ObjectHandle -> handleResultShape(BridgeType.Nullable(type))
    is BridgeType.Primitive -> if (type.kind != PrimitiveKind.BOOLEAN) {
      val nullable: BridgeType = BridgeType.Nullable(type)
      ForwardResultShape(
        wireType = ForwardAbiWireType.BOOLEAN,
        transfer = transfer("result", nullable, ForwardFlow.OUT_OF_KOTLIN),
        extraParameters = listOf(
          ForwardAbiParameter(
            name = "valueOut",
            wireType = ForwardAbiWireType.POINTER,
            direction = ForwardAbiDirection.OUT,
            transfer = ForwardTransfer(
              subject = "valueOut",
              type = type,
              flow = ForwardFlow.OUT_OF_KOTLIN,
              passing = ForwardPassing.OUT,
              ownership = ForwardOwnership.BORROWED,
              conversion = ForwardConversion.DIRECT,
            ),
          )
        ),
      )
    } else {
      null
    }

    else -> null
  }

  private fun handleResultShape(
    type: BridgeType,
    helper: ForwardHelperRequirement? = null,
  ): ForwardResultShape = ForwardResultShape(
    wireType = ForwardAbiWireType.POINTER,
    transfer = ForwardTransfer(
      subject = "result",
      type = type,
      flow = ForwardFlow.OUT_OF_KOTLIN,
      passing = ForwardPassing.VALUE,
      ownership = ForwardOwnership.OWNED_HANDLE,
      conversion = when (type.unwrapNullable()) {
        is BridgeType.Collection -> ForwardConversion.COLLECTION_TO_HANDLE
        else -> ForwardConversion.STABLE_REF_TO_HANDLE
      },
    ),
    cleanup = listOf(ForwardCleanup("result", ForwardCleanupKind.DISPOSE_STABLE_REF)),
    helperRequirements = setOfNotNull(helper),
  )

  private data class ForwardResultShape(
    val wireType: ForwardAbiWireType,
    val transfer: ForwardTransfer,
    val extraParameters: List<ForwardAbiParameter> = emptyList(),
    val cleanup: List<ForwardCleanup> = emptyList(),
    val helperRequirements: Set<ForwardHelperRequirement> = emptySet(),
  )

  private sealed interface ForwardReceiver {
    val type: BridgeType?

    data class Handle(
      override val type: BridgeType,
      val name: String = "handle",
    ) : ForwardReceiver

    data class Value(
      override val type: BridgeType,
      val name: String = "receiver",
    ) : ForwardReceiver

    data object Static : ForwardReceiver {
      override val type: BridgeType? = null
    }
  }

  private fun receiverParameter(receiver: ForwardReceiver): List<ForwardAbiParameter> = when (receiver) {
    is ForwardReceiver.Handle -> listOf(
      ForwardAbiParameter(
        name = receiver.name,
        wireType = ForwardAbiWireType.POINTER,
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          subject = receiver.name,
          type = receiver.type,
          flow = ForwardFlow.INTO_KOTLIN,
          passing = ForwardPassing.VALUE,
          ownership = ForwardOwnership.BORROWED,
          conversion = ForwardConversion.HANDLE_TO_STABLE_REF,
        ),
      )
    )

    is ForwardReceiver.Value -> nativeInputParameters(receiver.name, receiver.type)
    ForwardReceiver.Static -> emptyList()
  }

  private fun BridgeType.skipReason(): ForwardPlanSkipReason? = when (this) {
    BridgeType.Unit, is BridgeType.Primitive -> null
    BridgeType.Char -> ForwardPlanSkipReason.CHAR
    BridgeType.String -> ForwardPlanSkipReason.STRING
    is BridgeType.Nullable -> ForwardPlanSkipReason.NULLABLE
    is BridgeType.Collection, is BridgeType.RawCollection -> ForwardPlanSkipReason.COLLECTION
    is BridgeType.Enum -> ForwardPlanSkipReason.ENUM
    is BridgeType.ObjectHandle -> ForwardPlanSkipReason.HANDLE
    is BridgeType.ValueClass -> ForwardPlanSkipReason.VALUE_CLASS
    is BridgeType.SpecializedProtocol -> when {
      name.startsWith("flow ") -> ForwardPlanSkipReason.FLOW_PROTOCOL
      name.startsWith("suspend lambda ") -> ForwardPlanSkipReason.SUSPEND_CALLBACK_PROTOCOL
      name.startsWith("lambda ") || name.startsWith("interface bridge ") -> ForwardPlanSkipReason.CALLBACK_PROTOCOL
      name.startsWith("sealed helper ") -> ForwardPlanSkipReason.SEALED_PROTOCOL
      name.startsWith("generic declaration ") -> ForwardPlanSkipReason.GENERIC
      else -> error("Forward planner has no explicit legacy route for specialized protocol $name")
    }

    is BridgeType.RawKSType -> error("Forward planner received raw KSP type $rendered")
    is BridgeType.Unsupported -> ForwardPlanSkipReason.UNSUPPORTED
  }

  private fun BridgeType.inputSkipReason(): ForwardPlanSkipReason? = when (this) {
    BridgeType.String, BridgeType.Char -> null
    is BridgeType.Enum -> null
    is BridgeType.ObjectHandle -> null
    is BridgeType.Collection -> if (kind == CollectionKind.LIST || kind == CollectionKind.MUTABLE_LIST) {
      null
    } else {
      ForwardPlanSkipReason.COLLECTION
    }

    is BridgeType.Nullable -> when (type) {
      BridgeType.String, is BridgeType.ObjectHandle, is BridgeType.Primitive -> null
      else -> ForwardPlanSkipReason.NULLABLE
    }

    else -> skipReason()
  }

  private fun BridgeType.wireType(): ForwardAbiWireType = when (this) {
    BridgeType.Unit -> ForwardAbiWireType.VOID
    is BridgeType.Primitive -> when (kind) {
      PrimitiveKind.BOOLEAN -> ForwardAbiWireType.BOOLEAN
      PrimitiveKind.BYTE -> ForwardAbiWireType.INT8
      PrimitiveKind.UBYTE -> ForwardAbiWireType.UINT8
      PrimitiveKind.SHORT -> ForwardAbiWireType.INT16
      PrimitiveKind.USHORT -> ForwardAbiWireType.UINT16
      PrimitiveKind.INT -> ForwardAbiWireType.INT32
      PrimitiveKind.UINT -> ForwardAbiWireType.UINT32
      PrimitiveKind.LONG -> ForwardAbiWireType.INT64
      PrimitiveKind.ULONG -> ForwardAbiWireType.UINT64
      PrimitiveKind.FLOAT -> ForwardAbiWireType.FLOAT32
      PrimitiveKind.DOUBLE -> ForwardAbiWireType.FLOAT64
    }

    BridgeType.String -> ForwardAbiWireType.STRING
    BridgeType.Char -> ForwardAbiWireType.CHAR16
    is BridgeType.Nullable,
    is BridgeType.Collection,
    is BridgeType.RawCollection,
    is BridgeType.Enum,
    is BridgeType.ObjectHandle,
    is BridgeType.ValueClass,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
      -> error("Forward planner requested a wire type for ineligible $this")
  }

  private fun BridgeType.unwrapNullable(): BridgeType = if (this is BridgeType.Nullable) type else this

  private fun String.csharpIdentifier(): String = if (this in CSHARP_KEYWORDS) "@$this" else this

  private companion object {
    val CSHARP_KEYWORDS: Set<String> = setOf(
      "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked",
      "class", "const", "continue", "decimal", "default", "delegate", "do", "double", "else",
      "enum", "event", "explicit", "extern", "false", "finally", "fixed", "float", "for",
      "foreach", "goto", "if", "implicit", "in", "int", "interface", "internal", "is", "lock",
      "long", "namespace", "new", "null", "object", "operator", "out", "override", "params",
      "private", "protected", "public", "readonly", "ref", "return", "sbyte", "sealed", "short",
      "sizeof", "stackalloc", "static", "string", "struct", "switch", "this", "throw", "true",
      "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort", "using", "virtual",
      "void", "volatile", "while",
    )
  }
}
