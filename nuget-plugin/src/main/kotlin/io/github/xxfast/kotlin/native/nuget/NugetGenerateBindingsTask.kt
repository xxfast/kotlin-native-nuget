package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.NUGET_RUNTIME_CONTRACT_HASH
import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirConstructor
import io.github.xxfast.kotlin.native.nuget.rir.RirDiagnostic
import io.github.xxfast.kotlin.native.nuget.rir.RirEnum
import io.github.xxfast.kotlin.native.nuget.rir.RirEnumType
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirObjectHandleType
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirProperty
import io.github.xxfast.kotlin.native.nuget.rir.RirRegistrable
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirTypeKey
import io.github.xxfast.kotlin.native.nuget.rir.RirTypeRef
import io.github.xxfast.kotlin.native.nuget.rir.RirVoidType
import io.github.xxfast.kotlin.native.nuget.rir.boundHandleTypes
import io.github.xxfast.kotlin.native.nuget.rir.bridgeableRegistrables
import io.github.xxfast.kotlin.native.nuget.rir.collisionDiagnostics
import io.github.xxfast.kotlin.native.nuget.rir.contractHash
import io.github.xxfast.kotlin.native.nuget.rir.isNullable
import io.github.xxfast.kotlin.native.nuget.rir.parseReverseIr
import io.github.xxfast.kotlin.native.nuget.rir.registrationExportName
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

