package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.AbiArg
import io.github.xxfast.kotlin.native.nuget.rir.NUGET_RUNTIME_CONTRACT_HASH
import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirConstructor
import io.github.xxfast.kotlin.native.nuget.rir.RirDiagnostic
import io.github.xxfast.kotlin.native.nuget.rir.RirDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.rir.RirEnum
import io.github.xxfast.kotlin.native.nuget.rir.RirEnumType
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirObjectHandleType
import io.github.xxfast.kotlin.native.nuget.rir.RirParameter
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirProperty
import io.github.xxfast.kotlin.native.nuget.rir.RirRegistrable
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirStruct
import io.github.xxfast.kotlin.native.nuget.rir.RirStructType
import io.github.xxfast.kotlin.native.nuget.rir.RirTypeKey
import io.github.xxfast.kotlin.native.nuget.rir.RirTypeRef
import io.github.xxfast.kotlin.native.nuget.rir.RirVoidType
import io.github.xxfast.kotlin.native.nuget.rir.abiArgs
import io.github.xxfast.kotlin.native.nuget.rir.abiOutArgs
import io.github.xxfast.kotlin.native.nuget.rir.abiReturnType
import io.github.xxfast.kotlin.native.nuget.rir.boundHandleTypes
import io.github.xxfast.kotlin.native.nuget.rir.boundStructTypes
import io.github.xxfast.kotlin.native.nuget.rir.bridgeableRegistrables
import io.github.xxfast.kotlin.native.nuget.rir.bridgeId
import io.github.xxfast.kotlin.native.nuget.rir.bridgeableStructRegistrables
import io.github.xxfast.kotlin.native.nuget.rir.bridgeSuffix
import io.github.xxfast.kotlin.native.nuget.rir.collisionDiagnostics
import io.github.xxfast.kotlin.native.nuget.rir.contractHash
import io.github.xxfast.kotlin.native.nuget.rir.isNullable
import io.github.xxfast.kotlin.native.nuget.rir.parseReverseIr
import io.github.xxfast.kotlin.native.nuget.rir.registrationExportName
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

// ADR-056: does this type reference — either directly, or (if it is a struct) through one of
// its OWN components — satisfy [predicate]? Every "does this file need X" detector below
// (needsInterop, needsEnums, hasStringReturn, hasStringParam, hasEnumReturn, ...) used to test
// only the top-level RirTypeRef, which made a struct's component types invisible to them: a method
// returning only a struct with a string component, and no directly-string-typed member anywhere
// else in the file, would silently skip emitting NugetInterop.kt (freeManagedString) and fail to
// compile. A struct can only be one level deep in v1 (nested struct components are unsupported,
// ADR-056 Scope), so this never needs to recurse more than once.
private fun typeContains(
  type: RirTypeRef,
  structs: Map<RirTypeKey, RirStruct>,
  predicate: (RirTypeRef) -> Boolean,
): Boolean {
  if (predicate(type)) return true
  val struct: RirStruct? =
    (type as? RirStructType)?.let { structs[RirTypeKey(it.namespace, it.name)] }
  return struct?.components.orEmpty().any { predicate(it.type) }
}

private fun isStringRef(type: RirTypeRef): Boolean = type is RirStringType
private fun isEnumRef(type: RirTypeRef): Boolean = type is RirEnumType

// ADR-056: every enum a struct's OWN components reference — used to widen the
// `import <pkg>.<Enum>` resolution (enumImports/referencedEnumTypes) beyond top-level
// method/ctor/property types, since a struct-typed member's enum components are otherwise
// invisible to that resolution.
private fun structEnumComponents(
  type: RirTypeRef,
  structs: Map<RirTypeKey, RirStruct>,
): List<RirEnumType> {
  val struct: RirStruct? =
    (type as? RirStructType)?.let { structs[RirTypeKey(it.namespace, it.name)] }
  return struct?.components.orEmpty().mapNotNull { it.type as? RirEnumType }
}

