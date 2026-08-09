package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.AbiArg
import io.github.xxfast.kotlin.native.nuget.rir.KotlinBridgePlan
import io.github.xxfast.kotlin.native.nuget.rir.NUGET_RUNTIME_CONTRACT_HASH
import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirConstructor
import io.github.xxfast.kotlin.native.nuget.rir.RirDiagnostic
import io.github.xxfast.kotlin.native.nuget.rir.RirDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.rir.RirEnum
import io.github.xxfast.kotlin.native.nuget.rir.RirEnumType
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirGenericInstanceType
import io.github.xxfast.kotlin.native.nuget.rir.RirInstantiation
import io.github.xxfast.kotlin.native.nuget.rir.RirInterface
import io.github.xxfast.kotlin.native.nuget.rir.RirInterfaceType
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirObjectHandleType
import io.github.xxfast.kotlin.native.nuget.rir.RirParameter
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirProperty
import io.github.xxfast.kotlin.native.nuget.rir.RirRegistrable
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirStruct
import io.github.xxfast.kotlin.native.nuget.rir.RirStructShape
import io.github.xxfast.kotlin.native.nuget.rir.RirStructType
import io.github.xxfast.kotlin.native.nuget.rir.RirTypeKey
import io.github.xxfast.kotlin.native.nuget.rir.RirTypeParameterType
import io.github.xxfast.kotlin.native.nuget.rir.RirTypeRef
import io.github.xxfast.kotlin.native.nuget.rir.RirVoidType
import io.github.xxfast.kotlin.native.nuget.rir.abiArgs
import io.github.xxfast.kotlin.native.nuget.rir.abiOutArgs
import io.github.xxfast.kotlin.native.nuget.rir.abiReturnType
import io.github.xxfast.kotlin.native.nuget.rir.arityLimitDiagnostics
import io.github.xxfast.kotlin.native.nuget.rir.boundGenericClassDefinitions
import io.github.xxfast.kotlin.native.nuget.rir.boundHandleTypes
import io.github.xxfast.kotlin.native.nuget.rir.boundInterfaceTypes
import io.github.xxfast.kotlin.native.nuget.rir.boundStructTypes
import io.github.xxfast.kotlin.native.nuget.rir.bridgeableInterfaceRegistrables
import io.github.xxfast.kotlin.native.nuget.rir.bridgeableRegistrables
import io.github.xxfast.kotlin.native.nuget.rir.bridgeId
import io.github.xxfast.kotlin.native.nuget.rir.bridgeIds
import io.github.xxfast.kotlin.native.nuget.rir.bridgeableStructRegistrables
import io.github.xxfast.kotlin.native.nuget.rir.bridgeSuffix
import io.github.xxfast.kotlin.native.nuget.rir.classInterfaceSupertypes
import io.github.xxfast.kotlin.native.nuget.rir.collisionDiagnostics
import io.github.xxfast.kotlin.native.nuget.rir.contractHash
import io.github.xxfast.kotlin.native.nuget.rir.fnv1a64
import io.github.xxfast.kotlin.native.nuget.rir.interfaceBaseKeys
import io.github.xxfast.kotlin.native.nuget.rir.isNullable
import io.github.xxfast.kotlin.native.nuget.rir.isHandleBacked
import io.github.xxfast.kotlin.native.nuget.rir.kotlinBridgeContractHash
import io.github.xxfast.kotlin.native.nuget.rir.kotlinBridgePlan
import io.github.xxfast.kotlin.native.nuget.rir.parseInterfaceRef
import io.github.xxfast.kotlin.native.nuget.rir.parseReverseIr
import io.github.xxfast.kotlin.native.nuget.rir.registrationExportName
import io.github.xxfast.kotlin.native.nuget.rir.structArityLimitDiagnostics
import io.github.xxfast.kotlin.native.nuget.rir.structContractHash
import io.github.xxfast.kotlin.native.nuget.rir.structReceiverAbiArgs
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

private const val INTERNAL_PKG = "io.github.xxfast.kotlin.native.nuget.internal"
private const val INTERNAL_DIR = "io/github/xxfast/kotlin/native/nuget/internal"


data class GeneratedFile(
  val relativePath: String,
  val content: String,
)

// The Kotlin package a RIR namespace maps to. Single source of truth for the resolution order
// (namespace alias, then per-package override, then the sanitised packageId) so the enum-package
// pre-pass below and the per-namespace loop in generateKotlinStubs can never disagree about where
// a generated type lives. A disagreement would emit an import pointing at a package that has no
// such class.
private fun kotlinPackage(
  packageId: String,
  namespaceName: String,
  packageNameOverrides: Map<String, String>,
  namespaceAliases: Map<String, Map<String, String>>,
): String = namespaceAliases[packageId]?.get(namespaceName)
  ?: packageNameOverrides[packageId]
  ?: packageId.lowercase().replace('-', '_')

// Every enum declared anywhere in the RirFile, mapped to the Kotlin package its generated
// `enum class` is emitted into. Derived once up front for the whole file (the same anti-drift
// pattern as boundHandleTypes) because an enum is routinely referenced from a namespace other than
// the one that declares it: the referencing class's stub needs `import <pkg>.<EnumName>`, which is
// only knowable after the whole file has been seen.
private fun enumPackages(
  file: RirFile,
  packageNameOverrides: Map<String, String>,
  namespaceAliases: Map<String, Map<String, String>>,
): Map<RirTypeKey, String> = file.assemblies.flatMap { assembly ->
  assembly.namespaces.flatMap { namespace ->
    namespace.types.filterIsInstance<RirEnum>().map { enum ->
      RirTypeKey(namespace.name, enum.name) to kotlinPackage(
        assembly.packageId, namespace.name, packageNameOverrides, namespaceAliases,
      )
    }
  }
}.toMap()

// ADR-059: the struct equivalent of enumPackages above — every struct declared anywhere in the
// RirFile, mapped to the Kotlin package its generated `data class` is emitted into. A struct's OWN
// component can itself be a struct declared in a different Kotlin package (e.g. Nursery in
// test.nested nesting Litter from test.structs), and that reference needs a real
// `import <pkg>.<StructName>` line — see structFileContent's structImports call — exactly as an
// enum component reference already does via enumPackages/enumImports.
private fun structPackages(
  file: RirFile,
  packageNameOverrides: Map<String, String>,
  namespaceAliases: Map<String, Map<String, String>>,
): Map<RirTypeKey, String> = file.assemblies.flatMap { assembly ->
  assembly.namespaces.flatMap { namespace ->
    namespace.types.filterIsInstance<RirStruct>().map { struct ->
      RirTypeKey(namespace.name, struct.name) to kotlinPackage(
        assembly.packageId, namespace.name, packageNameOverrides, namespaceAliases,
      )
    }
  }
}.toMap()

// ADR-070: the interface equivalent of enumPackages/structPackages above — every admissible,
// bound interface declared anywhere in the RirFile, mapped to the Kotlin package its generated
// `interface`/`{Name}Handle` pair is emitted into.
private fun interfacePackages(
  file: RirFile,
  packageNameOverrides: Map<String, String>,
  namespaceAliases: Map<String, Map<String, String>>,
): Map<RirTypeKey, String> = file.assemblies.flatMap { assembly ->
  assembly.namespaces.flatMap { namespace ->
    namespace.types.filterIsInstance<RirInterface>().map { iface ->
      RirTypeKey(namespace.name, iface.name) to kotlinPackage(
        assembly.packageId, namespace.name, packageNameOverrides, namespaceAliases,
      )
    }
  }
}.toMap()

// ADR-072: the RirObjectHandleType equivalent of enumPackages/structPackages/interfacePackages
// above: every bound (non-static) RirClass declared anywhere in the RirFile, mapped to the
// Kotlin package its generated wrapper class is emitted into. This was missing before this
// feature: a bound class used as a PARAMETER or RETURN type in a different Kotlin package than
// its own declaration (e.g. `Boxes.OfFerret(Ferret ferret)` in `test.boxes` referencing `Ferret`
// in `test.menagerie`) rendered with no `import` line at all: a latent gap, never exercised by
// any fixture before `Box<Ferret>` forced the first cross-package bound-class-handle parameter.
private fun handlePackages(
  file: RirFile,
  packageNameOverrides: Map<String, String>,
  namespaceAliases: Map<String, Map<String, String>>,
): Map<RirTypeKey, String> = file.assemblies.flatMap { assembly ->
  assembly.namespaces.flatMap { namespace ->
    namespace.types.filterIsInstance<RirClass>().filter { !it.isStatic }.map { cls ->
      RirTypeKey(namespace.name, cls.name) to kotlinPackage(
        assembly.packageId, namespace.name, packageNameOverrides, namespaceAliases,
      )
    }
  }
}.toMap()

// ADR-056/059: does this type reference — either directly, or (if it is a struct) through one of
// its OWN components, AT ANY NESTING DEPTH — satisfy [predicate]? Every "does this file need X"
// detector below (needsInterop, needsEnums, hasStringReturn, hasStringParam, hasEnumReturn, ...)
// used to test only the top-level RirTypeRef, which made a struct's component types invisible to
// them: a method returning only a struct with a string component, and no directly-string-typed
// member anywhere else in the file, would silently skip emitting NugetInterop.kt
// (freeManagedString) and fail to compile. ADR-059 lifts the former one-level restriction — a
// struct-typed component may itself be a struct — by recursing through typeContains itself instead
// of testing only the immediate component list.
private fun typeContains(
  type: RirTypeRef,
  structs: Map<RirTypeKey, RirStruct>,
  predicate: (RirTypeRef) -> Boolean,
): Boolean {
  if (predicate(type)) return true
  val struct: RirStruct? =
    (type as? RirStructType)?.let { structs[RirTypeKey(it.namespace, it.name)] }
  return struct?.components.orEmpty().any { typeContains(it.type, structs, predicate) }
}

private fun isStringRef(type: RirTypeRef): Boolean = type is RirStringType
private fun isEnumRef(type: RirTypeRef): Boolean = type is RirEnumType

// ADR-070: a handle-typed reference and an interface-typed reference (RirInterfaceType) both cross
// the ABI as the same COpaquePointer? slot and both force NugetRuntime.kt (NugetObjectHandle/
// Cleaner) to be emitted — shared so the "does this member need a handle" checks below don't
// duplicate the two-branch `is` check at each call site.
private fun isHandleLike(type: RirTypeRef): Boolean =
  type is RirObjectHandleType || type is RirInterfaceType

// ADR-056/059: every enum a struct's OWN components reference, AT ANY NESTING DEPTH — used to
// widen the `import <pkg>.<Enum>` resolution (enumImports/referencedEnumTypes) beyond top-level
// method/ctor/property types, since a struct-typed member's enum components are otherwise
// invisible to that resolution. Recurses into a struct-typed component's own components so an enum
// reached only through a nested struct (e.g. Litter -> Mother: Profile -> Mood: CatMood) is found
// too.
private fun structEnumComponents(
  type: RirTypeRef,
  structs: Map<RirTypeKey, RirStruct>,
): List<RirEnumType> {
  val struct: RirStruct? =
    (type as? RirStructType)?.let { structs[RirTypeKey(it.namespace, it.name)] }
  return struct?.components.orEmpty().flatMap { c ->
    listOfNotNull(c.type as? RirEnumType) + structEnumComponents(c.type, structs)
  }
}

fun generateKotlinStubs(
  file: RirFile,
  packageNameOverrides: Map<String, String> = emptyMap(),
  namespaceAliases: Map<String, Map<String, String>> = emptyMap(),
): List<GeneratedFile> {
  validateDiagnostics(file)
  validateKotlinSignatures(file)
  validateGenericArityCollisions(file)
  val result: MutableList<GeneratedFile> = mutableListOf()
  var needsInterop = false
  var needsRuntime = false
  var needsEnums = false

  // ADR-054: every "{Namespace}.{Type}" this build emits a register export for, in the order
  // encountered — baked into the generated NugetRegistry.kt's `expected` list so the "N of M
  // registrations fired" message can name exactly what did/did not land. "<runtime>" (if needed)
  // is prepended after the loop, once whether-any-class-needs-it is known.
  val expectedRegistrations: MutableList<String> = mutableListOf()

  // ADR-085: one arm per interface a Kotlin class can implement and hand back to C#. Drives the
  // single generated nugetMintBridge dispatcher.
  val bridgeDispatch: MutableList<BridgeDispatchArm> = mutableListOf()

  // ADR-051: derive once for the whole file — both generators must use the same helper
  // (anti-drift contract, ADR-049 Alternative 10 extended by ADR-051).
  val boundTypes: Set<RirTypeKey> = boundHandleTypes(file)
  // ADR-070: derived once for the whole file, same anti-drift pattern.
  val boundIfaces: Map<RirTypeKey, RirInterface> = boundInterfaceTypes(file)
  // ADR-072: derived once for the whole file, same anti-drift pattern. An ordinary (non-generic)
  // member referencing a closed generic instantiation is bridgeable iff its definition resolves
  // here AND the exact instantiation was discovered by the reader's Decision 2 pass.
  val genericDefs: Map<RirTypeKey, RirClass> = boundGenericClassDefinitions(file)
  val enumPkgs: Map<RirTypeKey, String> =
    enumPackages(file, packageNameOverrides, namespaceAliases)
  // ADR-059: derived once for the whole file, same anti-drift pattern as enumPkgs — a struct's own
  // struct-typed component can be declared in a different Kotlin package (see structImports).
  val structPkgs: Map<RirTypeKey, String> =
    structPackages(file, packageNameOverrides, namespaceAliases)
  // ADR-070: derived once for the whole file, same anti-drift pattern as structPkgs.
  val interfacePkgs: Map<RirTypeKey, String> =
    interfacePackages(file, packageNameOverrides, namespaceAliases)
  // ADR-072: derived once for the whole file, same anti-drift pattern as enumPkgs/structPkgs. A
  // bound-class-handle-typed reference (an ordinary class parameter/return, or a generic
  // instantiation's own type argument) needs an `import <pkg>.<ClassName>` line whenever it is
  // declared in a different Kotlin package than the referencing file.
  val handlePkgs: Map<RirTypeKey, String> =
    handlePackages(file, packageNameOverrides, namespaceAliases)
  // ADR-056: derived once for the whole file, same anti-drift pattern — both this task and
  // NugetGenerateShimsTask resolve a RirStructType reference's component list through this map.
  val structs: Map<RirTypeKey, RirStruct> = boundStructTypes(file)
  val qualifiedTypeNames: Map<RirTypeKey, String> = qualifiedTypeNames(
    file, packageNameOverrides, namespaceAliases,
  )

  file.assemblies.forEach { assembly ->
    assembly.namespaces.forEach { namespace ->
      val kotlinPkg: String = kotlinPackage(
        assembly.packageId, namespace.name, packageNameOverrides, namespaceAliases,
      )
      val pkgPath: String = kotlinPkg.replace('.', '/')

      namespace.types.filterIsInstance<RirEnum>().forEach { enum ->
        result.add(
          GeneratedFile(
            relativePath = "nativeMain/$pkgPath/${enum.name}.kt",
            content = enumFileContent(kotlinPkg, enum, assembly.packageId),
          )
        )
      }

      // ADR-056 + deferred members: a struct always emits a data class; Bindings/register only
      // when alternate ctors or bridgeable methods/computed props claim slots.
      namespace.types.filterIsInstance<RirStruct>().forEach { struct ->
        val registrables: List<RirRegistrable> =
          bridgeableStructRegistrables(struct, boundTypes, structs)
        val constructors: List<RirConstructor> =
          registrables.filterIsInstance<RirRegistrable.Ctor>().map { it.ctor }
        val staticMethods: List<RirMethod> = registrables
          .filterIsInstance<RirRegistrable.Method>()
          .map { it.method }
          .filter { it.isStatic }
        val instanceMethods: List<RirMethod> = registrables
          .filterIsInstance<RirRegistrable.Method>()
          .map { it.method }
          .filter { !it.isStatic }
        val computedGetters: List<RirProperty> =
          registrables.filterIsInstance<RirRegistrable.PropertyGetter>().map { it.property }

        result.add(
          GeneratedFile(
            relativePath = "nativeMain/$pkgPath/${struct.name}.kt",
            content = structFileContent(
              kotlinPkg, struct, assembly.packageId, enumPkgs, structPkgs, constructors,
              instanceMethods, staticMethods, computedGetters, namespace.name,
              structs, qualifiedTypeNames,
            ),
          )
        )
        if (registrables.isNotEmpty()) {
          val exportName: String = registrationExportName(namespace.name, struct.name)
          expectedRegistrations.add("${namespace.name}.${struct.name}")
          result.add(
            GeneratedFile(
              relativePath = "nativeMain/$pkgPath/${struct.name}Bindings.kt",
              content = structBindingsFileContent(
                kotlinPkg, namespace.name, struct, registrables, exportName,
                assembly.packageId, structs,
              ),
            )
          )
        }
      }

      // ADR-072 Decision 3: a generic class definition (typeParameters.isNotEmpty()) is routed to
      // the dedicated generic-class witness path BEFORE the ordinary class path below, never
      // both, and never falling through to the ordinary path (whose isV1Type gate would silently
      // emit nothing for it).
      namespace.types.filterIsInstance<RirClass>()
        .filter { it.typeParameters.isNotEmpty() }
        .forEach { cls ->
          val genericFiles: List<GeneratedFile> = genericClassFiles(
            cls, kotlinPkg, namespace.name, assembly.packageId, enumPkgs, handlePkgs,
          )
          if (genericFiles.isNotEmpty()) {
            result.addAll(genericFiles)
            needsRuntime = true
            if (genericClassNeedsInterop(cls)) needsInterop = true
            if (genericClassNeedsEnums(cls)) needsEnums = true
            cls.instantiations.forEach { inst ->
              // Decision 10: the DISPLAYED qualified name strips the CLR arity suffix: never
              // leak `Box`1` into generated source (NugetRegistry.kt's `expected` list literal).
              expectedRegistrations.add(
                "${namespace.name}.${cls.name.substringBefore('`')}" +
                    "[${canonicalInstantiationSignature(inst)}]",
              )
            }
          }
        }

      namespace.types.filterIsInstance<RirClass>().filterNot { it.typeParameters.isNotEmpty() }
        .forEach { cls ->
          // ADR-052/Phase 9 line 151 "shared bridgeable ordering": ONE ordered list, constructor
          // (if any) first, then bridgeable static methods, then bridgeable instance methods, then
          // per-property getter/[setter] pairs. That list is the single source of truth both this
          // task and NugetGenerateShimsTask derive their registration-order-sensitive output from.
          // Member-name collisions with the ADR-051 wrapper (handle/close/cleaner) are already
          // excluded here.
          val registrables: List<RirRegistrable> =
            bridgeableRegistrables(cls, boundTypes, structs, boundIfaces, genericDefs)
          val ctors: List<RirConstructor> =
            registrables.filterIsInstance<RirRegistrable.Ctor>().map { it.ctor }
          val staticMethods: List<RirMethod> = registrables
            .filterIsInstance<RirRegistrable.Method>()
            .map { it.method }.filter { it.isStatic }
          val instanceMethods: List<RirMethod> = registrables
            .filterIsInstance<RirRegistrable.Method>()
            .map { it.method }.filter { !it.isStatic }
          val propertyGetters: List<RirProperty> =
            registrables.filterIsInstance<RirRegistrable.PropertyGetter>().map { it.property }
          val instancePropertyGetters: List<RirProperty> = propertyGetters.filterNot { it.isStatic }
          val staticPropertyGetters: List<RirProperty> = propertyGetters.filter { it.isStatic }
          val propertySetterNames: Set<String> = registrables
            .filterIsInstance<RirRegistrable.PropertySetter>()
            .map { it.property.name }
            .toSet()

          if (registrables.isEmpty()) return@forEach

          val allMethods: List<RirMethod> = staticMethods + instanceMethods
          val methodsHaveString: Boolean = allMethods.any { method ->
            typeContains(method.returnType, structs, ::isStringRef) ||
                method.parameters.any { p -> typeContains(p.type, structs, ::isStringRef) }
          }
          val ctorsHaveString: Boolean = ctors.any { ctor ->
            ctor.parameters.any { p -> typeContains(p.type, structs, ::isStringRef) }
          }
          val propertiesHaveString: Boolean =
            propertyGetters.any { typeContains(it.type, structs, ::isStringRef) }
          val hasString: Boolean = methodsHaveString || ctorsHaveString || propertiesHaveString
          if (hasString) needsInterop = true

          // ADR-051/052/Phase 9 line 151: NugetRuntime.kt is needed whenever any bridgeable
          // signature contains a handle type, the class has a public instance constructor (a
          // constructor's return is implicitly the class's own handle type), or the class has any
          // instance method/property at all (both require the receiver `handle` field regardless of
          // whether a handle TYPE appears in any individual signature). Emitted once (below),
          // regardless of how many classes trigger it.
          // ADR-070: an interface-typed member also forces NugetRuntime.kt: an interface value
          // wraps in `{Name}Handle`, which needs the same NugetObjectHandle/Cleaner machinery.
          val methodsHaveHandle: Boolean = allMethods.any { method ->
            isHandleLike(method.returnType) || method.parameters.any { p -> isHandleLike(p.type) }
          }
          val hasInstanceMember: Boolean = ctors.isNotEmpty() ||
              instanceMethods.isNotEmpty() || instancePropertyGetters.isNotEmpty()
          val instancePropertiesHaveHandle: Boolean =
            instancePropertyGetters.any { isHandleLike(it.type) }
          val hasHandle: Boolean =
            methodsHaveHandle || hasInstanceMember || instancePropertiesHaveHandle
          if (hasHandle) needsRuntime = true

          // NugetEnums.kt is needed whenever a bridgeable member RECEIVES an enum from C# (a method
          // return or a property value): that is the only direction where the ordinal can be out of
          // range, since a C# enum is not a closed set ((CatMood)99 is a legal C# value). Enum
          // arguments travel the other way as Kotlin's own `.ordinal` and are always in range.
          val methodsHaveEnumReturn: Boolean =
            allMethods.any { typeContains(it.returnType, structs, ::isEnumRef) }
          val propertiesHaveEnumReturn: Boolean =
            propertyGetters.any { typeContains(it.type, structs, ::isEnumRef) }
          val hasEnumReturn: Boolean = methodsHaveEnumReturn || propertiesHaveEnumReturn
          if (hasEnumReturn) needsEnums = true

          val exportName: String = registrationExportName(namespace.name, cls.name)
          expectedRegistrations.add("${namespace.name}.${cls.name}")

          result.add(
            GeneratedFile(
              relativePath = "nativeMain/$pkgPath/${cls.name}Bindings.kt",
              content = bindingsFileContent(
                kotlinPkg, cls, registrables, exportName, assembly.packageId, namespace.name,
                structs,
              ),
            )
          )
          // ADR-070 Decision 5: the interface supertypes this class declares (already filtered to
          // "maximal": a redundant base of another declared supertype is dropped), plus every
          // Kotlin member name (method/property) those supertypes' EFFECTIVE (own + inherited)
          // members require an `override` modifier for.
          val supertypeKeys: List<RirTypeKey> = classInterfaceSupertypes(cls, boundIfaces)
          val supertypeNames: List<String> = supertypeKeys.map { it.name }
          val effectiveSupertypeMembers: List<OwnedInterfaceMember> = supertypeKeys.flatMap { key ->
            effectiveInterfaceRegistrables(boundIfaces.getValue(key), boundTypes, boundIfaces)
          }
          val overrideMethodNames: Set<String> = effectiveSupertypeMembers
            .mapNotNull {
              (it.registrable as? RirRegistrable.Method)?.method?.name?.toMethodCamelCase()
            }
            .toSet()
          val overridePropertyNames: Set<String> = effectiveSupertypeMembers
            .mapNotNull {
              (it.registrable as? RirRegistrable.PropertyGetter)
                ?.property?.name?.toMethodCamelCase()
            }
            .toSet()

          result.add(
            GeneratedFile(
              relativePath = "nativeMain/$pkgPath/${cls.name}.kt",
              content = stubFileContent(
                kotlinPkg, cls, staticMethods, instanceMethods, ctors,
                instancePropertyGetters, staticPropertyGetters, propertySetterNames,
                assembly.packageId, namespace.name, enumPkgs, structPkgs, handlePkgs, structs,
                qualifiedTypeNames, supertypeNames, overrideMethodNames, overridePropertyNames,
                genericDefs,
              ),
            )
          )
        }

      // ADR-070: an admissible, bound interface always emits its pure `interface` + `{Name}Handle`
      // wrapper + `{Name}Bindings.kt` registration trio — mirrors the struct/class loops above.
      namespace.types.filterIsInstance<RirInterface>().forEach { iface ->
        val registrables: List<RirRegistrable> =
          bridgeableInterfaceRegistrables(iface, boundTypes, boundIfaces)
        if (registrables.isEmpty()) return@forEach

        val methods: List<RirMethod> =
          registrables.filterIsInstance<RirRegistrable.Method>().map { it.method }
        val propertyGetters: List<RirProperty> =
          registrables.filterIsInstance<RirRegistrable.PropertyGetter>().map { it.property }
        val propertySetterNames: Set<String> = registrables
          .filterIsInstance<RirRegistrable.PropertySetter>()
          .map { it.property.name }
          .toSet()

        val hasString: Boolean = methods.any { m ->
          m.returnType is RirStringType || m.parameters.any { it.type is RirStringType }
        } || propertyGetters.any { it.type is RirStringType }
        if (hasString) needsInterop = true
        val hasEnumReturn: Boolean = methods.any { it.returnType is RirEnumType } ||
            propertyGetters.any { it.type is RirEnumType }
        if (hasEnumReturn) needsEnums = true
        // ADR-070: an interface's own `{Name}Handle` wrapper always needs NugetObjectHandle/Cleaner
        // (Decision 2's Xamarin-Invoker shape), regardless of its members' own types.
        needsRuntime = true

        val exportName: String = registrationExportName(namespace.name, iface.name)
        expectedRegistrations.add("${namespace.name}.${iface.name}")
        // ADR-085: a plannable interface contributes one `is <Interface> ->` arm to the shared
        // nugetMintBridge dispatcher emitted below.
        kotlinBridgePlan(iface, boundTypes, boundIfaces)?.let {
          bridgeDispatch.add(
            BridgeDispatchArm(kotlinPkg, it.iface.name, "${namespace.name}.${iface.name}"),
          )
        }

        result.add(
          GeneratedFile(
            relativePath = "nativeMain/$pkgPath/${iface.name}.kt",
            content = interfaceFileContent(
              kotlinPkg, iface, boundIfaces, assembly.packageId, enumPkgs,
            ),
          )
        )
        result.add(
          GeneratedFile(
            relativePath = "nativeMain/$pkgPath/${iface.name}Handle.kt",
            content = interfaceHandleFileContent(
              kotlinPkg, iface, boundIfaces, boundTypes, assembly.packageId, namespace.name,
              enumPkgs, interfacePkgs, qualifiedTypeNames,
            ),
          )
        )
        result.add(
          GeneratedFile(
            relativePath = "nativeMain/$pkgPath/${iface.name}Bindings.kt",
            content = interfaceBindingsFileContent(
              kotlinPkg, namespace.name, iface, registrables, exportName, assembly.packageId,
              kotlinBridgePlan(iface, boundTypes, boundIfaces), enumPkgs,
              handlePkgs + interfacePkgs,
            ),
          )
        )
      }
    }
  }

  if (needsInterop) {
    result.add(
      GeneratedFile(
        relativePath = "nativeMain/$INTERNAL_DIR/NugetInterop.kt",
        content = nugetInteropExpect(),
      )
    )
    result.add(
      GeneratedFile(
        relativePath = "mingwMain/$INTERNAL_DIR/NugetInterop.kt",
        content = nugetInteropMingw(),
      )
    )
    result.add(
      GeneratedFile(
        relativePath = "posixMain/$INTERNAL_DIR/NugetInterop.kt",
        content = nugetInteropPosix(),
      )
    )
  }

  if (needsRuntime) {
    result.add(
      GeneratedFile(
        relativePath = "nativeMain/$INTERNAL_DIR/NugetRuntime.kt",
        content = nugetRuntimeContent(),
      )
    )
    // ADR-085: the ONE dispatcher nugetHandle()'s fallback calls. Emitted alongside NugetRuntime.kt
    // (which declares nugetHandle) even when nothing is plannable, so nugetHandle always resolves.
    result.add(
      GeneratedFile(
        relativePath = "nativeMain/$INTERNAL_DIR/NugetKotlinBridges.kt",
        content = nugetKotlinBridgesContent(bridgeDispatch),
      )
    )
  }

  // ADR-054: NugetRegistry.kt is needed whenever any register export (a bound type's, or
  // nuget_runtime_register's) is emitted — i.e. exactly whenever there is something to expect a
  // registration from. "<runtime>" (if needed) is listed first, matching the ADR's illustrative
  // NugetRegistry.kt example.
  val expected: List<String> =
    (if (needsRuntime) listOf("<runtime>") else emptyList()) + expectedRegistrations
  if (expected.isNotEmpty()) {
    result.add(
      GeneratedFile(
        relativePath = "nativeMain/$INTERNAL_DIR/NugetRegistry.kt",
        content = nugetRegistryContent(expected),
      )
    )
    // ADR-054: NugetTrace.kt is needed exactly whenever NugetRegistry.kt is — the registry's
    // record(...) is the only Kotlin-side call site for nugetTrace(...). Shared code (no
    // expect/actual split): the walking skeleton verified platform.posix.stderr/fopen/fputs/
    // fclose/getenv all bind and link on mingwX64 (this project's only non-POSIX target).
    result.add(
      GeneratedFile(
        relativePath = "nativeMain/$INTERNAL_DIR/NugetTrace.kt",
        content = nugetTraceContent(),
      )
    )
  }

  if (needsEnums) {
    result.add(
      GeneratedFile(
        relativePath = "nativeMain/$INTERNAL_DIR/NugetEnums.kt",
        content = nugetEnumsContent(),
      )
    )
  }

  return result
}

// ============================================================================================
// ADR-072: closed constructed generics. Decision 1 (Kotlin shape), Decision 4 (per-instantiation
// slot accounting), Decision 5 (fake-constructor ambiguity), Decision 10 (names / the `Box`1` leak
// fix). A generic class definition never falls through to the ordinary (non-generic) class path
// above (see generateKotlinStubs' routing), so everything below is self-contained rather than
// threading typeParameters/instantiations through the shared bridgeableRegistrables machinery.
// ============================================================================================

// ADR-072 Decision 3: substitutes [args] (positionally matching the declaring class's own
// typeParameters) into [type], recursively: a bare type parameter resolves directly; a generic
// instantiation (including a self-referencing one, e.g. `Box<T>.Rewrap(): Box<T>`) substitutes
// through its own typeArguments; every other type is unaffected (Decision 6: no other shape can
// contain an unresolved type parameter).
internal fun substituteGenericType(
  type: RirTypeRef,
  args: List<RirTypeRef>,
): RirTypeRef = when (type) {
  is RirTypeParameterType -> args[type.index]
  is RirGenericInstanceType -> type.copy(
    typeArguments = type.typeArguments.map { substituteGenericType(it, args) },
  )

  else -> type
}

// ADR-072 Decision 1: the Kotlin-facing type for a (possibly unsubstituted) generic-aware type
// reference: a bare type parameter renders as its own name ("T"), a generic instantiation strips
// the CLR arity suffix and renders its OWN type arguments recursively ("Box<Int>"), and every
// other leaf falls back to the ordinary declKotlinType rendering.
private fun genericAwareKotlinType(type: RirTypeRef): String = when (type) {
  is RirTypeParameterType -> type.name
  is RirGenericInstanceType -> {
    val base: String = type.name.substringBefore('`')
    val args: String = type.typeArguments.joinToString(", ") { genericAwareKotlinType(it) }
    "$base<$args>" + if (type.nullable) "?" else ""
  }

  else -> declKotlinType(type)
}

// ADR-072 Decision 10: the internal instantiation tag for ONE type argument, the Kotlin simple
// type name, "Nullable"-prefixed for a nullable reference argument. Anything outside Decision 6's
// v1 vocabulary fails fast (the reader is responsible for never emitting such an instantiation as
// "discovered" in the first place; reaching here means that contract was violated).
internal fun instantiationArgTag(type: RirTypeRef): String = when (type) {
  is RirPrimitiveType -> kotlinType(type)
  is RirStringType -> (if (type.nullable) "Nullable" else "") + "String"
  is RirObjectHandleType -> (if (type.nullable) "Nullable" else "") + type.name
  is RirEnumType -> type.name
  is RirInterfaceType -> (if (type.nullable) "Nullable" else "") + type.name
  is RirVoidType -> error("[nuget] void cannot be a generic type argument")
  is RirStructType -> error(
    "[nuget] struct type arguments are excluded from v1 (ADR-072 Decision 6): " +
        "${type.namespace}.${type.name}"
  )

  is RirGenericInstanceType -> error(
    "[nuget] nested generic instantiations are excluded from v1 (ADR-072 Decision 6): " +
        "${type.namespace}.${type.name}"
  )

  is RirTypeParameterType -> error(
    "[nuget] an unresolved type parameter cannot be a generic type argument: ${type.name}"
  )
}

// ADR-072 Decision 10: "BoxOfInt", "BoxOfNullableString", "PairingOfStringAndInt". The
// definition's own stripped simple name, "Of", then each argument's own tag joined by "And".
internal fun instantiationTag(cls: RirClass, instantiation: RirInstantiation): String {
  val base: String = cls.name.substringBefore('`')
  val argTags: String = instantiation.typeArguments.joinToString("And") { instantiationArgTag(it) }
  return "${base}Of$argTags"
}

// ADR-072 Decision 1: the per-instantiation witness object an ORDINARY (non-generic) member must
// pass alongside the wrapper's handle when constructing a value of [type]: "Box(fn.invoke(...)
// ?: error(...), BoxOfStringBridge)" per Decision 1's rewrap() sketch. The witness is chosen
// entirely at generation time from the instantiation discovered at THIS position, never at
// runtime. Fails fast if [type]'s definition is not in [genericDefs]. That should already be
// impossible by the time this is called, because the shared isV1Type filter only ever admits an
// instantiation whose definition resolved there (see RirBridging.kt's isV1Type
// RirGenericInstanceType branch).
private fun witnessObjectName(
  type: RirGenericInstanceType,
  genericDefs: Map<RirTypeKey, RirClass>,
): String {
  val definition: RirClass = requireNotNull(genericDefs[RirTypeKey(type.namespace, type.name)]) {
    "[nuget] generic instantiation ${type.namespace}.${type.name} referenced at an ordinary " +
        "member position, but its definition was not found among the bound generic class " +
        "definitions. This should already have been excluded by the shared isV1Type filter."
  }
  return "${instantiationTag(definition, RirInstantiation(type.typeArguments))}Bridge"
}

