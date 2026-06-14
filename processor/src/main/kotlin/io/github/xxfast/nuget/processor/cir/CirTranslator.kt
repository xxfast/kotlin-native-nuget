package io.github.xxfast.nuget.processor.cir

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration

data class NugetContext(
  val libraryName: String,
  val rootNamespace: String,
  val rootPackage: String,
  val className: String,
)

fun translate(
  context: NugetContext,
  logger: KSPLogger,
  functions: List<KSFunctionDeclaration>,
  genericFunctions: List<KSFunctionDeclaration>,
  classes: List<KSClassDeclaration>,
  enums: List<KSClassDeclaration> = emptyList(),
  interfaces: List<KSClassDeclaration> = emptyList(),
  sealedClasses: List<KSClassDeclaration> = emptyList(),
  objects: List<KSClassDeclaration> = emptyList(),
): CirFile {
  val (genericClasses, regularClasses) = classes.partition { it.typeParameters.isNotEmpty() }

  val exportedTypes: Set<String> = buildSet {
    classes.forEach { add(it.qualifiedName?.asString() ?: "") }
    enums.forEach { add(it.qualifiedName?.asString() ?: "") }
    interfaces.forEach { add(it.qualifiedName?.asString() ?: "") }
    sealedClasses.forEach { cls ->
      add(cls.qualifiedName?.asString() ?: "")
      cls.getSealedSubclasses().forEach { add(it.qualifiedName?.asString() ?: "") }
    }
    objects.forEach { add(it.qualifiedName?.asString() ?: "") }
    remove("")
  }

  fun namespaceOf(pkg: String): String =
    mapPackageToNamespace(pkg, context.rootPackage, context.rootNamespace)

  fun groupByNamespaceAndFile(
    funcs: List<KSFunctionDeclaration>,
  ): Map<Pair<String, String>, List<KSFunctionDeclaration>> =
    funcs.groupBy { func ->
      val namespace: String = namespaceOf(func.packageName.asString())
      val fileName: String = func.containingFile?.fileName?.removeSuffix(".kt") ?: context.className
      namespace to fileName
    }

  fun resolveStaticClassName(fileClassName: String, namespace: String): String {
    val conflictsWithClass: Boolean = classes.any {
      it.simpleName.asString() == fileClassName && namespaceOf(it.packageName.asString()) == namespace
    }

    val conflictsWithSealed: Boolean = sealedClasses.any {
      it.simpleName.asString() == fileClassName && namespaceOf(it.packageName.asString()) == namespace
    }

    return if (conflictsWithClass || conflictsWithSealed) "${fileClassName}Kt" else fileClassName
  }

  val namespaces: MutableList<CirNamespace> = mutableListOf()
  var needsMarshalHelper: Boolean = false
  val tracker = CollectionHelperTracker()

  for ((key, funcs) in groupByNamespaceAndFile(functions)) {
    val (namespace, fileClassName) = key
    val finalClassName: String = resolveStaticClassName(fileClassName, namespace)
    val members: List<CirMember> = funcs.flatMap { translateFunction(it, context.libraryName, tracker, exportedTypes, logger) }
    namespaces.addDeclaration(namespace, CirStaticClass(finalClassName, members))
  }

  for ((key, funcs) in groupByNamespaceAndFile(genericFunctions)) {
    val (namespace, fileClassName) = key
    val finalClassName: String = resolveStaticClassName(fileClassName, namespace)
    val members: List<CirMember> = funcs.flatMap { translateGenericFunction(it, context.libraryName) }
    namespaces.mergeStaticClass(namespace, finalClassName, members)
  }

  for (cls in regularClasses) {
    namespaces.addDeclaration(namespaceOf(cls.packageName.asString()), translateClass(cls, context.libraryName, tracker, exportedTypes, logger))
  }

  for (cls in genericClasses) {
    namespaces.addDeclaration(namespaceOf(cls.packageName.asString()), translateGenericClass(cls, context.libraryName))
    needsMarshalHelper = true
  }

  for (enum in enums) {
    namespaces.addDeclaration(namespaceOf(enum.packageName.asString()), translateEnum(enum, context.libraryName))
  }

  for (iface in interfaces) {
    namespaces.addDeclaration(namespaceOf(iface.packageName.asString()), translateInterface(iface, context.libraryName))
  }

  for (sealed in sealedClasses) {
    namespaces.addDeclaration(namespaceOf(sealed.packageName.asString()), translateSealedClass(sealed, context.libraryName, tracker, exportedTypes, logger))
  }

  for (obj in objects) {
    namespaces.addDeclaration(namespaceOf(obj.packageName.asString()), translateObject(obj, context.libraryName))
  }

  if (tracker.needsList || tracker.needsMap || tracker.needsSet || tracker.lambdaArities.isNotEmpty()) {
    needsMarshalHelper = true
  }

  if (needsMarshalHelper) {
    val helpers: MutableList<CirDeclaration> = mutableListOf(CirMarshalHelper(context.libraryName))
    if (tracker.needsList) helpers.add(CirListHelper(context.libraryName))
    if (tracker.needsMap) helpers.add(CirMapHelper(context.libraryName))
    if (tracker.needsSet) helpers.add(CirSetHelper(context.libraryName))
    if (tracker.lambdaArities.isNotEmpty()) helpers.add(CirFuncNativeHelper(context.libraryName, tracker.lambdaArities))

    val firstNamespace: CirNamespace = namespaces.firstOrNull() ?: CirNamespace(context.rootNamespace, emptyList())
    val idx: Int = namespaces.indexOfFirst { it.name == firstNamespace.name }

    if (idx >= 0) {
      namespaces[idx] = firstNamespace.copy(declarations = helpers + firstNamespace.declarations)
    } else {
      namespaces.add(0, CirNamespace(context.rootNamespace, helpers))
    }

    if (tracker.lambdaArities.isNotEmpty()) {
      val helperNs: String = firstNamespace.name
      val funcHelper = CirFuncHelper(context.libraryName, tracker.lambdaArities, helperNs)
      val rootIdx: Int = namespaces.indexOfFirst { it.name == context.rootNamespace }

      if (rootIdx >= 0) {
        val root: CirNamespace = namespaces[rootIdx]
        namespaces[rootIdx] = root.copy(declarations = listOf(funcHelper) + root.declarations)
      } else {
        namespaces.add(CirNamespace(context.rootNamespace, listOf(funcHelper)))
      }
    }
  }

  val usings: MutableList<String> = mutableListOf("System", "System.Runtime.InteropServices")
  if (tracker.needsList || tracker.needsMap || tracker.needsSet) {
    usings.add("System.Collections.Generic")
  }

  return CirFile(usings = usings, namespaces = namespaces)
}

private fun MutableList<CirNamespace>.addDeclaration(namespace: String, declaration: CirDeclaration) {
  val existing = find { it.name == namespace }
  if (existing != null) {
    val index: Int = indexOf(existing)
    this[index] = existing.copy(declarations = existing.declarations + declaration)
  } else {
    add(CirNamespace(namespace, listOf(declaration)))
  }
}

private fun MutableList<CirNamespace>.mergeStaticClass(namespace: String, className: String, members: List<CirMember>) {
  val existing = find { it.name == namespace }

  if (existing == null) {
    add(CirNamespace(namespace, listOf(CirStaticClass(className, members))))
    return
  }

  val index: Int = indexOf(existing)
  val existingClass = existing.declarations.find { it is CirStaticClass && it.name == className } as? CirStaticClass

  if (existingClass != null) {
    val updatedDecls = existing.declarations.map { decl ->
      if (decl is CirStaticClass && decl.name == className) decl.copy(members = decl.members + members)
      else decl
    }
    this[index] = existing.copy(declarations = updatedDecls)
  } else {
    this[index] = existing.copy(declarations = existing.declarations + CirStaticClass(className, members))
  }
}