fun generateKotlinStubs(
  file: RirFile,
  packageNameOverrides: Map<String, String> = emptyMap(),
  namespaceAliases: Map<String, Map<String, String>> = emptyMap(),
): List<GeneratedFile> {
  validateDiagnostics(file)
  validateKotlinSignatures(file)
  val result: MutableList<GeneratedFile> = mutableListOf()
  var needsInterop = false
  var needsRuntime = false
  var needsEnums = false

  // ADR-054: every "{Namespace}.{Type}" this build emits a register export for, in the order
  // encountered — baked into the generated NugetRegistry.kt's `expected` list so the "N of M
  // registrations fired" message can name exactly what did/did not land. "<runtime>" (if needed)
  // is prepended after the loop, once whether-any-class-needs-it is known.
  val expectedRegistrations: MutableList<String> = mutableListOf()

  // ADR-051: derive once for the whole file — both generators must use the same helper
  // (anti-drift contract, ADR-049 Alternative 10 extended by ADR-051).
  val boundTypes: Set<RirTypeKey> = boundHandleTypes(file)
  val enumPkgs: Map<RirTypeKey, String> =
    enumPackages(file, packageNameOverrides, namespaceAliases)
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
          bridgeableStructRegistrables(struct, boundTypes)
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
              kotlinPkg, struct, assembly.packageId, enumPkgs, constructors,
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

      namespace.types.filterIsInstance<RirClass>().forEach { cls ->
        // ADR-052/Phase 9 line 151 "shared bridgeable ordering": ONE ordered list — constructor
        // (if any) first, then bridgeable static methods, then bridgeable instance methods, then
        // per-property getter/[setter] pairs — is the single source of truth both this task and
        // NugetGenerateShimsTask derive their registration-order-sensitive output from. Member-name
        // collisions with the ADR-051 wrapper (handle/close/cleaner) are already excluded here.
        val registrables: List<RirRegistrable> = bridgeableRegistrables(cls, boundTypes)
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
        val methodsHaveHandle: Boolean = allMethods.any { method ->
          method.returnType is RirObjectHandleType ||
              method.parameters.any { p -> p.type is RirObjectHandleType }
        }
        val hasInstanceMember: Boolean =
          ctors.isNotEmpty() || instanceMethods.isNotEmpty() || instancePropertyGetters.isNotEmpty()
        val instancePropertiesHaveHandle: Boolean =
          instancePropertyGetters.any { it.type is RirObjectHandleType }
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
              kotlinPkg, cls, registrables, exportName, assembly.packageId, namespace.name, structs,
            ),
          )
        )
        result.add(
          GeneratedFile(
            relativePath = "nativeMain/$pkgPath/${cls.name}.kt",
            content = stubFileContent(
              kotlinPkg, cls, staticMethods, instanceMethods, ctors,
              instancePropertyGetters, staticPropertyGetters, propertySetterNames,
              assembly.packageId, namespace.name, enumPkgs, structs,
              qualifiedTypeNames,
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

// Every enum type referenced by a class's bridgeable members, in declaration order, deduplicated.
// The referencing class's stub must import each one that lives in a different Kotlin package than
// the stub itself, so this covers every position an enum can appear in: method returns, method
// parameters, constructor parameters and property types.
private fun referencedEnumTypes(
  methods: List<RirMethod>,
  ctors: List<RirConstructor>,
  properties: List<RirProperty>,
): List<RirEnumType> {
  val fromMethods: List<RirEnumType> = methods.flatMap { method ->
    listOfNotNull(method.returnType as? RirEnumType) +
        method.parameters.mapNotNull { it.type as? RirEnumType }
  }
  val fromCtors: List<RirEnumType> =
    ctors.flatMap { ctor -> ctor.parameters.mapNotNull { it.type as? RirEnumType } }
  val fromProperties: List<RirEnumType> = properties.mapNotNull { it.type as? RirEnumType }

  return (fromMethods + fromCtors + fromProperties).distinct()
}

// The `import <pkg>.<EnumName>` lines a stub file needs for the enums it references. An enum
// declared in the same Kotlin package as the referencing stub needs no import; one declared in
// another package (a C# enum in `Sample.Enums` consumed by a class in `Sample.Text`, say) does, or
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
// struct itself (this repo's own bind{} config aliases Sample.Structs and Sample.Enums to
// different packages, so this is the normal case, not an edge one) — mirrors the import handling
// bindingsFileContent/stubFileContent already do for method/property enum references.
private fun structFileContent(
  kotlinPkg: String,
  struct: RirStruct,
  packageId: String,
  enumPkgs: Map<RirTypeKey, String>,
  constructors: List<RirConstructor>,
  instanceMethods: List<RirMethod>,
  staticMethods: List<RirMethod>,
  computedGetters: List<RirProperty>,
  namespaceName: String,
  structs: Map<RirTypeKey, RirStruct>,
  qualifiedTypeNames: Map<RirTypeKey, String>,
): String {
  val params: String = struct.components.joinToString(",\n  ") { c ->
    "val ${c.name.toMethodCamelCase()}: ${kotlinType(c.type)}"
  }
  val allMethods: List<RirMethod> = instanceMethods + staticMethods
  val hasMembers: Boolean =
    constructors.isNotEmpty() ||
        instanceMethods.isNotEmpty() ||
        staticMethods.isNotEmpty() ||
        computedGetters.isNotEmpty()

  val memberEnumTypes: List<RirEnumType> = (
      struct.components.mapNotNull { it.type as? RirEnumType } +
          allMethods.flatMap { method ->
            listOfNotNull(method.returnType as? RirEnumType) +
                method.parameters.mapNotNull { it.type as? RirEnumType }
          } +
          computedGetters.mapNotNull { it.type as? RirEnumType }
      ).distinct()

  val imports: MutableList<String> =
    enumImports(memberEnumTypes, enumPkgs, kotlinPkg).toMutableList()
  if (hasMembers) {
    imports.add("import $INTERNAL_PKG.NugetRegistry")
    imports.add("import kotlinx.cinterop.invoke")
  }

  val receiverHasString: Boolean = struct.components.any { it.type is RirStringType }
  val receiverHasEnum: Boolean = struct.components.any { it.type is RirEnumType }
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
      struct.components.forEach { add(cVarType(it.type)) }
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
  return """
    |@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    |
    |package $kotlinPkg
    |
    |$importsBlock/**
    | * Kotlin value type for the C# struct `$packageId.${struct.name}`.
    | *
    | * Copied by value across the bridge: equality is structural, and there is nothing to close.
    | * Mutating this value never affects the C# side (a copy crossed the boundary); use [copy].
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
      val nested: RirStruct? =
        (p.type as? RirStructType)?.let { structs[RirTypeKey(it.namespace, it.name)] }
      if (nested == null) listOf(argConversion(p.type, p.name))
      else nested.components.map { component ->
        argConversion(component.type, "${p.name}.${component.name}")
      }
    }
    val outArgs: List<AbiArg> = struct.components.map { component ->
      AbiArg("out${component.readName}", component.type, isOutPointer = true)
    }
    val allocations: String = outArgs.joinToString("\n") { arg ->
      "  val ${arg.name} = alloc<${cVarType(arg.type)}>()"
    }
    val invokeArgs: String = (inArgs + outArgs.map { "${it.name}.ptr" }).joinToString(", ")
    val reads: List<ComponentRead> = struct.components.zip(outArgs).map { (component, arg) ->
      componentRead(component.type, arg)
    }
    val statements: String = reads.flatMap { it.statements }.joinToString("\n") { "  $it" }
    val values: String = reads.joinToString(", ") { it.expression }
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
    if (!method.isStatic) structReceiverAbiArgs(struct).map { cfnType(it.type) }
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
        val outTypes: List<String> = struct.components.map { cfnOutPointerType(it.type) }
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
          structReceiverAbiArgs(struct).map { cfnType(it.type) }
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
    addAll(struct.components.map { cVarType(it.type) })
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
    struct.components.map { c -> argConversion(c.type, c.name.toMethodCamelCase()) }
  } else {
    emptyList()
  }
  val paramArgs: List<String> = method.parameters.flatMap { p ->
    val nested: RirStruct? =
      (p.type as? RirStructType)?.let { ref -> structs[RirTypeKey(ref.namespace, ref.name)] }
    if (nested == null) listOf(argConversion(p.type, p.name))
    else nested.components.map { c ->
      argConversion(c.type, "${p.name}.${c.name.toMethodCamelCase()}")
    }
  }
  val receiverHasString: Boolean =
    !method.isStatic && struct.components.any { it.type is RirStringType }
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
      val reads: List<ComponentRead> = retStruct.components.zip(outArgs)
        .map { (c, arg) -> componentRead(c.type, arg) }
      val constructArgs: String = reads.joinToString(", ") { it.expression }
      buildString {
        appendLine("fun $name($params)$retSuffix = memScoped {")
        appendLine("  val fn = requireNotNull($fnVar) {")
        appendLine("    $failMsg")
        appendLine("  }")
        outArgs.forEach { arg ->
          appendLine("  val ${arg.name} = alloc<${cVarType(arg.type)}>()")
        }
        appendLine("  fn.invoke($fullInvokeArgs)")
        reads.forEach { read -> read.statements.forEach { appendLine("  $it") } }
        appendLine("  ${retType.name}($constructArgs)")
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
  val receiverArgs: List<String> =
    struct.components.map { c -> argConversion(c.type, c.name.toMethodCamelCase()) }
  val hasStringReceiver: Boolean = struct.components.any { it.type is RirStringType }

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
      val reads: List<ComponentRead> = retStruct.components.zip(outArgs)
        .map { (c, arg) -> componentRead(c.type, arg) }
      val constructArgs: String = reads.joinToString(", ") { it.expression }
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
        reads.forEach { read -> read.statements.forEach { appendLine("    $it") } }
        appendLine("    ${type.name}($constructArgs)")
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
  val key: RirTypeKey? = when (type) {
    is RirObjectHandleType -> RirTypeKey(type.namespace, type.name)
    is RirEnumType -> RirTypeKey(type.namespace, type.name)
    is RirStructType -> RirTypeKey(type.namespace, type.name)
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
  is RirVoidType -> error("[nuget] void cannot be a struct out-pointer component")
  is RirStructType -> error("[nuget] nested struct components are not supported in v1 (ADR-056)")
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
      "[nuget] handle-typed struct components are not supported (ADR-056 v1 component vocabulary" +
          " is primitives, string, and bound enums only)"
    )

    is RirVoidType -> error("[nuget] void cannot be a struct component")
    is RirStructType -> error("[nuget] nested struct components are not supported in v1 (ADR-056)")
  }
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
  structs: Map<RirTypeKey, RirStruct>,
  qualifiedTypeNames: Map<RirTypeKey, String>,
): String {
  val hasHandle: Boolean = staticMethods.any { method ->
    method.returnType is RirObjectHandleType ||
        method.parameters.any { p -> p.type is RirObjectHandleType }
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
      namespaceName, enumPkgs, structs,
      qualifiedTypeNames,
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

  // ADR-054: NugetRegistry.notRegistered(...) is called at runtime (not baked as a constant
  // string) so the "N of M registrations fired" message reflects what actually landed by the time
  // a bridge call fails, rather than the fixed generation-time text this replaces.
  imports.add("import $INTERNAL_PKG.NugetRegistry")

  val methods: String = staticMethods.joinToString("\n\n") {
    buildStubMethod(cls, it, packageId, namespaceName, structs, qualifiedTypeNames)
  }
  val properties: String = staticPropertyGetters.joinToString("\n\n") { property ->
    buildStubProperty(
      cls, property, hasSetter = property.name in propertySetterNames, packageId, namespaceName,
      structs,
      qualifiedTypeNames,
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
    // hand-authored sample-library sources that call it), but invisible to the forward-direction
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
  structs: Map<RirTypeKey, RirStruct>,
  qualifiedTypeNames: Map<RirTypeKey, String>,
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
    "import $INTERNAL_PKG.NugetObjectHandle",
    "import $INTERNAL_PKG.NugetRegistry",
    "import kotlin.experimental.ExperimentalNativeApi",
    "import kotlin.native.ref.createCleaner",
    "import kotlinx.cinterop.COpaquePointer",
    "import kotlinx.cinterop.invoke",
  )
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

  val instanceMethodsText: String = instanceMethods.joinToString("\n\n") {
    buildStubMethod(cls, it, packageId, namespaceName, structs, qualifiedTypeNames)
  }
  val propertiesText: String = instancePropertyGetters.joinToString("\n\n") { property ->
    buildStubProperty(
      cls, property, hasSetter = property.name in propertySetterNames, packageId, namespaceName,
      structs,
      qualifiedTypeNames,
    )
  }
  val staticMethodsText: String = staticMethods.joinToString("\n\n") {
    buildStubMethod(cls, it, packageId, namespaceName, structs, qualifiedTypeNames)
  }
  val staticPropertiesText: String = staticPropertyGetters.joinToString("\n\n") { property ->
    buildStubProperty(
      cls, property, hasSetter = property.name in propertySetterNames, packageId, namespaceName,
      structs,
      qualifiedTypeNames,
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
    appendLine(
      "internal class ${cls.name} internal constructor(handle: COpaquePointer) : AutoCloseable {",
    )
    appendLine("  internal val handle: NugetObjectHandle = NugetObjectHandle(handle)")
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
private fun argConversion(type: RirTypeRef, name: String): String = when {
  type is RirStringType && type.nullable -> "if ($name == null) null else $name.cstr.ptr"
  type is RirStringType -> "$name.cstr.ptr"
  // ADR-051: unwrap the opaque pointer via handle.require() which also guards against
  // use-after-close (throws IllegalStateException if the handle was already freed).
  type is RirObjectHandleType && type.nullable -> "$name?.handle?.require(\"${type.name}\")"
  type is RirObjectHandleType -> "$name.handle.require(\"${type.name}\")"
  type is RirEnumType -> "$name.ordinal"
  type is RirPrimitiveType && type.name == "char" -> "$name.code.toUShort()"
  // ADR-056: a struct-typed argument must be decomposed into its components (one invoke()
  // argument per component, e.g. "p.x", "p.y") before reaching argConversion — buildStubMethod's
  // paramArgs does that decomposition itself rather than calling argConversion for a struct.
  type is RirStructType -> error(
    "[nuget] a struct-typed argument must be decomposed into its components before " +
        "argConversion — got ${type.namespace}.${type.name}"
  )

  else -> name
}

// Like String.prependIndent, but leaves blank lines completely empty instead of padding them out
// to the indent's width (String.prependIndent pads any blank line shorter than the given indent).
// The generated members this file assembles are separated by blank lines; padding them would leave
// trailing whitespace on lines that must stay empty (STYLE.md: "blank lines... should contain no
// characters at all").
private fun String.indented(indent: String): String =
  lineSequence().joinToString("\n") { line -> if (line.isBlank()) line else indent + line }

// Shared "bindings not registered" guard message — used by buildStubMethod, buildConstructHelper,
// and buildStubProperty so the wording never drifts between the three call sites.
//
// ADR-054: this used to be a constant string baked at GENERATION time. It is now a call to
// NugetRegistry.notRegistered(...), evaluated at RUNTIME, on the failure path only — so the
// message can say how many of this build's registrations actually fired ("0 of 7", "6 of 7, only
// Sample.Text.Template is missing") instead of a fixed sentence that cannot distinguish "nothing
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
    val struct: RirStruct? =
      (p.type as? RirStructType)?.let { ref -> structs[RirTypeKey(ref.namespace, ref.name)] }
    if (struct == null) listOf(argConversion(p.type, p.name))
    else struct.components.map { component ->
      argConversion(component.type, "${p.name}.${component.name}")
    }
  }.joinToString(", ")

  val invokeCall: String =
    if (hasStringParam) "memScoped { fn.invoke($invokeArgs) }" else "fn.invoke($invokeArgs)"

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
): String {
  val name: String = method.name.toMethodCamelCase()
  val fnVar: String =
    "${bindingsObjectName(cls.name)}.$name${method.bridgeSuffix()}Fn"
  // ADR-056: a struct component can itself be a string (not exercised by the v1 fixture, but
  // checked here for correctness) — memScoped is needed whenever ANY component crossing as a
  // string argument requires it, not just a direct top-level string parameter.
  val hasStringParam: Boolean = method.parameters.any { p ->
    val struct: RirStruct? =
      (p.type as? RirStructType)?.let { ref -> structs[RirTypeKey(ref.namespace, ref.name)] }
    if (struct != null) struct.components.any { it.type is RirStringType }
    else p.type is RirStringType
  }

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
    val struct: RirStruct? =
      (p.type as? RirStructType)?.let { ref -> structs[RirTypeKey(ref.namespace, ref.name)] }
    if (struct == null) listOf(argConversion(p.type, p.name))
    else struct.components.map { c ->
      argConversion(c.type, "${p.name}.${c.name.toMethodCamelCase()}")
    }
  }
  val invokeArgs: String = (listOfNotNull(receiverArg) + paramArgs).joinToString(", ")

  val failMsg: String = bindingsNotRegisteredMessage(cls.name, packageId, namespaceName)

  val invokeCall: String =
    if (hasStringParam) "memScoped { fn.invoke($invokeArgs) }" else "fn.invoke($invokeArgs)"

  val nullMsg: String = "${cls.name}.${method.name} returned null" +
      ", expected a non-null string pointer"
  val nonNullHandleMsg: String = "${cls.name}.${method.name} returned null, but the C# API " +
      "annotates it non-null."

  // Each branch below renders a self-contained block (`fun` at column 0, its body at column 2)
  // so the caller can shift the whole block to its actual embedding depth with a single
  // String.prependIndent() call, rather than baking one specific nesting depth into this function.
  return when (val retType = method.returnType) {
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
      // ADR-056: each component is read back through componentRead — the SAME per-type
      // conversion a top-level return of that type already uses — instead of the raw `.value`,
      // which is only correct for the pass-through primitives (int/long/float/double).
      val reads: List<ComponentRead> = struct.components.zip(outArgs)
        .map { (c, arg) -> componentRead(c.type, arg) }
      val constructArgs: String = reads.joinToString(", ") { it.expression }
      buildString {
        appendLine("fun $name($params)$retSuffix = memScoped {")
        appendLine("  val fn = requireNotNull($fnVar) {")
        appendLine("    $failMsg")
        appendLine("  }")
        outArgs.forEach { arg -> appendLine("  val ${arg.name} = alloc<${cVarType(arg.type)}>()") }
        appendLine("  fn.invoke($fullInvokeArgs)")
        reads.forEach { read -> read.statements.forEach { appendLine("  $it") } }
        appendLine("  ${retType.name}($constructArgs)")
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
  }
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
      val reads: List<ComponentRead> = struct.components.zip(outArgs)
        .map { (c, arg) -> componentRead(c.type, arg) }
      val constructArgs: String = reads.joinToString(", ") { it.expression }
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
        reads.forEach { read -> read.statements.forEach { appendLine("    $it") } }
        appendLine("    ${type.name}($constructArgs)")
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
      val componentArgs: List<String> = struct.components.map { c ->
        argConversion(c.type, "value.${c.name.toMethodCamelCase()}")
      }
      val invokeArgs: String = (listOfNotNull(receiverArg) + componentArgs).joinToString(", ")
      val hasStringComponent: Boolean = struct.components.any { it.type is RirStringType }
      if (hasStringComponent) "memScoped { fn.invoke($invokeArgs) }" else "fn.invoke($invokeArgs)"
    } else {
      val valueArg: String = argConversion(propType, "value")
      val hasStringValue: Boolean = propType is RirStringType
      if (hasStringValue) {
        if (receiverArg == null) "memScoped { fn.invoke($valueArg) }"
        else "memScoped { fn.invoke($receiverArg, $valueArg) }"
      } else {
        if (receiverArg == null) "fn.invoke($valueArg)" else "fn.invoke($receiverArg, $valueArg)"
      }
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

  return buildString {
    appendLine("$keyword $name: $declType")
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
  |import kotlinx.cinterop.COpaquePointer
  |import kotlinx.cinterop.CFunction
  |import kotlinx.cinterop.CPointer
  |import kotlinx.cinterop.invoke
  |import kotlinx.cinterop.reinterpret
  |import kotlin.experimental.ExperimentalNativeApi
  |
  |internal var freeGcHandleFn: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
  |
  |// ADR-054: slotCount/contractHash are the same two leading scalars every register export gains
  |// (slotCount is always 1 here — the runtime shim registers exactly one thunk). The pointer
  |// parameter is nullable so a stale caller passing zero args (pre-ADR-054) is read-only-safe up
  |// to the checkContract call, which never touches freeGcHandlePtr before deciding to proceed.
  |@OptIn(ExperimentalNativeApi::class)
  |@CName("nuget_runtime_register")
  |fun nuget_runtime_register(slotCount: Int, contractHash: Long, freeGcHandlePtr: COpaquePointer?) {
  |  NugetRegistry.checkContract(
  |    qualifiedType = "<runtime>",
  |    packageId = "",
  |    slotCount = slotCount,
  |    contractHash = contractHash,
  |    expectedSlots = 1,
  |    expectedHash = ${NUGET_RUNTIME_CONTRACT_HASH}L,
  |  )
  |  freeGcHandleFn = requireNotNull(freeGcHandlePtr) {
  |    "nuget_runtime_register passed a null freeGcHandle thunk pointer."
  |  }.reinterpret()
  |  NugetRegistry.record("<runtime>", 1)
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
""".trimMargin().trim()

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
  return (fromReader + fromCollisions)
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
  rir.assemblies.forEach { assembly ->
    assembly.namespaces.forEach { namespace ->
      namespace.types.filterIsInstance<RirClass>().forEach { cls ->
        val methods: List<RirMethod> = cls.methods.filter { method ->
          bridgeableRegistrables(cls, boundHandleTypes(rir)).any { registrable ->
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