// ADR-072 Decision 4: the canonical instantiation signature Decision 4 hashes the contract
// against: "Test.Boxes.Box`1[System.Int32]" style, using the SAME describe()-shaped rendering
// contractHash's own signaturePart already produces for a RirGenericInstanceType (so a definition
// reference and its own instantiation's canonical name are never independently re-derived).
internal fun canonicalInstantiationSignature(instantiation: RirInstantiation): String =
  instantiation.typeArguments.joinToString(",") { instantiationCanonicalArg(it) }

internal fun instantiationCanonicalArg(type: RirTypeRef): String = when (type) {
  is RirPrimitiveType -> canonicalPrimitiveName(type.name)
  is RirStringType -> "System.String" + if (type.nullable) "?" else ""
  is RirObjectHandleType -> "${type.namespace}.${type.name}" + if (type.nullable) "?" else ""
  is RirEnumType -> "${type.namespace}.${type.name}"
  is RirInterfaceType -> "${type.namespace}.${type.name}" + if (type.nullable) "?" else ""
  else -> error("[nuget] unexpected generic type argument shape: $type")
}

internal fun canonicalPrimitiveName(name: String): String = when (name) {
  "bool" -> "System.Boolean"
  "byte" -> "System.Byte"
  "short" -> "System.Int16"
  "int" -> "System.Int32"
  "long" -> "System.Int64"
  "float" -> "System.Single"
  "double" -> "System.Double"
  "char" -> "System.Char"
  else -> error("[nuget] Unknown primitive type name '$name'")
}

// ADR-072 Decision 3: the definition's OWN ordered registrable list. Constructor (if any), then
// static methods, then instance methods, then per-property getter/[setter] pairs, in RIR
// declaration order. Unlike bridgeableRegistrablesCandidates, no isV1Type filter runs here: a
// generic definition's own member types are RirTypeParameterType/RirGenericInstanceType-shaped by
// construction and never pass that ordinary (non-generic) filter, so every declared member is
// treated as bridgeable. Decision 6's vocabulary check already ran per-instantiation, one layer
// up, before this list is used to render anything.
internal fun genericDefinitionRegistrables(cls: RirClass): List<RirRegistrable> {
  val ctor: List<RirRegistrable> = cls.constructors.map { RirRegistrable.Ctor(it) }
  val staticMethods: List<RirRegistrable> =
    cls.methods.filter { it.isStatic }.map { RirRegistrable.Method(it) }
  val instanceMethods: List<RirRegistrable> =
    cls.methods.filterNot { it.isStatic }.map { RirRegistrable.Method(it) }
  val instanceProperties: List<RirRegistrable> = cls.properties.filterNot { it.isStatic }
    .flatMap { p ->
      if (p.isReadOnly) listOf(RirRegistrable.PropertyGetter(p))
      else listOf(RirRegistrable.PropertyGetter(p), RirRegistrable.PropertySetter(p))
    }
  val staticProperties: List<RirRegistrable> = cls.properties.filter { it.isStatic }
    .flatMap { p ->
      if (p.isReadOnly) listOf(RirRegistrable.PropertyGetter(p))
      else listOf(RirRegistrable.PropertyGetter(p), RirRegistrable.PropertySetter(p))
    }
  return ctor + staticMethods + instanceMethods + instanceProperties + staticProperties
}

// The witness-interface-level Kotlin member NAME for one registrable: a constructor is always
// named "construct" (there is no C# member name to reuse), everything else reuses its ordinary
// camelCase Kotlin name.
internal fun genericMemberName(r: RirRegistrable): String = when (r) {
  is RirRegistrable.Ctor -> "construct"
  is RirRegistrable.Method -> r.method.name.toMethodCamelCase()
  is RirRegistrable.PropertyGetter -> r.property.name.toMethodCamelCase()
  is RirRegistrable.PropertySetter -> r.property.name.toMethodCamelCase() + "Set"
}

internal fun genericMemberParams(r: RirRegistrable): List<RirParameter> = when (r) {
  is RirRegistrable.Ctor -> r.ctor.parameters
  is RirRegistrable.Method -> r.method.parameters
  is RirRegistrable.PropertyGetter -> emptyList()
  is RirRegistrable.PropertySetter -> listOf(RirParameter("value", r.property.type))
}

// The witness-interface-level Kotlin RETURN type for one registrable, generic-aware (may still
// contain RirTypeParameterType/RirGenericInstanceType at the DEFINITION level; a witness OBJECT
// renders the same registrable after substituteGenericType has replaced every type parameter with
// its instantiation's concrete argument).
internal fun genericMemberReturnType(r: RirRegistrable, cls: RirClass): RirTypeRef = when (r) {
  is RirRegistrable.Ctor -> RirGenericInstanceType(
    namespace = "", name = cls.name,
    typeArguments = cls.typeParameters.mapIndexed { i, n -> RirTypeParameterType(i, n) },
  )

  is RirRegistrable.Method -> r.method.returnType
  is RirRegistrable.PropertyGetter -> r.property.type
  is RirRegistrable.PropertySetter -> RirVoidType
}

// ADR-072 Decision 3: `BoxBridge<T>`, one witness interface per generic definition, declaring
// EVERY registrable member (constructor included, as "construct"), each taking the receiver
// `NugetObjectHandle` first (construct excepted, since it has no receiver yet). Rendered against
// the definition's OWN (unsubstituted) type parameters. Decision 1's "every member goes through
// the witness, including a T-free member" is why describe()-shaped members are declared here too.
private fun genericBridgeInterfaceFileContent(
  cls: RirClass,
  kotlinPkg: String,
  enumPkgs: Map<RirTypeKey, String>,
  handlePkgs: Map<RirTypeKey, String>,
): String {
  val simpleName: String = cls.name.substringBefore('`')
  val typeParams: String = cls.typeParameters.joinToString(", ")
  val registrables: List<RirRegistrable> = genericDefinitionRegistrables(cls)
  val members: String = registrables.joinToString("\n") { r ->
    val isCtor: Boolean = r is RirRegistrable.Ctor
    val receiverParam: String = if (isCtor) "" else "handle: NugetObjectHandle"
    val ownParams: String = genericMemberParams(r).joinToString(", ") { p ->
      "${p.name}: ${genericAwareKotlinType(p.type)}"
    }
    val allParams: String =
      listOf(receiverParam, ownParams).filter { it.isNotEmpty() }.joinToString(", ")
    // A constructor slot's real return is the raw ADR-051 handle, not a Box<T> instance. The
    // fake top-level constructor (genericClassWrapperFileContent) is the one that wraps it.
    val retSuffix: String = if (isCtor) ": NugetObjectHandle" else {
      val retType: RirTypeRef = genericMemberReturnType(r, cls)
      if (retType is RirVoidType) "" else ": ${genericAwareKotlinType(retType)}"
    }
    "  fun ${genericMemberName(r)}($allParams)$retSuffix"
  }
  // ADR-072: the definition's OWN (unsubstituted) member signatures can still name a concrete
  // cross-package enum/handle type directly (not just through T). See
  // genericAwareReferencedEnumTypes' KDoc for why this file previously emitted such a reference
  // with no `import` line at all.
  val referencedTypes: List<RirTypeRef> = registrables.flatMap { r ->
    genericMemberParams(r).map { it.type } + genericMemberReturnType(r, cls)
  }
  val enumImportLines: List<String> = enumImports(
    referencedTypes.flatMap(::genericAwareReferencedEnumTypes).distinct(), enumPkgs, kotlinPkg,
  )
  val handleImportLines: List<String> = handleImports(
    referencedTypes.flatMap(::genericAwareReferencedHandleTypes).distinct(), handlePkgs, kotlinPkg,
  )
  val imports: String =
    (listOf("import $INTERNAL_PKG.NugetObjectHandle") + enumImportLines + handleImportLines)
      .distinct().joinToString("\n")
  return """
    |package $kotlinPkg
    |
    |$imports
    |
    |// Generated (ADR-072): one witness per closed instantiation of `$simpleName<$typeParams>`
    |// implements this interface: every member, T-free members included, dispatches through it.
    |internal interface ${simpleName}Bridge<$typeParams> {
    |$members
    |}
  """.trimMargin().trim()
}

// ADR-072 Decision 1: the generic class itself, `internal class Box<T>` over an ADR-051 erased
// handle plus a per-instantiation witness, every member delegating to the witness. Also emits one
// top-level fake-constructor function per UNAMBIGUOUS instantiation (Decision 5).
private fun genericClassWrapperFileContent(
  cls: RirClass,
  kotlinPkg: String,
  unambiguousInstantiations: List<RirInstantiation>,
  enumPkgs: Map<RirTypeKey, String>,
  handlePkgs: Map<RirTypeKey, String>,
): String {
  val simpleName: String = cls.name.substringBefore('`')
  val typeParams: String = cls.typeParameters.joinToString(", ")
  val registrables: List<RirRegistrable> = genericDefinitionRegistrables(cls)
  val ctorRegistrable: RirRegistrable.Ctor? =
    registrables.filterIsInstance<RirRegistrable.Ctor>().firstOrNull()

  val nonCtorRegistrables: List<RirRegistrable> =
    registrables.filterNot { it is RirRegistrable.Ctor }
  val members: String = nonCtorRegistrables.joinToString("\n\n") { r ->
    val name: String = genericMemberName(r)
    val bridgeName: String = if (r is RirRegistrable.PropertySetter) "${name}Set" else name
    val ownParams: String = genericMemberParams(r).joinToString(", ") { p -> p.name }
    val retType: RirTypeRef = genericMemberReturnType(r, cls)
    val retSuffix: String =
      if (retType is RirVoidType) "" else ": ${genericAwareKotlinType(retType)}"
    when (r) {
      is RirRegistrable.PropertyGetter ->
        "  val $name$retSuffix get() = bridge.$bridgeName(handle)"

      is RirRegistrable.PropertySetter ->
        "  fun set${name.replaceFirstChar { it.uppercaseChar() }}(value: " +
            "${genericAwareKotlinType(genericMemberParams(r).single().type)}) {\n" +
            "    bridge.$bridgeName(handle, value)\n  }"

      else -> {
        val params: String = genericMemberParams(r).joinToString(", ") { p ->
          "${p.name}: ${genericAwareKotlinType(p.type)}"
        }
        val invokeArgs: String = (listOf("handle") + genericMemberParams(r).map { it.name })
          .joinToString(", ")
        "  fun $name($params)$retSuffix = bridge.$name($invokeArgs)"
      }
    }
  }

  val fakeCtors: String = if (ctorRegistrable == null) "" else unambiguousInstantiations
    .joinToString("\n") { inst ->
      val tag: String = instantiationTag(cls, inst)
      val params: String = ctorRegistrable.ctor.parameters.joinToString(", ") { p ->
        "${p.name}: ${genericAwareKotlinType(substituteGenericType(p.type, inst.typeArguments))}"
      }
      val args: String = ctorRegistrable.ctor.parameters.joinToString(", ") { it.name }
      val retTypeArgs: String = inst.typeArguments.joinToString(", ") { genericAwareKotlinType(it) }
      // internal (not public): matches the internal visibility of `$simpleName` itself. A public
      // top-level function returning an internal generic type is a visibility error the Kotlin
      // compiler would catch, but it is ALSO a public API surface the forward-direction (KSP)
      // exporter's public-API scan would try to bridge back into C#: a reverse-bound generic
      // type must stay invisible to that scan exactly like every other reverse-generated
      // declaration (see the "internal (not public)" notes on
      // stubFileContent/classWrapperContent/structFileContent).
      """
      |internal fun $simpleName($params): $simpleName<$retTypeArgs> {
      |  val handle = ${tag}Bridge.construct($args)
      |  return $simpleName(handle, ${tag}Bridge)
      |}
    """.trimMargin()
    }

  // ADR-072: cross-package enum/handle imports; see genericAwareReferencedEnumTypes' KDoc. Two
  // sources: the definition's OWN member signatures (registrables, unsubstituted, rarely a
  // concrete type, but Describe()-shaped members can still name one directly), and each
  // unambiguous instantiation's OWN type arguments (the fake constructors, e.g.
  // `fun Box(value: CatMood): Box<CatMood>`), which is the one this feature's fixture exercises.
  val memberTypes: List<RirTypeRef> = registrables
    .flatMap { r -> genericMemberParams(r).map { it.type } + genericMemberReturnType(r, cls) }
  val instantiationArgTypes: List<RirTypeRef> =
    unambiguousInstantiations.flatMap { it.typeArguments }
  val referencedTypes: List<RirTypeRef> = memberTypes + instantiationArgTypes
  val enumImportLines: List<String> = enumImports(
    referencedTypes.flatMap(::genericAwareReferencedEnumTypes).distinct(), enumPkgs, kotlinPkg,
  )
  val handleImportLines: List<String> = handleImports(
    referencedTypes.flatMap(::genericAwareReferencedHandleTypes).distinct(), handlePkgs, kotlinPkg,
  )
  val imports: String = (
      listOf(
        "import $INTERNAL_PKG.NugetHandleOwner",
        "import $INTERNAL_PKG.NugetObjectHandle",
        "import kotlinx.cinterop.COpaquePointer",
      ) + enumImportLines + handleImportLines
      ).distinct().joinToString("\n")

  return """
    |@file:OptIn(
    |  kotlinx.cinterop.ExperimentalForeignApi::class,
    |  kotlin.experimental.ExperimentalNativeApi::class,
    |)
    |
    |package $kotlinPkg
    |
    |$imports
    |
    |// Generated (ADR-072 Decision 1): a real Kotlin generic class over an erased ADR-051 handle.
    |// Every member (T-free members included, CS8895's constraint) dispatches through [bridge].
    |internal class $simpleName<$typeParams> internal constructor(
    |  handle: NugetObjectHandle,
    |  private val bridge: ${simpleName}Bridge<$typeParams>,
    |) : NugetHandleOwner, AutoCloseable {
    |  override val handle: NugetObjectHandle = handle
    |
    |  @Suppress("unused")
    |  private val cleaner = kotlin.native.ref.createCleaner(this.handle) { it.free() }
    |
    |  override fun close(): Unit = handle.free()
    |
    |$members
    |}
    |
    |$fakeCtors
  """.trimMargin().trim()
}

// ADR-072 Decision 4/10: `BoxOfIntBridge.kt`, one witness OBJECT per closed instantiation,
// implementing the definition's own BoxBridge<T> with T substituted to this instantiation's
// concrete argument(s), plus its OWN registration export/fn-pointer table (folded into the same
// file rather than a separate Bindings.kt: one fewer generated file per instantiation, and this
// witness is already the only thing that needs the pointers).
private fun genericWitnessObjectFileContent(
  cls: RirClass,
  instantiation: RirInstantiation,
  namespaceName: String,
  packageId: String,
  exportName: String,
  kotlinPkg: String,
  enumPkgs: Map<RirTypeKey, String>,
  handlePkgs: Map<RirTypeKey, String>,
): String {
  val simpleName: String = cls.name.substringBefore('`')
  val tag: String = instantiationTag(cls, instantiation)
  val args: List<RirTypeRef> = instantiation.typeArguments
  val typeArgsRendered: String = args.joinToString(", ") { genericAwareKotlinType(it) }
  val registrables: List<RirRegistrable> = genericDefinitionRegistrables(cls)
  bridgeIds(registrables.map { substitutedIdentity(it, args) })

  // Decision 10: DISPLAYED (embedded as a string literal in generated source) qualified name
  // strips the CLR arity suffix: the `Box`1` leak this feature fixes. The hash below is computed
  // over its own private, backtick-carrying signature (never emitted as a literal), so hashing is
  // unaffected by this display-only stripping.
  val qualifiedName =
    "$namespaceName.$simpleName[${canonicalInstantiationSignature(instantiation)}]"
  val hashQualifiedName =
    "$namespaceName.${cls.name}[${canonicalInstantiationSignature(instantiation)}]"
  val failMsg = "NugetRegistry.notRegistered(\"$qualifiedName\", \"$packageId\")"

  val fnVars: String = registrables.joinToString("\n") { r ->
    val name: String = genericMemberName(r)
    val paramTypes: List<String> = (
        (if (r !is RirRegistrable.Ctor) listOf("COpaquePointer?") else emptyList()) +
            genericMemberParams(r).map { cfnType(substituteGenericType(it.type, args)) }
        )
    val retType: RirTypeRef = substituteGenericType(genericMemberReturnType(r, cls), args)
    val retCfn: String = if (retType is RirVoidType) "Unit" else cfnType(retType)
    "  internal var ${name}Fn: CPointer<CFunction<(${paramTypes.joinToString(", ")}) -> " +
        "$retCfn>>? = null"
  }

  val overrides: String = registrables.joinToString("\n\n") { r ->
    val name: String = genericMemberName(r)
    val isCtor: Boolean = r is RirRegistrable.Ctor
    val receiverParam: String = if (isCtor) "" else "handle: NugetObjectHandle"
    val ownParams: List<RirParameter> = genericMemberParams(r)
    val ownParamsRendered: String = ownParams.joinToString(", ") { p ->
      "${p.name}: ${genericAwareKotlinType(substituteGenericType(p.type, args))}"
    }
    val allParams: String =
      listOf(receiverParam, ownParamsRendered).filter { it.isNotEmpty() }.joinToString(", ")
    val retType: RirTypeRef = substituteGenericType(genericMemberReturnType(r, cls), args)
    val retSuffix: String = if (isCtor) {
      ": NugetObjectHandle"
    } else if (retType is RirVoidType) {
      ""
    } else {
      ": ${genericAwareKotlinType(retType)}"
    }
    val receiverArg: List<String> =
      if (isCtor) emptyList() else listOf("handle.require(\"$simpleName\")")
    val convertedArgs: List<String> = ownParams.map { p ->
      argConversion(substituteGenericType(p.type, args), p.name)
    }
    val invokeArgs: String = (receiverArg + convertedArgs).joinToString(", ")
    // ADR-072: a substituted string-typed OWN parameter's argConversion(...) is `.cstr.ptr`, which
    // must be allocated inside a `memScoped { ... }` block and released at its end. Mirrors
    // buildStubMethod's own `hasStringParam` -> memScoped wrapping. This witness previously called
    // `.invoke(...)` un-scoped whenever a substituted parameter happened to be a string.
    val hasStringOwnParam: Boolean =
      ownParams.any { p -> substituteGenericType(p.type, args) is RirStringType }
    val rawInvoke = "requireNotNull($name" + "Fn) { $failMsg }.invoke($invokeArgs)"
    val callExpr = if (hasStringOwnParam) "memScoped { $rawInvoke }" else rawInvoke
    val body: String = when {
      isCtor -> "NugetObjectHandle(requireNotNull($callExpr) { \"$simpleName constructor " +
          "returned null\" })"

      retType is RirVoidType -> callExpr
      // ADR-072: the raw callExpr is the erased ADR-051 GCHandle IntPtr (a COpaquePointer?), NOT a
      // NugetObjectHandle. Every OTHER handle-returning site in this file (buildStubMethod's
      // RirObjectHandleType/RirInterfaceType branches) wraps it in NugetObjectHandle(...) before
      // handing it to a wrapper constructor. This site previously passed the raw pointer straight
      // into `$retSimple(...)`, which does not compile: the generic class's own constructor
      // requires `handle: NugetObjectHandle`.
      retType is RirGenericInstanceType -> {
        val retSimple: String = retType.name.substringBefore('`')
        val retTag: String = instantiationTag(cls, RirInstantiation(retType.typeArguments))
        // ADR-053/ADR-070/ADR-072, third instance: a NULLABLE generic-instance return (e.g. a
        // hypothetical `Box<T>? Rewrap()`) must map a null pointer to Kotlin `null`, not guard.
        // Mirrors the RirObjectHandleType/RirInterfaceType branches below, which already got this
        // right. A non-null-annotated return keeps the existing fail-fast requireNotNull.
        if (retType.nullable) {
          "$callExpr?.let { $retSimple(NugetObjectHandle(it), ${retTag}Bridge) }"
        } else {
          "$retSimple(NugetObjectHandle(requireNotNull($callExpr) " +
              "{ \"$simpleName.$name returned null\" }), ${retTag}Bridge)"
        }
      }

      // ADR-072: a bound-class-handle type argument (Decision 6) returned by a substituted member
      // (e.g. `Box<Ferret>.value`) needs the SAME wrapping an ordinary (non-generic) handle return
      // gets (buildStubMethod's RirObjectHandleType branch): this file previously returned the
      // raw pointer unwrapped, which does not typecheck against the declared `Ferret` return type.
      retType is RirObjectHandleType -> if (retType.nullable) {
        "$callExpr?.let { ${retType.name}(it) }"
      } else {
        "${retType.name}(requireNotNull($callExpr) { \"$simpleName.$name returned null\" })"
      }

      // ADR-072: the interface equivalent of the RirObjectHandleType branch above. A bound
      // interface type argument returns through its `{Name}Handle` concrete wrapper, exactly like
      // buildStubMethod's RirInterfaceType branch.
      retType is RirInterfaceType -> if (retType.nullable) {
        "$callExpr?.let { nuget${retType.name}Value(it) }"
      } else {
        "${retType.name}Handle(requireNotNull($callExpr) { \"$simpleName.$name returned null\" })"
      }

      // ADR-053, third instance: a NULLABLE-annotated string return (e.g. `Box<string?>.value`)
      // must map a null pointer to Kotlin `null`, the same shape buildStubMethod's own
      // RirStringType branch already uses (`?: return null` there, `?.let { ... }` here since this
      // site builds a single expression). A non-null-annotated return keeps the existing
      // fail-fast requireNotNull.
      retType is RirStringType -> if (retType.nullable) {
        "$callExpr?.let { val s = it.reinterpret<ByteVar>().toKString(); " +
            "freeManagedString(it); s }"
      } else {
        "requireNotNull($callExpr) { \"$simpleName.$name returned " +
            "null\" }.let { val s = it.reinterpret<ByteVar>().toKString(); " +
            "freeManagedString(it); s }"
      }

      retType is RirEnumType ->
        "nugetEnumEntry(${retType.name}.entries, $callExpr, \"${retType.name}\")"

      else -> callExpr
    }
    "  override fun $name($allParams)$retSuffix = $body"
  }

  val assignments: String = registrables.joinToString("\n  ") { r ->
    val name: String = genericMemberName(r)
    "${tag}Bridge.${name}Fn = requireNotNull(${name}Ptr).reinterpret()"
  }
  val params: String = registrables.joinToString(",\n  ") { r ->
    "${genericMemberName(r)}Ptr: COpaquePointer?"
  }
  // Must match NugetGenerateShimsTask's genericRegistrationFileContent hash input EXACTLY (both
  // built off the SAME hashQualifiedName/substitutedIdentity; ADR-054's contract check depends
  // on the two sides never independently re-deriving this).
  val hash: Long = fnv1a64(
    "$hashQualifiedName|" + registrables.joinToString("|") { substitutedIdentity(it, args) },
  )

  // ADR-072: cross-package enum/handle imports for this witness's SUBSTITUTED signatures; see
  // genericAwareReferencedEnumTypes' KDoc. Every registrable's param/return type after
  // substituteGenericType(..., args) can name a concrete enum/handle type (the whole point of a
  // per-instantiation witness), and this file previously had a FIXED import list that never
  // accounted for that.
  val substitutedParamTypes: List<RirTypeRef> = registrables.flatMap { r ->
    genericMemberParams(r).map { p -> substituteGenericType(p.type, args) }
  }
  val substitutedReturnTypes: List<RirTypeRef> = registrables.map { r ->
    substituteGenericType(genericMemberReturnType(r, cls), args)
  }
  val substitutedTypes: List<RirTypeRef> = substitutedParamTypes + substitutedReturnTypes
  val enumImportLines: List<String> = enumImports(
    substitutedTypes.flatMap(::genericAwareReferencedEnumTypes).distinct(), enumPkgs, kotlinPkg,
  )
  val handleImportLines: List<String> = handleImports(
    substitutedTypes.flatMap(::genericAwareReferencedHandleTypes).distinct(), handlePkgs, kotlinPkg,
  )
  // ADR-072: a string-typed (or string-carrying) SUBSTITUTED parameter's argConversion(...) uses
  // `.cstr.ptr` (the same conversion any bound string parameter uses): this witness's previously
  // FIXED import list never accounted for a substituted param needing it (e.g. `Box<String>`'s
  // `construct(value: String)`).
  val hasStringParam: Boolean = substitutedParamTypes.any { it is RirStringType }
  val imports: String = (
      listOf(
        "import $INTERNAL_PKG.NugetObjectHandle",
        "import $INTERNAL_PKG.NugetRegistry",
        "import $INTERNAL_PKG.freeManagedString",
        "import $INTERNAL_PKG.nugetEnumEntry",
        "import kotlinx.cinterop.ByteVar",
        "import kotlinx.cinterop.CFunction",
        "import kotlinx.cinterop.COpaquePointer",
        "import kotlinx.cinterop.CPointer",
        "import kotlinx.cinterop.invoke",
        "import kotlinx.cinterop.reinterpret",
        "import kotlinx.cinterop.toKString",
        "import kotlin.experimental.ExperimentalNativeApi",
      ) + enumImportLines + handleImportLines +
          (
              if (hasStringParam) {
                listOf(
                  "import kotlinx.cinterop.cstr",
                  "import kotlinx.cinterop.memScoped",
                  "import kotlinx.cinterop.ptr",
                )
              } else {
                emptyList()
              }
              )
      ).distinct().joinToString("\n")

  return """
    |@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    |
    |package $kotlinPkg
    |
    |$imports
    |
    |// Generated (ADR-072 Decision 1/4): the witness for $simpleName<$typeArgsRendered>.
    |internal object ${tag}Bridge : ${simpleName}Bridge<$typeArgsRendered> {
    |${fnVars.indented("  ")}
    |
    |$overrides
    |}
    |
    |@OptIn(ExperimentalNativeApi::class)
    |@CName("$exportName")
    |fun $exportName(
    |  slotCount: Int,
    |  contractHash: Long,
    |  $params,
    |) {
    |  NugetRegistry.checkContract(
    |    qualifiedType = "$qualifiedName",
    |    packageId = "$packageId",
    |    slotCount = slotCount,
    |    contractHash = contractHash,
    |    expectedSlots = ${registrables.size},
    |    expectedHash = ${hash}L,
    |  )
    |  $assignments
    |  NugetRegistry.record("$qualifiedName", ${registrables.size})
    |}
  """.trimMargin().trim()
}

// bridgeId's identity, computed off the SUBSTITUTED signature (Decision 4: bridgeId/bridgeSuffix
// digests are only ever used to name members WITHIN one instantiation's own witness object,
// never shared across instantiations, so calling bridgeIds() per instantiation (never over the
// union) is both correct and sufficient here).
internal fun substitutedIdentity(r: RirRegistrable, args: List<RirTypeRef>): String = when (r) {
  is RirRegistrable.Ctor -> "ctor(" +
      r.ctor.parameters.joinToString(",") { substituteGenericType(it.type, args).toString() } + ")"

  is RirRegistrable.Method -> "method:${r.method.name}(" +
      r.method.parameters.joinToString(",") { substituteGenericType(it.type, args).toString() } +
      "):" + substituteGenericType(r.method.returnType, args).toString()

  is RirRegistrable.PropertyGetter ->
    "get:${r.property.name}:${substituteGenericType(r.property.type, args)}"

  is RirRegistrable.PropertySetter ->
    "set:${r.property.name}:${substituteGenericType(r.property.type, args)}"
}

// ADR-072 Decision 5: the fake constructor's own erased (ignoring nullability entirely,
// kotlinType, not declKotlinType) Kotlin parameter-type list for one instantiation, shared by
// unambiguousInstantiations and ambiguousGenericConstructorDiagnostics below, which group the SAME
// instantiations by this SAME signature for complementary purposes (keep vs. diagnose).
private fun erasedFakeConstructorSignature(ctor: RirConstructor, inst: RirInstantiation): String =
  ctor.parameters.joinToString(",") { p ->
    kotlinType(substituteGenericType(p.type, inst.typeArguments))
  }

// ADR-072 Decision 5: the ambiguity rule. Two or more instantiations of ONE definition whose fake
// constructor erases to the same Kotlin parameter-type list must ALL lose their fake constructor,
// regardless of declaration order (grouping is inherently order-independent, unlike a sequential
// "keep the first" pick).
internal fun unambiguousInstantiations(cls: RirClass): List<RirInstantiation> {
  val ctor: RirConstructor = cls.constructors.firstOrNull() ?: return emptyList()
  val groups: Map<String, List<RirInstantiation>> =
    cls.instantiations.groupBy { erasedFakeConstructorSignature(ctor, it) }
  return cls.instantiations.filter { inst ->
    groups.getValue(erasedFakeConstructorSignature(ctor, inst)).size == 1
  }
}

internal fun ambiguousGenericConstructorDiagnostics(cls: RirClass): List<RirDiagnostic> {
  val ctor: RirConstructor = cls.constructors.firstOrNull() ?: return emptyList()
  val groups: Map<String, List<RirInstantiation>> =
    cls.instantiations.groupBy { erasedFakeConstructorSignature(ctor, it) }
  return groups.values.filter { it.size > 1 }.flatMap { group ->
    group.map { inst ->
      RirDiagnostic(
        kind = RirDiagnosticKind.SKIPPED_AMBIGUOUS_GENERIC_CONSTRUCTOR,
        typeName = cls.name,
        memberName = "constructor",
        memberSignature = canonicalInstantiationSignature(inst),
        reason = "two or more instantiations of ${cls.name} erase their fake constructor to the " +
            "same Kotlin parameter list (${erasedFakeConstructorSignature(ctor, inst)}): " +
            "ambiguous, ALL are skipped",
        hint = "Obtain an instance of this instantiation from a bound member instead of a " +
            "top-level fake constructor.",
      )
    }
  }
}

// ADR-072 Decision 10: `Box` and `Box`1` in one namespace (or two instantiations of ONE
// definition sharing an internal tag) is a hard generation failure.
private fun validateGenericArityCollisions(rir: RirFile) {
  rir.assemblies.forEach { assembly ->
    assembly.namespaces.forEach { namespace ->
      val classes: List<RirClass> = namespace.types.filterIsInstance<RirClass>()
      val bySimpleName: Map<String, List<RirClass>> =
        classes.groupBy { it.name.substringBefore('`') }
      bySimpleName.forEach { (simpleName, group) ->
        require(group.size == 1) {
          val names: String = group.joinToString("`, `") { it.name }
          "[nuget] error_generic_arity_name_collision: `$names` in namespace `${namespace.name}` " +
              "all strip to the Kotlin name `$simpleName`."
        }
      }
      classes.filter { it.typeParameters.isNotEmpty() }.forEach { cls ->
        val tags: Map<String, List<RirInstantiation>> =
          cls.instantiations.groupBy { instantiationTag(cls, it) }
        tags.forEach { (tag, group) ->
          require(group.size == 1) {
            "[nuget] error_generic_arity_name_collision: two instantiations of `${cls.name}` " +
                "both produce the internal tag `$tag`."
          }
        }
      }
    }
  }
}

// ADR-072 Decision 10: a generic definition with zero discovered instantiations emits NOTHING at
// all: no Kotlin type, no witness, no bindings, no registration export (this is what closes the
// `Box`1` leak). Returns the empty list for that case; the caller (generateKotlinStubs) adds no
// files and records no expected registration.
private fun genericClassFiles(
  cls: RirClass,
  kotlinPkg: String,
  namespaceName: String,
  packageId: String,
  enumPkgs: Map<RirTypeKey, String>,
  handlePkgs: Map<RirTypeKey, String>,
): List<GeneratedFile> {
  if (cls.instantiations.isEmpty()) return emptyList()
  val pkgPath: String = kotlinPkg.replace('.', '/')
  val simpleName: String = cls.name.substringBefore('`')
  val unambiguous: List<RirInstantiation> = unambiguousInstantiations(cls)

  val result: MutableList<GeneratedFile> = mutableListOf(
    GeneratedFile(
      relativePath = "nativeMain/$pkgPath/$simpleName.kt",
      content = genericClassWrapperFileContent(cls, kotlinPkg, unambiguous, enumPkgs, handlePkgs),
    ),
    GeneratedFile(
      relativePath = "nativeMain/$pkgPath/${simpleName}Bridge.kt",
      content = genericBridgeInterfaceFileContent(cls, kotlinPkg, enumPkgs, handlePkgs),
    ),
  )
  cls.instantiations.forEach { inst ->
    val tag: String = instantiationTag(cls, inst)
    val exportName: String = registrationExportName(namespaceName, tag)
    result.add(
      GeneratedFile(
        relativePath = "nativeMain/$pkgPath/${tag}Bridge.kt",
        content = genericWitnessObjectFileContent(
          cls, inst, namespaceName, packageId, exportName, kotlinPkg, enumPkgs, handlePkgs,
        ),
      )
    )
  }
  return result
}

// ADR-072: whether any generic definition/instantiation member needs NugetInterop.kt (a string
// anywhere in the SUBSTITUTED signature of any registrable, across all instantiations).
private fun genericClassNeedsInterop(cls: RirClass): Boolean {
  val registrables: List<RirRegistrable> = genericDefinitionRegistrables(cls)
  return cls.instantiations.any { inst ->
    registrables.any { r ->
      val params: List<RirTypeRef> = genericMemberParams(r).map { it.type }
      val ret: RirTypeRef = genericMemberReturnType(r, cls)
      (params + ret).any { substituteGenericType(it, inst.typeArguments) is RirStringType }
    }
  }
}

private fun genericClassNeedsEnums(cls: RirClass): Boolean {
  val registrables: List<RirRegistrable> = genericDefinitionRegistrables(cls)
  return cls.instantiations.any { inst ->
    registrables.any { r ->
      val ret: RirTypeRef = genericMemberReturnType(r, cls)
      substituteGenericType(ret, inst.typeArguments) is RirEnumType
    }
  }
}

// Every enum type referenced by a class's bridgeable members, in declaration order, deduplicated.
// The referencing class's stub must import each one that lives in a different Kotlin package than
// the stub itself, so this covers every position an enum can appear in: method returns, method
// parameters, constructor parameters and property types.
// ADR-072 Decision 6: an enum is in the v1 type-argument vocabulary, so a bridgeable generic
// instantiation's declared Kotlin type (declKotlinType's "Box<CatMood>" rendering) can name an enum
// that never appears at this member's own top level: referencedEnumTypes below must also widen
// into [type]'s typeArguments (one level: Decision 6 forbids a nested generic instantiation as a
// type argument, so no deeper recursion is possible) or the import for it is silently missing.
private fun genericInstanceEnumArgs(type: RirTypeRef): List<RirEnumType> =
  if (type is RirGenericInstanceType) type.typeArguments.filterIsInstance<RirEnumType>()
  else emptyList()