fun generateKotlinStubs(
  file: RirFile,
  packageNameOverrides: Map<String, String> = emptyMap(),
  namespaceAliases: Map<String, Map<String, String>> = emptyMap(),
): List<GeneratedFile> {
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

      namespace.types.filterIsInstance<RirClass>().forEach { cls ->
        // ADR-052/Phase 9 line 151 "shared bridgeable ordering": ONE ordered list — constructor
        // (if any) first, then bridgeable static methods, then bridgeable instance methods, then
        // per-property getter/[setter] pairs — is the single source of truth both this task and
        // NugetGenerateShimsTask derive their registration-order-sensitive output from. Member-name
        // collisions with the ADR-051 wrapper (handle/close/cleaner) are already excluded here.
        val registrables: List<RirRegistrable> = bridgeableRegistrables(cls, boundTypes)
        val ctors: List<RirConstructor> =
          registrables.filterIsInstance<RirRegistrable.Ctor>().map { it.ctor }
        val staticMethods: List<RirMethod> = registrables.filterIsInstance<RirRegistrable.Method>()
          .map { it.method }.filter { it.isStatic }
        val instanceMethods: List<RirMethod> = registrables.filterIsInstance<RirRegistrable.Method>()
          .map { it.method }.filter { !it.isStatic }
        val propertyGetters: List<RirProperty> =
          registrables.filterIsInstance<RirRegistrable.PropertyGetter>().map { it.property }
        val instancePropertyGetters: List<RirProperty> = propertyGetters.filterNot { it.isStatic }
        val staticPropertyGetters: List<RirProperty> = propertyGetters.filter { it.isStatic }
        val propertySetterNames: Set<String> =
          registrables.filterIsInstance<RirRegistrable.PropertySetter>().map { it.property.name }.toSet()

        if (registrables.isEmpty()) return@forEach

        val allMethods: List<RirMethod> = staticMethods + instanceMethods
        val hasString: Boolean = allMethods.any { method ->
          method.returnType is RirStringType ||
              method.parameters.any { p -> p.type is RirStringType }
        } || ctors.any { ctor -> ctor.parameters.any { p -> p.type is RirStringType } } ||
            propertyGetters.any { it.type is RirStringType }
        if (hasString) needsInterop = true

        // ADR-051/052/Phase 9 line 151: NugetRuntime.kt is needed whenever any bridgeable
        // signature contains a handle type, the class has a public instance constructor (a
        // constructor's return is implicitly the class's own handle type), or the class has any
        // instance method/property at all (both require the receiver `handle` field regardless of
        // whether a handle TYPE appears in any individual signature). Emitted once (below),
        // regardless of how many classes trigger it.
        val hasHandle: Boolean = allMethods.any { method ->
          method.returnType is RirObjectHandleType ||
              method.parameters.any { p -> p.type is RirObjectHandleType }
        } || ctors.isNotEmpty() || instanceMethods.isNotEmpty() || instancePropertyGetters.isNotEmpty() ||
            instancePropertyGetters.any { it.type is RirObjectHandleType }
        if (hasHandle) needsRuntime = true

        // NugetEnums.kt is needed whenever a bridgeable member RECEIVES an enum from C# (a method
        // return or a property value): that is the only direction where the ordinal can be out of
        // range, since a C# enum is not a closed set ((CatMood)99 is a legal C# value). Enum
        // arguments travel the other way as Kotlin's own `.ordinal` and are always in range.
        val hasEnumReturn: Boolean = allMethods.any { it.returnType is RirEnumType } ||
            propertyGetters.any { it.type is RirEnumType }
        if (hasEnumReturn) needsEnums = true

        val exportName: String = registrationExportName(namespace.name, cls.name)
        expectedRegistrations.add("${namespace.name}.${cls.name}")

        result.add(
          GeneratedFile(
            relativePath = "nativeMain/$pkgPath/${cls.name}Bindings.kt",
            content = bindingsFileContent(
              kotlinPkg, cls, registrables, exportName, assembly.packageId, namespace.name,
            ),
          )
        )
        result.add(
          GeneratedFile(
            relativePath = "nativeMain/$pkgPath/${cls.name}.kt",
            content = stubFileContent(
              kotlinPkg, cls, staticMethods, instanceMethods, ctors,
              instancePropertyGetters, staticPropertyGetters, propertySetterNames, assembly.packageId,
              namespace.name, enumPkgs,
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

private fun kotlinType(type: RirTypeRef): String = when (type) {
  is RirVoidType -> "Unit"
  is RirStringType -> "String"
  is RirEnumType -> type.name
  // ADR-051: the Kotlin type name for a handle is simply the C# simple type name (e.g. Template).
  is RirObjectHandleType -> type.name
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

private fun cfnType(type: RirTypeRef): String = when (type) {
  is RirVoidType -> "Unit"
  is RirStringType -> "COpaquePointer?"
  is RirEnumType -> "Int"
  // ADR-051: handles cross the ABI as IntPtr ↔ COpaquePointer? (same slot as strings).
  is RirObjectHandleType -> "COpaquePointer?"
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

// Phase 9 (ROADMAP line 151): the ordered list of CFunction parameter cfn-types for a method's
// function-pointer TYPE declaration — instance methods gain a leading `COpaquePointer?` receiver
// slot, exactly mirroring the receiver argument buildStubMethod prepends at the call site
// (`handle.require("Type")`) and the leading `IntPtr selfHandle` NugetGenerateShimsTask.kt's
// thunk signature gains. Single shared source for this list (rather than re-deriving it at each
// call site) so the *Bindings.kt CFunction type and the Template.kt call site can never drift out
// of arity — the same anti-drift reasoning as bridgeableRegistrables' shared ordering.
private fun methodParamCfnTypes(method: RirMethod): List<String> {
  val receiverCfnType: String? = if (!method.isStatic) "COpaquePointer?" else null
  return listOfNotNull(receiverCfnType) + method.parameters.map { cfnType(it.type) }
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
  }

  // ADR-052: rendered directly off the shared bridgeableRegistrables() ordering — constructor
  // pointer first (if any), then method pointers — so the register signature/body below can
  // never drift out of sync with the C# ModuleInitializer's pointer-argument order
  // (NugetGenerateShimsTask consumes the exact same ordered list).
  val fnVars: String = registrables.joinToString("\n\n") { r ->
    when (r) {
      is RirRegistrable.Ctor -> {
        val paramCfnTypes: String = r.ctor.parameters.joinToString(", ") { cfnType(it.type) }
        "@Suppress(\"NOTHING_TO_INLINE\")\n" +
            "internal var ctorFn: CPointer<CFunction<($paramCfnTypes) -> COpaquePointer?>>? = null"
      }

      is RirRegistrable.Method -> {
        val paramCfnTypes: String = methodParamCfnTypes(r.method).joinToString(", ")
        val retCfnType: String = cfnType(r.method.returnType)
        "@Suppress(\"NOTHING_TO_INLINE\")\n" +
            "internal var ${r.method.name.toMethodCamelCase()}Fn: " +
            "CPointer<CFunction<($paramCfnTypes) -> $retCfnType>>? = null"
      }
      // Phase 9 (ROADMAP line 151): a getter thunk takes the receiver only and returns the
      // property's value; a setter thunk takes the receiver plus the new value and returns Unit.
      is RirRegistrable.PropertyGetter -> {
        val retCfnType: String = cfnType(r.property.type)
        val receiverCfnType: String = if (r.property.isStatic) "" else "COpaquePointer?"
        "@Suppress(\"NOTHING_TO_INLINE\")\n" +
            "internal var ${r.property.name.toMethodCamelCase()}GetterFn: " +
            "CPointer<CFunction<($receiverCfnType) -> $retCfnType>>? = null"
      }

      is RirRegistrable.PropertySetter -> {
        val valueCfnType: String = cfnType(r.property.type)
        val receiverCfnType: String = if (r.property.isStatic) "" else "COpaquePointer?, "
        "@Suppress(\"NOTHING_TO_INLINE\")\n" +
            "internal var ${r.property.name.toMethodCamelCase()}SetterFn: " +
            "CPointer<CFunction<($receiverCfnType$valueCfnType) -> Unit>>? = null"
      }
    }
  }

  // ADR-054: pointer parameters are nullable (a stale caller passing fewer args than declared
  // leaves the tail argument registers unpopulated — see checkContract below, which reads only
  // slotCount/contractHash and returns before either storing or dereferencing any pointer here).
  val regParams: String = registrables.joinToString(",\n  ") { r ->
    when (r) {
      is RirRegistrable.Ctor -> "ctorPtr: COpaquePointer?"
      is RirRegistrable.Method -> "${r.method.name.toMethodCamelCase()}Ptr: COpaquePointer?"
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
      is RirRegistrable.Ctor -> "$objectName.ctorFn = requireNotNull(ctorPtr) " +
          "{ \"$exportName passed a null ctor thunk pointer.\" }.reinterpret()"

      is RirRegistrable.Method -> {
        val name: String = r.method.name.toMethodCamelCase()
        "$objectName.${name}Fn = requireNotNull(${name}Ptr) " +
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
  val expectedHash: Long = contractHash(cls, registrables)

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
  val isClassWrapper: Boolean = !cls.isStatic &&
      (hasHandle || ctors.isNotEmpty() || instanceMethods.isNotEmpty() || instancePropertyGetters.isNotEmpty())
  if (isClassWrapper) {
    return classWrapperContent(
      kotlinPkg, cls, staticMethods, instanceMethods, ctors,
      instancePropertyGetters, staticPropertyGetters, propertySetterNames, packageId,
      namespaceName, enumPkgs,
    )
  }

  // object shape (ADR-048, statics only — a non-wrapper class never has ctors/instance
  // methods/properties, per isClassWrapper above).
  val hasStringReturn: Boolean = staticMethods.any { it.returnType is RirStringType } ||
      staticPropertyGetters.any { it.type is RirStringType }
  val hasStringParam: Boolean = staticMethods.any { m -> m.parameters.any { p -> p.type is RirStringType } } ||
      staticPropertyGetters.any { it.type is RirStringType && it.name in propertySetterNames }

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
  val hasEnumReturn: Boolean = staticMethods.any { it.returnType is RirEnumType } ||
      staticPropertyGetters.any { it.type is RirEnumType }
  if (hasEnumReturn) imports.add("import $INTERNAL_PKG.nugetEnumEntry")
  imports.addAll(
    enumImports(
      referencedEnumTypes(staticMethods, ctors, staticPropertyGetters), enumPkgs, kotlinPkg,
    )
  )

  // ADR-054: NugetRegistry.notRegistered(...) is called at runtime (not baked as a constant
  // string) so the "N of M registrations fired" message reflects what actually landed by the time
  // a bridge call fails, rather than the fixed generation-time text this replaces.
  imports.add("import $INTERNAL_PKG.NugetRegistry")

  val methods: String = staticMethods.joinToString("\n\n") {
    buildStubMethod(cls, it, packageId, namespaceName)
  }
  val properties: String = staticPropertyGetters.joinToString("\n\n") { property ->
    buildStubProperty(
      cls, property, hasSetter = property.name in propertySetterNames, packageId, namespaceName,
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
): String {
  val allMethods: List<RirMethod> = staticMethods + instanceMethods
  val hasStringReturn: Boolean = allMethods.any { it.returnType is RirStringType } ||
      instancePropertyGetters.any { it.type is RirStringType } ||
      staticPropertyGetters.any { it.type is RirStringType }
  val hasStringParam: Boolean = allMethods.any { m -> m.parameters.any { p -> p.type is RirStringType } } ||
      ctors.any { ctor -> ctor.parameters.any { p -> p.type is RirStringType } } ||
      instancePropertyGetters.any { it.type is RirStringType && it.name in propertySetterNames } ||
      staticPropertyGetters.any { it.type is RirStringType && it.name in propertySetterNames }

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
  val hasEnumReturn: Boolean = allMethods.any { it.returnType is RirEnumType } ||
      allPropertyGetters.any { it.type is RirEnumType }
  if (hasEnumReturn) imports.add("import $INTERNAL_PKG.nugetEnumEntry")
  imports.addAll(
    enumImports(
      referencedEnumTypes(allMethods, ctors, allPropertyGetters), enumPkgs, kotlinPkg,
    )
  )

  val instanceMethodsText: String =
    instanceMethods.joinToString("\n\n") { buildStubMethod(cls, it, packageId, namespaceName) }
  val propertiesText: String = instancePropertyGetters.joinToString("\n\n") { property ->
    buildStubProperty(
      cls, property, hasSetter = property.name in propertySetterNames, packageId, namespaceName,
    )
  }
  val staticMethodsText: String =
    staticMethods.joinToString("\n\n") { buildStubMethod(cls, it, packageId, namespaceName) }
  val staticPropertiesText: String = staticPropertyGetters.joinToString("\n\n") { property ->
    buildStubProperty(
      cls, property, hasSetter = property.name in propertySetterNames, packageId, namespaceName,
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
    appendLine(" * Equality is wrapper identity: two wrappers around the same C# object are not equal.")
    appendLine(" * The underlying C# object is released automatically when this wrapper is garbage-collected;")
    appendLine(" * call [close] (or use `use { }`) for deterministic release. [close] is optional and idempotent.")
    appendLine(" */")
    appendLine("@OptIn(ExperimentalNativeApi::class)")
    appendLine("internal class ${cls.name} internal constructor(handle: COpaquePointer) : AutoCloseable {")
    appendLine("  internal val handle: NugetObjectHandle = NugetObjectHandle(handle)")
    appendLine()
    appendLine("  @Suppress(\"unused\")")
    appendLine("  private val cleaner = createCleaner(this.handle) { it.free() }")
    appendLine()
    appendLine("  override fun close(): Unit = handle.free()")
    if (ctors.isNotEmpty()) {
      appendLine()
      ctors.forEach { ctor -> appendLine("  ${buildSecondaryConstructor(ctor)}") }
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
        append(buildConstructHelper(cls, ctor, packageId, namespaceName))
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
private fun buildSecondaryConstructor(ctor: RirConstructor): String {
  val params: String = ctor.parameters.joinToString(", ") { p ->
    "${p.name}: ${declKotlinType(p.type)}"
  }
  val args: String = ctor.parameters.joinToString(", ") { it.name }
  return "constructor($params) : this(construct($args))"
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
): String {
  val params: String = ctor.parameters.joinToString(", ") { p ->
    "${p.name}: ${declKotlinType(p.type)}"
  }
  val hasStringParam: Boolean = ctor.parameters.any { it.type is RirStringType }

  val invokeArgs: String = ctor.parameters.joinToString(", ") { p -> argConversion(p.type, p.name) }

  val invokeCall: String =
    if (hasStringParam) "memScoped { fn.invoke($invokeArgs) }" else "fn.invoke($invokeArgs)"

  val failMsg: String = bindingsNotRegisteredMessage(cls.name, packageId, namespaceName)
  val bindingsObj: String = bindingsObjectName(cls.name)

  return """
    |private fun construct($params): COpaquePointer {
    |  val fn = requireNotNull($bindingsObj.ctorFn) {
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
): String {
  val name: String = method.name.toMethodCamelCase()
  val fnVar: String = "${bindingsObjectName(cls.name)}.${name}Fn"
  val hasStringParam: Boolean = method.parameters.any { it.type is RirStringType }

  val params: String = method.parameters.joinToString(", ") { p ->
    "${p.name}: ${declKotlinType(p.type)}"
  }

  // ADR-053: the return's nullability is driven by the RIR's decoded metadata for both strings
  // and handles (a nullable annotation renders `T?`; a non-null annotation — including an
  // oblivious, un-annotated type, which binds non-null — renders bare `T`). This replaces
  // ADR-051's hardcoded "handle returns are always Foo?" policy.
  val retSuffix: String =
    if (method.returnType is RirVoidType) "" else ": ${declKotlinType(method.returnType)}"

  // Phase 9 (ROADMAP line 151): an instance thunk is a static thunk whose first parameter is the
  // receiver handle (ADR-051 insight) — prepend it via the same handle.require(...) pattern used
  // for handle-typed parameters, referencing the wrapper's own `handle` field (not a Kotlin
  // parameter). Static methods are unaffected — no receiver is prepended.
  val receiverArg: String? = if (!method.isStatic) "handle.require(\"${cls.name}\")" else null
  val paramArgs: List<String> = method.parameters.map { p -> argConversion(p.type, p.name) }
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
): String {
  val name: String = property.name.toMethodCamelCase()
  val bindingsObj: String = bindingsObjectName(cls.name)
  val getterFnVar = "$bindingsObj.${name}GetterFn"
  val receiverArg: String? = if (property.isStatic) null else "handle.require(\"${cls.name}\")"
  val failMsg: String = bindingsNotRegisteredMessage(cls.name, packageId, namespaceName)

  val declType: String = declKotlinType(property.type)
  val keyword: String = if (hasSetter) "var" else "val"
  val getterInvoke: String = if (receiverArg == null) "fn.invoke()" else "fn.invoke($receiverArg)"
  val nonNullHandleMsg: String = "${cls.name}.${property.name} returned null, but the C# API " +
      "annotates it non-null."

  // Each block below is self-contained (`get()`/`set(value)` at column 0, its body at column 2)
  // so it can be shifted under the property declaration with a single String.prependIndent() call,
  // the same composition buildStubMethod above uses for its own fun blocks.
  val getterBlock: String = when (val type = property.type) {
    is RirVoidType -> error("[nuget] a property cannot have void type")

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
    val valueArg: String = argConversion(property.type, "value")
    val hasStringValue: Boolean = property.type is RirStringType
    val invokeCall: String = if (hasStringValue) {
      if (receiverArg == null) "memScoped { fn.invoke($valueArg) }"
      else "memScoped { fn.invoke($receiverArg, $valueArg) }"
    } else {
      if (receiverArg == null) "fn.invoke($valueArg)" else "fn.invoke($receiverArg, $valueArg)"
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
    |        "Missing: ${'$'}{missing.joinToString(", ")}."
    |    } else {
    |      "[nuget] ${'$'}qualifiedType bindings are not registered${'$'}suffix. " +
    |        "${'$'}{landedNow.size} of ${'$'}{expected.size} expected registrations have fired: " +
    |        "${'$'}{landedNow.joinToString(", ")}. Missing: ${'$'}{missing.joinToString(", ")}."
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
