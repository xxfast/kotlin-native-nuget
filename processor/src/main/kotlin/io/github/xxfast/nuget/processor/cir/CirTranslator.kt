package io.github.xxfast.nuget.processor.cir

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration

class CirTranslator(
  private val libraryName: String,
  private val rootNamespace: String,
  private val rootPackage: String,
  private val className: String,
) {
  fun translate(
    functions: List<KSFunctionDeclaration>,
    genericFunctions: List<KSFunctionDeclaration>,
    classes: List<KSClassDeclaration>,
    enums: List<KSClassDeclaration> = emptyList(),
    interfaces: List<KSClassDeclaration> = emptyList(),
    sealedClasses: List<KSClassDeclaration> = emptyList(),
    objects: List<KSClassDeclaration> = emptyList(),
  ): CirFile {
    val (genericClasses, regularClasses) = classes.partition { it.typeParameters.isNotEmpty() }

    val functionsByNamespaceAndFile: Map<Pair<String, String>, List<KSFunctionDeclaration>> = functions
      .groupBy { func ->
        val namespace: String = mapPackageToNamespace(func.packageName.asString(), rootPackage, rootNamespace)
        val fileName: String = func.containingFile?.fileName?.removeSuffix(".kt") ?: className
        namespace to fileName
      }

    val genericFunctionsByNamespaceAndFile: Map<Pair<String, String>, List<KSFunctionDeclaration>> = genericFunctions
      .groupBy { func ->
        val namespace: String = mapPackageToNamespace(func.packageName.asString(), rootPackage, rootNamespace)
        val fileName: String = func.containingFile?.fileName?.removeSuffix(".kt") ?: className
        namespace to fileName
      }

    val namespaces: MutableList<CirNamespace> = mutableListOf()
    var needsMarshalHelper: Boolean = false
    val tracker = CollectionHelperTracker()

    for ((key, funcs) in functionsByNamespaceAndFile) {
      val (namespace, fileClassName) = key

      val conflictsWithClass: Boolean = classes.any {
        it.simpleName.asString() == fileClassName &&
          mapPackageToNamespace(it.packageName.asString(), rootPackage, rootNamespace) == namespace
      }

      val conflictsWithSealed: Boolean = sealedClasses.any {
        it.simpleName.asString() == fileClassName &&
          mapPackageToNamespace(it.packageName.asString(), rootPackage, rootNamespace) == namespace
      }

      val finalClassName: String = if (conflictsWithClass || conflictsWithSealed) "${fileClassName}Kt" else fileClassName

      val members: List<CirMember> = funcs.flatMap { translateFunction(it, libraryName, tracker) }
      val existing = namespaces.find { it.name == namespace }

      if (existing != null) {
        val index: Int = namespaces.indexOf(existing)
        namespaces[index] = existing.copy(declarations = existing.declarations + CirStaticClass(finalClassName, members))
      } else {
        namespaces.add(CirNamespace(namespace, listOf(CirStaticClass(finalClassName, members))))
      }
    }

    for ((key, funcs) in genericFunctionsByNamespaceAndFile) {
      val (namespace, fileClassName) = key

      val conflictsWithClass: Boolean = classes.any {
        it.simpleName.asString() == fileClassName &&
          mapPackageToNamespace(it.packageName.asString(), rootPackage, rootNamespace) == namespace
      }

      val conflictsWithSealed: Boolean = sealedClasses.any {
        it.simpleName.asString() == fileClassName &&
          mapPackageToNamespace(it.packageName.asString(), rootPackage, rootNamespace) == namespace
      }

      val finalClassName: String = if (conflictsWithClass || conflictsWithSealed) "${fileClassName}Kt" else fileClassName

      val members: List<CirMember> = funcs.flatMap { translateGenericFunction(it, libraryName) }
      val existing = namespaces.find { it.name == namespace }

      if (existing != null) {
        val existingClass = existing.declarations.find { decl ->
          decl is CirStaticClass && decl.name == finalClassName
        } as? CirStaticClass

        if (existingClass != null) {
          val index: Int = namespaces.indexOf(existing)
          val updatedDecls = existing.declarations.map { decl ->
            if (decl is CirStaticClass && decl.name == finalClassName) {
              decl.copy(members = decl.members + members)
            } else {
              decl
            }
          }
          namespaces[index] = existing.copy(declarations = updatedDecls)
        } else {
          val index: Int = namespaces.indexOf(existing)
          namespaces[index] = existing.copy(declarations = existing.declarations + CirStaticClass(finalClassName, members))
        }
      } else {
        namespaces.add(CirNamespace(namespace, listOf(CirStaticClass(finalClassName, members))))
      }
    }

    for (cls in regularClasses) {
      val namespace: String = mapPackageToNamespace(cls.packageName.asString(), rootPackage, rootNamespace)
      val cirClass: CirClass = translateClass(cls, libraryName, tracker)
      val existing = namespaces.find { it.name == namespace }

      if (existing != null) {
        val index: Int = namespaces.indexOf(existing)
        namespaces[index] = existing.copy(declarations = existing.declarations + cirClass)
      } else {
        namespaces.add(CirNamespace(namespace, listOf(cirClass)))
      }
    }

    for (cls in genericClasses) {
      val namespace: String = mapPackageToNamespace(cls.packageName.asString(), rootPackage, rootNamespace)
      val cirGenericClass: CirGenericClass = translateGenericClass(cls, libraryName)
      val existing = namespaces.find { it.name == namespace }
      needsMarshalHelper = true

      if (existing != null) {
        val index: Int = namespaces.indexOf(existing)
        namespaces[index] = existing.copy(declarations = existing.declarations + cirGenericClass)
      } else {
        namespaces.add(CirNamespace(namespace, listOf(cirGenericClass)))
      }
    }

    for (enum in enums) {
      val namespace: String = mapPackageToNamespace(enum.packageName.asString(), rootPackage, rootNamespace)
      val cirEnum: CirEnum = translateEnum(enum, libraryName)
      val existing = namespaces.find { it.name == namespace }

      if (existing != null) {
        val index: Int = namespaces.indexOf(existing)
        namespaces[index] = existing.copy(declarations = existing.declarations + cirEnum)
      } else {
        namespaces.add(CirNamespace(namespace, listOf(cirEnum)))
      }
    }

    for (iface in interfaces) {
      val namespace: String = mapPackageToNamespace(iface.packageName.asString(), rootPackage, rootNamespace)
      val cirInterface: CirInterface = translateInterface(iface, libraryName)
      val existing = namespaces.find { it.name == namespace }

      if (existing != null) {
        val index: Int = namespaces.indexOf(existing)
        namespaces[index] = existing.copy(declarations = existing.declarations + cirInterface)
      } else {
        namespaces.add(CirNamespace(namespace, listOf(cirInterface)))
      }
    }

    for (sealed in sealedClasses) {
      val namespace: String = mapPackageToNamespace(sealed.packageName.asString(), rootPackage, rootNamespace)
      val cirSealedClass: CirSealedClass = translateSealedClass(sealed, libraryName, tracker)
      val existing = namespaces.find { it.name == namespace }

      if (existing != null) {
        val index: Int = namespaces.indexOf(existing)
        namespaces[index] = existing.copy(declarations = existing.declarations + cirSealedClass)
      } else {
        namespaces.add(CirNamespace(namespace, listOf(cirSealedClass)))
      }
    }

    for (obj in objects) {
      val namespace: String = mapPackageToNamespace(obj.packageName.asString(), rootPackage, rootNamespace)
      val cirObject: CirObject = translateObject(obj, libraryName)
      val existing = namespaces.find { it.name == namespace }

      if (existing != null) {
        val index: Int = namespaces.indexOf(existing)
        namespaces[index] = existing.copy(declarations = existing.declarations + cirObject)
      } else {
        namespaces.add(CirNamespace(namespace, listOf(cirObject)))
      }
    }

    if (tracker.needsList) {
      needsMarshalHelper = true
    }

    if (tracker.needsMap) {
      needsMarshalHelper = true
    }

    if (tracker.needsSet) {
      needsMarshalHelper = true
    }

    if (needsMarshalHelper) {
      val firstNamespace: CirNamespace = namespaces.firstOrNull() ?: CirNamespace(rootNamespace, emptyList())
      val idx: Int = namespaces.indexOfFirst { it.name == firstNamespace.name }

      val helpers: MutableList<CirDeclaration> = mutableListOf(CirMarshalHelper(libraryName))
      if (tracker.needsList) {
        helpers.add(CirListHelper(libraryName))
      }

      if (tracker.needsMap) {
        helpers.add(CirMapHelper(libraryName))
      }

      if (tracker.needsSet) {
        helpers.add(CirSetHelper(libraryName))
      }

      if (idx >= 0) {
        namespaces[idx] = firstNamespace.copy(
          declarations = helpers + firstNamespace.declarations,
        )
      } else {
        namespaces.add(0, CirNamespace(rootNamespace, helpers))
      }
    }

    val usings: MutableList<String> = mutableListOf("System", "System.Runtime.InteropServices")
    if (tracker.needsList || tracker.needsMap || tracker.needsSet) {
      usings.add("System.Collections.Generic")
    }

    return CirFile(usings = usings, namespaces = namespaces)
  }
}