private fun referencedEnumTypes(
  methods: List<RirMethod>,
  ctors: List<RirConstructor>,
  properties: List<RirProperty>,
): List<RirEnumType> {
  val fromMethods: List<RirEnumType> = methods.flatMap { method ->
    listOfNotNull(method.returnType as? RirEnumType) +
        method.parameters.mapNotNull { it.type as? RirEnumType } +
        genericInstanceEnumArgs(method.returnType) +
        method.parameters.flatMap { genericInstanceEnumArgs(it.type) }
  }
  val fromCtors: List<RirEnumType> = ctors.flatMap { ctor ->
    ctor.parameters.mapNotNull { it.type as? RirEnumType } +
        ctor.parameters.flatMap { genericInstanceEnumArgs(it.type) }
  }
  val fromProperties: List<RirEnumType> = properties.flatMap { property ->
    listOfNotNull(property.type as? RirEnumType) + genericInstanceEnumArgs(property.type)
  }

  return (fromMethods + fromCtors + fromProperties).distinct()
}

// The `import <pkg>.<EnumName>` lines a stub file needs for the enums it references. An enum
// declared in the same Kotlin package as the referencing stub needs no import; one declared in
// another package (a C# enum in `Test.Enums` consumed by a class in `Test.Text`, say) does, or
// the generated stub does not compile.
private fun enumImports(
  enumTypes: List<RirEnumType>,
  enumPkgs: Map<RirTypeKey, String>,
  kotlinPkg: String,
): List<String> = enumTypes
  .map { type ->
    val pkg: String = requireNotNull(enumPkgs[RirTypeKey(type.namespace, type.name)]) {
      "[nuget] Enum ${type.namespace}.${type.name} is referenced by a bound member but is not " +
          "declared anywhere in the reverse IR. The metadata reader must emit every referenced " +
          "enum as a declaration, or the generated stub cannot import it."
    }
    pkg to type.name
  }
  .filter { (pkg, _) -> pkg != kotlinPkg }
  .map { (pkg, name) -> "import $pkg.$name" }
  .distinct()
  .sorted()

// ADR-086: the imports a handle-backed slot BODY needs — the generated wrapper class for a bound
// object (`Ferret(ptr)`), and for a bound interface both the interface type and its own package's
// `nuget{Iface}Value` helper. Same cross-package hazard enumImports closes for enum-typed slots;
// a miss here is a loud Kotlin compile error, never silent.
private fun slotHandleImports(
  slots: List<RirRegistrable>,
  typePkgs: Map<RirTypeKey, String>,
  kotlinPkg: String,
): List<String> = slots
  .flatMap { r ->
    when (r) {
      is RirRegistrable.Method -> listOf(r.method.returnType) + r.method.parameters.map { it.type }
      is RirRegistrable.PropertyGetter -> listOf(r.property.type)
      is RirRegistrable.PropertySetter -> listOf(r.property.type)
      is RirRegistrable.Ctor -> emptyList()
    }
  }
  .filter { it.isHandleBacked() }
  .flatMap { type ->
    val key: RirTypeKey = when (type) {
      is RirObjectHandleType -> RirTypeKey(type.namespace, type.name)
      is RirInterfaceType -> RirTypeKey(type.namespace, type.name)
      else -> error("[nuget] only handle-backed slot types reach slotHandleImports")
    }
    val name: String = when (type) {
      is RirObjectHandleType -> type.name
      is RirInterfaceType -> type.name
      else -> error("[nuget] only handle-backed slot types reach slotHandleImports")
    }
    val pkg: String = requireNotNull(typePkgs[key]) {
      "[nuget] ${key.namespace}.${key.name} is named by a Kotlin bridge slot but has no generated " +
          "Kotlin package. The planner admitted an unbound type — that is a planner bug, not a " +
          "reverse-IR gap."
    }
    if (pkg == kotlinPkg) emptyList()
    else if (type is RirInterfaceType) listOf("import $pkg.$name", "import $pkg.nuget${name}Value")
    else listOf("import $pkg.$name")
  }
  .distinct()
  .sorted()

// ADR-070/085: every enum type a set of interface registrables NAMES in generated Kotlin source —
// return types, method parameters, and property types (getter and setter alike). An inbound
// position (parameter, setter) names the enum just as much as an outbound one does
// (`nugetEnumEntry(EnergyLevel.entries, ...)`), so all three interface files need the same import
// coverage the class/struct files already get from enumImports.
private fun registrableEnumTypes(registrables: List<RirRegistrable>): List<RirEnumType> =
  registrables.flatMap { r ->
    when (r) {
      is RirRegistrable.Method -> listOfNotNull(r.method.returnType as? RirEnumType) +
          r.method.parameters.mapNotNull { it.type as? RirEnumType }

      is RirRegistrable.PropertyGetter -> listOfNotNull(r.property.type as? RirEnumType)
      is RirRegistrable.PropertySetter -> listOfNotNull(r.property.type as? RirEnumType)
      is RirRegistrable.Ctor -> emptyList()
    }
  }.distinct()

// ADR-059: the struct equivalent of enumImports above — the `import <pkg>.<StructName>` lines a
// struct's own generated data class file needs for the DIRECT struct-typed components it declares
// (a struct-typed component's own inner components live inside the referenced struct's own
// generated file and never need importing here — only ONE level of import resolution is needed at
// this call site, unlike the ABI expansion, because a Kotlin `data class` property type reference
// only ever names its immediate component type).
private fun structImports(
  structTypes: List<RirStructType>,
  structPkgs: Map<RirTypeKey, String>,
  kotlinPkg: String,
): List<String> = structTypes
  .map { type ->
    val pkg: String = requireNotNull(structPkgs[RirTypeKey(type.namespace, type.name)]) {
      "[nuget] Struct ${type.namespace}.${type.name} is referenced by a bound member but is not " +
          "declared anywhere in the reverse IR. The metadata reader must emit every referenced " +
          "struct as a declaration, or the generated stub cannot import it."
    }
    pkg to type.name
  }
  .filter { (pkg, _) -> pkg != kotlinPkg }
  .map { (pkg, name) -> "import $pkg.$name" }
  .distinct()
  .sorted()

// ADR-072: the RirObjectHandleType equivalent of enumImports/structImports above: the
// `import <pkg>.<ClassName>` lines a file needs for the bound-class-handle-typed references it
// makes (method/ctor/property top-level types, plus one level into a generic instantiation's own
// type arguments, see genericAwareReferencedHandleTypes).
private fun handleImports(
  handleTypes: List<RirObjectHandleType>,
  handlePkgs: Map<RirTypeKey, String>,
  kotlinPkg: String,
): List<String> = handleTypes
  .map { type ->
    val pkg: String = requireNotNull(handlePkgs[RirTypeKey(type.namespace, type.name)]) {
      "[nuget] Class ${type.namespace}.${type.name} is referenced by a bound member as a handle " +
          "type but is not declared anywhere in the reverse IR. The metadata reader must emit " +
          "every referenced class as a declaration, or the generated stub cannot import it."
    }
    pkg to type.name
  }
  .filter { (pkg, _) -> pkg != kotlinPkg }
  .map { (pkg, name) -> "import $pkg.$name" }
  .distinct()
  .sorted()

// The bound-class-handle-typed references a stub file needs an `import <pkg>.<ClassName>` line
// for: every method/ctor/property top-level RirObjectHandleType, mirroring referencedEnumTypes.
private fun referencedHandleTypes(
  methods: List<RirMethod>,
  ctors: List<RirConstructor>,
  properties: List<RirProperty>,
): List<RirObjectHandleType> {
  val fromMethods: List<RirObjectHandleType> = methods.flatMap { method ->
    listOfNotNull(method.returnType as? RirObjectHandleType) +
        method.parameters.mapNotNull { it.type as? RirObjectHandleType }
  }
  val fromCtors: List<RirObjectHandleType> = ctors.flatMap { ctor ->
    ctor.parameters.mapNotNull { it.type as? RirObjectHandleType }
  }
  val fromProperties: List<RirObjectHandleType> =
    properties.mapNotNull { it.type as? RirObjectHandleType }
  return (fromMethods + fromCtors + fromProperties).distinct()
}

// ADR-072: every enum/handle TYPE genuinely reachable from a (possibly generic-aware) type
// reference: a bare RirEnumType/RirObjectHandleType names itself, and a RirGenericInstanceType
// widens ONE level into its own type arguments (Decision 6 forbids a nested generic instantiation
// as a type argument, so no deeper recursion is possible/needed). Used by the generic-class file
// content builders below (genericBridgeInterfaceFileContent/genericClassWrapperFileContent/
// genericWitnessObjectFileContent), which otherwise have NO import-collection at all: they render
// every cross-package enum/handle reference (a fake constructor's own type argument, e.g.
// `fun Box(value: CatMood): Box<CatMood>`, or a witness's substituted member signature) as a bare
// unqualified name with no `import` line, which compiles only by accident when the referenced type
// happens to share a package: the exact hazard this feature's `Box<CatMood>`/`Box<Ferret>`
// fixture seams exist to catch.
private fun genericAwareReferencedEnumTypes(type: RirTypeRef): List<RirEnumType> = when (type) {
  is RirEnumType -> listOf(type)
  is RirGenericInstanceType -> type.typeArguments.flatMap(::genericAwareReferencedEnumTypes)
  else -> emptyList()
}

private fun genericAwareReferencedHandleTypes(type: RirTypeRef): List<RirObjectHandleType> =
  when (type) {
    is RirObjectHandleType -> listOf(type)
    is RirGenericInstanceType -> type.typeArguments.flatMap(::genericAwareReferencedHandleTypes)
    else -> emptyList()
  }

// ADR-059: every struct TYPE in [type]'s own component tree, including [type] itself if it is a
// struct — the outer struct AND every struct-typed component, at any nesting depth. Needed
// wherever a struct value is REASSEMBLED (structComponentReads/structComponentExprs): a struct
// return renders a bare `TypeName(...)` constructor call for EVERY struct in this tree (Litter's
// own call plus a nested Profile(...) plus a nested Extent(...), for instance), not just the
// outermost one, so each one needs its own import when it lives in a different Kotlin package than
// the file doing the reassembling — this is the site `structFileContent` already covers for a
// struct's OWN component declarations (via structImports above), but a bound CLASS's stub file
// (stubFileContent/classWrapperContent) reassembles struct RETURNS the exact same way and needs
// the identical import coverage.
private fun structTypesInTree(
  type: RirTypeRef,
  structs: Map<RirTypeKey, RirStruct>,
): List<RirStructType> {
  val ref: RirStructType = type as? RirStructType ?: return emptyList()
  val struct: RirStruct = structs[RirTypeKey(ref.namespace, ref.name)] ?: return listOf(ref)
  return listOf(ref) + struct.components.flatMap { c -> structTypesInTree(c.type, structs) }
}

// PascalCase method name → camelCase: lowercase the first character only.
// e.g. SerializeObject → serializeObject
private fun String.toMethodCamelCase(): String = replaceFirstChar { it.lowercaseChar() }

private fun String.toEnumScreamingSnake(): String = buildString {
  this@toEnumScreamingSnake.forEachIndexed { index, char ->
    if (index > 0 && char.isUpperCase()) append('_')
    append(char.uppercaseChar())
  }
}

private fun enumFileContent(kotlinPkg: String, enum: RirEnum, packageId: String): String {
  val entries: String = enum.entries.joinToString(",\n") { it.name.toEnumScreamingSnake() }
  return """
    |package $kotlinPkg
    |
    |// Generated: ordinal-backed Kotlin enum for $packageId.${enum.name}
    |enum class ${enum.name} {
    |${entries.indented("  ")}
    |}
  """.trimMargin().trim()
}

// ADR-056: renders a struct's own components as a `data class Point(val x: Int, val y: Int)` —
// immutable, no handle, no close(), no Cleaner. A v1 struct claims zero registration slots (see
// generateKotlinStubs, which never adds a struct to expectedRegistrations).
// A struct's components can reference an enum declared in a different Kotlin package than the
// struct itself (this repo's own bind{} config aliases Test.Structs and Test.Enums to
// different packages, so this is the normal case, not an edge one) — mirrors the import handling
// bindingsFileContent/stubFileContent already do for method/property enum references.
private fun structFileContent(
  kotlinPkg: String,
  struct: RirStruct,
  packageId: String,
  enumPkgs: Map<RirTypeKey, String>,
  structPkgs: Map<RirTypeKey, String>,
  constructors: List<RirConstructor>,
  instanceMethods: List<RirMethod>,
  staticMethods: List<RirMethod>,
  computedGetters: List<RirProperty>,
  namespaceName: String,
  structs: Map<RirTypeKey, RirStruct>,
  qualifiedTypeNames: Map<RirTypeKey, String>,
): String {
  // ADR-059: declKotlinType(c.type, qualifiedTypeNames) — not the bare kotlinType(c.type) this
  // used to call — so a struct-typed component whose simple name collides with another declared
  // type elsewhere in the file renders fully qualified, exactly like every OTHER reference site
  // (buildStubMethod's params, structConstructorHelpers' carrierParams, ...). The import for the
  // ordinary (non-colliding) cross-package case is handled separately below via structImports —
  // declKotlinType alone does not add an import line.
  val params: String = struct.components.joinToString(",\n  ") { c ->
    "val ${c.name.toMethodCamelCase()}: ${declKotlinType(c.type, qualifiedTypeNames)}"
  }
  val allMethods: List<RirMethod> = instanceMethods + staticMethods
  val hasMembers: Boolean =
    constructors.isNotEmpty() ||
        instanceMethods.isNotEmpty() ||
        staticMethods.isNotEmpty() ||
        computedGetters.isNotEmpty()

  // ADR-059: struct.components' own enum references are gathered via structEnumComponents
  // (recursive at any nesting depth), not a bare mapNotNull — an enum reached only through a
  // nested struct component (e.g. Litter -> Mother: Profile -> Mood: CatMood) is otherwise
  // invisible here.
  val memberEnumTypes: List<RirEnumType> = (
      struct.components.mapNotNull { it.type as? RirEnumType } +
          struct.components.flatMap { structEnumComponents(it.type, structs) } +
          allMethods.flatMap { method ->
            listOfNotNull(method.returnType as? RirEnumType) +
                method.parameters.mapNotNull { it.type as? RirEnumType }
          } +
          computedGetters.mapNotNull { it.type as? RirEnumType }
      ).distinct()

  // ADR-059: a struct's OWN direct component can be a struct declared in a different Kotlin
  // package than this struct (e.g. Nursery in test.nested nesting Litter from test.structs) —
  // give it a real `import`, exactly as memberEnumTypes/enumImports already does for an enum
  // component. Only the DIRECT component types are needed here (see structImports).
  val directStructComponentTypes: List<RirStructType> =
    struct.components.mapNotNull { it.type as? RirStructType }.distinct()

  val imports: MutableList<String> =
    (
        enumImports(memberEnumTypes, enumPkgs, kotlinPkg) +
            structImports(directStructComponentTypes, structPkgs, kotlinPkg)
        ).toMutableList()
  if (hasMembers) {
    imports.add("import $INTERNAL_PKG.NugetRegistry")
    imports.add("import kotlinx.cinterop.invoke")
  }

  // ADR-059: recurses through a struct-typed component's OWN components (typeContains), so a
  // string/enum reachable only through a nested struct (e.g. Litter's Mother: Profile carries a
  // string Tag) is not invisible to the import/memScoped decisions below.
  val receiverHasString: Boolean =
    struct.components.any { typeContains(it.type, structs, ::isStringRef) }
  val receiverHasEnum: Boolean =
    struct.components.any { typeContains(it.type, structs, ::isEnumRef) }
  val methodsReturnStruct: Boolean = allMethods.any { it.returnType is RirStructType }
  val gettersReturnStruct: Boolean = computedGetters.any { it.type is RirStructType }
  val methodsReturnString: Boolean = allMethods.any { it.returnType is RirStringType }
  val gettersReturnString: Boolean = computedGetters.any { it.type is RirStringType }
  val methodsReturnEnum: Boolean = allMethods.any { it.returnType is RirEnumType }
  val gettersReturnEnum: Boolean = computedGetters.any { it.type is RirEnumType }

  val paramsHaveString: Boolean = allMethods.any { method ->
    method.parameters.any { p -> typeContains(p.type, structs, ::isStringRef) }
  }
  val ctorsHaveString: Boolean = constructors.any { ctor ->
    ctor.parameters.any { p -> typeContains(p.type, structs, ::isStringRef) }
  }
  val usesReceiverString: Boolean =
    receiverHasString &&
        (instanceMethods.isNotEmpty() || computedGetters.isNotEmpty())

  // memScoped: out-pointer allocs (struct returns / secondary ctors) or any .cstr.ptr use.
  val needsMemScoped: Boolean =
    constructors.isNotEmpty() ||
        methodsReturnStruct ||
        gettersReturnStruct ||
        usesReceiverString ||
        paramsHaveString ||
        ctorsHaveString
  if (needsMemScoped) {
    imports.add("import kotlinx.cinterop.alloc")
    imports.add("import kotlinx.cinterop.memScoped")
    imports.add("import kotlinx.cinterop.ptr")
    imports.add("import kotlinx.cinterop.value")
  }

  // String returns and string out-pointer reads both need freeManagedString + toKString.
  val reconstructsStringComponent: Boolean =
    receiverHasString && (constructors.isNotEmpty() || methodsReturnStruct)
  val needsStringInterop: Boolean =
    methodsReturnString || gettersReturnString || reconstructsStringComponent
  if (needsStringInterop) {
    imports.add("import $INTERNAL_PKG.freeManagedString")
    imports.add("import kotlinx.cinterop.ByteVar")
    imports.add("import kotlinx.cinterop.reinterpret")
    imports.add("import kotlinx.cinterop.toKString")
  }

  val needsCstr: Boolean =
    ctorsHaveString || paramsHaveString || usesReceiverString
  if (needsCstr) {
    imports.add("import kotlinx.cinterop.cstr")
  }

  val outVarTypes: Set<String> = buildSet {
    if (constructors.isNotEmpty() || methodsReturnStruct || gettersReturnStruct) {
      // ADR-059: this struct's OWN out-pointer leaves, recursively flattened (a RirStructType
      // wrapping this exact namespace/name always resolves back to [struct] in the structs map).
      abiOutArgs(RirStructType(namespaceName, struct.name), structs)
        .forEach { add(cVarType(it.type)) }
    }
    allMethods.forEach { method ->
      if (method.returnType is RirStructType) {
        abiOutArgs(method.returnType, structs).forEach { add(cVarType(it.type)) }
      }
    }
    computedGetters.forEach { prop ->
      if (prop.type is RirStructType) {
        abiOutArgs(prop.type, structs).forEach { add(cVarType(it.type)) }
      }
    }
  }
  outVarTypes.sorted().forEach { type -> imports.add("import kotlinx.cinterop.$type") }

  val reconstructsEnumComponent: Boolean =
    receiverHasEnum && (constructors.isNotEmpty() || methodsReturnStruct)
  val needsEnumEntry: Boolean =
    memberEnumTypes.isNotEmpty() &&
        (methodsReturnEnum || gettersReturnEnum || reconstructsEnumComponent)
  if (needsEnumEntry) {
    imports.add("import $INTERNAL_PKG.nugetEnumEntry")
  }

  val importsBlock: String = if (imports.isEmpty()) ""
  else imports.distinct().joinToString("\n") + "\n\n"
  val privateCtor: String = if (constructors.isEmpty()) "" else {
    val args: String = struct.components.joinToString(", ") { "components.${it.name}" }
    "  private constructor(components: ${struct.name}ConstructorComponents) : this($args)"
  }
  val secondaryCtors: String = constructors.joinToString("\n") { ctor ->
    val ctorParams: String = ctor.parameters.joinToString(", ") { p ->
      "${p.name}: ${declKotlinType(p.type, qualifiedTypeNames)}"
    }
    val args: String = ctor.parameters.joinToString(", ") { it.name }
    "  constructor($ctorParams) : this(construct__${ctor.bridgeId()}($args))"
  }
  val instanceMethodBodies: String = instanceMethods.joinToString("\n\n") { method ->
    buildStructStubMethod(
      struct, method, packageId, namespaceName, structs, qualifiedTypeNames,
    ).prependIndent("  ")
  }
  val propertyBodies: String = computedGetters.joinToString("\n\n") { property ->
    buildStructStubProperty(
      struct, property, packageId, namespaceName, structs, qualifiedTypeNames,
    ).prependIndent("  ")
  }
  val companionBody: String = if (staticMethods.isEmpty()) "" else {
    val staticBodies: String = staticMethods.joinToString("\n\n") { method ->
      buildStructStubMethod(
        struct, method, packageId, namespaceName, structs, qualifiedTypeNames,
      ).prependIndent("    ")
    }
    "  companion object {\n$staticBodies\n  }"
  }
  val body: String = listOf(
    privateCtor, secondaryCtors, instanceMethodBodies, propertyBodies, companionBody,
  ).filter { it.isNotEmpty() }.joinToString("\n")
  val helpers: String = if (constructors.isEmpty()) "" else "\n\n" +
      structConstructorHelpers(
        struct, constructors, packageId, namespaceName, structs, qualifiedTypeNames,
      )
  // ADR-058 Decision 3's mitigation: for a Shape B struct, component order is C# FieldDef
  // (declaration) order, which — unlike a Shape A struct's constructor-parameter order — is NOT
  // C# public API. A package author can reorder public fields/auto-properties without it being a
  // source-breaking C# change, and every Kotlin consumer constructing this data class
  // POSITIONALLY silently starts meaning something else whenever the two reordered components
  // share a type. Decision 5's contractHash fix protects the WIRE (a stale shim is caught); this
  // KDoc is the only defence for the Kotlin consumer's SOURCE — named arguments cost nothing and
  // sidestep the hazard entirely. Shape A's order is already public API (reordering a
  // constructor's parameters is already a breaking C# change), so its KDoc carries no such
  // warning.
  val kdocBody: String = if (struct.shape == RirStructShape.CONSTRUCTOR) {
    """
    | * Copied by value across the bridge: equality is structural, and there is nothing to close.
    | * Mutating this value never affects the C# side (a copy crossed the boundary); use [copy].
    """.trimMargin()
  } else {
    """
    | * Copied by value across the bridge: equality is structural, and there is nothing to close.
    | * The C# struct's fields/properties are settable, but a Kotlin-side change can never be
    | * observable in C# (a copy crossed the boundary), so every component is a `val`. Use [copy]
    | * and pass the result back.
    | *
    | * Component order follows the C# declaration order. Prefer named arguments.
    """.trimMargin()
  }
  return """
    |@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    |
    |package $kotlinPkg
    |
    |$importsBlock/**
    | * Kotlin value type for the C# struct `$packageId.${struct.name}`.
    | *
    |$kdocBody
    | */
    |// internal (not public): consumable from anywhere else in this same Gradle module, but
    |// invisible to the forward-direction (KSP) exporter's public-API scan — this reverse-bound
    |// type must not be re-exported forward into the packed nupkg's own Interop.cs (mirrors the
    |// same note on the Bindings.kt/wrapper-class files; unlike a reverse-generated enum class,
    |// no ADR authorises a forward mapping for a decomposed struct).
    |internal data class ${struct.name}(
    |  $params,
    |) {
    |$body
    |}$helpers
  """.trimMargin().trim()
}

private fun structConstructorHelpers(
  struct: RirStruct,
  constructors: List<RirConstructor>,
  packageId: String,
  namespaceName: String,
  structs: Map<RirTypeKey, RirStruct>,
  qualifiedTypeNames: Map<RirTypeKey, String>,
): String {
  val carrierParams: String = struct.components.joinToString(", ") { component ->
    "val ${component.name}: ${declKotlinType(component.type, qualifiedTypeNames)}"
  }
  val helpers: String = constructors.joinToString("\n\n") { ctor ->
    val params: String = ctor.parameters.joinToString(", ") { p ->
      "${p.name}: ${declKotlinType(p.type, qualifiedTypeNames)}"
    }
    val inArgs: List<String> = ctor.parameters.flatMap { p ->
      structArgConversions(p.type, p.name, structs)
    }
    // ADR-059: this struct's OWN out-pointer leaves — the same shared flattening abiOutArgs uses
    // for a top-level struct return, applied here to the struct being constructed itself (a
    // RirStructType wrapping this exact namespace/name always resolves back to [struct] in the
    // structs map, so abiOutArgs' recursion behaves identically).
    val outArgs: List<AbiArg> = abiOutArgs(RirStructType(namespaceName, struct.name), structs)
    val allocations: String = outArgs.joinToString("\n") { arg ->
      "  val ${arg.name} = alloc<${cVarType(arg.type)}>()"
    }
    val invokeArgs: String = (inArgs + outArgs.map { "${it.name}.ptr" }).joinToString(", ")
    val (readStatements: List<String>, readExprs: List<String>) =
      structComponentExprs(struct, outArgs.iterator(), structs)
    val statements: String = readStatements.joinToString("\n") { "  $it" }
    val values: String = readExprs.joinToString(", ")
    """
      |private fun construct__${ctor.bridgeId()}($params): ${struct.name}ConstructorComponents =
      |    memScoped {
      |$allocations
      |  val fn = requireNotNull(${struct.name}Bindings.ctor__${ctor.bridgeId()}Fn) {
      |    NugetRegistry.notRegistered("$namespaceName.${struct.name}", "$packageId")
      |  }
      |  fn.invoke($invokeArgs)
      |$statements
      |  ${struct.name}ConstructorComponents($values)
      |}
    """.trimMargin()
  }
  return "private data class ${struct.name}ConstructorComponents($carrierParams)\n\n$helpers"
}

private fun structMethodParamCfnTypes(
  struct: RirStruct,
  method: RirMethod,
  structs: Map<RirTypeKey, RirStruct>,
): List<String> {
  val receiverTypes: List<String> =
    if (!method.isStatic) structReceiverAbiArgs(struct, structs).map { cfnType(it.type) }
    else emptyList()
  val inTypes: List<String> = abiArgs(method.parameters, structs).map { cfnType(it.type) }
  val outTypes: List<String> =
    abiOutArgs(method.returnType, structs).map { cfnOutPointerType(it.type) }
  return receiverTypes + inTypes + outTypes
}

private fun structBindingsFileContent(
  kotlinPkg: String,
  namespaceName: String,
  struct: RirStruct,
  registrables: List<RirRegistrable>,
  exportName: String,
  packageId: String,
  structs: Map<RirTypeKey, RirStruct>,
): String {
  val fnVars: String = registrables.joinToString("\n\n") { r ->
    when (r) {
      is RirRegistrable.Ctor -> {
        val inTypes: List<String> = abiArgs(r.ctor.parameters, structs).map { cfnType(it.type) }
        // ADR-059: this struct's OWN out-pointer leaves, recursively flattened — a RirStructType
        // wrapping this exact namespace/name always resolves back to [struct] in the structs map.
        val outTypes: List<String> = abiOutArgs(RirStructType(namespaceName, struct.name), structs)
          .map { cfnOutPointerType(it.type) }
        "internal var ctor__${r.ctor.bridgeId()}Fn: CPointer<CFunction<(" +
            (inTypes + outTypes).joinToString(", ") + ") -> Unit>>? = null"
      }

      is RirRegistrable.Method -> {
        val paramCfnTypes: String =
          structMethodParamCfnTypes(struct, r.method, structs).joinToString(", ")
        val retCfnType: String = cfnType(abiReturnType(r.method.returnType, structs))
        "internal var ${r.method.name.toMethodCamelCase()}${r.method.bridgeSuffix()}Fn: " +
            "CPointer<CFunction<($paramCfnTypes) -> $retCfnType>>? = null"
      }

      is RirRegistrable.PropertyGetter -> {
        val receiverTypes: List<String> =
          structReceiverAbiArgs(struct, structs).map { cfnType(it.type) }
        val outTypes: List<String> =
          abiOutArgs(r.property.type, structs).map { cfnOutPointerType(it.type) }
        val retCfnType: String = cfnType(abiReturnType(r.property.type, structs))
        val paramCfnTypes: String = (receiverTypes + outTypes).joinToString(", ")
        "internal var ${r.property.name.toMethodCamelCase()}GetterFn: " +
            "CPointer<CFunction<($paramCfnTypes) -> $retCfnType>>? = null"
      }

      is RirRegistrable.PropertySetter -> error(
        "[nuget] struct property setters are out of scope (ADR-056 deferred)",
      )
    }
  }
  val params: String = registrables.joinToString(",\n  ") { r ->
    when (r) {
      is RirRegistrable.Ctor -> "ctor__${r.ctor.bridgeId()}Ptr: COpaquePointer?"
      is RirRegistrable.Method ->
        "${r.method.name.toMethodCamelCase()}${r.method.bridgeSuffix()}Ptr: COpaquePointer?"

      is RirRegistrable.PropertyGetter ->
        "${r.property.name.toMethodCamelCase()}GetterPtr: COpaquePointer?"

      is RirRegistrable.PropertySetter -> error(
        "[nuget] struct property setters are out of scope (ADR-056 deferred)",
      )
    }
  }
  val assignments: String = registrables.joinToString("\n  ") { r ->
    when (r) {
      is RirRegistrable.Ctor ->
        "${struct.name}Bindings.ctor__${r.ctor.bridgeId()}Fn = " +
            "requireNotNull(ctor__${r.ctor.bridgeId()}Ptr).reinterpret()"

      is RirRegistrable.Method -> {
        val name: String = r.method.name.toMethodCamelCase() + r.method.bridgeSuffix()
        "${struct.name}Bindings.${name}Fn = requireNotNull(${name}Ptr).reinterpret()"
      }

      is RirRegistrable.PropertyGetter -> {
        val name: String = r.property.name.toMethodCamelCase()
        "${struct.name}Bindings.${name}GetterFn = requireNotNull(${name}GetterPtr).reinterpret()"
      }

      is RirRegistrable.PropertySetter -> error(
        "[nuget] struct property setters are out of scope (ADR-056 deferred)",
      )
    }
  }
  val hash: Long = structContractHash(namespaceName, struct, registrables, structs)
  val outVarTypes: List<String> = buildList {
    // ADR-059: this struct's OWN out-pointer leaves, recursively flattened (see the Ctor branch
    // above for why RirStructType(namespaceName, struct.name) always resolves back to [struct]).
    addAll(abiOutArgs(RirStructType(namespaceName, struct.name), structs).map { cVarType(it.type) })
    registrables.forEach { r ->
      when (r) {
        is RirRegistrable.Method ->
          addAll(abiOutArgs(r.method.returnType, structs).map { cVarType(it.type) })

        is RirRegistrable.PropertyGetter ->
          addAll(abiOutArgs(r.property.type, structs).map { cVarType(it.type) })

        is RirRegistrable.Ctor, is RirRegistrable.PropertySetter -> Unit
      }
    }
  }.distinct().sorted()
  val outVarImports: String = outVarTypes.joinToString("\n") { "import kotlinx.cinterop.$it" }
  return """
    |@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    |
    |package $kotlinPkg
    |
    |import $INTERNAL_PKG.NugetRegistry
    |import kotlinx.cinterop.CFunction
    |import kotlinx.cinterop.COpaquePointer
    |import kotlinx.cinterop.CPointer
    |import kotlinx.cinterop.reinterpret
    |$outVarImports
    |import kotlin.experimental.ExperimentalNativeApi
    |
    |internal object ${struct.name}Bindings {
    |${fnVars.indented("  ")}
    |}
    |
    |@OptIn(ExperimentalNativeApi::class)
    |@CName("$exportName")
    |fun $exportName(
    |  slotCount: Int,
    |  contractHash: Long,
    |  $params,
    |) {
    |  NugetRegistry.checkContract(
    |    qualifiedType = "$namespaceName.${struct.name}",
    |    packageId = "$packageId",
    |    slotCount = slotCount,
    |    contractHash = contractHash,
    |    expectedSlots = ${registrables.size},
    |    expectedHash = ${hash}L,
    |  )
    |  $assignments
    |  NugetRegistry.record("$namespaceName.${struct.name}", ${registrables.size})
    |}
  """.trimMargin().trim()
}

private fun buildStructStubMethod(
  struct: RirStruct,
  method: RirMethod,
  packageId: String,
  namespaceName: String,
  structs: Map<RirTypeKey, RirStruct>,
  qualifiedTypeNames: Map<RirTypeKey, String>,
): String {
  val name: String = method.name.toMethodCamelCase()
  val fnVar: String = "${struct.name}Bindings.$name${method.bridgeSuffix()}Fn"
  val params: String = method.parameters.joinToString(", ") { p ->
    "${p.name}: ${declKotlinType(p.type, qualifiedTypeNames)}"
  }
  val retSuffix: String =
    if (method.returnType is RirVoidType) ""
    else ": ${declKotlinType(method.returnType, qualifiedTypeNames)}"
  val receiverArgs: List<String> = if (!method.isStatic) {
    struct.components.flatMap { c ->
      structArgConversions(c.type, c.name.toMethodCamelCase(), structs)
    }
  } else {
    emptyList()
  }
  val paramArgs: List<String> = method.parameters.flatMap { p ->
    structArgConversions(p.type, p.name, structs)
  }
  val receiverHasString: Boolean =
    !method.isStatic && struct.components.any { typeContains(it.type, structs, ::isStringRef) }
  val paramsHaveString: Boolean =
    method.parameters.any { p -> typeContains(p.type, structs, ::isStringRef) }
  val hasStringArg: Boolean = receiverHasString || paramsHaveString
  val failMsg: String =
    "NugetRegistry.notRegistered(\"$namespaceName.${struct.name}\", \"$packageId\")"
  val invokeArgsBase: List<String> = receiverArgs + paramArgs

  return when (val retType: RirTypeRef = method.returnType) {
    is RirVoidType -> {
      val invokeArgs: String = invokeArgsBase.joinToString(", ")
      val invokeCall: String =
        if (hasStringArg) "memScoped { fn.invoke($invokeArgs) }" else "fn.invoke($invokeArgs)"
      """
        |fun $name($params)$retSuffix {
        |  val fn = requireNotNull($fnVar) {
        |    $failMsg
        |  }
        |  $invokeCall
        |}
      """.trimMargin()
    }

    is RirStringType -> {
      val invokeArgs: String = invokeArgsBase.joinToString(", ")
      val invokeCall: String =
        if (hasStringArg) "memScoped { fn.invoke($invokeArgs) }" else "fn.invoke($invokeArgs)"
      val nullMsg: String =
        "${struct.name}.${method.name} returned null, expected a non-null string pointer"
      """
        |fun $name($params)$retSuffix {
        |  val fn = requireNotNull($fnVar) {
        |    $failMsg
        |  }
        |  val resultPtr = $invokeCall
        |    ?: error("$nullMsg")
        |  val result = resultPtr.reinterpret<ByteVar>().toKString()
        |  freeManagedString(resultPtr)
        |  return result
        |}
      """.trimMargin()
    }

    is RirEnumType -> {
      val invokeArgs: String = invokeArgsBase.joinToString(", ")
      val invokeCall: String =
        if (hasStringArg) "memScoped { fn.invoke($invokeArgs) }" else "fn.invoke($invokeArgs)"
      """
        |fun $name($params)$retSuffix {
        |  val fn = requireNotNull($fnVar) {
        |    $failMsg
        |  }
        |  return nugetEnumEntry(${retType.name}.entries, $invokeCall, "${retType.name}")
        |}
      """.trimMargin()
    }

    is RirStructType -> {
      val retStruct: RirStruct =
        requireNotNull(structs[RirTypeKey(retType.namespace, retType.name)]) {
          "[nuget] struct ${retType.namespace}.${retType.name} is referenced as a return type " +
              "but not declared in reverse-ir.json"
        }
      val outArgs: List<AbiArg> = abiOutArgs(retType, structs)
      val fullInvokeArgs: String =
        (invokeArgsBase + outArgs.map { "${it.name}.ptr" }).joinToString(", ")
      val read: ComponentRead = structComponentReads(retStruct, outArgs.iterator(), structs)
      buildString {
        appendLine("fun $name($params)$retSuffix = memScoped {")
        appendLine("  val fn = requireNotNull($fnVar) {")
        appendLine("    $failMsg")
        appendLine("  }")
        outArgs.forEach { arg ->
          appendLine("  val ${arg.name} = alloc<${cVarType(arg.type)}>()")
        }
        appendLine("  fn.invoke($fullInvokeArgs)")
        read.statements.forEach { appendLine("  $it") }
        appendLine("  ${read.expression}")
        append("}")
      }
    }

    is RirPrimitiveType -> {
      val invokeArgs: String = invokeArgsBase.joinToString(", ")
      val invokeCall: String =
        if (hasStringArg) "memScoped { fn.invoke($invokeArgs) }" else "fn.invoke($invokeArgs)"
      val returnExpr: String =
        if (retType.name == "char") "$invokeCall.toInt().toChar()" else invokeCall
      """
        |fun $name($params)$retSuffix {
        |  val fn = requireNotNull($fnVar) {
        |    $failMsg
        |  }
        |  return $returnExpr
        |}
      """.trimMargin()
    }

    is RirObjectHandleType -> error(
      "[nuget] handle returns on struct methods are out of scope (ADR-056 deferred)",
    )

    is RirInterfaceType -> error(
      "[nuget] interface returns on struct methods are out of scope (ADR-070 v1)",
    )

    is RirGenericInstanceType, is RirTypeParameterType -> error(
      "[nuget] generic instantiations/type parameters on struct methods are out of scope " +
          "(ADR-072 Decision 6: struct type arguments are excluded)",
    )
  }
}

private fun buildStructStubProperty(
  struct: RirStruct,
  property: RirProperty,
  packageId: String,
  namespaceName: String,
  structs: Map<RirTypeKey, RirStruct>,
  qualifiedTypeNames: Map<RirTypeKey, String>,
): String {
  val name: String = property.name.toMethodCamelCase()
  val getterFnVar: String = "${struct.name}Bindings.${name}GetterFn"
  val failMsg: String =
    "NugetRegistry.notRegistered(\"$namespaceName.${struct.name}\", \"$packageId\")"
  val declType: String = declKotlinType(property.type, qualifiedTypeNames)
  val receiverArgs: List<String> = struct.components.flatMap { c ->
    structArgConversions(c.type, c.name.toMethodCamelCase(), structs)
  }
  val hasStringReceiver: Boolean =
    struct.components.any { typeContains(it.type, structs, ::isStringRef) }

  val getterBlock: String = when (val type: RirTypeRef = property.type) {
    is RirVoidType -> error("[nuget] a property cannot have void type")
    is RirStringType -> {
      val invokeArgs: String = receiverArgs.joinToString(", ")
      val invokeCall: String =
        if (hasStringReceiver) "memScoped { fn.invoke($invokeArgs) }"
        else "fn.invoke($invokeArgs)"
      val nullMsg: String =
        "${struct.name}.${property.name} returned null, expected a non-null string pointer"
      """
        |get() {
        |  val fn = requireNotNull($getterFnVar) {
        |    $failMsg
        |  }
        |  val resultPtr = $invokeCall
        |    ?: error("$nullMsg")
        |  val result = resultPtr.reinterpret<ByteVar>().toKString()
        |  freeManagedString(resultPtr)
        |  return result
        |}
      """.trimMargin()
    }

    is RirEnumType -> {
      val invokeArgs: String = receiverArgs.joinToString(", ")
      val invokeCall: String =
        if (hasStringReceiver) "memScoped { fn.invoke($invokeArgs) }"
        else "fn.invoke($invokeArgs)"
      """
        |get() {
        |  val fn = requireNotNull($getterFnVar) {
        |    $failMsg
        |  }
        |  return nugetEnumEntry(${type.name}.entries, $invokeCall, "${type.name}")
        |}
      """.trimMargin()
    }

    is RirStructType -> {
      val retStruct: RirStruct =
        requireNotNull(structs[RirTypeKey(type.namespace, type.name)]) {
          "[nuget] struct ${type.namespace}.${type.name} is referenced as a property type but " +
              "not declared in reverse-ir.json"
        }
      val outArgs: List<AbiArg> = abiOutArgs(type, structs)
      val invokeArgs: String =
        (receiverArgs + outArgs.map { "${it.name}.ptr" }).joinToString(", ")
      val read: ComponentRead = structComponentReads(retStruct, outArgs.iterator(), structs)
      buildString {
        appendLine("get() {")
        appendLine("  val fn = requireNotNull($getterFnVar) {")
        appendLine("    $failMsg")
        appendLine("  }")
        appendLine("  return memScoped {")
        outArgs.forEach { arg ->
          appendLine("    val ${arg.name} = alloc<${cVarType(arg.type)}>()")
        }
        appendLine("    fn.invoke($invokeArgs)")
        read.statements.forEach { appendLine("    $it") }
        appendLine("    ${read.expression}")
        appendLine("  }")
        append("}")
      }
    }

    is RirPrimitiveType -> {
      val invokeArgs: String = receiverArgs.joinToString(", ")
      val invokeCall: String =
        if (hasStringReceiver) "memScoped { fn.invoke($invokeArgs) }"
        else "fn.invoke($invokeArgs)"
      val returnExpr: String =
        if (type.name == "char") "$invokeCall.toInt().toChar()" else invokeCall
      """
        |get() {
        |  val fn = requireNotNull($getterFnVar) {
        |    $failMsg
        |  }
        |  return $returnExpr
        |}
      """.trimMargin()
    }

    is RirObjectHandleType -> error(
      "[nuget] handle-typed computed properties on structs are out of scope (ADR-056 deferred)",
    )

    is RirInterfaceType -> error(
      "[nuget] interface-typed computed properties on structs are out of scope (ADR-070 v1)",
    )

    is RirGenericInstanceType, is RirTypeParameterType -> error(
      "[nuget] generic instantiations/type parameters on struct properties are out of scope " +
          "(ADR-072 Decision 6: struct type arguments are excluded)",
    )
  }

  return "val $name: $declType\n" + getterBlock.prependIndent("  ")
}

private fun kotlinType(type: RirTypeRef): String = when (type) {
  is RirVoidType -> "Unit"
  is RirStringType -> "String"
  is RirEnumType -> type.name
  // ADR-051: the Kotlin type name for a handle is simply the C# simple type name (e.g. Template).
  is RirObjectHandleType -> type.name
  // ADR-056: the Kotlin-facing type for a struct is its own generated data class's simple name —
  // the ABI-level expansion (abiArgs/abiOutArgs) is an implementation detail of the call site, not
  // of the declared Kotlin signature.
  is RirStructType -> type.name
  // ADR-070 Decision 1/3: the DECLARED Kotlin type for an interface reference is the pure
  // interface's own simple name (e.g. IFeedable) — a value arriving there is always wrapped in
  // the interface's OWN `{Name}Handle` implementation (buildStubMethod/buildStubProperty's
  // RirInterfaceType branch), which upcasts to this declared type.
  is RirInterfaceType -> type.name
  is RirPrimitiveType -> when (type.name) {
    "bool" -> "Boolean"
    "byte" -> "UByte"
    "short" -> "Short"
    "int" -> "Int"
    "long" -> "Long"
    "float" -> "Float"
    "double" -> "Double"
    "char" -> "Char"
    else -> error(
      "[nuget] Unknown primitive type name '${type.name}' — " +
          "update the v1 type-mapping table in NugetGenerateBindingsTask.kt"
    )
  }

  // ADR-072: a generic instantiation/type parameter never reaches this ORDINARY (non-generic)
  // type renderer: it is only ever bridgeable through the dedicated generic-class witness path
  // (genericAwareKotlinType), which substitutes it away before any ordinary rendering runs.
  is RirGenericInstanceType, is RirTypeParameterType -> error(
    "[nuget] a generic instantiation/type parameter must be substituted/rendered through the " +
        "dedicated ADR-072 generic-class path, never through kotlinType()"
  )
}

// ADR-053: the *declared* Kotlin type for a parameter, return, or property — kotlinType's bare
// name plus a trailing `?` when the RIR says this type reference is nullable (a decoded
// NullableAttribute(2), or an oblivious/un-annotated reference, which binds non-null per ADR-053
// Decision 1a). Kept distinct from kotlinType itself, which several call sites (e.g. wrapping a
// handle return in `${retType.name}(it)`, or `nugetEnumEntry(${retType.name}.entries, ...)`) use to
// get the bare simple name regardless of nullability.
private fun declKotlinType(type: RirTypeRef): String =
  kotlinType(type) + if (type.isNullable) "?" else ""

private fun declKotlinType(
  type: RirTypeRef,
  qualifiedTypeNames: Map<RirTypeKey, String>,
): String {
  // ADR-072 Decision 3, NugetGenerateBindingsTask.kt:1358 permissive site: a RirGenericInstanceType
  // reference also needs cross-package qualification: silently rendering an unqualified simple
  // name would be a latent name collision, the exact same hazard qualifiedTypeNames already closes
  // for handle/enum/struct/interface references.
  if (type is RirGenericInstanceType) {
    val key = RirTypeKey(type.namespace, type.name)
    val base: String = qualifiedTypeNames[key] ?: type.name.substringBefore('`')
    val args: String =
      type.typeArguments.joinToString(", ") { declKotlinType(it, qualifiedTypeNames) }
    return "$base<$args>" + if (type.isNullable) "?" else ""
  }
  if (type is RirTypeParameterType) return type.name

  val key: RirTypeKey? = when (type) {
    is RirObjectHandleType -> RirTypeKey(type.namespace, type.name)
    is RirEnumType -> RirTypeKey(type.namespace, type.name)
    is RirStructType -> RirTypeKey(type.namespace, type.name)
    is RirInterfaceType -> RirTypeKey(type.namespace, type.name)
    else -> null
  }
  val rendered: String = key?.let { qualifiedTypeNames[it] } ?: kotlinType(type)
  return rendered + if (type.isNullable) "?" else ""
}

private fun qualifiedTypeNames(
  file: RirFile,
  packageNameOverrides: Map<String, String>,
  namespaceAliases: Map<String, Map<String, String>>,
): Map<RirTypeKey, String> {
  val declarations: List<Pair<RirTypeKey, String>> = file.assemblies.flatMap { assembly ->
    assembly.namespaces.flatMap { namespace ->
      val pkg: String = kotlinPackage(
        assembly.packageId, namespace.name, packageNameOverrides, namespaceAliases,
      )
      namespace.types.map { type -> RirTypeKey(namespace.name, type.name) to pkg }
    }
  }
  val duplicateNames: Set<String> = declarations.groupBy { it.first.name }
    .filterValues { it.size > 1 }
    .keys
  return declarations.filter { it.first.name in duplicateNames }
    .associate { (key, pkg) -> key to "$pkg.${key.name}" }
}

private fun cfnType(type: RirTypeRef): String = when (type) {
  is RirVoidType -> "Unit"
  is RirStringType -> "COpaquePointer?"
  is RirEnumType -> "Int"
  // ADR-051: handles cross the ABI as IntPtr ↔ COpaquePointer? (same slot as strings).
  is RirObjectHandleType -> "COpaquePointer?"
  // ADR-070 Decision 1: wire-identical to a handle (GCHandle.ToIntPtr / IntPtr.Zero).
  is RirInterfaceType -> "COpaquePointer?"
  // ADR-056: a struct never reaches cfnType directly — abiArgs/abiOutArgs expand it into scalar
  // components before any call site asks for a CFunction type. Struct-typed constructor
  // parameters and properties are not yet supported (v1 scope: static/instance methods only).
  is RirStructType -> error(
    "[nuget] struct ${type.namespace}.${type.name} must be expanded via abiArgs/abiOutArgs " +
        "before reaching cfnType — struct-typed constructor parameters and properties are not " +
        "yet supported."
  )

  is RirPrimitiveType -> when (type.name) {
    "bool" -> "Boolean"
    "byte" -> "UByte"
    "short" -> "Short"
    "int" -> "Int"
    "long" -> "Long"
    "float" -> "Float"
    "double" -> "Double"
    "char" -> "UShort"
    else -> error(
      "[nuget] Unknown primitive type name '${type.name}' — " +
          "update the v1 type-mapping table in NugetGenerateBindingsTask.kt"
    )
  }

  // ADR-072 Decision 1: wire-identical to a handle. A generic instantiation crosses the ABI as
  // an erased GCHandle IntPtr regardless of its type argument(s), exactly like RirObjectHandleType
  // above (CS8894 forbids a closed generic itself on an [UnmanagedCallersOnly] signature, but its
  // GCHandle wrapper is ordinary).
  is RirGenericInstanceType -> "COpaquePointer?"
  is RirTypeParameterType -> error(
    "[nuget] a bare type parameter must be substituted to a concrete type before reaching " +
        "cfnType()"
  )
}

// ADR-056: the kotlinx.cinterop CVariable subtype an out-pointer component allocates via
// `alloc<...>()`, per the wire table (component type -> Kotlin CFunction out-ptr).
private fun cVarType(type: RirTypeRef): String = when (type) {
  is RirPrimitiveType -> when (type.name) {
    "bool" -> "UByteVar"
    "byte" -> "UByteVar"
    "short" -> "ShortVar"
    "int" -> "IntVar"
    "long" -> "LongVar"
    "float" -> "FloatVar"
    "double" -> "DoubleVar"
    "char" -> "UShortVar"
    else -> error(
      "[nuget] Unknown primitive type name '${type.name}' — " +
          "update the v1 struct out-pointer mapping table in NugetGenerateBindingsTask.kt"
    )
  }

  is RirStringType -> "COpaquePointerVar"
  is RirEnumType -> "IntVar"
  is RirObjectHandleType -> "COpaquePointerVar"
  is RirInterfaceType -> "COpaquePointerVar"
  is RirVoidType -> error("[nuget] void cannot be a struct out-pointer component")
  is RirStructType -> error("[nuget] nested struct components are not supported in v1 (ADR-056)")
  is RirGenericInstanceType, is RirTypeParameterType -> error(
    "[nuget] generic instantiations/type parameters are not supported as struct components " +
        "(ADR-072 Decision 6: struct type arguments are excluded)"
  )
}

private fun cfnOutPointerType(type: RirTypeRef): String = "CPointer<${cVarType(type)}>"

// ADR-056: zero or more statements that must run BEFORE [expression] is evaluated (only a string
// component needs any — reinterpret+toKString+free is not a single expression), and the final
// Kotlin expression yielding the component's Kotlin-level value.
private data class ComponentRead(val statements: List<String>, val expression: String)

// ADR-056: converts a struct out-pointer allocation's raw `.value` read into the Kotlin-level
// component value — the RETURN-side mirror of argConversion (which converts a Kotlin value INTO
// its wire representation). Routes each component type through the EXACT SAME conversion the
// equivalent top-level return already uses (reinterpret+toKString+free for a non-null string,
// nugetEnumEntry for an enum, toInt().toChar() for char, a UByte->Boolean comparison for bool,
// untouched for int/long/float/double) — a component must never bypass the conversion its own
// type already requires on the return side, mirroring argConversion's role on the parameter side.
// Shared by buildStubMethod's struct-return branch and buildStubProperty's struct-getter branch.
private fun componentRead(type: RirTypeRef, arg: AbiArg): ComponentRead {
  val raw = "${arg.name}.value"
  return when (type) {
    is RirPrimitiveType -> when (type.name) {
      // UByteVar.value is UByte (0 or 1) — see cVarType: there is no BooleanVar in
      // kotlinx.cinterop, so the out-pointer slot is UByte and must be narrowed back to Boolean
      // here, exactly mirroring the C# thunk's `result ? (byte)1 : (byte)0` write.
      "bool" -> ComponentRead(emptyList(), "$raw.toInt() != 0")
      // Mirrors the existing top-level primitive-return branch's `$invokeCall.toInt().toChar()`.
      "char" -> ComponentRead(emptyList(), "$raw.toInt().toChar()")
      else -> ComponentRead(emptyList(), raw)
    }
    // Mirrors the existing top-level enum-return branch's bounds-checked nugetEnumEntry lookup —
    // a C# enum is not a closed set, so the ordinal is validated here exactly as it would be for
    // a directly-returned enum.
    is RirEnumType -> ComponentRead(
      emptyList(),
      "nugetEnumEntry(${type.name}.entries, $raw, \"${type.name}\")",
    )
    // Mirrors the existing top-level string-return branches (nullable: null-propagating; non-null:
    // fail-fast requireNotNull) — reinterpret+toKString+freeManagedString either way.
    is RirStringType -> {
      val resultVar = "${arg.name}Result"
      if (type.nullable) {
        ComponentRead(
          statements = listOf(
            "val $resultVar: String? = $raw?.let { p ->",
            "  val s = p.reinterpret<ByteVar>().toKString()",
            "  freeManagedString(p)",
            "  s",
            "}",
          ),
          expression = resultVar,
        )
      } else {
        val ptrVar = "${arg.name}Ptr"
        ComponentRead(
          statements = listOf(
            "val $ptrVar = requireNotNull($raw) { " +
                "\"a struct string component returned null unexpectedly\" }",
            "val $resultVar = $ptrVar.reinterpret<ByteVar>().toKString()",
            "freeManagedString($ptrVar)",
          ),
          expression = resultVar,
        )
      }
    }

    is RirObjectHandleType -> error(
      "[nuget] handle-typed struct components are not supported (ADR-056/059 v1 component " +
          "vocabulary is primitives, string, bound enums, and bound structs only)"
    )

    is RirInterfaceType -> error(
      "[nuget] interface-typed struct components are not supported (ADR-070 v1)",
    )

    is RirVoidType -> error("[nuget] void cannot be a struct component")
    // ADR-059: componentRead is only ever called on a LEAF type — structComponentReads below
    // recurses through a struct-typed component itself, before any of ITS OWN components reach
    // this function. Reaching this branch means a RirStructType slipped past that recursion
    // (a caller building a ComponentRead directly off struct.components instead of through
    // structComponentReads), which is exactly the bug this guard exists to catch.
    is RirStructType -> error(
      "[nuget] struct ${type.namespace}.${type.name} must be expanded via structComponentReads " +
          "before reaching componentRead — componentRead only accepts leaf (scalar) types."
    )

    is RirGenericInstanceType, is RirTypeParameterType -> error(
      "[nuget] generic instantiations/type parameters are not supported as struct components " +
          "(ADR-072 Decision 6: struct type arguments are excluded)"
    )
  }
}

// ADR-059 Decision 1a/3a's "componentRead becomes recursive" and returns `(statements,
// expression)` as it already did: reassembles a WHOLE struct value from its flattened out-pointer
// leaves ([outArgs], an iterator so nested recursive calls consume from the SAME shared position —
// outArgs was built by the SAME depth-first pre-order walk in abiOutArgs/structReceiverAbiArgs, so
// walking [struct]'s own component tree here in that same order pairs each leaf with its own
// out-pointer correctly). A struct-typed component's own [ComponentRead] is itself the result of a
// nested structComponentReads call — its statements are hoisted (concatenated in DFS order, so a
// string leaf's toKString()/freeManagedString locals are always in scope before the final nested
// constructor expression is evaluated) and its expression becomes one of the outer struct's own
// constructor arguments. The Kotlin reassembly is shape-agnostic (ADR-059): the generated data
// class's primary constructor is always positional, regardless of which C# shape (Shape A
// constructor or Shape B object initializer) reconstructed the value on the C# side.
// Unwrapped form: [struct]'s own top-level component statements/expressions, WITHOUT the
// `${struct.name}(...)` wrapper structComponentReads (below) adds. Used directly by
// structConstructorHelpers, whose final expression wraps in
// `${struct.name}ConstructorComponents(...)` — a different Kotlin type from [struct] itself — so
// it needs the bare per-component expressions, not a `${struct.name}(...)` call it would then have
// to unwrap again.
private fun structComponentExprs(
  struct: RirStruct,
  outArgs: Iterator<AbiArg>,
  structs: Map<RirTypeKey, RirStruct>,
): Pair<List<String>, List<String>> {
  val statements: MutableList<String> = mutableListOf()
  val expressions: MutableList<String> = mutableListOf()
  struct.components.forEach { c ->
    val nested: RirStruct? =
      (c.type as? RirStructType)?.let { structs[RirTypeKey(it.namespace, it.name)] }
    val read: ComponentRead =
      if (nested == null) componentRead(c.type, outArgs.next())
      else structComponentReads(nested, outArgs, structs)
    statements += read.statements
    expressions += read.expression
  }
  return statements to expressions
}

// ADR-059 Decision 1a/3a's "componentRead becomes recursive" and returns `(statements,
// expression)` as it already did: reassembles a WHOLE struct value from its flattened out-pointer
// leaves ([outArgs], an iterator so nested recursive calls consume from the SAME shared position —
// outArgs was built by the SAME depth-first pre-order walk in abiOutArgs/structReceiverAbiArgs, so
// walking [struct]'s own component tree here in that same order pairs each leaf with its own
// out-pointer correctly). A struct-typed component's own [ComponentRead] is itself the result of a
// nested structComponentReads call — its statements are hoisted (concatenated in DFS order, so a
// string leaf's toKString()/freeManagedString locals are always in scope before the final nested
// constructor expression is evaluated) and its expression becomes one of the outer struct's own
// constructor arguments. The Kotlin reassembly is shape-agnostic (ADR-059): the generated data
// class's primary constructor is always positional, regardless of which C# shape (Shape A
// constructor or Shape B object initializer) reconstructed the value on the C# side.
private fun structComponentReads(
  struct: RirStruct,
  outArgs: Iterator<AbiArg>,
  structs: Map<RirTypeKey, RirStruct>,
): ComponentRead {
  val (statements: List<String>, expressions: List<String>) =
    structComponentExprs(struct, outArgs, structs)
  return ComponentRead(statements, "${struct.name}(${expressions.joinToString(", ")})")
}

// Phase 9 (ROADMAP line 151): the ordered list of CFunction parameter cfn-types for a method's
// function-pointer TYPE declaration — instance methods gain a leading `COpaquePointer?` receiver
// slot, exactly mirroring the receiver argument buildStubMethod prepends at the call site
// (`handle.require("Type")`) and the leading `IntPtr selfHandle` NugetGenerateShimsTask.kt's
// thunk signature gains. Single shared source for this list (rather than re-deriving it at each
// call site) so the *Bindings.kt CFunction type and the Template.kt call site can never drift out
// of arity — the same anti-drift reasoning as bridgeableRegistrables' shared ordering.
//
// ADR-056: parameters and return are expanded through the shared abiArgs/abiOutArgs functions —
// a struct-typed parameter contributes one cfn-type per component, and a struct-typed return
// appends one out-pointer cfn-type per component after the real parameters. Both are no-ops for a
// method with no struct in its signature.
private fun methodParamCfnTypes(
  method: RirMethod,
  structs: Map<RirTypeKey, RirStruct>,
): List<String> {
  val receiverCfnType: String? = if (!method.isStatic) "COpaquePointer?" else null
  val inCfnTypes: List<String> = abiArgs(method.parameters, structs).map { cfnType(it.type) }
  val outCfnTypes: List<String> =
    abiOutArgs(method.returnType, structs).map { cfnOutPointerType(it.type) }
  return listOfNotNull(receiverCfnType) + inCfnTypes + outCfnTypes
}

// Corequisite fix (surfaced by an ADR-053 fixture, unrelated to nullability): the fn-pointer vars
// below used to be unqualified top-level properties in each {Type}Bindings.kt. Top-level
// declarations are package-scoped, not file-scoped, so two bound classes sharing a namespace (or
// two types sharing a method name, e.g. Find on both NicknameBook and LegacyNicknameBook) collide
// at compile time. Every prior reverse fixture had exactly one bound class per namespace, which is
// why nothing caught this — a real NuGet namespace holds many types. Wrapping each type's vars in
// its own `internal object {Type}Bindings` makes the collision structurally impossible (two types
// cannot share a name within one namespace) with no ABI change: the nuget_{ns}_{type}_register
// export name and its one-COpaquePointer-per-method parameter order are untouched.
private fun bindingsObjectName(typeName: String): String = "${typeName}Bindings"

private fun bindingsFileContent(
  kotlinPkg: String,
  cls: RirClass,
  registrables: List<RirRegistrable>,
  exportName: String,
  packageId: String,
  namespaceName: String,
  structs: Map<RirTypeKey, RirStruct>,
): String {
  val objectName: String = bindingsObjectName(cls.name)
  val qualifiedType: String = "$namespaceName.${cls.name}"
  val hasString: Boolean = registrables.any { r ->
    when (r) {
      is RirRegistrable.Ctor -> r.ctor.parameters.any { it.type is RirStringType }
      is RirRegistrable.Method -> r.method.returnType is RirStringType ||
          r.method.parameters.any { it.type is RirStringType }

      is RirRegistrable.PropertyGetter -> r.property.type is RirStringType
      is RirRegistrable.PropertySetter -> r.property.type is RirStringType
    }
  }

  // ADR-056: every distinct kotlinx.cinterop CVariable subtype a struct-typed return's
  // out-pointer allocations need across this type's registrables (e.g. "IntVar" for Point's two
  // int components) — empty (and therefore no extra imports) when no registrable returns a
  // struct.
  val structOutVarTypes: List<String> = registrables.flatMap { r ->
    when (r) {
      is RirRegistrable.Method -> abiOutArgs(r.method.returnType, structs)
      is RirRegistrable.PropertyGetter -> abiOutArgs(r.property.type, structs)
      is RirRegistrable.Ctor, is RirRegistrable.PropertySetter -> emptyList()
    }
  }.map { cVarType(it.type) }.distinct().sorted()

  val imports: List<String> = buildList {
    if (hasString) {
      add("import $INTERNAL_PKG.freeManagedString")
      add("import kotlinx.cinterop.ByteVar")
    }
    add("import $INTERNAL_PKG.NugetRegistry")
    add("import kotlinx.cinterop.CFunction")
    add("import kotlinx.cinterop.COpaquePointer")
    add("import kotlinx.cinterop.CPointer")
    add("import kotlinx.cinterop.reinterpret")
    add("import kotlin.experimental.ExperimentalNativeApi")
    structOutVarTypes.forEach { add("import kotlinx.cinterop.$it") }
  }

  // ADR-052: rendered directly off the shared bridgeableRegistrables() ordering — constructor
  // pointer first (if any), then method pointers — so the register signature/body below can
  // never drift out of sync with the C# ModuleInitializer's pointer-argument order
  // (NugetGenerateShimsTask consumes the exact same ordered list).
  val fnVars: String = registrables.joinToString("\n\n") { r ->
    when (r) {
      is RirRegistrable.Ctor -> {
        val paramCfnTypes: String = abiArgs(r.ctor.parameters, structs)
          .joinToString(", ") { cfnType(it.type) }
        "@Suppress(\"NOTHING_TO_INLINE\")\n" +
            "internal var ctor${r.ctor.bridgeSuffix()}Fn: " +
            "CPointer<CFunction<($paramCfnTypes) -> COpaquePointer?>>? = null"
      }

      is RirRegistrable.Method -> {
        val paramCfnTypes: String = methodParamCfnTypes(r.method, structs).joinToString(", ")
        val retCfnType: String = cfnType(abiReturnType(r.method.returnType, structs))
        "@Suppress(\"NOTHING_TO_INLINE\")\n" +
            "internal var ${r.method.name.toMethodCamelCase()}${r.method.bridgeSuffix()}Fn: " +
            "CPointer<CFunction<($paramCfnTypes) -> $retCfnType>>? = null"
      }
      // Phase 9 (ROADMAP line 151): a getter thunk takes the receiver only and returns the
      // property's value; a setter thunk takes the receiver plus the new value and returns Unit.
      // ADR-056: both are expanded through the shared abiArgs/abiOutArgs/abiReturnType functions
      // — a struct-typed property contributes one out-pointer cfn-type per component to the
      // getter (return becomes Unit), or one in cfn-type per component to the setter — a no-op
      // for a property with no struct in its type.
      is RirRegistrable.PropertyGetter -> {
        val receiverCfnType: String? = if (r.property.isStatic) null else "COpaquePointer?"
        val outCfnTypes: List<String> =
          abiOutArgs(r.property.type, structs).map { cfnOutPointerType(it.type) }
        val retCfnType: String = cfnType(abiReturnType(r.property.type, structs))
        val paramCfnTypes: String =
          (listOfNotNull(receiverCfnType) + outCfnTypes).joinToString(", ")
        "@Suppress(\"NOTHING_TO_INLINE\")\n" +
            "internal var ${r.property.name.toMethodCamelCase()}GetterFn: " +
            "CPointer<CFunction<($paramCfnTypes) -> $retCfnType>>? = null"
      }

      is RirRegistrable.PropertySetter -> {
        val receiverCfnType: String? = if (r.property.isStatic) null else "COpaquePointer?"
        val valueParam = RirParameter(name = "value", type = r.property.type)
        val inCfnTypes: List<String> = abiArgs(listOf(valueParam), structs).map { cfnType(it.type) }
        val paramCfnTypes: String = (listOfNotNull(receiverCfnType) + inCfnTypes).joinToString(", ")
        "@Suppress(\"NOTHING_TO_INLINE\")\n" +
            "internal var ${r.property.name.toMethodCamelCase()}SetterFn: " +
            "CPointer<CFunction<($paramCfnTypes) -> Unit>>? = null"
      }
    }
  }

  // ADR-054: pointer parameters are nullable (a stale caller passing fewer args than declared
  // leaves the tail argument registers unpopulated — see checkContract below, which reads only
  // slotCount/contractHash and returns before either storing or dereferencing any pointer here).
  val regParams: String = registrables.joinToString(",\n  ") { r ->
    when (r) {
      is RirRegistrable.Ctor -> "ctor${r.ctor.bridgeSuffix()}Ptr: COpaquePointer?"
      is RirRegistrable.Method ->
        "${r.method.name.toMethodCamelCase()}${r.method.bridgeSuffix()}Ptr: COpaquePointer?"

      is RirRegistrable.PropertyGetter ->
        "${r.property.name.toMethodCamelCase()}GetterPtr: COpaquePointer?"

      is RirRegistrable.PropertySetter ->
        "${r.property.name.toMethodCamelCase()}SetterPtr: COpaquePointer?"
    }
  }

  // Each pointer is requireNotNull'd only AFTER checkContract has already agreed the counts/hash
  // match — a null here past that point is a generator bug, not a legitimate mismatch, hence the
  // fail-fast rather than a silent skip.
  val regBody: String = registrables.joinToString("\n  ") { r ->
    when (r) {
      is RirRegistrable.Ctor -> "$objectName.ctor${r.ctor.bridgeSuffix()}Fn = " +
          "requireNotNull(ctor${r.ctor.bridgeSuffix()}Ptr) " +
          "{ \"$exportName passed a null ctor thunk pointer.\" }.reinterpret()"

      is RirRegistrable.Method -> {
        val name: String = r.method.name.toMethodCamelCase()
        val internalName: String = name + r.method.bridgeSuffix()
        "$objectName.${internalName}Fn = requireNotNull(${internalName}Ptr) " +
            "{ \"$exportName passed a null $name thunk pointer.\" }.reinterpret()"
      }

      is RirRegistrable.PropertyGetter -> {
        val name: String = r.property.name.toMethodCamelCase()
        "$objectName.${name}GetterFn = requireNotNull(${name}GetterPtr) " +
            "{ \"$exportName passed a null $name getter thunk pointer.\" }.reinterpret()"
      }

      is RirRegistrable.PropertySetter -> {
        val name: String = r.property.name.toMethodCamelCase()
        "$objectName.${name}SetterFn = requireNotNull(${name}SetterPtr) " +
            "{ \"$exportName passed a null $name setter thunk pointer.\" }.reinterpret()"
      }
    }
  }

  val expectedSlots: Int = registrables.size
  val expectedHash: Long = contractHash(cls, registrables, structs)

  return """
    |@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    |
    |package $kotlinPkg
    |
    |${imports.joinToString("\n")}
    |
    |// Generated: registration machinery for $packageId.${cls.name}
    |// Do not call these functions from Kotlin code directly. Vars live inside $objectName (not as
    |// unqualified top-level properties) because top-level declarations are package-scoped: two
    |// bound classes sharing a namespace, or two types sharing a method name, would otherwise emit
    |// the same top-level var twice and fail to compile.
    |internal object $objectName {
    |${fnVars.indented("  ")}
    |}
    |
    |@OptIn(ExperimentalNativeApi::class)
    |// Must stay public (not internal): @CName is what makes Kotlin/Native emit this as a native
    |// C export, and internal visibility suppresses that native export entirely. The
    |// forward-direction (KSP) exporter is the one that must not re-wrap this function into
    |// another C-ABI export — it does so by skipping every @CName-annotated top-level function
    |// (see `hasCNameAnnotation()` in nuget-processor's NugetProcessor.kt), not by hiding this
    |// function from Kotlin visibility.
    |@CName("$exportName")
    |fun $exportName(
    |  slotCount: Int,
    |  contractHash: Long,
    |  $regParams,
    |) {
    |  // ADR-054: refuses to store any pointer if the caller's counts disagree with this build's —
    |  // a stale C# shim (fewer args than declared here) is read-only-safe up to this point: only
    |  // slotCount/contractHash (the two leading scalars) are read before this call decides whether
    |  // to proceed.
    |  NugetRegistry.checkContract(
    |    qualifiedType = "$qualifiedType",
    |    packageId = "$packageId",
    |    slotCount = slotCount,
    |    contractHash = contractHash,
    |    expectedSlots = $expectedSlots,
    |    expectedHash = ${expectedHash}L,
    |  )
    |  $regBody
    |  NugetRegistry.record("$qualifiedType", $expectedSlots)
    |}
  """.trimMargin().trim()
}

private fun stubFileContent(
  kotlinPkg: String,
  cls: RirClass,
  staticMethods: List<RirMethod>,
  instanceMethods: List<RirMethod>,
  ctors: List<RirConstructor>,
  instancePropertyGetters: List<RirProperty>,
  staticPropertyGetters: List<RirProperty>,
  propertySetterNames: Set<String>,
  packageId: String,
  namespaceName: String,
  enumPkgs: Map<RirTypeKey, String>,
  structPkgs: Map<RirTypeKey, String>,
  handlePkgs: Map<RirTypeKey, String>,
  structs: Map<RirTypeKey, RirStruct>,
  qualifiedTypeNames: Map<RirTypeKey, String>,
  interfaceSupertypeNames: List<String> = emptyList(),
  overrideMethodNames: Set<String> = emptySet(),
  overridePropertyNames: Set<String> = emptySet(),
  genericDefs: Map<RirTypeKey, RirClass> = emptyMap(),
): String {
  val hasHandle: Boolean = staticMethods.any { method ->
    isHandleLike(method.returnType) || method.parameters.any { p -> isHandleLike(p.type) }
  }

  // ADR-051: a non-static class that appears as a handle type in its own bridgeable methods
  // renders as a wrapper class. ADR-052 extends this: a non-static class with a public instance
  // constructor is always a wrapper too — the constructor's implicit return is the class's own
  // handle type, even if no *method* on the class happens to reference it. Phase 9 (line 151)
  // extends this further: any instance method or instance property also forces the wrapper shape
  // — both require the receiver `handle` field regardless of whether a handle TYPE appears
  // anywhere. Classes with none of these keep the ADR-048 `object` shape.
  val hasInstanceMember: Boolean =
    ctors.isNotEmpty() || instanceMethods.isNotEmpty() || instancePropertyGetters.isNotEmpty()
  val isClassWrapper: Boolean = !cls.isStatic && (hasHandle || hasInstanceMember)
  if (isClassWrapper) {
    return classWrapperContent(
      kotlinPkg, cls, staticMethods, instanceMethods, ctors,
      instancePropertyGetters, staticPropertyGetters, propertySetterNames, packageId,
      namespaceName, enumPkgs, structPkgs, handlePkgs, structs,
      qualifiedTypeNames, interfaceSupertypeNames, overrideMethodNames, overridePropertyNames,
      genericDefs,
    )
  }

  // object shape (ADR-048, statics only — a non-wrapper class never has ctors/instance
  // methods/properties, per isClassWrapper above).
  val hasStringReturn: Boolean =
    staticMethods.any { typeContains(it.returnType, structs, ::isStringRef) } ||
        staticPropertyGetters.any { typeContains(it.type, structs, ::isStringRef) }
  val hasStringParam: Boolean = staticMethods.any { m ->
    m.parameters.any { p -> typeContains(p.type, structs, ::isStringRef) }
  } || staticPropertyGetters.any {
    typeContains(it.type, structs, ::isStringRef) && it.name in propertySetterNames
  }

  // Always required: every stub method body calls `fn.invoke(...)` on a
  // `CPointer<CFunction<...>>?` — the `invoke` operator extension is declared in kotlinx.cinterop
  // and, being an extension function, is not resolved without an explicit import (unqualified
  // calls otherwise resolve to an unrelated same-named `invoke`, e.g. kotlin.DeepRecursiveFunction,
  // producing confusing "cannot infer type parameter" errors instead of a missing-import error).
  val imports: MutableList<String> = mutableListOf("import kotlinx.cinterop.invoke")
  // ADR-072: a static method/property returning a bound-class-handle, interface, or generic
  // instantiation declares `val ptr: COpaquePointer? = ...` (buildStubMethod's
  // RirObjectHandleType/RirInterfaceType/RirGenericInstanceType branches). This object-shape
  // file previously had NO import for it at all, a latent gap never exercised before this
  // feature's `Boxes.OfFerret`/`Boxes.OfMood` (returning `Box<Ferret>`/`Box<CatMood>`) forced the
  // first static method on a static class to return one of these shapes.
  val hasHandleReturn: Boolean = staticMethods.any { isHandleLike(it.returnType) } ||
      staticMethods.any { it.returnType is RirGenericInstanceType } ||
      staticPropertyGetters.any { isHandleLike(it.type) } ||
      staticPropertyGetters.any { it.type is RirGenericInstanceType }
  if (hasHandleReturn) imports.add("import kotlinx.cinterop.COpaquePointer")
  // ADR-072: a generic-instance return additionally wraps the raw pointer in NugetObjectHandle
  // before handing it to the generic class's own constructor (see buildStubMethod's
  // RirGenericInstanceType branch), needed here, never for a plain RirObjectHandleType return,
  // since an ordinary bound class's public constructor takes the raw COpaquePointer itself.
  val hasGenericInstanceReturn: Boolean =
    staticMethods.any { it.returnType is RirGenericInstanceType } ||
        staticPropertyGetters.any { it.type is RirGenericInstanceType }
  if (hasGenericInstanceReturn) imports.add("import $INTERNAL_PKG.NugetObjectHandle")
  if (hasStringReturn) {
    imports.add("import $INTERNAL_PKG.freeManagedString")
    imports.add("import kotlinx.cinterop.ByteVar")
    imports.add("import kotlinx.cinterop.reinterpret")
    imports.add("import kotlinx.cinterop.toKString")
  }
  if (hasStringParam) {
    imports.add("import kotlinx.cinterop.cstr")
    imports.add("import kotlinx.cinterop.memScoped")
    imports.add("import kotlinx.cinterop.ptr")
  }
  // ADR-056: a struct-typed return needs memScoped (alloc<T>() requires a MemScope receiver),
  // one alloc<...>()/.ptr/.value per component, and an import for each distinct CVariable subtype
  // those allocations use — a no-op (empty structOutVarTypes) when no static method/property
  // returns a struct. `value` on a CPrimitiveVar (e.g. IntVar) is an EXTENSION property, not a
  // member — verified against a real Kotlin/Native compile: `outX.value` is "Unresolved
  // reference" without `import kotlinx.cinterop.value`, even though `outX.ptr` (also an
  // extension) resolves fine off the `IntVar` import alone, because IDE/compiler member-lookup
  // for an unimported extension fails silently rather than suggesting the import.
  val hasStructReturn: Boolean =
    staticMethods.any { abiOutArgs(it.returnType, structs).isNotEmpty() } ||
        staticPropertyGetters.any { abiOutArgs(it.type, structs).isNotEmpty() }
  if (hasStructReturn) {
    if ("import kotlinx.cinterop.memScoped" !in imports) {
      imports.add("import kotlinx.cinterop.memScoped")
    }
    imports.add("import kotlinx.cinterop.alloc")
    imports.add("import kotlinx.cinterop.value")
    if ("import kotlinx.cinterop.ptr" !in imports) imports.add("import kotlinx.cinterop.ptr")
    val structOutVarTypes: List<String> = (
        staticMethods.flatMap { abiOutArgs(it.returnType, structs) } +
            staticPropertyGetters.flatMap { abiOutArgs(it.type, structs) }
        ).map { cVarType(it.type) }.distinct().sorted()
    structOutVarTypes.forEach { imports.add("import kotlinx.cinterop.$it") }
  }
  val hasEnumReturn: Boolean =
    staticMethods.any { typeContains(it.returnType, structs, ::isEnumRef) } ||
        staticPropertyGetters.any { typeContains(it.type, structs, ::isEnumRef) }
  if (hasEnumReturn) imports.add("import $INTERNAL_PKG.nugetEnumEntry")
  imports.addAll(
    enumImports(
      referencedEnumTypes(staticMethods, ctors, staticPropertyGetters), enumPkgs, kotlinPkg,
    )
  )
  // ADR-056: a struct-typed method/property in this stub may reference an enum declared in a
  // different Kotlin package than the stub itself — the struct's own component list is invisible
  // to referencedEnumTypes (which only looks at method/ctor/property TOP-LEVEL types), so gather
  // those separately.
  val structEnumTypes: List<RirEnumType> = (
      staticMethods.flatMap { method ->
        structEnumComponents(method.returnType, structs) +
            method.parameters.flatMap { p -> structEnumComponents(p.type, structs) }
      } +
          staticPropertyGetters.flatMap { structEnumComponents(it.type, structs) }
      ).distinct()
  imports.addAll(enumImports(structEnumTypes, enumPkgs, kotlinPkg))

  // ADR-059: a static method/property whose RETURN is (or nests) a struct reassembles it with a
  // bare `TypeName(...)` constructor call per struct in the tree (structComponentReads) — each one
  // needs an import when it lives in a different Kotlin package than this stub. A struct-typed
  // PARAMETER needs none here: the CALLER constructs it, this file only decomposes it via property
  // access, which needs no type import.
  val structReturnTypes: List<RirStructType> = (
      staticMethods.flatMap { structTypesInTree(it.returnType, structs) } +
          staticPropertyGetters.flatMap { structTypesInTree(it.type, structs) }
      ).distinct()
  imports.addAll(structImports(structReturnTypes, structPkgs, kotlinPkg))

  // ADR-072: a static method/ctor/property whose signature names another bound class as a handle
  // type (e.g. `Boxes.OfFerret(Ferret ferret)`, `Ferret` declared in a different Kotlin package
  // than `Boxes`) needs the same `import` coverage enumImports already gives enum references;
  // this was missing before this feature (see handleImports' KDoc).
  imports.addAll(
    handleImports(
      referencedHandleTypes(staticMethods, ctors, staticPropertyGetters), handlePkgs, kotlinPkg,
    )
  )

  // ADR-054: NugetRegistry.notRegistered(...) is called at runtime (not baked as a constant
  // string) so the "N of M registrations fired" message reflects what actually landed by the time
  // a bridge call fails, rather than the fixed generation-time text this replaces.
  imports.add("import $INTERNAL_PKG.NugetRegistry")

  val methods: String = staticMethods.joinToString("\n\n") {
    buildStubMethod(
      cls, it, packageId, namespaceName, structs, qualifiedTypeNames, genericDefs = genericDefs,
    )
  }
  val properties: String = staticPropertyGetters.joinToString("\n\n") { property ->
    buildStubProperty(
      cls, property, hasSetter = property.name in propertySetterNames, packageId, namespaceName,
      structs,
      qualifiedTypeNames,
      genericDefs = genericDefs,
    )
  }

  return buildString {
    appendLine("@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)")
    appendLine()
    appendLine("package $kotlinPkg")
    appendLine()
    if (imports.isNotEmpty()) {
      imports.forEach { appendLine(it) }
      appendLine()
    }
    appendLine("// Generated: Kotlin-idiomatic stubs for $packageId.${cls.name}")
    appendLine()
    // internal (not public): consumable from anywhere else in this same Gradle module (e.g. the
    // hand-authored test-library sources that call it), but invisible to the forward-direction
    // (KSP) exporter's public-API scan — this reverse-bound API must not be re-exported forward
    // into the packed nupkg's own Interop.cs (see the matching note on the Bindings.kt file).
    appendLine("internal object ${cls.name} {")
    appendLine()
    appendLine(methods.indented("  "))
    if (methods.isNotEmpty() && properties.isNotEmpty()) appendLine()
    if (properties.isNotEmpty()) appendLine(properties.indented("  "))
    append("}")
  }
}

// ADR-051: renders a non-static class as a full wrapper class with:
//   - internal constructor taking COpaquePointer
//   - internal NugetObjectHandle field
//   - createCleaner for automatic GCHandle release on GC
//   - AutoCloseable / close() for deterministic release
//   - companion object containing the bridged static methods
// ADR-052 additionally renders, for a class with a public instance constructor:
//   - a public secondary `constructor(...)` delegating through `this(construct(...))`
//   - a file-private `construct(...)` helper (below the class) that runs the ctor thunk and
//     requireNotNulls the returned handle (a C# constructor never returns null)
// Phase 9 (ROADMAP line 151) additionally renders:
//   - bridgeable instance methods as member functions (before the companion object), each
//     prepending `handle.require("TypeName")` as the receiver argument to fn.invoke(...)
//   - bridgeable instance properties as val/var declarations with bridge-backed get()/set(value)
private fun classWrapperContent(
  kotlinPkg: String,
  cls: RirClass,
  staticMethods: List<RirMethod>,
  instanceMethods: List<RirMethod>,
  ctors: List<RirConstructor>,
  instancePropertyGetters: List<RirProperty>,
  staticPropertyGetters: List<RirProperty>,
  propertySetterNames: Set<String>,
  packageId: String,
  namespaceName: String,
  enumPkgs: Map<RirTypeKey, String>,
  structPkgs: Map<RirTypeKey, String>,
  handlePkgs: Map<RirTypeKey, String>,
  structs: Map<RirTypeKey, RirStruct>,
  qualifiedTypeNames: Map<RirTypeKey, String>,
  interfaceSupertypeNames: List<String> = emptyList(),
  overrideMethodNames: Set<String> = emptySet(),
  overridePropertyNames: Set<String> = emptySet(),
  genericDefs: Map<RirTypeKey, RirClass> = emptyMap(),
): String {
  val allMethods: List<RirMethod> = staticMethods + instanceMethods
  val methodsHaveString: Boolean =
    allMethods.any { typeContains(it.returnType, structs, ::isStringRef) }
  val instancePropertiesHaveString: Boolean =
    instancePropertyGetters.any { typeContains(it.type, structs, ::isStringRef) }
  val staticPropertiesHaveString: Boolean =
    staticPropertyGetters.any { typeContains(it.type, structs, ::isStringRef) }
  val hasStringReturn: Boolean =
    methodsHaveString || instancePropertiesHaveString || staticPropertiesHaveString

  val methodsHaveStringParam: Boolean =
    allMethods.any { m -> m.parameters.any { p -> typeContains(p.type, structs, ::isStringRef) } }
  val ctorsHaveStringParam: Boolean = ctors.any { ctor ->
    ctor.parameters.any { p -> typeContains(p.type, structs, ::isStringRef) }
  }
  val instanceSettablePropertiesHaveString: Boolean = instancePropertyGetters.any {
    typeContains(it.type, structs, ::isStringRef) && it.name in propertySetterNames
  }
  val staticSettablePropertiesHaveString: Boolean = staticPropertyGetters.any {
    typeContains(it.type, structs, ::isStringRef) && it.name in propertySetterNames
  }
  val hasStringParam: Boolean = methodsHaveStringParam || ctorsHaveStringParam ||
      instanceSettablePropertiesHaveString || staticSettablePropertiesHaveString

  val imports: MutableList<String> = mutableListOf(
    "import $INTERNAL_PKG.NugetHandleOwner",
    "import $INTERNAL_PKG.NugetObjectHandle",
    "import $INTERNAL_PKG.NugetRegistry",
    "import kotlin.experimental.ExperimentalNativeApi",
    "import kotlin.native.ref.createCleaner",
    "import kotlinx.cinterop.COpaquePointer",
    "import kotlinx.cinterop.invoke",
  )
  // ADR-070 Decision 4: an interface-typed parameter's argConversion(...) calls the shared
  // `nugetHandle()` extension — needed whenever this class has an instance method/property/ctor
  // parameter typed as an interface.
  val methodsHaveInterfaceParam: Boolean =
    allMethods.any { m -> m.parameters.any { it.type is RirInterfaceType } }
  val ctorsHaveInterfaceParam: Boolean =
    ctors.any { it.parameters.any { p -> p.type is RirInterfaceType } }
  val settablePropertiesHaveInterfaceParam: Boolean =
    instancePropertyGetters.any { it.type is RirInterfaceType && it.name in propertySetterNames }
  val hasInterfaceParam: Boolean =
    methodsHaveInterfaceParam || ctorsHaveInterfaceParam || settablePropertiesHaveInterfaceParam
  if (hasInterfaceParam) imports.add("import $INTERNAL_PKG.nugetTransferScope")
  if (hasStringReturn) {
    imports.add("import $INTERNAL_PKG.freeManagedString")
    imports.add("import kotlinx.cinterop.ByteVar")
    imports.add("import kotlinx.cinterop.reinterpret")
    imports.add("import kotlinx.cinterop.toKString")
  }
  if (hasStringParam) {
    imports.add("import kotlinx.cinterop.cstr")
    imports.add("import kotlinx.cinterop.memScoped")
    imports.add("import kotlinx.cinterop.ptr")
  }
  val allPropertyGetters: List<RirProperty> = instancePropertyGetters + staticPropertyGetters
  // ADR-056: same struct-return import logic as the object-shape path (stubFileContent) — see the
  // comment there.
  val methodsHaveStructReturn: Boolean =
    allMethods.any { abiOutArgs(it.returnType, structs).isNotEmpty() }
  val propertiesHaveStructReturn: Boolean =
    allPropertyGetters.any { abiOutArgs(it.type, structs).isNotEmpty() }
  val hasStructReturn: Boolean = methodsHaveStructReturn || propertiesHaveStructReturn
  if (hasStructReturn) {
    if ("import kotlinx.cinterop.memScoped" !in imports) {
      imports.add("import kotlinx.cinterop.memScoped")
    }
    imports.add("import kotlinx.cinterop.alloc")
    imports.add("import kotlinx.cinterop.value")
    if ("import kotlinx.cinterop.ptr" !in imports) imports.add("import kotlinx.cinterop.ptr")
    val structOutVarTypes: List<String> = (
        allMethods.flatMap { abiOutArgs(it.returnType, structs) } +
            allPropertyGetters.flatMap { abiOutArgs(it.type, structs) }
        ).map { cVarType(it.type) }.distinct().sorted()
    structOutVarTypes.forEach { imports.add("import kotlinx.cinterop.$it") }
  }
  val methodsHaveEnumReturn: Boolean =
    allMethods.any { typeContains(it.returnType, structs, ::isEnumRef) }
  val propertiesHaveEnumReturn: Boolean =
    allPropertyGetters.any { typeContains(it.type, structs, ::isEnumRef) }
  val hasEnumReturn: Boolean = methodsHaveEnumReturn || propertiesHaveEnumReturn
  if (hasEnumReturn) imports.add("import $INTERNAL_PKG.nugetEnumEntry")
  val structEnumTypes: List<RirEnumType> = (
      allMethods.flatMap { method ->
        structEnumComponents(method.returnType, structs) +
            method.parameters.flatMap { p -> structEnumComponents(p.type, structs) }
      } +
          ctors.flatMap { it.parameters.flatMap { p -> structEnumComponents(p.type, structs) } } +
          allPropertyGetters.flatMap { structEnumComponents(it.type, structs) }
      ).distinct()
  imports.addAll(
    enumImports(
      referencedEnumTypes(allMethods, ctors, allPropertyGetters) + structEnumTypes,
      enumPkgs, kotlinPkg,
    )
  )

  // ADR-059: same struct-return reassembly import coverage as the object-shape path
  // (stubFileContent) — see the comment there. Ctors are excluded here for the same reason a
  // struct-typed PARAMETER never needs one: a constructor never returns a struct value (its
  // implicit return is the class's own handle), so nothing here calls structComponentReads on a
  // ctor's parameters.
  val structReturnTypes: List<RirStructType> = (
      allMethods.flatMap { structTypesInTree(it.returnType, structs) } +
          allPropertyGetters.flatMap { structTypesInTree(it.type, structs) }
      ).distinct()
  imports.addAll(structImports(structReturnTypes, structPkgs, kotlinPkg))

  // ADR-072: same missing-import gap as the object-shape path (stubFileContent); see
  // handleImports' KDoc. A wrapper's OWN handle type never needs an import (it is this file's own
  // declared class); any OTHER bound class named as a method/ctor/property type does.
  imports.addAll(
    handleImports(
      referencedHandleTypes(allMethods, ctors, allPropertyGetters), handlePkgs, kotlinPkg,
    )
  )

  val instanceMethodsText: String = instanceMethods.joinToString("\n\n") {
    buildStubMethod(
      cls, it, packageId, namespaceName, structs, qualifiedTypeNames,
      isOverride = it.name.toMethodCamelCase() in overrideMethodNames,
      genericDefs = genericDefs,
    )
  }
  val propertiesText: String = instancePropertyGetters.joinToString("\n\n") { property ->
    buildStubProperty(
      cls, property, hasSetter = property.name in propertySetterNames, packageId, namespaceName,
      structs,
      qualifiedTypeNames,
      isOverride = property.name.toMethodCamelCase() in overridePropertyNames,
      genericDefs = genericDefs,
    )
  }
  val staticMethodsText: String = staticMethods.joinToString("\n\n") {
    buildStubMethod(
      cls, it, packageId, namespaceName, structs, qualifiedTypeNames, genericDefs = genericDefs,
    )
  }
  val staticPropertiesText: String = staticPropertyGetters.joinToString("\n\n") { property ->
    buildStubProperty(
      cls, property, hasSetter = property.name in propertySetterNames, packageId, namespaceName,
      structs,
      qualifiedTypeNames,
      genericDefs = genericDefs,
    )
  }

  return buildString {
    appendLine("@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)")
    appendLine()
    appendLine("package $kotlinPkg")
    appendLine()
    imports.forEach { appendLine(it) }
    appendLine()
    appendLine("// Generated: Kotlin-idiomatic wrapper for $packageId.${cls.name}")
    appendLine()
    appendLine("/**")
    appendLine(" * Kotlin wrapper for the C# type `$packageId.${cls.name}`.")
    appendLine(" *")
    appendLine(
      " * Equality is wrapper identity: two wrappers around the same C# object are not equal.",
    )
    appendLine(
      " * The underlying C# object is released automatically when this wrapper is " +
          "garbage-collected;",
    )
    appendLine(
      " * call [close] (or use `use { }`) for deterministic release. [close] is optional and " +
          "idempotent.",
    )
    appendLine(" */")
    appendLine("@OptIn(ExperimentalNativeApi::class)")
    // ADR-070 Decision 5: every admissible+bound interface this class implements identically is
    // declared as a Kotlin supertype, ahead of the ADR-070 Decision 4 NugetHandleOwner marker
    // every wrapper carries (so an interface-typed parameter position can unwrap this class
    // generically) and AutoCloseable.
    val supertypes: String =
      (interfaceSupertypeNames + listOf("NugetHandleOwner", "AutoCloseable")).joinToString(", ")
    appendLine(
      "internal class ${cls.name} internal constructor(handle: COpaquePointer) : $supertypes {",
    )
    appendLine("  override val handle: NugetObjectHandle = NugetObjectHandle(handle)")
    appendLine()
    appendLine("  @Suppress(\"unused\")")
    appendLine("  private val cleaner = createCleaner(this.handle) { it.free() }")
    appendLine()
    appendLine("  override fun close(): Unit = handle.free()")
    if (ctors.isNotEmpty()) {
      appendLine()
      ctors.forEach { ctor ->
        appendLine("  ${buildSecondaryConstructor(ctor, qualifiedTypeNames)}")
      }
    }
    if (instanceMethodsText.isNotEmpty()) {
      appendLine()
      appendLine(instanceMethodsText.indented("  "))
    }
    if (propertiesText.isNotEmpty()) {
      appendLine()
      appendLine(propertiesText.indented("  "))
    }
    appendLine()
    appendLine("  companion object {")
    appendLine()
    if (staticMethodsText.isNotEmpty()) appendLine(staticMethodsText.indented("    "))
    if (staticMethodsText.isNotEmpty() && staticPropertiesText.isNotEmpty()) appendLine()
    if (staticPropertiesText.isNotEmpty()) appendLine(staticPropertiesText.indented("    "))
    appendLine("  }")
    appendLine("}")
    if (ctors.isNotEmpty()) {
      appendLine()
      ctors.forEach { ctor ->
        append(
          buildConstructHelper(
            cls, ctor, packageId, namespaceName, structs, qualifiedTypeNames,
          ),
        )
      }
    }
  }
}

// Converts a Kotlin-side value expression (a parameter name, or the property setter's implicit
// `value`) into the expression passed to `fn.invoke(...)` — shared by buildStubMethod,
// buildConstructHelper, and buildStubProperty's setter so the marshalling rules never drift
// between the three call sites.
//
// ADR-053: a nullable string/handle value must not eagerly dereference `.cstr`/`.handle` — doing
// so would NPE before the null ever gets a chance to cross as a null pointer. `name.cstr.ptr`
// requires a non-null receiver, so a nullable string is guarded with an explicit if/else; a
// nullable handle uses a safe-call chain instead (both still evaluate inside the same
// memScoped/fn.invoke(...) call site as the non-null case — no separate branch needed there).
// ADR-085: the two scopes a generated invoke may need, innermost first. memScoped owns the
// `.cstr.ptr` buffers of string arguments; nugetTransferScope owns the bridge handles minted for
// Kotlin-implemented interface arguments (freed after the call, never before it).
private fun wrapInvoke(rawInvoke: String, hasStringArg: Boolean, hasInterfaceArg: Boolean): String {
  val scoped: String = if (hasStringArg) "memScoped { $rawInvoke }" else rawInvoke
  return if (hasInterfaceArg) "nugetTransferScope { $scoped }" else scoped
}

private fun argConversion(type: RirTypeRef, name: String): String = when {
  type is RirStringType && type.nullable -> "if ($name == null) null else $name.cstr.ptr"
  type is RirStringType -> "$name.cstr.ptr"
  // ADR-051: unwrap the opaque pointer via handle.require() which also guards against
  // use-after-close (throws IllegalStateException if the handle was already freed).
  type is RirObjectHandleType && type.nullable -> "$name?.handle?.require(\"${type.name}\")"
  type is RirObjectHandleType -> "$name.handle.require(\"${type.name}\")"
  // ADR-070 Decision 4: an interface-typed value may be a Kotlin-side implementation of the
  // interface (out of scope until Phase 13), so it is not unwrapped via a `.handle` member on the
  // interface itself (the generated interface stays pure, no handle member — Decision 4) but via
  // the NugetHandleOwner marker every generated wrapper (a bound class, or an interface's own
  // {Name}Handle) implements.
  // ADR-085: `handleOf` is a member of the NugetTransferScope this call site is wrapped in (see
  // wrapInvoke), so a bridge minted for a Kotlin implementation is freed after the native call
  // returns instead of rooting the bridge forever.
  // The name passed is the QUALIFIED C# name: it keys nugetMintBridge's dispatch, so it must
  // identify the target position's interface unambiguously across namespaces.
  type is RirInterfaceType && type.nullable ->
    "handleOfOrNull($name, \"${type.namespace}.${type.name}\")"

  type is RirInterfaceType -> "handleOf($name, \"${type.namespace}.${type.name}\")"
  type is RirEnumType -> "$name.ordinal"
  type is RirPrimitiveType && type.name == "char" -> "$name.code.toUShort()"
  // ADR-059: a struct-typed argument must be decomposed into its LEAVES via structArgConversions
  // (below) before reaching argConversion — argConversion only accepts leaf (scalar) types.
  // Reaching this branch means a RirStructType slipped past that recursion.
  type is RirStructType -> error(
    "[nuget] struct ${type.namespace}.${type.name} must be expanded via structArgConversions " +
        "before reaching argConversion — argConversion only accepts leaf (scalar) types."
  )

  // ADR-072 Decision 3, NugetGenerateBindingsTask.kt:2241 permissive site (the one the compiler
  // cannot force): a generic-instance argument is wire-identical to a handle and must be
  // unwrapped the SAME way: the boolean-guard `when`'s fall-through `else -> name` would
  // otherwise silently pass the raw Kotlin wrapper instead of its handle.
  type is RirGenericInstanceType && type.nullable ->
    "$name?.handle?.require(\"${type.name.substringBefore('`')}\")"

  type is RirGenericInstanceType -> "$name.handle.require(\"${type.name.substringBefore('`')}\")"

  else -> name
}

// ADR-059 Decision 1a: the ONE shared recursive path-expression builder that replaces the four
// one-level copies this file used to carry (structConstructorHelpers' inArgs,
// buildStructStubMethod's receiverArgs/paramArgs, buildConstructHelper's invokeArgs,
// buildStubMethod's paramArgs) — all four were "if this parameter's type resolves to a struct, map
// its components through argConversion, else call argConversion directly", one level deep, and
// would each need the identical fix independently. [rootExpr] is the Kotlin expression for the
// WHOLE value at this position (a parameter name, an implicit receiver component's bare name, or a
// property setter's `value`); for a struct-typed [type], each of its OWN components recurses with
// `$rootExpr.$componentName` appended, so a leaf reached through a path (e.g. `l.mother.tag`) gets
// exactly the same argConversion(...) a top-level parameter of that leaf's type would use.
private fun structArgConversions(
  type: RirTypeRef,
  rootExpr: String,
  structs: Map<RirTypeKey, RirStruct>,
): List<String> {
  val struct: RirStruct? =
    (type as? RirStructType)?.let { structs[RirTypeKey(it.namespace, it.name)] }
  return if (struct == null) listOf(argConversion(type, rootExpr))
  else struct.components.flatMap { c ->
    structArgConversions(c.type, "$rootExpr.${c.name.toMethodCamelCase()}", structs)
  }
}

// Like String.prependIndent, but leaves blank lines completely empty instead of padding them out
// to the indent's width (String.prependIndent pads any blank line shorter than the given indent).
// The generated members this file assembles are separated by blank lines; padding them would leave
// trailing whitespace on lines that must stay empty (STYLE.md: "blank lines... should contain no
// characters at all").
internal fun String.indented(indent: String): String =
  lineSequence().joinToString("\n") { line -> if (line.isBlank()) line else indent + line }

// Shared "bindings not registered" guard message — used by buildStubMethod, buildConstructHelper,
// and buildStubProperty so the wording never drifts between the three call sites.
//
// ADR-054: this used to be a constant string baked at GENERATION time. It is now a call to
// NugetRegistry.notRegistered(...), evaluated at RUNTIME, on the failure path only — so the
// message can say how many of this build's registrations actually fired ("0 of 7", "6 of 7, only
// Test.Text.Template is missing") instead of a fixed sentence that cannot distinguish "nothing
// registered" from "everything but this one type registered".
private fun bindingsNotRegisteredMessage(
  typeName: String,
  packageId: String,
  namespaceName: String,
): String =
  "NugetRegistry.notRegistered(\"$namespaceName.$typeName\", \"$packageId\")"

// ADR-052: the public secondary `constructor(...)` on the wrapper class, delegating through the
// file-private `construct(...)` helper — the "can't run code before this(...)" restriction is
// sidestepped by pushing the bridge call into the delegation expression itself.
private fun buildSecondaryConstructor(
  ctor: RirConstructor,
  qualifiedTypeNames: Map<RirTypeKey, String>,
): String {
  val params: String = ctor.parameters.joinToString(", ") { p ->
    "${p.name}: ${declKotlinType(p.type, qualifiedTypeNames)}"
  }
  val args: String = ctor.parameters.joinToString(", ") { it.name }
  return "constructor($params) : this(construct${ctor.bridgeSuffix()}($args))"
}

// ADR-052: file-private helper (not a class member — Kotlin's `: this(...)` delegation only
// accepts an expression, so the bridge call lives in an ordinary top-level function). Marshals
// parameters exactly as buildStubMethod does, then requireNotNulls the returned handle — a C#
// constructor never returns null, unlike ADR-051's nullable Foo? factory returns.
private fun buildConstructHelper(
  cls: RirClass,
  ctor: RirConstructor,
  packageId: String,
  namespaceName: String,
  structs: Map<RirTypeKey, RirStruct>,
  qualifiedTypeNames: Map<RirTypeKey, String>,
): String {
  val params: String = ctor.parameters.joinToString(", ") { p ->
    "${p.name}: ${declKotlinType(p.type, qualifiedTypeNames)}"
  }
  val hasStringParam: Boolean = ctor.parameters.any { it.type is RirStringType }

  val invokeArgs: String = ctor.parameters.flatMap { p ->
    structArgConversions(p.type, p.name, structs)
  }.joinToString(", ")

  val hasInterfaceParam: Boolean = ctor.parameters.any { it.type is RirInterfaceType }
  val invokeCall: String =
    wrapInvoke("fn.invoke($invokeArgs)", hasStringParam, hasInterfaceParam)

  val failMsg: String = bindingsNotRegisteredMessage(cls.name, packageId, namespaceName)
  val bindingsObj: String = bindingsObjectName(cls.name)

  return """
    |private fun construct${ctor.bridgeSuffix()}($params): COpaquePointer {
    |  val fn = requireNotNull($bindingsObj.ctor${ctor.bridgeSuffix()}Fn) {
    |    $failMsg
    |  }
    |  val ptr: COpaquePointer? = $invokeCall
    |  return requireNotNull(ptr) {
    |    "${cls.name} constructor returned a null handle — a C# constructor never returns null."
    |  }
    |}
  """.trimMargin()
}

private fun buildStubMethod(
  cls: RirClass,
  method: RirMethod,
  packageId: String,
  namespaceName: String,
  structs: Map<RirTypeKey, RirStruct>,
  qualifiedTypeNames: Map<RirTypeKey, String>,
  isOverride: Boolean = false,
  genericDefs: Map<RirTypeKey, RirClass> = emptyMap(),
): String {
  val name: String = method.name.toMethodCamelCase()
  val fnVar: String =
    "${bindingsObjectName(cls.name)}.$name${method.bridgeSuffix()}Fn"
  // ADR-056/059: a struct component can itself be (or contain, at any nesting depth) a string —
  // memScoped is needed whenever ANY leaf crossing as a string argument requires it, not just a
  // direct top-level string parameter.
  val hasStringParam: Boolean =
    method.parameters.any { p -> typeContains(p.type, structs, ::isStringRef) }

  val params: String = method.parameters.joinToString(", ") { p ->
    "${p.name}: ${declKotlinType(p.type, qualifiedTypeNames)}"
  }

  // ADR-053: the return's nullability is driven by the RIR's decoded metadata for both strings
  // and handles (a nullable annotation renders `T?`; a non-null annotation — including an
  // oblivious, un-annotated type, which binds non-null — renders bare `T`). This replaces
  // ADR-051's hardcoded "handle returns are always Foo?" policy.
  val retSuffix: String =
    if (method.returnType is RirVoidType) ""
    else ": ${declKotlinType(method.returnType, qualifiedTypeNames)}"

  // Phase 9 (ROADMAP line 151): an instance thunk is a static thunk whose first parameter is the
  // receiver handle (ADR-051 insight) — prepend it via the same handle.require(...) pattern used
  // for handle-typed parameters, referencing the wrapper's own `handle` field (not a Kotlin
  // parameter). Static methods are unaffected — no receiver is prepended.
  val receiverArg: String? = if (!method.isStatic) "handle.require(\"${cls.name}\")" else null
  // ADR-056: a struct-typed parameter decomposes into one invoke() argument per component — its
  // Kotlin property, in the struct's declared component order (mirrors the shared abiArgs()
  // expansion in terms of Kotlin call-site expressions rather than ABI names). Each component
  // expression is routed through the SAME argConversion(...) a top-level parameter of that
  // component's type would use (`.cstr.ptr` for string, `.ordinal` for enum, `.code.toUShort()`
  // for char, untouched for int/bool/etc.) — a component must never bypass the conversion its own
  // type already requires, or it silently mismatches the CFunction type methodParamCfnTypes
  // declares for that slot (e.g. a raw Char passed where UShort is expected).
  val paramArgs: List<String> = method.parameters.flatMap { p ->
    structArgConversions(p.type, p.name, structs)
  }
  val invokeArgs: String = (listOfNotNull(receiverArg) + paramArgs).joinToString(", ")

  val failMsg: String = bindingsNotRegisteredMessage(cls.name, packageId, namespaceName)

  // ADR-085: an interface-typed parameter may mint a bridge, whose transfer handle this call
  // site owns and frees after the invoke (wrapInvoke's nugetTransferScope).
  val hasInterfaceParam: Boolean = method.parameters.any { it.type is RirInterfaceType }
  val invokeCall: String =
    wrapInvoke("fn.invoke($invokeArgs)", hasStringParam, hasInterfaceParam)

  val nullMsg: String = "${cls.name}.${method.name} returned null" +
      ", expected a non-null string pointer"
  val nonNullHandleMsg: String = "${cls.name}.${method.name} returned null, but the C# API " +
      "annotates it non-null."

  // Each branch below renders a self-contained block (`fun` at column 0, its body at column 2)
  // so the caller can shift the whole block to its actual embedding depth with a single
  // String.prependIndent() call, rather than baking one specific nesting depth into this function.
  val rendered: String = when (val retType = method.returnType) {
    is RirVoidType -> """
      |fun $name($params)$retSuffix {
      |  val fn = requireNotNull($fnVar) {
      |    $failMsg
      |  }
      |  $invokeCall
      |}
    """.trimMargin()

    // ADR-053: a nullable string return crosses a null pointer through to Kotlin `null` — no more
    // `?: error(...)`. A non-null-annotated return (including an oblivious one) keeps the existing
    // ADR-048 fail-fast error() fallback.
    is RirStringType -> if (retType.nullable) """
      |fun $name($params)$retSuffix {
      |  val fn = requireNotNull($fnVar) {
      |    $failMsg
      |  }
      |  val resultPtr = $invokeCall
      |    ?: return null
      |  val result = resultPtr.reinterpret<ByteVar>().toKString()
      |  freeManagedString(resultPtr)
      |  return result
      |}
    """.trimMargin() else """
      |fun $name($params)$retSuffix {
      |  val fn = requireNotNull($fnVar) {
      |    $failMsg
      |  }
      |  val resultPtr = $invokeCall
      |    ?: error("$nullMsg")
      |  val result = resultPtr.reinterpret<ByteVar>().toKString()
      |  freeManagedString(resultPtr)
      |  return result
      |}
    """.trimMargin()

    // ADR-053: a nullable-annotated handle return keeps ADR-051's existing IntPtr.Zero-maps-to-
    // null shape. A non-null-annotated return instead requireNotNulls the raw pointer, naming the
    // member in the failure message — a null arriving where the metadata says non-null is a
    // bridge-invariant violation, not a legitimate value (ADR-053 Decision 1a's fail-fast guard).
    is RirObjectHandleType -> if (retType.nullable) """
      |fun $name($params)$retSuffix {
      |  val fn = requireNotNull($fnVar) {
      |    $failMsg
      |  }
      |  val ptr: COpaquePointer? = $invokeCall
      |  return ptr?.let { ${retType.name}(it) }
      |}
    """.trimMargin() else """
      |fun $name($params)$retSuffix {
      |  val fn = requireNotNull($fnVar) {
      |    $failMsg
      |  }
      |  val ptr: COpaquePointer? = $invokeCall
      |  return ${retType.name}(requireNotNull(ptr) {
      |    "$nonNullHandleMsg"
      |  })
      |}
    """.trimMargin()

    // ADR-070 Decision 3: an interface-typed return always wraps in the interface's OWN
    // `{Name}Handle` implementation (never the concrete bound class the runtime object might
    // actually be — the wire carries no type tag), which upcasts to the declared interface type.
    // Otherwise byte-identical to the handle-return branch above.
    is RirInterfaceType -> if (retType.nullable) """
      |fun $name($params)$retSuffix {
      |  val fn = requireNotNull($fnVar) {
      |    $failMsg
      |  }
      |  val ptr: COpaquePointer? = $invokeCall
      |  return ptr?.let { nuget${retType.name}Value(it) }
      |}
    """.trimMargin() else """
      |fun $name($params)$retSuffix {
      |  val fn = requireNotNull($fnVar) {
      |    $failMsg
      |  }
      |  val ptr: COpaquePointer? = $invokeCall
      |  return nuget${retType.name}Value(requireNotNull(ptr) {
      |    "$nonNullHandleMsg"
      |  })
      |}
    """.trimMargin()

    // The ordinal comes back from C#, where an enum is not a closed set, so it is bounds-checked
    // through the shared nugetEnumEntry helper rather than indexed straight into `entries`.
    is RirEnumType -> """
      |fun $name($params)$retSuffix {
      |  val fn = requireNotNull($fnVar) {
      |    $failMsg
      |  }
      |  return nugetEnumEntry(${retType.name}.entries, $invokeCall, "${retType.name}")
      |}
    """.trimMargin()

    // ADR-056: a struct-typed return crosses as `void` plus one out-pointer argument per
    // component (abiOutArgs) — the thunk return itself carries nothing. memScoped both hosts the
    // alloc<...>() out-slots and (if hasStringParam) the string-argument .cstr.ptr conversions, so
    // the WHOLE function body is `= memScoped { ... }` rather than wrapping only the invoke call
    // the way the other branches do.
    is RirStructType -> {
      val struct = requireNotNull(structs[RirTypeKey(retType.namespace, retType.name)]) {
        "[nuget] struct ${retType.namespace}.${retType.name} is referenced as a return type " +
            "but not declared in reverse-ir.json"
      }
      val outArgs: List<AbiArg> = abiOutArgs(retType, structs)
      val outPtrArgs: List<String> = outArgs.map { "${it.name}.ptr" }
      val fullInvokeArgs: String =
        (listOfNotNull(receiverArg) + paramArgs + outPtrArgs).joinToString(", ")
      // ADR-059: each LEAF is read back through structComponentReads' recursive use of
      // componentRead — the SAME per-type conversion a top-level return of that leaf's type
      // already uses — instead of the raw `.value`, which is only correct for the pass-through
      // primitives (int/long/float/double).
      val read: ComponentRead = structComponentReads(struct, outArgs.iterator(), structs)
      buildString {
        appendLine("fun $name($params)$retSuffix = memScoped {")
        appendLine("  val fn = requireNotNull($fnVar) {")
        appendLine("    $failMsg")
        appendLine("  }")
        outArgs.forEach { arg -> appendLine("  val ${arg.name} = alloc<${cVarType(arg.type)}>()") }
        appendLine("  fn.invoke($fullInvokeArgs)")
        read.statements.forEach { appendLine("  $it") }
        appendLine("  ${read.expression}")
        append("}")
      }
    }

    is RirPrimitiveType -> {
      val isChar: Boolean = retType.name == "char"
      val returnExpr: String = if (isChar) "$invokeCall.toInt().toChar()" else invokeCall
      """
        |fun $name($params)$retSuffix {
        |  val fn = requireNotNull($fnVar) {
        |    $failMsg
        |  }
        |  return $returnExpr
        |}
      """.trimMargin()
    }

    // ADR-072: an ordinary (non-generic) member returning a bridgeable closed instantiation
    // constructs the SAME per-instantiation wrapper the fake-constructor/`rewrap()` sites use
    // (Decision 1), passing the pointer plus this position's own witness, chosen entirely at
    // generation time from the discovered instantiation, never dispatched at runtime. Otherwise
    // byte-identical to the ordinary handle-return branch above.
    is RirGenericInstanceType -> {
      val simpleName: String = retType.name.substringBefore('`')
      val witness: String = witnessObjectName(retType, genericDefs)
      // ADR-072: the generic class's own constructor takes `handle: NugetObjectHandle`, not the
      // raw ADR-051 GCHandle pointer: wrap it exactly like the ordinary RirObjectHandleType
      // branch above does (`${retType.name}(requireNotNull(ptr) {...})`), or this does not
      // typecheck.
      if (retType.nullable) """
        |fun $name($params)$retSuffix {
        |  val fn = requireNotNull($fnVar) {
        |    $failMsg
        |  }
        |  val ptr: COpaquePointer? = $invokeCall
        |  return ptr?.let { $simpleName(NugetObjectHandle(it), $witness) }
        |}
      """.trimMargin() else """
        |fun $name($params)$retSuffix {
        |  val fn = requireNotNull($fnVar) {
        |    $failMsg
        |  }
        |  val ptr: COpaquePointer? = $invokeCall
        |  return $simpleName(NugetObjectHandle(requireNotNull(ptr) {
        |    "$nonNullHandleMsg"
        |  }), $witness)
        |}
      """.trimMargin()
    }

    is RirTypeParameterType -> error(
      "[nuget] a bare type parameter must be substituted/rendered through the dedicated ADR-072 " +
          "generic-class path, never through buildStubMethod()"
    )
  }
  // ADR-070 Decision 5: a matching class member gains `override` — the ONE call site both a
  // simple and a struct-return branch share, rather than baking the keyword into every one of
  // the branches above ("fun $name(" appears exactly once, at the very start of each block).
  return if (isOverride) rendered.replaceFirst("fun $name(", "override fun $name(") else rendered
}

// Phase 9 (ROADMAP line 151): a bridgeable instance property renders as:
//   - read-only (isReadOnly=true) -> `val x: T get() = ...` (bridge-backed, so an explicit get()
//     is mandatory — it cannot be a stored val)
//   - settable -> `var x: T` with both get() and set(value)
// ADR-053 (ROADMAP line 157 unblock): "rule 4" — a handle-typed property used to ALWAYS render as
// a read-only `val x: Foo?`, even when [hasSetter] was true, because object returns were
// unconditionally Foo? and object params unconditionally non-null Foo, leaving no single type a
// handle-typed var could use. Now that a property's single RIR-decoded nullable flag drives both
// the getter and the setter, they always agree on one type, so [hasSetter] alone decides val/var
// — the same rule every other property type already followed.
private fun buildStubProperty(
  cls: RirClass,
  property: RirProperty,
  hasSetter: Boolean,
  packageId: String,
  namespaceName: String,
  structs: Map<RirTypeKey, RirStruct>,
  qualifiedTypeNames: Map<RirTypeKey, String>,
  isOverride: Boolean = false,
  genericDefs: Map<RirTypeKey, RirClass> = emptyMap(),
): String {
  val name: String = property.name.toMethodCamelCase()
  val bindingsObj: String = bindingsObjectName(cls.name)
  val getterFnVar = "$bindingsObj.${name}GetterFn"
  val receiverArg: String? = if (property.isStatic) null else "handle.require(\"${cls.name}\")"
  val failMsg: String = bindingsNotRegisteredMessage(cls.name, packageId, namespaceName)

  val declType: String = declKotlinType(property.type, qualifiedTypeNames)
  val keyword: String = if (hasSetter) "var" else "val"
  val getterInvoke: String = if (receiverArg == null) "fn.invoke()" else "fn.invoke($receiverArg)"
  val nonNullHandleMsg: String = "${cls.name}.${property.name} returned null, but the C# API " +
      "annotates it non-null."

  // Each block below is self-contained (`get()`/`set(value)` at column 0, its body at column 2)
  // so it can be shifted under the property declaration with a single String.prependIndent() call,
  // the same composition buildStubMethod above uses for its own fun blocks.
  val getterBlock: String = when (val type = property.type) {
    is RirVoidType -> error("[nuget] a property cannot have void type")

    // ADR-056: "property getter -> as a return (out-pointers)" (Decision, wire-format table) —
    // the same shape as buildStubMethod's struct-return branch, with the getter's own
    // (receiver-only, no other params) invoke() call instead of a method's.
    is RirStructType -> {
      val struct = requireNotNull(structs[RirTypeKey(type.namespace, type.name)]) {
        "[nuget] struct ${type.namespace}.${type.name} is referenced as a property type but " +
            "not declared in reverse-ir.json"
      }
      val outArgs: List<AbiArg> = abiOutArgs(type, structs)
      val invokeArgs: String =
        (listOfNotNull(receiverArg) + outArgs.map { "${it.name}.ptr" }).joinToString(", ")
      val read: ComponentRead = structComponentReads(struct, outArgs.iterator(), structs)
      buildString {
        appendLine("get() {")
        appendLine("  val fn = requireNotNull($getterFnVar) {")
        appendLine("    $failMsg")
        appendLine("  }")
        appendLine("  return memScoped {")
        outArgs.forEach { arg ->
          appendLine("    val ${arg.name} = alloc<${cVarType(arg.type)}>()")
        }
        appendLine("    fn.invoke($invokeArgs)")
        read.statements.forEach { appendLine("    $it") }
        appendLine("    ${read.expression}")
        appendLine("  }")
        append("}")
      }
    }

    // ADR-053: a nullable-annotated string property keeps ADR-048's null-pointer-maps-to-null-
    // string shape (no fallback needed — the caller's declared type is already String?). A
    // non-null-annotated property keeps the existing fail-fast error() fallback.
    is RirStringType -> if (type.nullable) """
      |get() {
      |  val fn = requireNotNull($getterFnVar) {
      |    $failMsg
      |  }
      |  val resultPtr = $getterInvoke ?: return null
      |  val result = resultPtr.reinterpret<ByteVar>().toKString()
      |  freeManagedString(resultPtr)
      |  return result
      |}
    """.trimMargin() else """
      |get() {
      |  val fn = requireNotNull($getterFnVar) {
      |    $failMsg
      |  }
      |  val resultPtr = $getterInvoke
      |    ?: error("${cls.name}.${property.name} returned null — expected a non-null string pointer")
      |  val result = resultPtr.reinterpret<ByteVar>().toKString()
      |  freeManagedString(resultPtr)
      |  return result
      |}
    """.trimMargin()

    // ADR-053: a nullable-annotated handle property keeps ADR-051's existing IntPtr.Zero-maps-to-
    // null shape. A non-null-annotated property instead requireNotNulls the raw pointer, naming the
    // member in the failure message (mirrors buildStubMethod's non-null handle return).
    is RirObjectHandleType -> if (type.nullable) """
      |get() {
      |  val fn = requireNotNull($getterFnVar) {
      |    $failMsg
      |  }
      |  val ptr: COpaquePointer? = $getterInvoke
      |  return ptr?.let { ${type.name}(it) }
      |}
    """.trimMargin() else """
      |get() {
      |  val fn = requireNotNull($getterFnVar) {
      |    $failMsg
      |  }
      |  val ptr: COpaquePointer? = $getterInvoke
      |  return ${type.name}(requireNotNull(ptr) {
      |    "$nonNullHandleMsg"
      |  })
      |}
    """.trimMargin()

    // ADR-070 Decision 3: identical to the handle-property branch above, but wraps in the
    // interface's OWN `{Name}Handle` implementation — never the concrete bound class.
    is RirInterfaceType -> if (type.nullable) """
      |get() {
      |  val fn = requireNotNull($getterFnVar) {
      |    $failMsg
      |  }
      |  val ptr: COpaquePointer? = $getterInvoke
      |  return ptr?.let { nuget${type.name}Value(it) }
      |}
    """.trimMargin() else """
      |get() {
      |  val fn = requireNotNull($getterFnVar) {
      |    $failMsg
      |  }
      |  val ptr: COpaquePointer? = $getterInvoke
      |  return nuget${type.name}Value(requireNotNull(ptr) {
      |    "$nonNullHandleMsg"
      |  })
      |}
    """.trimMargin()

    // Bounds-checked through the shared nugetEnumEntry helper: see buildStubMethod's enum branch.
    is RirEnumType -> """
      |get() {
      |  val fn = requireNotNull($getterFnVar) {
      |    $failMsg
      |  }
      |  return nugetEnumEntry(${type.name}.entries, $getterInvoke, "${type.name}")
      |}
    """.trimMargin()

    is RirPrimitiveType -> {
      val isChar: Boolean = type.name == "char"
      val returnExpr: String =
        if (isChar) "$getterInvoke.toInt().toChar()" else getterInvoke
      """
        |get() {
        |  val fn = requireNotNull($getterFnVar) {
        |    $failMsg
        |  }
        |  return $returnExpr
        |}
      """.trimMargin()
    }

    // ADR-072: mirrors buildStubMethod's own RirGenericInstanceType branch above: a bridgeable
    // generic instantiation constructs the per-instantiation wrapper with THIS position's own
    // witness, chosen entirely at generation time.
    is RirGenericInstanceType -> {
      val simpleName: String = type.name.substringBefore('`')
      val witness: String = witnessObjectName(type, genericDefs)
      // ADR-072: same NugetObjectHandle wrapping fix as buildStubMethod's own
      // RirGenericInstanceType branch: the generic class's constructor takes a
      // NugetObjectHandle, not the raw pointer.
      if (type.nullable) """
        |get() {
        |  val fn = requireNotNull($getterFnVar) {
        |    $failMsg
        |  }
        |  val ptr: COpaquePointer? = $getterInvoke
        |  return ptr?.let { $simpleName(NugetObjectHandle(it), $witness) }
        |}
      """.trimMargin() else """
        |get() {
        |  val fn = requireNotNull($getterFnVar) {
        |    $failMsg
        |  }
        |  val ptr: COpaquePointer? = $getterInvoke
        |  return $simpleName(NugetObjectHandle(requireNotNull(ptr) {
        |    "$nonNullHandleMsg"
        |  }), $witness)
        |}
      """.trimMargin()
    }

    is RirTypeParameterType -> error(
      "[nuget] a bare type parameter must be substituted/rendered through the dedicated ADR-072 " +
          "generic-class path, never through buildStubProperty()"
    )
  }

  val setterBlock: String? = if (hasSetter) {
    val setterFnVar = "$bindingsObj.${name}SetterFn"
    // ADR-056: "property setter -> as a parameter (decomposed arguments)" (Decision, wire-format
    // table) — the same component decomposition buildStubMethod's paramArgs uses for a
    // struct-typed method parameter, applied to the setter's implicit `value`.
    val propType: RirTypeRef = property.type
    val invokeCall: String = if (propType is RirStructType) {
      val struct = requireNotNull(structs[RirTypeKey(propType.namespace, propType.name)]) {
        "[nuget] struct ${propType.namespace}.${propType.name} is referenced as a property type " +
            "but not declared in reverse-ir.json"
      }
      val componentArgs: List<String> = struct.components.flatMap { c ->
        structArgConversions(c.type, "value.${c.name.toMethodCamelCase()}", structs)
      }
      val invokeArgs: String = (listOfNotNull(receiverArg) + componentArgs).joinToString(", ")
      val hasStringComponent: Boolean =
        struct.components.any { typeContains(it.type, structs, ::isStringRef) }
      if (hasStringComponent) "memScoped { fn.invoke($invokeArgs) }" else "fn.invoke($invokeArgs)"
    } else {
      val valueArg: String = argConversion(propType, "value")
      val raw: String =
        if (receiverArg == null) "fn.invoke($valueArg)" else "fn.invoke($receiverArg, $valueArg)"
      wrapInvoke(raw, propType is RirStringType, propType is RirInterfaceType)
    }
    """
      |set(value) {
      |  val fn = requireNotNull($setterFnVar) {
      |    $failMsg
      |  }
      |  $invokeCall
      |}
    """.trimMargin()
  } else {
    null
  }

  val overridePrefix: String = if (isOverride) "override " else ""
  return buildString {
    appendLine("$overridePrefix$keyword $name: $declType")
    append(getterBlock.indented("  "))
    if (setterBlock != null) {
      appendLine()
      append(setterBlock.indented("  "))
    }
  }
}

private fun nugetInteropExpect(): String = """
  |@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
  |
  |package $INTERNAL_PKG
  |
  |import kotlinx.cinterop.COpaquePointer
  |
  |internal expect fun freeManagedString(ptr: COpaquePointer?)
""".trimMargin().trim()

private fun nugetInteropMingw(): String = """
  |@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
  |
  |package $INTERNAL_PKG
  |
  |import kotlinx.cinterop.COpaquePointer
  |import platform.windows.CoTaskMemFree
  |
  |internal actual fun freeManagedString(ptr: COpaquePointer?) {
  |  ptr?.let { CoTaskMemFree(it) }
  |}
""".trimMargin().trim()

private fun nugetInteropPosix(): String = """
  |@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
  |
  |package $INTERNAL_PKG
  |
  |import kotlinx.cinterop.COpaquePointer
  |import platform.posix.free
  |
  |internal actual fun freeManagedString(ptr: COpaquePointer?) {
  |  ptr?.let { free(it) }
  |}
""".trimMargin().trim()

// ADR-051: NugetRuntime.kt — shared runtime support emitted once into the internal package
// whenever any bound signature contains a RirObjectHandleType. Contains:
//   - freeGcHandleFn: the registered C# thunk for freeing a GCHandle
//   - nuget_runtime_register: the @CName export that C# calls at startup
//   - NugetObjectHandle: the Cleaner resource holder (separate from the wrapper to avoid
//     the createCleaner self-reference leak hazard)
private fun nugetRuntimeContent(): String = """
  |@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
  |
  |package $INTERNAL_PKG
  |
  |import kotlin.concurrent.AtomicInt
  |import kotlin.concurrent.AtomicReference
  |import kotlin.native.identityHashCode
  |import kotlinx.cinterop.COpaquePointer
  |import kotlinx.cinterop.CFunction
  |import kotlinx.cinterop.CPointer
  |import kotlinx.cinterop.ByteVar
  |import kotlinx.cinterop.allocArray
  |import kotlinx.cinterop.StableRef
  |import kotlinx.cinterop.asStableRef
  |import kotlinx.cinterop.free
  |import kotlinx.cinterop.invoke
  |import kotlinx.cinterop.nativeHeap
  |import kotlinx.cinterop.set
  |import kotlinx.cinterop.reinterpret
  |import kotlin.experimental.ExperimentalNativeApi
  |
  |internal var freeGcHandleFn: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
  |
  |// ADR-089: a weak GCHandle to the same target as the given strong one. Verified by spike: a weak
  |// handle does NOT root the bridge, which is what lets the reuse table hold one without
  |// re-creating the cross-runtime strong cycle that killed ADR-084's reuse design.
  |internal var weakenGcHandleFn:
  |  CPointer<CFunction<(COpaquePointer) -> COpaquePointer?>>? = null
  |
  |// ADR-089: a FRESH strong transfer handle to the weak handle's target, or null once the .NET GC
  |// has collected it (the mint-fresh signal).
  |internal var resolveGcHandleFn:
  |  CPointer<CFunction<(COpaquePointer) -> COpaquePointer?>>? = null
  |
  |// ADR-054: slotCount/contractHash are the same two leading scalars every register export gains
  |// (slotCount is always 3 here — the runtime shim registers exactly the free/weaken/resolve
  |// thunks). The pointer parameters are nullable so a stale caller passing zero args (pre-ADR-054)
  |// is read-only-safe up to the checkContract call, which never touches them before deciding to
  |// proceed.
  |@OptIn(ExperimentalNativeApi::class)
  |@CName("nuget_runtime_register")
  |fun nuget_runtime_register(
  |  slotCount: Int,
  |  contractHash: Long,
  |  freeGcHandlePtr: COpaquePointer?,
  |  weakenGcHandlePtr: COpaquePointer?,
  |  resolveGcHandlePtr: COpaquePointer?,
  |) {
  |  NugetRegistry.checkContract(
  |    qualifiedType = "<runtime>",
  |    packageId = "",
  |    slotCount = slotCount,
  |    contractHash = contractHash,
  |    expectedSlots = 3,
  |    expectedHash = ${NUGET_RUNTIME_CONTRACT_HASH}L,
  |  )
  |  freeGcHandleFn = requireNotNull(freeGcHandlePtr) {
  |    "nuget_runtime_register passed a null freeGcHandle thunk pointer."
  |  }.reinterpret()
  |  weakenGcHandleFn = requireNotNull(weakenGcHandlePtr) {
  |    "nuget_runtime_register passed a null weakenGcHandle thunk pointer."
  |  }.reinterpret()
  |  resolveGcHandleFn = requireNotNull(resolveGcHandlePtr) {
  |    "nuget_runtime_register passed a null resolveGcHandle thunk pointer."
  |  }.reinterpret()
  |  NugetRegistry.record("<runtime>", 3)
  |}
  |
  |// ADR-089: the reuse table's key. Identity, never `equals` — a Kotlin data class implementing a
  |// bound interface must still get one bridge PER INSTANCE, and `kotlin.native.identityHashCode`
  |// is the only stable per-instance hash on this target (verified against Kotlin/Native 2.4.10).
  |private class NugetIdentityKey(val ref: Any) {
  |  override fun equals(other: Any?): Boolean = other is NugetIdentityKey && other.ref === ref
  |
  |  @OptIn(ExperimentalNativeApi::class)
  |  override fun hashCode(): Int = ref.identityHashCode()
  |}
  |
  |// [weakBridge] never roots the C# bridge (spike-verified); [ctx] is the StableRef pointer the
  |// bridge dispatches through, and the guard the release path matches on.
  |private class NugetBridgeEntry(val weakBridge: COpaquePointer, val ctx: COpaquePointer)
  |
  |// ADR-089 lazy free: `nuget_kotlin_release` runs on the .NET finalizer thread INSIDE a C#-to-Kotlin
  |// P/Invoke, and calling a Kotlin-to-C# thunk from there is an unspiked re-entry shape. So an
  |// evicted weak handle is queued here instead of freed on the spot, and drained on the next mint
  |// (an ordinary caller thread). Bounded: at most one queued handle per dead bridge.
  |private val nugetPendingWeakFrees = AtomicReference<List<COpaquePointer>>(emptyList())
  |
  |private fun nugetQueueWeakFree(handle: COpaquePointer) {
  |  while (true) {
  |    val current: List<COpaquePointer> = nugetPendingWeakFrees.value
  |    if (nugetPendingWeakFrees.compareAndSet(current, current + handle)) return
  |  }
  |}
  |
  |private fun nugetDrainWeakFrees() {
  |  while (true) {
  |    val current: List<COpaquePointer> = nugetPendingWeakFrees.value
  |    if (current.isEmpty()) return
  |    if (nugetPendingWeakFrees.compareAndSet(current, emptyList())) {
  |      val fn = requireNotNull(freeGcHandleFn) { NugetRegistry.notRegistered("<runtime>", "") }
  |      current.forEach { fn.invoke(it) }
  |      return
  |    }
  |  }
  |}
  |
  |// ADR-089: every table registers itself here so the interface-agnostic release path can offer a
  |// dying ctx to all of them. Per-interface tables, so one Kotlin object implementing two bound
  |// interfaces holds up to one live bridge per interface.
  |private val nugetBridgeTables = AtomicReference<List<NugetBridgeTable>>(emptyList())
  |
  |// ADR-089: the per-interface bridge reuse table. One instance per `{Iface}Bindings.kt`.
  |// Every access is guarded by a CAS spinlock in the same style as NugetObjectHandle.free and
  |// NugetRegistry.record (no new dependency); contention is one acquisition per interface-typed
  |// crossing plus one per finalized bridge, nowhere near a per-member hot path.
  |internal class NugetBridgeTable {
  |  private val entries: MutableMap<NugetIdentityKey, NugetBridgeEntry> = mutableMapOf()
  |  private val lock = AtomicInt(0)
  |
  |  init {
  |    while (true) {
  |      val current: List<NugetBridgeTable> = nugetBridgeTables.value
  |      if (nugetBridgeTables.compareAndSet(current, current + this)) break
  |    }
  |  }
  |
  |  private fun <R> locked(block: () -> R): R {
  |    while (!lock.compareAndSet(0, 1)) {
  |      // Spin. The critical sections are a map lookup plus one GCHandle thunk call.
  |    }
  |    try {
  |      return block()
  |    } finally {
  |      lock.value = 0
  |    }
  |  }
  |
  |  // A FRESH strong transfer handle to the bridge already minted for [impl], or null when there is
  |  // none (or the .NET GC collected it, in which case the dead entry is cleaned out here).
  |  fun resolve(impl: Any): COpaquePointer? = locked {
  |    nugetDrainWeakFrees()
  |    val key = NugetIdentityKey(impl)
  |    val entry: NugetBridgeEntry = entries[key] ?: return@locked null
  |    val fn = requireNotNull(resolveGcHandleFn) { NugetRegistry.notRegistered("<runtime>", "") }
  |    val resolved: COpaquePointer? = fn.invoke(entry.weakBridge)
  |    if (resolved != null) return@locked resolved
  |    entries.remove(key)
  |    val free = requireNotNull(freeGcHandleFn) { NugetRegistry.notRegistered("<runtime>", "") }
  |    free.invoke(entry.weakBridge)
  |    null
  |  }
  |
  |  // Records the freshly minted [bridge] weakly. [ctx] is the release-path guard.
  |  fun store(impl: Any, bridge: COpaquePointer, ctx: COpaquePointer): Unit = locked {
  |    val fn = requireNotNull(weakenGcHandleFn) { NugetRegistry.notRegistered("<runtime>", "") }
  |    val weak: COpaquePointer = requireNotNull(fn.invoke(bridge)) {
  |      "[nuget] weakenGcHandle returned null for a live bridge handle."
  |    }
  |    val previous: NugetBridgeEntry? = entries.put(NugetIdentityKey(impl), NugetBridgeEntry(weak, ctx))
  |    // Two threads minting for the same object concurrently is the only way to get here; the loser
  |    // is a valid bridge nobody will reuse, so drop its weak handle rather than leak it.
  |    if (previous != null) nugetQueueWeakFree(previous.weakBridge)
  |  }
  |
  |  // ADR-089 ctx-guarded eviction: only the entry this exact ctx belongs to is removed. Without the
  |  // guard, a late finalizer for bridge #1 would evict the live entry a later crossing installed
  |  // for bridge #2 and leak #2's weak handle.
  |  fun evict(impl: Any, ctx: COpaquePointer): Unit = locked {
  |    val key = NugetIdentityKey(impl)
  |    val entry: NugetBridgeEntry = entries[key] ?: return@locked
  |    if (entry.ctx.rawValue != ctx.rawValue) return@locked
  |    entries.remove(key)
  |    nugetQueueWeakFree(entry.weakBridge)
  |  }
  |}
  |
  |// Holder passed as the Cleaner resource. Deliberately a separate object from the wrapper so the
  |// cleanup lambda captures nothing (createCleaner self-reference leak hazard).
  |internal class NugetObjectHandle(private val raw: COpaquePointer) {
  |  private val freed = AtomicInt(0)
  |
  |  fun free() {
  |    if (freed.compareAndSet(0, 1)) {
  |      val fn = requireNotNull(freeGcHandleFn) {
  |        NugetRegistry.notRegistered("<runtime>", "")
  |      }
  |      fn.invoke(raw)
  |    }
  |  }
  |
  |  fun require(typeName: String): COpaquePointer {
  |    check(freed.value == 0) { "${'$'}typeName is closed — the underlying C# object handle was already released." }
  |    return raw
  |  }
  |}
  |
  |// ADR-070 Decision 4: every generated wrapper (a bound class, or an interface's own
  |// `{Name}Handle`) implements this marker so an interface-typed parameter position can unwrap
  |// ANY wrapper generically, without the generated interface itself carrying a handle member
  |// (Decision 4 keeps the generated `interface IFoo` pure — no source break when Phase 13 later
  |// lets Kotlin implement it).
  |internal interface NugetHandleOwner {
  |  // Kotlin does not allow an explicit `internal` modifier on an abstract interface member (only
  |  // `public`/`private` are legal there); this member's effective visibility is bounded by the
  |  // enclosing `internal interface` itself, so it is invisible outside this module either way.
  |  val handle: NugetObjectHandle
  |}
  |
  |// ADR-070 Decision 4: the exact mirror of ADR-040's shipped
  |// `NugetMarshal.HandleOf(object)`, which throws for a Kotlin-implemented C# interface passed
  |// back to C#. An extension on Any (not on the generated interface itself) because the
  |// interface stays pure — this is the ONE place that downcasts to NugetHandleOwner.
  |// ADR-085: a value that is NOT a generated wrapper is a Kotlin implementation of the interface;
  |// mint a C#-side bridge for it (nugetMintBridge, generated into NugetKotlinBridges.kt). The
  |// error(...) text survives for a value implementing no bridgeable bound interface.
  |// [interfaceName] is the TARGET position's fully qualified C# name (`Test.Menagerie.IFeedable`):
  |// a Kotlin class may implement several bound interfaces, and only the crossing position knows
  |// which bridge C# is asking for. The error text keeps the readable simple name.
  |internal fun Any.nugetHandle(interfaceName: String): NugetObjectHandle =
  |  (this as? NugetHandleOwner)?.handle
  |    ?: nugetMintBridge(this, interfaceName)?.let { NugetObjectHandle(it) }
  |    ?: error(
  |      "[nuget] ${'$'}{this::class.simpleName} is a Kotlin implementation of " +
  |          "${'$'}{interfaceName.substringAfterLast('.')}; passing a Kotlin-implemented C# " +
  |          "interface back to C# is not supported yet."
  |    )
  |
  |// ADR-087 stage 2: the envelope a throwing slot writes through its trailing error out-param.
  |// Structurally identical to the FORWARD pipeline's NugetError (type, message, stack, cause
  |// chain, same seen-set cycle guard) but reverse-owned, because the ADR's "both halves compile
  |// into one module" claim does not hold: KSP emits CNameExports.kt into the per-target source
  |// set (macosArm64Main), while these reverse bindings live in nativeMain, its PARENT — a parent
  |// source set cannot see a child's declarations. The C# side still throws the forward PUBLIC
  |// KotlinException hierarchy, so consumers keep ONE catch hierarchy across both directions.
  |internal class NugetKotlinError(
  |  val type: String,
  |  val message: String,
  |  val stackTrace: String,
  |  val cause: NugetKotlinError?,
  |)
  |
  |internal fun nugetKotlinError(t: Throwable): COpaquePointer {
  |  val seen: MutableSet<Throwable> = mutableSetOf()
  |  fun build(e: Throwable): NugetKotlinError? {
  |    if (!seen.add(e)) return null
  |    return NugetKotlinError(
  |      type = e::class.qualifiedName ?: e::class.simpleName ?: "UnknownException",
  |      message = e.message ?: "Kotlin error",
  |      stackTrace = e.stackTraceToString(),
  |      cause = e.cause?.let(::build),
  |    )
  |  }
  |  return StableRef.create(build(t)!!).asCPointer()
  |}
  |
  |private tailrec fun NugetKotlinError.at(index: Int): NugetKotlinError =
  |  if (index == 0) this else cause!!.at(index - 1)
  |
  |private fun COpaquePointer.error(): NugetKotlinError = asStableRef<NugetKotlinError>().get()
  |
  |// The accessor exports the C# side reads the envelope through. Deliberately NOT the forward
  |// nuget_error_* entry points: those resolve a StableRef of the FORWARD NugetError class, and
  |// handing them a reverse envelope would be an unchecked cast. Strings ride the same
  |// nugetKotlinString / nuget_kotlin_string_free wire every other reverse string uses.
  |@OptIn(ExperimentalNativeApi::class)
  |@CName("nuget_kotlin_error_type")
  |fun nuget_kotlin_error_type(handle: COpaquePointer): COpaquePointer? =
  |  nugetKotlinString(handle.error().type)
  |
  |@OptIn(ExperimentalNativeApi::class)
  |@CName("nuget_kotlin_error_message")
  |fun nuget_kotlin_error_message(handle: COpaquePointer): COpaquePointer? =
  |  nugetKotlinString(handle.error().message)
  |
  |@OptIn(ExperimentalNativeApi::class)
  |@CName("nuget_kotlin_error_stacktrace")
  |fun nuget_kotlin_error_stacktrace(handle: COpaquePointer): COpaquePointer? =
  |  nugetKotlinString(handle.error().stackTrace)
  |
  |@OptIn(ExperimentalNativeApi::class)
  |@CName("nuget_kotlin_error_cause_count")
  |fun nuget_kotlin_error_cause_count(handle: COpaquePointer): Int {
  |  var count = 0
  |  var current: NugetKotlinError? = handle.error()
  |  while (current != null) {
  |    count++
  |    current = current.cause
  |  }
  |  return count
  |}
  |
  |@OptIn(ExperimentalNativeApi::class)
  |@CName("nuget_kotlin_error_cause_type")
  |fun nuget_kotlin_error_cause_type(handle: COpaquePointer, index: Int): COpaquePointer? =
  |  nugetKotlinString(handle.error().at(index).type)
  |
  |@OptIn(ExperimentalNativeApi::class)
  |@CName("nuget_kotlin_error_cause_message")
  |fun nuget_kotlin_error_cause_message(handle: COpaquePointer, index: Int): COpaquePointer? =
  |  nugetKotlinString(handle.error().at(index).message)
  |
  |@OptIn(ExperimentalNativeApi::class)
  |@CName("nuget_kotlin_error_cause_stacktrace")
  |fun nuget_kotlin_error_cause_stacktrace(handle: COpaquePointer, index: Int): COpaquePointer? =
  |  nugetKotlinString(handle.error().at(index).stackTrace)
  |
  |@OptIn(ExperimentalNativeApi::class)
  |@CName("nuget_kotlin_error_free")
  |fun nuget_kotlin_error_free(handle: COpaquePointer) {
  |  handle.asStableRef<NugetKotlinError>().dispose()
  |}
  |
  |// ADR-086: the OUT-direction lowering for a handle-backed bridge slot (a bound-object or
  |// bound-interface return or getter). Always a FRESH transfer handle, which the C# bridge member
  |// resolves and frees immediately:
  |//   - a generated wrapper (NugetHandleOwner) DUPLICATES its GCHandle through the per-interface
  |//     dup thunk. Returning the wrapper's own pointer instead would hand C# a handle whose Kotlin
  |//     owner may already be unreachable, leaving its cleaner free to release it mid-read.
  |//   - a plain Kotlin implementation mints a bridge, which is already a fresh transfer handle
  |//     (ADR-085). One bridge per crossing; C#-side identity across crossings stays unpromised.
  |// Spiked before use: GCHandle.Alloc(GCHandle.FromIntPtr(h).Target) yields an independent handle
  |// to the SAME instance, and freeing the duplicate leaves the original allocated and resolvable.
  |internal fun nugetHandleOut(
  |  value: Any?,
  |  typeName: String,
  |  dup: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>?,
  |): COpaquePointer? {
  |  if (value == null) return null
  |  val simple: String = typeName.substringAfterLast('.')
  |  val owner = value as? NugetHandleOwner
  |  if (owner != null) {
  |    val dupFn = requireNotNull(dup) {
  |      "[nuget] the ${'$'}simple bridge has no dupHandle thunk registered; the compiled C# shim " +
  |          "predates ADR-086's handle-backed slots (stale build state)."
  |    }
  |    return requireNotNull(dupFn.invoke(owner.handle.require(simple))) {
  |      "[nuget] dupHandle returned null for a ${'$'}simple handle."
  |    }
  |  }
  |  return nugetMintBridge(value, typeName)
  |    ?: error(
  |      "[nuget] ${'$'}{value::class.simpleName} was returned at a ${'$'}simple position but is " +
  |          "neither a generated wrapper nor a Kotlin implementation of a bound interface this " +
  |          "build can bridge."
  |    )
  |}
  |
  |// ADR-085 ownership: a MINTED bridge handle is a TRANSFER handle — the call site that minted
  |// it frees it once the native call returns, leaving C#'s own managed reference (if it stored the
  |// bridge) as the only root. A wrapper's OWN handle is never freed here: it belongs to the
  |// wrapper's Cleaner. The distinction is the whole reason this is a scope and not an expression:
  |// the free needs an "after the call" point, exactly like memScoped's deallocation.
  |internal class NugetTransferScope {
  |  private val minted: MutableList<NugetObjectHandle> = mutableListOf()
  |
  |  fun handleOf(value: Any, interfaceName: String): COpaquePointer {
  |    val owned: Boolean = value !is NugetHandleOwner
  |    val handle: NugetObjectHandle = value.nugetHandle(interfaceName)
  |    if (owned) minted.add(handle)
  |    return handle.require(interfaceName.substringAfterLast('.'))
  |  }
  |
  |  fun handleOfOrNull(value: Any?, interfaceName: String): COpaquePointer? =
  |    if (value == null) null else handleOf(value, interfaceName)
  |
  |  fun releaseMinted() {
  |    minted.forEach { it.free() }
  |    minted.clear()
  |  }
  |}
  |
  |// The receiver is resolved the same way memScoped's is: an interface-typed argument's conversion
  |// is `handleOf(...)`, a member of THIS scope, so a call site that can mint must wrap its invoke.
  |internal inline fun <R> nugetTransferScope(block: NugetTransferScope.() -> R): R {
  |  val scope = NugetTransferScope()
  |  try {
  |    return scope.block()
  |  } finally {
  |    scope.releaseMinted()
  |  }
  |}
  |
  |// ADR-085: a Kotlin member reached through a bridge slot returns its String on the Kotlin native
  |// heap; C# reads it with Marshal.PtrToStringUTF8 and hands the pointer straight back to
  |// nuget_kotlin_string_free. The inverse of the shipped CoTaskMemUTF8 -> toKString wire.
  |internal fun nugetKotlinString(value: String?): COpaquePointer? {
  |  if (value == null) return null
  |  val bytes: ByteArray = value.encodeToByteArray()
  |  val ptr: CPointer<ByteVar> = nativeHeap.allocArray(bytes.size + 1)
  |  bytes.forEachIndexed { i, b -> ptr[i] = b }
  |  ptr[bytes.size] = 0
  |  return ptr
  |}
  |
  |// ADR-085: a C# bridge's SafeHandle releases the Kotlin object it holds through this export.
  |@OptIn(ExperimentalNativeApi::class)
  |@CName("nuget_kotlin_string_free")
  |fun nuget_kotlin_string_free(ptr: COpaquePointer) {
  |  nativeHeap.free(ptr)
  |}
  |
  |// ADR-085 observability (the reverse mirror of ADR-084's NugetBridgeState.ReleasedCount): a
  |// release is GC-timed on the .NET side, so the only way to assert it happened is to count it.
  |private val kotlinReleaseCount = AtomicInt(0)
  |
  |internal fun nugetKotlinReleaseCount(): Int = kotlinReleaseCount.value
  |
  |@OptIn(ExperimentalNativeApi::class)
  |@CName("nuget_kotlin_release")
  |fun nuget_kotlin_release(ctx: COpaquePointer) {
  |  while (true) {
  |    val current: Int = kotlinReleaseCount.value
  |    if (kotlinReleaseCount.compareAndSet(current, current + 1)) break
  |  }
  |  // ADR-089: the bridge this ctx belonged to is gone, so drop its reuse entry before the impl
  |  // loses its last root. Offered to every table because the release path is interface-agnostic;
  |  // the ctx guard inside evict() means at most one table owns this ctx, and a table holding a
  |  // NEWER bridge for the same impl is left alone.
  |  val ref = ctx.asStableRef<Any>()
  |  val impl: Any = ref.get()
  |  nugetBridgeTables.value.forEach { it.evict(impl, ctx) }
  |  ref.dispose()
  |}
""".trimMargin().trim()

// ADR-085: one nugetMintBridge arm — [kotlinPkg].[name] is the generated Kotlin interface a value
// must be an instance of, [qualifiedName] is the C# `{Namespace}.{Type}` the crossing position
// asks for (the dispatch key; two namespaces can declare the same simple name).
private data class BridgeDispatchArm(
  val kotlinPkg: String,
  val name: String,
  val qualifiedName: String,
)

// ADR-085: NugetKotlinBridges.kt — the single dispatcher nugetHandle()'s fallback calls, one arm
// per interface a Kotlin class can implement and hand back to C#. Deliberately a separate file
// from NugetRuntime.kt: this one references the generated per-interface mint functions in their
// own packages, and NugetRuntime.kt is fixed content with no knowledge of them.
private fun nugetKotlinBridgesContent(dispatch: List<BridgeDispatchArm>): String {
  val arms: String = dispatch.joinToString("\n") { arm ->
    "  interfaceName == \"${arm.qualifiedName}\" && value is ${arm.kotlinPkg}.${arm.name} -> " +
        "${arm.kotlinPkg}.mint${arm.name}Bridge(value)"
  }
  val body: String = if (arms.isEmpty()) "  else -> null" else "$arms\n  else -> null"
  return """
    |@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    |
    |package $INTERNAL_PKG
    |
    |import kotlinx.cinterop.COpaquePointer
    |
    |// Generated (ADR-085): dispatch from a Kotlin object at an interface-typed position to the
    |// factory that mints its C#-side bridge. `null` means no bound interface matched, which is
    |// exactly the case nugetHandle() still error(...)s for.
    |// Keyed on the TARGET position's interface (its fully qualified C# name — two namespaces may
    |// declare the same simple name), not on the value's own type alone: a Kotlin class
    |// implementing two bound interfaces must mint the bridge the CROSSING POSITION asks for, and
    |// a first-match-by-declaration-order `when` silently mints the other one.
    |internal fun nugetMintBridge(value: Any, interfaceName: String): COpaquePointer? = when {
    |$body
    |}
  """.trimMargin().trim()
}

// ADR-054: NugetRegistry.kt — the always-on registration registry + contract self-check, emitted
// once whenever anything in this build registers (any bound class, or the shared runtime).
//   - `expected`: baked at generation time — every "{Namespace}.{Type}" (plus "<runtime>") this
//     build emits a register export for.
//   - `landed`: populated at process startup, one CAS per register export that actually fires.
//   - `notRegistered(...)`: computes the "N of M registrations fired" failure message, called
//     lazily by every generated stub's requireNotNull guard — only on the failure path.
//   - `checkContract(...)`: the registration-time self-check every register export calls before
//     storing any pointer (see bindingsFileContent/nugetRuntimeContent above).
private fun nugetRegistryContent(expected: List<String>): String {
  val expectedList: String = expected.joinToString(",\n    ") { "\"$it\"" }
  return """
    |package $INTERNAL_PKG
    |
    |import kotlin.concurrent.AtomicReference
    |
    |// Generated: ADR-054 always-on registration registry + contract self-check.
    |internal object NugetRegistry {
    |  private val expected: List<String> = listOf(
    |    $expectedList,
    |  )
    |
    |  private val landed = AtomicReference<List<String>>(emptyList())
    |
    |  // ADR-054: the ONLY Kotlin-side nugetTrace(...) call site. Registration-granularity only
    |  // (once per bound type at process start) — never on the hot bridge-call path, which this
    |  // function is nowhere near.
    |  fun record(qualifiedType: String, slots: Int) {
    |    while (true) {
    |      val current: List<String> = landed.value
    |      if (landed.compareAndSet(current, current + qualifiedType)) break
    |    }
    |    nugetTrace {
    |      val slotWord: String = if (slots == 1) "slot" else "slots"
    |      "registered ${'$'}qualifiedType (${'$'}slots ${'$'}slotWord) [${'$'}{landed.value.size}/${'$'}{expected.size}]"
    |    }
    |  }
    |
    |  // Computes, on the failure path only, one of two messages: "nothing has registered at all"
    |  // (a whole-assembly problem — the shim source likely never compiled in) versus "everything
    |  // but this one type registered" (scoped to this type alone). Those are different bugs with
    |  // different fixes, and telling them apart is the entire point of this function.
    |  fun notRegistered(qualifiedType: String, packageId: String): String {
    |    val landedNow: List<String> = landed.value
    |    val missing: List<String> = expected.filterNot { it in landedNow }
    |    val suffix: String = if (packageId.isEmpty()) "" else " (${'$'}packageId)"
    |    return if (landedNow.isEmpty()) {
    |      "[nuget] ${'$'}qualifiedType bindings are not registered${'$'}suffix. " +
    |        "0 of ${'$'}{expected.size} expected registrations have fired. NOTHING has registered. " +
    |        "Missing: ${'$'}{missing.joinToString(", ")}.\n\n" +
    |        "No [ModuleInitializer] in any *Registration.cs ran, so those files are not compiled into " +
    |        "any assembly the host has loaded. This is almost never a codegen bug. In order of likelihood:\n" +
    |        "  1. Stale build state: the consuming project's obj/project.assets.json was not re-resolved, " +
    |        "so NuGet never handed contentFiles/cs/any/*Registration.cs to the compiler. Delete obj/ and " +
    |        "bin/, purge the NuGet cache at ~/.nuget/packages/${'$'}packageId, restore, rebuild.\n" +
    |        "  2. The consuming project does not reference the packed package at all.\n" +
    |        "  3. The shim files compiled, but the assembly containing them was never loaded.\n" +
    |        "Verify with: NUGET_INTEROP_TRACE=1 (each [ModuleInitializer] logs as it fires)."
    |    } else {
    |      "[nuget] ${'$'}qualifiedType bindings are not registered${'$'}suffix. " +
    |        "${'$'}{landedNow.size} of ${'$'}{expected.size} expected registrations have fired: " +
    |        "${'$'}{landedNow.joinToString(", ")}. Missing: ${'$'}{missing.joinToString(", ")}.\n\n" +
    |        "Other shims DID register, so the shim source IS compiled in and the native library IS loaded. " +
    |        "Scope this to ${'$'}qualifiedType alone: its " +
    |        "${'$'}{qualifiedType.substringAfterLast('.')}Registration.cs is absent from the compiled output, " +
    |        "or its [ModuleInitializer] threw before reaching the register call.\n" +
    |        "Verify with: NUGET_INTEROP_TRACE=1."
    |    }
    |  }
    |
    |  // ADR-054: refuses to store any pointer if slotCount/contractHash disagree with this build's
    |  // own compile-time values — a mismatch means the compiled C# shim and this native library
    |  // came from different generations (one of them is stale), which would otherwise corrupt the
    |  // function-pointer table silently. Throws IllegalStateException, naming both counts, rather
    |  // than storing anything.
    |  fun checkContract(
    |    qualifiedType: String,
    |    packageId: String,
    |    slotCount: Int,
    |    contractHash: Long,
    |    expectedSlots: Int,
    |    expectedHash: Long,
    |  ) {
    |    check(slotCount == expectedSlots && contractHash == expectedHash) {
    |      "[nuget] FATAL: registration contract mismatch for ${'$'}qualifiedType (${'$'}packageId). " +
    |        "The C# shim passed ${'$'}slotCount slots (contract ${'$'}contractHash); " +
    |        "this native library expects ${'$'}expectedSlots slots (contract ${'$'}expectedHash). " +
    |        "The compiled C# shim and the native library were generated from different builds. " +
    |        "One of them is stale. No pointers were stored (a mismatched table would corrupt memory)."
    |    }
    |  }
    |}
  """.trimMargin().trim()
}

// ADR-054: NugetTrace.kt — the opt-in registration trace sink, Kotlin side. Shared code (no
// expect/actual split): the walking skeleton step verified platform.posix.stderr/fopen/fputs/
// fclose/getenv all bind AND link on mingwX64 (cross-compiled from macOS), so the mingw-macro
// binding risk this ADR flagged as a possibility does not apply — an expect/actual split would
// buy nothing here.
//
// Registration-granularity only: the only call site is NugetRegistry.record(...), once per bound
// type at process start. Nothing on the hot bridge-call path calls this — there is no branch to
// skip when the trace is off, per ADR-054's "cost when off: exactly zero" on the call path.
private fun nugetTraceContent(): String = """
  |@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
  |
  |package $INTERNAL_PKG
  |
  |import kotlinx.cinterop.toKString
  |import platform.posix.fclose
  |import platform.posix.fopen
  |import platform.posix.fputs
  |import platform.posix.getenv
  |import platform.posix.stderr
  |
  |// Generated: ADR-054 opt-in registration trace. NUGET_INTEROP_TRACE=1 (also "true"/"all")
  |// enables it; NUGET_INTEROP_TRACEFILE=<path> redirects from stderr to a file (opened in append
  |// mode, flushed — via fclose — on every line, so a crashed process still leaves the lines it
  |// wrote). Both env vars are read once per process (lazy top-level vals), not once per call.
  |private val nugetTraceEnabled: Boolean by lazy {
  |  when (getenv("NUGET_INTEROP_TRACE")?.toKString()) {
  |    "1", "true", "all" -> true
  |    else -> false
  |  }
  |}
  |
  |private val nugetTraceFilePath: String? by lazy { getenv("NUGET_INTEROP_TRACEFILE")?.toKString() }
  |
  |// The lambda parameter means the message string is never built when the trace is off — the
  |// ONLY branch this adds anywhere is the `if (!nugetTraceEnabled) return` below, and it is never
  |// reached from a bridge call, only from registration.
  |internal fun nugetTrace(message: () -> String) {
  |  if (!nugetTraceEnabled) return
  |  val line = "[nuget] ${'$'}{message()}\n"
  |  val path = nugetTraceFilePath
  |  if (path == null) {
  |    fputs(line, stderr)
  |  } else {
  |    val file = fopen(path, "a") ?: return
  |    fputs(line, file)
  |    fclose(file)
  |  }
  |}
""".trimMargin().trim()

// NugetEnums.kt: emitted once into the internal package whenever any bridgeable member hands an
// enum back from C#. Holds the single shared bounds-checked ordinal lookup every generated stub
// calls, rather than each stub inlining its own copy of the same check.
private fun nugetEnumsContent(): String = """
  |package $INTERNAL_PKG
  |
  |// Generated: fail-fast ordinal lookup, shared by every enum-returning bridge call.
  |//
  |// A C# enum is not a closed set: `(CatMood)99` is a legal C# value and any bound API can return
  |// one. Indexing `entries` with such an ordinal directly would throw a bare
  |// IndexOutOfBoundsException naming neither the enum nor the offending value, so every generated
  |// stub routes the ordinal it receives through this check first.
  |internal fun <T : Enum<T>> nugetEnumEntry(entries: List<T>, ordinal: Int, name: String): T {
  |  check(ordinal in entries.indices) {
  |    "${'$'}name has no entry for ordinal ${'$'}ordinal returned from C#. " +
  |      "Expected 0..${'$'}{entries.size - 1}; the C# enum value has no Kotlin counterpart."
  |  }
  |  return entries[ordinal]
  |}
""".trimMargin().trim()

// ADR-070 Decision 2: one member of an admissible interface, tagged with the C# NATIVE
// (`{Name}Bindings`) object it is registered under — its OWN declaring interface's, never the
// derived interface asking for it. This is what lets `{Derived}Handle` dispatch an inherited
// member through the BASE interface's own slot table (Decision 5's "handle-agnostic" insight:
// nothing needs to be re-registered) while a locally-declared member dispatches through its own.
private data class OwnedInterfaceMember(
  val bindingsObjectName: String,
  val registrable: RirRegistrable,
)

// ADR-070 Decision 5: [iface]'s OWN bridgeable registrables, plus (recursively) every ADMISSIBLE,
// BOUND base interface's own — each tagged with the Bindings object that actually owns its slot.
private fun effectiveInterfaceRegistrables(
  iface: RirInterface,
  boundHandleTypes: Set<RirTypeKey>,
  boundIfaces: Map<RirTypeKey, RirInterface>,
): List<OwnedInterfaceMember> {
  val own: List<OwnedInterfaceMember> =
    bridgeableInterfaceRegistrables(iface, boundHandleTypes, boundIfaces)
      .map { OwnedInterfaceMember("${iface.name}Bindings", it) }
  val fromBases: List<OwnedInterfaceMember> = interfaceBaseKeys(iface, boundIfaces).flatMap { key ->
    effectiveInterfaceRegistrables(boundIfaces.getValue(key), boundHandleTypes, boundIfaces)
  }
  return own + fromBases
}

// ADR-070 Decision 1: the DECLARED Kotlin signature text for one interface member — shared by the
// pure `interface` (interfaceFileContent) and its `{Name}Handle` implementation
// (interfaceHandleFileContent), so a member's declared type/nullability can never drift between
// the two.
private fun interfaceMemberSignature(
  registrable: RirRegistrable,
  qualifiedTypeNames: Map<RirTypeKey, String>,
): String = when (registrable) {
  is RirRegistrable.Method -> {
    val params: String = registrable.method.parameters.joinToString(", ") { p ->
      "${p.name}: ${declKotlinType(p.type, qualifiedTypeNames)}"
    }
    val retSuffix: String = if (registrable.method.returnType is RirVoidType) ""
    else ": ${declKotlinType(registrable.method.returnType, qualifiedTypeNames)}"
    "fun ${registrable.method.name.toMethodCamelCase()}($params)$retSuffix"
  }

  is RirRegistrable.PropertyGetter -> {
    val keyword: String = if (registrable.property.isReadOnly) "val" else "var"
    "$keyword ${registrable.property.name.toMethodCamelCase()}: " +
        declKotlinType(registrable.property.type, qualifiedTypeNames)
  }

  is RirRegistrable.PropertySetter -> error(
    "[nuget] a PropertySetter never renders its own declaration line (see PropertyGetter " +
        "with isReadOnly=false, which renders 'var')",
  )

  is RirRegistrable.Ctor -> error("[nuget] an interface never has a constructor (ADR-070)")
}

// ADR-070 Decision 1: the pure Kotlin `interface` for an admissible, bound C# interface — members
// only, no handle. Declares Kotlin supertypes for every admissible+bound base interface (Decision
// 5) so ordinary Kotlin interface inheritance provides the DECLARED (not dispatched — see
// interfaceHandleFileContent) member set for a derived interface's own callers.
private fun interfaceFileContent(
  kotlinPkg: String,
  iface: RirInterface,
  boundIfaces: Map<RirTypeKey, RirInterface>,
  packageId: String,
  enumPkgs: Map<RirTypeKey, String> = emptyMap(),
  interfacePkgs: Map<RirTypeKey, String> = emptyMap(),
  qualifiedTypeNames: Map<RirTypeKey, String> = emptyMap(),
): String {
  val baseKeys: List<RirTypeKey> = interfaceBaseKeys(iface, boundIfaces)
  val supertypesSuffix: String =
    if (baseKeys.isEmpty()) "" else " : " + baseKeys.joinToString(", ") { it.name }

  val ownRegistrables: List<RirRegistrable> =
    iface.methods.filterNot { it.isStatic }.map { RirRegistrable.Method(it) } +
        iface.properties.filterNot { it.isStatic }.map { RirRegistrable.PropertyGetter(it) }

  // A member's DECLARED type can name an enum from another bound package (`var energy:
  // EnergyLevel`), exactly as a bound class's member can — same enumImports resolution.
  val imports: List<String> = (baseKeys
    .filter { interfacePkgs[it] != null && interfacePkgs[it] != kotlinPkg }
    .map { "import ${interfacePkgs.getValue(it)}.${it.name}" } +
      enumImports(registrableEnumTypes(ownRegistrables), enumPkgs, kotlinPkg))
    .distinct()
    .sorted()
  val importsBlock: String = if (imports.isEmpty()) "" else imports.joinToString("\n") + "\n\n"

  val members: String = ownRegistrables.joinToString("\n") { r ->
    "  ${interfaceMemberSignature(r, qualifiedTypeNames)}"
  }
  val body: String = if (members.isEmpty()) "" else "\n$members\n"

  return """
    |package $kotlinPkg
    |
    |${importsBlock}// Generated: pure Kotlin interface for the C# interface `$packageId.${iface.name}`
    |// (ADR-070). No handle member by design (Decision 4) — see [${iface.name}Handle] for the
    |// handle-backed implementation reached across the bridge.
    |internal interface ${iface.name}$supertypesSuffix {$body}
  """.trimMargin().trim()
}

// ADR-070 Decision 1 (the "Invoker" shape, mirroring Xamarin's Java interface bindings): the
// handle-backed implementation of an admissible interface — every effective (own + inherited)
// member dispatches through its OWN declaring interface's registered slot table, using THIS
// wrapper's single handle (Decision 5's "handle-agnostic" mechanism — nothing needs to be
// re-registered for a derived interface).
private fun interfaceHandleFileContent(
  kotlinPkg: String,
  iface: RirInterface,
  boundIfaces: Map<RirTypeKey, RirInterface>,
  boundHandleTypes: Set<RirTypeKey>,
  packageId: String,
  namespaceName: String,
  enumPkgs: Map<RirTypeKey, String>,
  interfacePkgs: Map<RirTypeKey, String>,
  qualifiedTypeNames: Map<RirTypeKey, String>,
): String {
  val effective: List<OwnedInterfaceMember> =
    effectiveInterfaceRegistrables(iface, boundHandleTypes, boundIfaces)

  val hasString: Boolean = effective.any { m ->
    when (val r = m.registrable) {
      is RirRegistrable.Method -> r.method.returnType is RirStringType ||
          r.method.parameters.any { it.type is RirStringType }

      is RirRegistrable.PropertyGetter -> r.property.type is RirStringType
      else -> false
    }
  }
  val hasEnumReturn: Boolean = effective.any { m ->
    when (val r = m.registrable) {
      is RirRegistrable.Method -> r.method.returnType is RirEnumType
      is RirRegistrable.PropertyGetter -> r.property.type is RirEnumType
      else -> false
    }
  }
  val enumTypes: List<RirEnumType> = registrableEnumTypes(effective.map { it.registrable })

  val imports: MutableList<String> = mutableListOf(
    "import $INTERNAL_PKG.NugetHandleOwner",
    "import $INTERNAL_PKG.NugetObjectHandle",
    "import $INTERNAL_PKG.NugetRegistry",
    "import $INTERNAL_PKG.nugetTransferScope",
    "import kotlin.experimental.ExperimentalNativeApi",
    "import kotlin.native.ref.createCleaner",
    "import kotlinx.cinterop.COpaquePointer",
    "import kotlinx.cinterop.invoke",
  )
  if (hasString) {
    imports.add("import $INTERNAL_PKG.freeManagedString")
    imports.add("import kotlinx.cinterop.ByteVar")
    imports.add("import kotlinx.cinterop.reinterpret")
    imports.add("import kotlinx.cinterop.toKString")
  }
  val hasStringParam: Boolean = effective.any { m ->
    (m.registrable as? RirRegistrable.Method)?.method?.parameters
      ?.any { it.type is RirStringType } == true
  }
  if (hasStringParam) {
    imports.add("import kotlinx.cinterop.cstr")
    imports.add("import kotlinx.cinterop.memScoped")
    imports.add("import kotlinx.cinterop.ptr")
  }
  if (hasEnumReturn) imports.add("import $INTERNAL_PKG.nugetEnumEntry")
  imports.addAll(enumImports(enumTypes, enumPkgs, kotlinPkg))

  val baseKeys: List<RirTypeKey> = interfaceBaseKeys(iface, boundIfaces)
  imports.addAll(
    baseKeys.filter { interfacePkgs[it] != null && interfacePkgs[it] != kotlinPkg }
      .map { "import ${interfacePkgs.getValue(it)}.${it.name}" },
  )

  // ADR-070: a settable property is TWO registrables (PropertyGetter + PropertySetter, Decision
  // 2) but must render as ONE Kotlin member (val/var with an attached get()/set() — a bare
  // `set(value) { ... }` with no preceding property declaration does not compile). Grouped by
  // (bindingsObjectName, property name) so an inherited settable property (getter owned by one
  // interface's Bindings object, setter by the same) still combines correctly.
  val setterKeys: Set<Pair<String, String>> = effective
    .mapNotNull { owned ->
      (owned.registrable as? RirRegistrable.PropertySetter)
        ?.let { owned.bindingsObjectName to it.property.name }
    }
    .toSet()
  val memberBlocks: List<String> = effective.mapNotNull { owned ->
    when (owned.registrable) {
      is RirRegistrable.Method ->
        interfaceHandleMethodMember(iface.name, owned, packageId, namespaceName)

      is RirRegistrable.PropertyGetter -> {
        val hasSetter: Boolean =
          (owned.bindingsObjectName to owned.registrable.property.name) in setterKeys
        interfaceHandlePropertyMember(iface.name, owned, hasSetter, packageId, namespaceName)
      }
      // Consumed above via the getter pass (Decision, "one Kotlin member per property").
      is RirRegistrable.PropertySetter -> null
      is RirRegistrable.Ctor -> error("[nuget] an interface never has a constructor (ADR-070)")
    }
  }
  val membersText: String = memberBlocks.joinToString("\n\n") { it.prependIndent("  ") }

  return buildString {
    appendLine("@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)")
    appendLine()
    appendLine("package $kotlinPkg")
    appendLine()
    imports.distinct().sorted().forEach { appendLine(it) }
    appendLine()
    appendLine(
      "// Generated: the handle-backed implementation of the C# interface " +
          "`$packageId.${iface.name}` (ADR-070's \"Invoker\" shape).",
    )
    appendLine()
    appendLine("/**")
    appendLine(" * A value received at an [${iface.name}]-typed position — always this wrapper,")
    appendLine(
      " * never the concrete bound class the underlying C# object might also be (ADR-070 " +
          "Decision 3).",
    )
    appendLine(" */")
    appendLine("@OptIn(ExperimentalNativeApi::class)")
    appendLine(
      "internal class ${iface.name}Handle internal constructor(handle: COpaquePointer) : " +
          "${iface.name}, NugetHandleOwner, AutoCloseable {",
    )
    appendLine("  override val handle: NugetObjectHandle = NugetObjectHandle(handle)")
    appendLine()
    appendLine("  @Suppress(\"unused\")")
    appendLine("  private val cleaner = createCleaner(this.handle) { it.free() }")
    appendLine()
    appendLine("  override fun close(): Unit = handle.free()")
    if (membersText.isNotEmpty()) {
      appendLine()
      appendLine(membersText)
    }
    append("}")
  }
}

// ADR-070 Decision 2/5: one dispatched METHOD member of `{Name}Handle` — routes through [owned]'s
// OWN declaring interface's Bindings object (which may not be [handleTypeName] itself, for an
// inherited member), using this wrapper's own `handle` field as the receiver either way (a base
// interface's slot table is handle-agnostic).
private fun interfaceHandleMethodMember(
  handleTypeName: String,
  owned: OwnedInterfaceMember,
  packageId: String,
  namespaceName: String,
): String {
  val r = owned.registrable as RirRegistrable.Method
  val failMsg: String = bindingsNotRegisteredMessage(handleTypeName, packageId, namespaceName)
  val receiverArg = "handle.require(\"$handleTypeName\")"
  val name: String = r.method.name.toMethodCamelCase()
  val fnVar = "${owned.bindingsObjectName}.$name${r.method.bridgeSuffix()}Fn"
  val params: String =
    r.method.parameters.joinToString(", ") { "${it.name}: ${declKotlinType(it.type)}" }
  val retSuffix: String =
    if (r.method.returnType is RirVoidType) "" else ": ${declKotlinType(r.method.returnType)}"
  val hasStringParam: Boolean = r.method.parameters.any { it.type is RirStringType }
  val hasInterfaceParam: Boolean = r.method.parameters.any { it.type is RirInterfaceType }
  val paramArgs: List<String> = r.method.parameters.map { argConversion(it.type, it.name) }
  val invokeArgs: String = (listOf(receiverArg) + paramArgs).joinToString(", ")
  val invokeCall: String =
    wrapInvoke("fn.invoke($invokeArgs)", hasStringParam, hasInterfaceParam)
  return interfaceHandleReturnBlock(
    "override fun $name($params)$retSuffix", r.method.returnType, fnVar, failMsg, invokeCall,
    "$handleTypeName.${r.method.name}",
  )
}

// ADR-070 Decision 2/5: one dispatched PROPERTY member of `{Name}Handle` — a settable property is
// TWO registrables (getter + setter) but ONE Kotlin member (a `var` with an attached get()/set()),
// mirroring buildStubProperty's [hasSetter] shape for a class member.
private fun interfaceHandlePropertyMember(
  handleTypeName: String,
  owned: OwnedInterfaceMember,
  hasSetter: Boolean,
  packageId: String,
  namespaceName: String,
): String {
  val r = owned.registrable as RirRegistrable.PropertyGetter
  val failMsg: String = bindingsNotRegisteredMessage(handleTypeName, packageId, namespaceName)
  val receiverArg = "handle.require(\"$handleTypeName\")"
  val name: String = r.property.name.toMethodCamelCase()
  val getterFnVar = "${owned.bindingsObjectName}.${name}GetterFn"
  val invokeCall = "fn.invoke($receiverArg)"
  val getterBlock: String = interfaceHandleReturnBlock(
    "get()", r.property.type, getterFnVar, failMsg, invokeCall,
    "$handleTypeName.${r.property.name}",
  )
  val keyword: String = if (hasSetter) "var" else "val"

  val setterBlock: String? = if (!hasSetter) null else {
    val setterFnVar = "${owned.bindingsObjectName}.${name}SetterFn"
    val valueArg: String = argConversion(r.property.type, "value")
    val invokeSetterCall: String = wrapInvoke(
      "fn.invoke($receiverArg, $valueArg)",
      r.property.type is RirStringType,
      r.property.type is RirInterfaceType,
    )
    """
      |set(value) {
      |  val fn = requireNotNull($setterFnVar) {
      |    $failMsg
      |  }
      |  $invokeSetterCall
      |}
    """.trimMargin()
  }

  return buildString {
    appendLine("override $keyword $name: ${declKotlinType(r.property.type)}")
    append(getterBlock.prependIndent("  "))
    if (setterBlock != null) {
      appendLine()
      append(setterBlock.prependIndent("  "))
    }
  }
}

// ADR-070: shared return-handling for an interface member's dispatch body — the SAME per-type
// conversion buildStubMethod/buildStubProperty already use for a class member, restricted to the
// v1 interface-member vocabulary (Decision 6: void, string, primitives, bound enums, bound class
// handles, bound interface handles — no structs).
private fun interfaceHandleReturnBlock(
  signature: String,
  returnType: RirTypeRef,
  fnVar: String,
  failMsg: String,
  invokeCall: String,
  memberQualifiedName: String,
): String {
  val nullMsg = "$memberQualifiedName returned null, expected a non-null string pointer"
  val nonNullHandleMsg = "$memberQualifiedName returned null, but the C# API annotates it non-null."
  return when (returnType) {
    is RirVoidType -> """
      |$signature {
      |  val fn = requireNotNull($fnVar) {
      |    $failMsg
      |  }
      |  $invokeCall
      |}
    """.trimMargin()

    is RirStringType -> if (returnType.nullable) """
      |$signature {
      |  val fn = requireNotNull($fnVar) {
      |    $failMsg
      |  }
      |  val resultPtr = $invokeCall
      |    ?: return null
      |  val result = resultPtr.reinterpret<ByteVar>().toKString()
      |  freeManagedString(resultPtr)
      |  return result
      |}
    """.trimMargin() else """
      |$signature {
      |  val fn = requireNotNull($fnVar) {
      |    $failMsg
      |  }
      |  val resultPtr = $invokeCall
      |    ?: error("$nullMsg")
      |  val result = resultPtr.reinterpret<ByteVar>().toKString()
      |  freeManagedString(resultPtr)
      |  return result
      |}
    """.trimMargin()

    is RirObjectHandleType -> if (returnType.nullable) """
      |$signature {
      |  val fn = requireNotNull($fnVar) {
      |    $failMsg
      |  }
      |  val ptr: COpaquePointer? = $invokeCall
      |  return ptr?.let { ${returnType.name}(it) }
      |}
    """.trimMargin() else """
      |$signature {
      |  val fn = requireNotNull($fnVar) {
      |    $failMsg
      |  }
      |  val ptr: COpaquePointer? = $invokeCall
      |  return ${returnType.name}(requireNotNull(ptr) {
      |    "$nonNullHandleMsg"
      |  })
      |}
    """.trimMargin()

    is RirInterfaceType -> if (returnType.nullable) """
      |$signature {
      |  val fn = requireNotNull($fnVar) {
      |    $failMsg
      |  }
      |  val ptr: COpaquePointer? = $invokeCall
      |  return ptr?.let { nuget${returnType.name}Value(it) }
      |}
    """.trimMargin() else """
      |$signature {
      |  val fn = requireNotNull($fnVar) {
      |    $failMsg
      |  }
      |  val ptr: COpaquePointer? = $invokeCall
      |  return nuget${returnType.name}Value(requireNotNull(ptr) {
      |    "$nonNullHandleMsg"
      |  })
      |}
    """.trimMargin()

    is RirEnumType -> """
      |$signature {
      |  val fn = requireNotNull($fnVar) {
      |    $failMsg
      |  }
      |  return nugetEnumEntry(${returnType.name}.entries, $invokeCall, "${returnType.name}")
      |}
    """.trimMargin()

    is RirPrimitiveType -> {
      val returnExpr: String =
        if (returnType.name == "char") "$invokeCall.toInt().toChar()" else invokeCall
      """
        |$signature {
        |  val fn = requireNotNull($fnVar) {
        |    $failMsg
        |  }
        |  return $returnExpr
        |}
      """.trimMargin()
    }

    is RirStructType -> error(
      "[nuget] struct-typed interface members are out of scope (ADR-070 v1)",
    )

    is RirGenericInstanceType, is RirTypeParameterType -> error(
      "[nuget] generic-typed interface members are out of scope (ADR-070/ADR-072: generic " +
          "interfaces are excluded)",
    )
  }
}

// ADR-070 Decision 2: `{Name}Bindings.kt` — an interface's own registration export
// (`nuget_{ns}_{Name}_register`) and function-pointer table, structurally identical to a class's
// (bindingsFileContent) but with NO constructor slot (an interface has none) and only [iface]'s
// OWN registrables (never inherited ones — Decision 5's handle-agnostic dispatch means a derived
// interface's Handle wrapper calls the BASE interface's Bindings object directly, so nothing here
// needs to duplicate it).
private fun interfaceBindingsFileContent(
  kotlinPkg: String,
  namespaceName: String,
  iface: RirInterface,
  registrables: List<RirRegistrable>,
  exportName: String,
  packageId: String,
  bridgePlan: KotlinBridgePlan? = null,
  enumPkgs: Map<RirTypeKey, String> = emptyMap(),
  // ADR-086: the generated Kotlin package of every bound class/interface, so a handle-backed slot
  // body can import a wrapper (or a nuget{Iface}Value helper) declared in another bound namespace.
  typePkgs: Map<RirTypeKey, String> = emptyMap(),
): String {
  val objectName = "${iface.name}Bindings"

  fun namesString(list: List<RirRegistrable>): Boolean = list.any { r ->
    when (r) {
      is RirRegistrable.Method -> r.method.returnType is RirStringType ||
          r.method.parameters.any { it.type is RirStringType }

      is RirRegistrable.PropertyGetter -> r.property.type is RirStringType
      is RirRegistrable.PropertySetter -> r.property.type is RirStringType
      is RirRegistrable.Ctor -> error("[nuget] an interface never has a constructor (ADR-070)")
    }
  }

  val hasString: Boolean = namesString(registrables)
  // ADR-085 Wave 2: a FLATTENED slot list can name a String no own registration slot does (the
  // string member is the base interface's), and the slot bodies still need ByteVar/toKString.
  val bridgeHasString: Boolean = bridgePlan != null && namesString(bridgePlan.slots)
  val imports: List<String> = buildList {
    if (hasString) add("import $INTERNAL_PKG.freeManagedString")
    if (hasString || bridgeHasString) add("import kotlinx.cinterop.ByteVar")
    add("import $INTERNAL_PKG.NugetRegistry")
    add("import $INTERNAL_PKG.NugetObjectHandle")
    add("import kotlinx.cinterop.CFunction")
    add("import kotlinx.cinterop.COpaquePointer")
    add("import kotlinx.cinterop.CPointer")
    add("import kotlinx.cinterop.asStableRef")
    add("import kotlinx.cinterop.invoke")
    add("import kotlinx.cinterop.reinterpret")
    add("import kotlin.experimental.ExperimentalNativeApi")
    // ADR-085: only the minted-bridge half needs these.
    if (bridgePlan != null) {
      add("import $INTERNAL_PKG.nugetKotlinString")
      // ADR-089: the per-interface bridge reuse table beside mint{Iface}Bridge.
      add("import $INTERNAL_PKG.NugetBridgeTable")
      add("import kotlinx.cinterop.StableRef")
      add("import kotlinx.cinterop.staticCFunction")
      if (bridgeHasString) add("import kotlinx.cinterop.toKString")
      val hasEnumSlot: Boolean = bridgePlan.slots.any { r ->
        when (r) {
          is RirRegistrable.Method -> r.method.parameters.any { it.type is RirEnumType }
          is RirRegistrable.PropertySetter -> r.property.type is RirEnumType
          else -> false
        }
      }
      if (hasEnumSlot) add("import $INTERNAL_PKG.nugetEnumEntry")
      // ADR-087 stage 2: every slot writes its error envelope through a trailing out-param.
      add("import $INTERNAL_PKG.nugetKotlinError")
      add("import kotlinx.cinterop.COpaquePointerVar")
      add("import kotlinx.cinterop.pointed")
      add("import kotlinx.cinterop.value")
      // ADR-086: the out-direction handle lowering, needed only by a handle-backed return/getter.
      if (bridgePlan.needsDupHandle) add("import $INTERNAL_PKG.nugetHandleOut")
      addAll(slotHandleImports(bridgePlan.slots, typePkgs, kotlinPkg))
      // A slot body NAMES its enum types (`nugetEnumEntry(EnergyLevel.entries, ...)`); one
      // declared in another bound package needs the same import a class stub gets. Only the
      // minted-bridge half names an enum at all — the registration half is all wire types.
      addAll(enumImports(registrableEnumTypes(bridgePlan.slots), enumPkgs, kotlinPkg))
    }
  }

  val fnVars: String = registrables.joinToString("\n\n") { r ->
    when (r) {
      is RirRegistrable.Method -> {
        // ADR-070: an interface method always has a receiver (no statics reach RirInterface,
        // Decision 6) — the SAME COpaquePointer? receiver slot a class instance method uses.
        val paramCfnTypes: String =
          (listOf("COpaquePointer?") + r.method.parameters.map { cfnType(it.type) })
            .joinToString(", ")
        val retCfnType: String = cfnType(r.method.returnType)
        "@Suppress(\"NOTHING_TO_INLINE\")\n" +
            "internal var ${r.method.name.toMethodCamelCase()}${r.method.bridgeSuffix()}Fn: " +
            "CPointer<CFunction<($paramCfnTypes) -> $retCfnType>>? = null"
      }

      is RirRegistrable.PropertyGetter -> {
        val name: String = r.property.name.toMethodCamelCase()
        "@Suppress(\"NOTHING_TO_INLINE\")\n" +
            "internal var ${name}GetterFn: " +
            "CPointer<CFunction<(COpaquePointer?) -> ${cfnType(r.property.type)}>>? = null"
      }

      is RirRegistrable.PropertySetter -> {
        val name: String = r.property.name.toMethodCamelCase()
        "@Suppress(\"NOTHING_TO_INLINE\")\n" +
            "internal var ${name}SetterFn: " +
            "CPointer<CFunction<(COpaquePointer?, ${cfnType(r.property.type)}) -> Unit>>? = null"
      }

      is RirRegistrable.Ctor -> error("[nuget] an interface never has a constructor (ADR-070)")
    }
  }
  val params: String = registrables.joinToString(",\n  ") { r ->
    when (r) {
      is RirRegistrable.Method ->
        "${r.method.name.toMethodCamelCase()}${r.method.bridgeSuffix()}Ptr: COpaquePointer?"

      is RirRegistrable.PropertyGetter ->
        "${r.property.name.toMethodCamelCase()}GetterPtr: COpaquePointer?"

      is RirRegistrable.PropertySetter ->
        "${r.property.name.toMethodCamelCase()}SetterPtr: COpaquePointer?"

      is RirRegistrable.Ctor -> error("[nuget] an interface never has a constructor (ADR-070)")
    }
  }
  val assignments: String = registrables.joinToString("\n  ") { r ->
    when (r) {
      is RirRegistrable.Method -> {
        val name: String = r.method.name.toMethodCamelCase() + r.method.bridgeSuffix()
        "$objectName.${name}Fn = requireNotNull(${name}Ptr).reinterpret()"
      }

      is RirRegistrable.PropertyGetter -> {
        val name: String = r.property.name.toMethodCamelCase()
        "$objectName.${name}GetterFn = requireNotNull(${name}GetterPtr).reinterpret()"
      }

      is RirRegistrable.PropertySetter -> {
        val name: String = r.property.name.toMethodCamelCase()
        "$objectName.${name}SetterFn = requireNotNull(${name}SetterPtr).reinterpret()"
      }

      is RirRegistrable.Ctor -> error("[nuget] an interface never has a constructor (ADR-070)")
    }
  }
  // ADR-070 Work item 7: contractHash's leading parameter generalized to a bare name — an
  // interface's own name works identically for its register export's contract.
  val memberHash: Long = contractHash(iface.name, registrables, emptyMap())

  // ADR-085: a plannable interface registers TWO extra trailing slots — the C# bridge factory and
  // the identity-token probe — so slotCount and the contract hash both shift, and a stale shim
  // that knows nothing of them is rejected at startup rather than mis-assigning pointers.
  // ADR-086: a handle-backed out position adds a THIRD — the dup thunk.
  val extraSlots: Int = when {
    bridgePlan == null -> 0
    bridgePlan.needsDupHandle -> 3
    else -> 2
  }
  val slotCount: Int = registrables.size + extraSlots
  val hash: Long =
    if (bridgePlan == null) memberHash else kotlinBridgeContractHash(memberHash, bridgePlan)
  val dupFnVar: String = if (bridgePlan?.needsDupHandle != true) "" else "\n\n" + """
    |internal var dupHandleFn:
    |  CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
  """.trimMargin()
  val bridgeFnVars: String = if (bridgePlan == null) "" else {
    val factoryParams: String =
      (bridgePlan.slots.map { "COpaquePointer?" } + "COpaquePointer?").joinToString(", ")
    "\n\n" + """
      |internal var createBridgeFn:
      |  CPointer<CFunction<($factoryParams) -> COpaquePointer?>>? = null
      |
      |internal var bridgeTokenFn:
      |  CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    """.trimMargin() + dupFnVar
  }
  val bridgeParams: String =
    if (bridgePlan == null) "" else "\n  createBridgePtr: COpaquePointer?," +
        "\n  bridgeTokenPtr: COpaquePointer?," +
        if (bridgePlan.needsDupHandle) "\n  dupHandlePtr: COpaquePointer?," else ""
  val bridgeAssignments: String = if (bridgePlan == null) "" else
    "\n  $objectName.createBridgeFn = requireNotNull(createBridgePtr).reinterpret()" +
        "\n  $objectName.bridgeTokenFn = requireNotNull(bridgeTokenPtr).reinterpret()" +
        if (bridgePlan.needsDupHandle) {
          "\n  $objectName.dupHandleFn = requireNotNull(dupHandlePtr).reinterpret()"
        } else ""
  val bridgeBlock: String =
    if (bridgePlan == null) "" else
      "\n\n" + kotlinBridgeBlock(bridgePlan, objectName, packageId, namespaceName)
  val valueHelper: String = kotlinInterfaceValueHelper(iface, bridgePlan != null)

  return """
    |@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    |
    |package $kotlinPkg
    |
    |${imports.joinToString("\n")}
    |
    |internal object $objectName {
    |${fnVars.indented("  ")}${bridgeFnVars.indented("  ")}
    |}
    |
    |@OptIn(ExperimentalNativeApi::class)
    |@CName("$exportName")
    |fun $exportName(
    |  slotCount: Int,
    |  contractHash: Long,
    |  $params,$bridgeParams
    |) {
    |  NugetRegistry.checkContract(
    |    qualifiedType = "$namespaceName.${iface.name}",
    |    packageId = "$packageId",
    |    slotCount = slotCount,
    |    contractHash = contractHash,
    |    expectedSlots = $slotCount,
    |    expectedHash = ${hash}L,
    |  )
    |  $assignments$bridgeAssignments
    |  NugetRegistry.record("$namespaceName.${iface.name}", $slotCount)
    |}$bridgeBlock
    |
    |$valueHelper
  """.trimMargin().trim()
}

// ADR-085 identity: a value arriving at an [iface]-typed RETURN position is normally wrapped as
// `{Name}Handle`, but when the underlying C# object is a bridge THIS build minted for a Kotlin
// object, the original Kotlin object must come back (`assertSame`), not a wrapper around a bridge
// around it. Always emitted so every return site can call it unconditionally; without a plan it
// is just the wrapper construction it replaces.
private fun kotlinInterfaceValueHelper(iface: RirInterface, planned: Boolean): String =
  if (!planned) """
    |internal fun nuget${iface.name}Value(ptr: COpaquePointer): ${iface.name} =
    |  ${iface.name}Handle(ptr)
  """.trimMargin() else """
    |internal fun nuget${iface.name}Value(ptr: COpaquePointer): ${iface.name} {
    |  val token: COpaquePointer? = ${iface.name}Bindings.bridgeTokenFn?.invoke(ptr)
    |  if (token != null) {
    |    // The bridge owns the ctx StableRef, and C# owns the bridge; this fresh transfer GCHandle
    |    // is ours to free (ADR-085's transfer-handle ownership, mirroring ADR-084 as shipped).
    |    NugetObjectHandle(ptr).free()
    |    return token.asStableRef<Any>().get() as ${iface.name}
    |  }
    |  return ${iface.name}Handle(ptr)
    |}
  """.trimMargin()

// ADR-085: the Kotlin half of a minted bridge — one top-level `staticCFunction`-able slot
// function per registered member (slot i here is slot i in the C# bridge's parameter list, both
// derived from the SAME KotlinBridgePlan) plus the mint entry point nugetMintBridge dispatches to.
private fun kotlinBridgeBlock(
  plan: KotlinBridgePlan,
  objectName: String,
  packageId: String,
  namespaceName: String,
): String {
  val iface: String = plan.iface.name
  val prefix: String = iface.replaceFirstChar { it.lowercaseChar() }
  val slotFns: List<Pair<String, String>> =
    plan.slots.map { kotlinBridgeSlotFunction(plan, prefix, objectName, it) }
  val failMsg: String = bindingsNotRegisteredMessage(iface, packageId, namespaceName)
  val mintArgs: String = (slotFns.map { "staticCFunction(::${it.first})" } + "ctx")
    .joinToString(",\n    ")
  return """
    |${slotFns.joinToString("\n\n") { it.second }}
    |
    |// ADR-089: this interface's bridge reuse table, keyed on the implementation object's identity.
    |// Holds the bridge WEAKLY, so it never roots what the .NET GC owns.
    |private val ${prefix}BridgeTable: NugetBridgeTable = NugetBridgeTable()
    |
    |// ADR-085: mint a C#-side bridge implementing `$iface` over a Kotlin object. The returned
    |// GCHandle is a TRANSFER handle: C# keeps its own managed reference if it stores the bridge.
    |// ADR-089: resolve-or-mint. While C# keeps the first bridge alive, every later crossing of the
    |// SAME Kotlin object resolves to it, so C#-side ReferenceEquals holds. Once the bridge is
    |// collected the table's weak handle resolves to null and this mints a fresh one.
    |internal fun mint${iface}Bridge(impl: $iface): COpaquePointer {
    |  val reused: COpaquePointer? = ${prefix}BridgeTable.resolve(impl)
    |  if (reused != null) return reused
    |  val fn = requireNotNull($objectName.createBridgeFn) {
    |    $failMsg
    |  }
    |  val ctx: COpaquePointer = StableRef.create(impl).asCPointer()
    |  val bridge: COpaquePointer = requireNotNull(
    |    fn.invoke(
    |    $mintArgs,
    |    ),
    |  ) {
    |    "[nuget] Create${iface}Bridge returned a null bridge handle."
    |  }
    |  ${prefix}BridgeTable.store(impl, bridge, ctx)
    |  return bridge
    |}
  """.trimMargin()
}

// One slot: (function name, source). The ctx pointer is always the StableRef of the Kotlin object;
// every other parameter and the return value ride the ADR-049/051 reverse wire types, read the
// other way round (Kotlin implements what C# calls, instead of calling what C# implements).
private fun kotlinBridgeSlotFunction(
  plan: KotlinBridgePlan,
  prefix: String,
  objectName: String,
  slot: RirRegistrable,
): Pair<String, String> {
  val iface: String = plan.iface.name
  val target = "ctx!!.asStableRef<$iface>().get()"
  // ADR-086: only a handle-backed out cell reads this; it is null for every other slot shape.
  val dupFnRef = "$objectName.dupHandleFn"
  return when (slot) {
    is RirRegistrable.Method -> {
      val fnName = "$prefix${slot.method.name}${slot.method.bridgeSuffix()}Slot"
      val declaredParams: String = (listOf("ctx: COpaquePointer?") +
          slot.method.parameters.mapIndexed { i, p -> "a$i: ${cfnType(p.type)}" } + ERR_OUT_PARAM)
        .joinToString(", ")
      val args: String = slot.method.parameters
        .mapIndexed { i, p -> kotlinBridgeInbound(p.type, "a$i") }
        .joinToString(", ")
      val call = "$target.${slot.method.name.toMethodCamelCase()}($args)"
      val body: String = kotlinBridgeOutbound(slot.method.returnType, call, dupFnRef)
      val ret: String = cfnType(slot.method.returnType)
      fnName to "private fun $fnName($declaredParams): $ret = " +
          kotlinSlotEnvelope(body, ret)
    }

    is RirRegistrable.PropertyGetter -> {
      val fnName = "$prefix${slot.property.name}GetterSlot"
      val call = "$target.${slot.property.name.toMethodCamelCase()}"
      val body: String = kotlinBridgeOutbound(slot.property.type, call, dupFnRef)
      val ret: String = cfnType(slot.property.type)
      fnName to "private fun $fnName(ctx: COpaquePointer?, $ERR_OUT_PARAM): $ret = " +
          kotlinSlotEnvelope(body, ret)
    }

    is RirRegistrable.PropertySetter -> {
      val fnName = "$prefix${slot.property.name}SetterSlot"
      val value: String = kotlinBridgeInbound(slot.property.type, "a0")
      val body = "$target.${slot.property.name.toMethodCamelCase()} = $value"
      fnName to
          "private fun $fnName(ctx: COpaquePointer?, a0: ${cfnType(slot.property.type)}, " +
          "$ERR_OUT_PARAM) {\n" +
          kotlinSlotEnvelope(body, "Unit").prependIndent("  ") + "\n}"
    }

    is RirRegistrable.Ctor -> error("[nuget] an interface never has a constructor (ADR-070)")
  }
}

// ADR-087 stage 2: the trailing out-param every slot gains, the slot-boundary mirror of ADR-024's
// forward error slot. C# passes `IntPtr*`; a nullable receiver keeps a (stale, pre-stage-2) caller
// passing nothing from writing through a wild pointer.
private const val ERR_OUT_PARAM = "errOut: CPointer<COpaquePointerVar>?"

// ADR-087 stage 2: what a slot returns after it has written the envelope. Never read by C# (the
// bridge member checks errOut FIRST), so the only requirement is that it type-checks and allocates
// nothing — in particular a string slot returns a null pointer, which the C# side must not hand to
// nuget_kotlin_string_free.
private fun cfnDummyValue(cfnType: String): String = when (cfnType) {
  "Unit" -> ""
  "Boolean" -> "false"
  "Byte" -> "0"
  "UByte" -> "0u"
  "Short" -> "0"
  "UShort" -> "0u"
  "Int" -> "0"
  "Long" -> "0L"
  "Float" -> "0.0f"
  "Double" -> "0.0"
  "COpaquePointer?" -> "null"
  else -> error(
    "[nuget] ADR-087 stage 2 has no dummy return value for slot wire type '$cfnType'. " +
        "Add one here when the slot vocabulary grows; a slot that throws MUST still return."
  )
}

// ADR-087 stage 2: the slot body's error boundary. Stage 1's catch printed one attribution line
// and rethrew, so the host still died through Kotlin/Native's unhandled-callback path; the SAME
// catch now writes the forward NugetError envelope through the trailing errOut and returns a
// dummy, so the exception becomes an ordinary catchable .NET exception in the C# bridge member.
// No println: the throw may be handled by the consumer, and attribution no longer needs stdout —
// the .NET stack trace names the generated bridge member, and the envelope carries the Kotlin
// type, message and Kotlin stack unmangled (ADR-029's KotlinException.KotlinStackTrace).
private fun kotlinSlotEnvelope(body: String, cfnType: String): String {
  val dummy: String = cfnDummyValue(cfnType)
  val dummyLine: String = if (dummy.isEmpty()) "" else "\n  $dummy"
  return """
    |try {
    |  $body
    |} catch (t: Throwable) {
    |  errOut?.pointed?.value = nugetKotlinError(t)$dummyLine
    |}
  """.trimMargin()
}

// C# -> Kotlin across a bridge slot: the wire value as the Kotlin member declares it.
// ADR-086: a handle-backed inbound value is a TRANSFER handle. A bound object becomes the ordinary
// generated wrapper, whose cleaner frees the GCHandle exactly like any other reverse handle, so a
// Kotlin implementation may STORE what it receives (the whole point of transfer over borrow). A
// bound interface goes through the shipped nuget{Iface}Value helper, which returns the ORIGINAL
// Kotlin object (freeing the transfer handle) when the value is a bridge this build minted.
private fun kotlinBridgeInbound(type: RirTypeRef, name: String): String = when {
  type is RirStringType && type.nullable -> "$name?.reinterpret<ByteVar>()?.toKString()"
  type is RirStringType -> "requireNotNull($name).reinterpret<ByteVar>().toKString()"
  type is RirEnumType -> "nugetEnumEntry(${type.name}.entries, $name, \"${type.name}\")"
  type is RirPrimitiveType && type.name == "char" -> "$name.toInt().toChar()"
  type is RirObjectHandleType && type.nullable -> "$name?.let { ${type.name}(it) }"
  type is RirObjectHandleType -> "${type.name}(requireNotNull($name))"
  type is RirInterfaceType && type.nullable -> "$name?.let { nuget${type.name}Value(it) }"
  type is RirInterfaceType -> "nuget${type.name}Value(requireNotNull($name))"
  else -> name
}

// Kotlin -> C# across a bridge slot: the Kotlin member's value as the wire expects it.
// [dupFnRef] is this interface's registered dup thunk, needed only by the handle-backed cells.
private fun kotlinBridgeOutbound(type: RirTypeRef, expr: String, dupFnRef: String): String = when {
  type is RirStringType -> "nugetKotlinString($expr)"
  type is RirEnumType -> "$expr.ordinal"
  type is RirPrimitiveType && type.name == "char" -> "$expr.code.toUShort()"
  type is RirObjectHandleType ->
    "nugetHandleOut($expr, \"${type.namespace}.${type.name}\", $dupFnRef)"

  type is RirInterfaceType ->
    "nugetHandleOut($expr, \"${type.namespace}.${type.name}\", $dupFnRef)"

  else -> expr
}

// ROADMAP line 142 ("surface RirDiagnostics to the build") + rule 5's existing
// member-name-collision warning generalized into ONE pure, testable function: every diagnostic
// that will ever reach a consumer's build log — whether it was emitted directly by the metadata
// reader into RirAssembly.diagnostics (skipped_overload_set, skipped_ref_struct, ..., and
// ADR-053's info_oblivious_nullability), or derived Gradle-plugin-side by
// collisionDiagnostics(cls) (rule 5's SKIPPED_MEMBER_NAME_COLLISION) — is formatted through the
// same code path. Kept pure (no logger access) so it is unit-testable like every other generator
// function in this file; the task below is the only place that actually calls logger.warn.
internal fun diagnosticWarnings(rir: RirFile): List<String> {
  validateDiagnostics(rir)
  val boundTypes: Set<RirTypeKey> = boundHandleTypes(rir)
  val structs: Map<RirTypeKey, RirStruct> = boundStructTypes(rir)
  // ADR-072: derived once here too, so a member skipped for exceeding the arity ceiling is still
  // reported even when its signature mentions a bridgeable generic instantiation.
  val genericDefs: Map<RirTypeKey, RirClass> = boundGenericClassDefinitions(rir)
  val fromReader: List<Pair<String, RirDiagnostic>> = rir.assemblies.flatMap { assembly ->
    assembly.diagnostics.map { assembly.packageId to it }
  }
  val fromCollisions: List<Pair<String, RirDiagnostic>> = rir.assemblies.flatMap { assembly ->
    assembly.namespaces.flatMap { namespace ->
      namespace.types.filterIsInstance<RirClass>().flatMap { cls ->
        collisionDiagnostics(cls).map { assembly.packageId to it }
      }
    }
  }
  // ADR-059 Decision 5a: the same shared arityLimitDiagnostics/structArityLimitDiagnostics
  // bridgeableRegistrables/bridgeableStructRegistrables already filter by, surfaced here too
  // (mirroring fromCollisions above) so a skipped_abi_arity_limit member is reported, not silently
  // dropped.
  val fromArityLimits: List<Pair<String, RirDiagnostic>> = rir.assemblies.flatMap { assembly ->
    assembly.namespaces.flatMap { namespace ->
      namespace.types.filterIsInstance<RirClass>().flatMap { cls ->
        arityLimitDiagnostics(cls, boundTypes, structs, boundGenericClassDefinitions = genericDefs)
          .map { assembly.packageId to it }
      } + namespace.types.filterIsInstance<RirStruct>().flatMap { struct ->
        structArityLimitDiagnostics(struct, boundTypes, structs).map { assembly.packageId to it }
      }
    }
  }
  // ADR-072 Decision 5: the analogous plugin-derived entry point for
  // skipped_ambiguous_generic_constructor, mirroring fromCollisions/fromArityLimits above,
  // routed through the SAME internal diagnosticWarnings(rir) surface as every other
  // plugin-derived kind, rather than left with no named entry point at all.
  val fromAmbiguousGenericConstructors: List<Pair<String, RirDiagnostic>> =
    rir.assemblies.flatMap { assembly ->
      assembly.namespaces.flatMap { namespace ->
        namespace.types.filterIsInstance<RirClass>()
          .filter { it.typeParameters.isNotEmpty() }
          .flatMap { cls ->
            ambiguousGenericConstructorDiagnostics(cls).map { assembly.packageId to it }
          }
      }
    }
  return (fromReader + fromCollisions + fromArityLimits + fromAmbiguousGenericConstructors)
    .map { (packageId, diagnostic) -> formatDiagnostic(packageId, diagnostic) }
}

private fun validateDiagnostics(rir: RirFile) {
  val errors: List<Pair<String, RirDiagnostic>> = rir.assemblies.flatMap { assembly ->
    assembly.diagnostics
      .filter { it.kind.name.startsWith("ERROR") }
      .map { assembly.packageId to it }
  }
  require(errors.isEmpty()) {
    errors.joinToString("\n") { (packageId, diagnostic) ->
      "[nuget:$packageId] ${diagnostic.kind.name.lowercase()}: ${diagnostic.reason}. " +
          diagnostic.hint
    }
  }
}

private fun validateKotlinSignatures(rir: RirFile) {
  val structs: Map<RirTypeKey, RirStruct> = boundStructTypes(rir)
  val genericDefs: Map<RirTypeKey, RirClass> = boundGenericClassDefinitions(rir)
  rir.assemblies.forEach { assembly ->
    assembly.namespaces.forEach { namespace ->
      // ADR-072: a generic class definition's members are RirTypeParameterType/
      // RirGenericInstanceType-shaped by construction: kotlinCollisionType() has no ordinary
      // rendering for those (they route through the dedicated generic-class path instead), so
      // this ordinary (non-generic) collision check must not run on cls.constructors/cls.methods
      // for a generic definition at all. An ordinary member mentioning a bridgeable generic
      // instantiation, however, is a normal registrable and must still be checked here.
      namespace.types.filterIsInstance<RirClass>().filterNot { it.typeParameters.isNotEmpty() }
        .forEach { cls ->
          val registrables: List<RirRegistrable> = bridgeableRegistrables(
            cls, boundHandleTypes(rir), structs, boundGenericClassDefinitions = genericDefs,
          )
          val methods: List<RirMethod> = cls.methods.filter { method ->
            registrables.any { registrable ->
              registrable is RirRegistrable.Method && registrable.method === method
            }
          }
          methods.groupBy { method ->
            val scope: String = if (method.isStatic) "static" else "instance"
            "$scope:${method.name.toMethodCamelCase()}(" +
                method.parameters.joinToString(",") { it.type.kotlinCollisionType() } + ")"
          }.values.filter { it.size > 1 }.forEach { collision ->
            val first: RirMethod = collision.first()
            val params: String = first.parameters.joinToString(", ") { p ->
              "${p.name}: ${declKotlinType(p.type)}"
            }
            require(false) {
              "[nuget] Kotlin signature collision: " +
                  collision.joinToString(" and ") { "`${it.managedSignature}`" } +
                  " both map to `fun ${first.name.toMethodCamelCase()}($params)`. " +
                  "Expose a differently named C# adapter."
            }
          }

          cls.constructors.groupBy { ctor ->
            ctor.parameters.joinToString(",") { it.type.kotlinCollisionType() }
          }.values.filter { it.size > 1 }.forEach { collision ->
            require(false) {
              "[nuget] Kotlin constructor signature collision: " +
                  collision.joinToString(" and ") { "`${it.managedSignature}`" } +
                  ". Expose a differently named C# adapter."
            }
          }
        }
    }
  }
}

private fun RirTypeRef.kotlinCollisionType(): String = when (this) {
  is RirObjectHandleType -> "$namespace.$name${if (isNullable) "?" else ""}"
  is RirEnumType -> "$namespace.$name"
  is RirStructType -> "$namespace.$name"
  is RirInterfaceType -> "$namespace.$name${if (isNullable) "?" else ""}"
  // ADR-072 Decision 3, NugetGenerateBindingsTask.kt:3751 permissive site: same gap as
  // declKotlinType's own qualification fix: without this branch two overloads differing only in
  // a generic instantiation's namespace could collide undetected inside the ADR-057 diagnostic.
  is RirGenericInstanceType ->
    "$namespace.$name[${typeArguments.joinToString(",") { it.kotlinCollisionType() }}]" +
        if (isNullable) "?" else ""

  else -> declKotlinType(this)
}

// A SKIPPED_* diagnostic means the member is absent from the generated output ("Skipping ...");
// an INFO_* diagnostic (e.g. info_oblivious_nullability) is not a skip — the member still binds,
// just under an assumed policy — so it reads as a "Note" instead. typeName/memberName may both be
// empty (a whole-assembly diagnostic, e.g. ADR-053's one-per-assembly oblivious signal), typeName
// alone may be populated with memberName empty, or both may be populated (member-scoped, or rule
// 5's per-member collision) — each renders progressively more of the location.
private fun formatDiagnostic(packageId: String, diagnostic: RirDiagnostic): String {
  val isSkip: Boolean = diagnostic.kind.name.startsWith("SKIPPED")
  val verb: String = if (isSkip) "Skipping" else "Note"
  val location: String = when {
    diagnostic.typeName.isEmpty() && diagnostic.memberName.isEmpty() -> ""
    diagnostic.memberName.isEmpty() -> " ${diagnostic.typeName}"
    else -> " ${diagnostic.typeName}.${diagnostic.memberName}(${diagnostic.memberSignature})"
  }
  return "w: [nuget:$packageId] $verb$location: ${diagnostic.reason}. ${diagnostic.hint}"
}

abstract class NugetGenerateBindingsTask : DefaultTask() {
  @get:InputFile
  abstract val reverseIrFile: RegularFileProperty

  @get:Input
  abstract val packageNameOverrides: MapProperty<String, String>

  @get:Input
  abstract val namespaceAliases: MapProperty<String, Map<String, String>>

  @get:OutputDirectory
  abstract val kotlinOutputDir: DirectoryProperty

  @TaskAction
  fun generate() {
    val rir: RirFile = parseReverseIr(reverseIrFile.get().asFile.readText())

    // ROADMAP line 142 / Phase 9 (rule 5) / ADR-053: surface every diagnostic — reader-emitted
    // (RirAssembly.diagnostics) and Gradle-plugin-derived (rule 5's collisionDiagnostics) alike —
    // as a Gradle warning, ADR-043 diagnostic-format style: "a diagnostic nobody sees is just a
    // silent skip." Detected here (not in generateKotlinStubs, which stays pure) because this
    // task's logger is the narrowest place to make it visible to a user running the build; the
    // actual skip (excluding a rule-5 collision from generated output) already happens inside the
    // shared bridgeableRegistrables() so both this task and NugetGenerateShimsTask agree on what
    // was dropped.
    diagnosticWarnings(rir).forEach { logger.warn(it) }

    val files: List<GeneratedFile> = generateKotlinStubs(
      file = rir,
      packageNameOverrides = packageNameOverrides.get(),
      namespaceAliases = namespaceAliases.get(),
    )

    val outputDir: File = kotlinOutputDir.get().asFile
    files.forEach { generated ->
      val out: File = outputDir.resolve(generated.relativePath)
      out.parentFile.mkdirs()
      out.writeText(generated.content)
    }
  }
}
