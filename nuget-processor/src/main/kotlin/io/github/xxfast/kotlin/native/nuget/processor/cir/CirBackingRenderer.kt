package io.github.xxfast.kotlin.native.nuget.processor.cir

/**
 * What an abstract owner's backing wrapper needs beyond its name: the overrides of the owner's
 * abstract members ([backingOverrides]) and, for an ordinary abstract class whose `Dispose()` is
 * itself abstract, the disposal it must implement. A sealed arm's `Dispose()` is concrete, so an
 * arm's wrapper passes no [dispose].
 */
internal data class CirBacking(
  val name: String,
  val ownerName: String,
  val libraryName: String,
  val nativePrefix: String,
  val properties: List<CirProperty>,
  val methods: List<CirMethod>,
  /** Extern names the wrapper inherits access to, so a redeclaration says `new` (CS0108). */
  val inheritedExternNames: Set<String>,
  val dispose: CirBackingDispose? = null,
  /** Overrides of abstract members declared on abstract bases above the owner. */
  val inherited: List<CirBackingInherited> = emptyList(),
  /**
   * A generic owner's type parameters. Its wrapper is generic too (`Backing<T> : Trove<T>`) and is
   * held by the ADR-196 non-generic holder `Trove`, since no extern may sit inside a generic type
   * (CS7042): the owner's privates are out of its reach, so it imports every member it overrides
   * itself, and its externs are hoisted onto the holder.
   */
  val typeParameters: List<CirTypeParameter> = emptyList(),
) {
  val isHeld: Boolean get() = typeParameters.isNotEmpty()
}

/**
 * The abstract members an abstract class inherits from one abstract base above it and leaves
 * unimplemented (`Puppy : Animal` leaving `Animal.legs()` open). The base planned each as a
 * call-through export under its own [nativePrefix], which dispatches virtually in Kotlin, so the
 * derived class's wrapper overrides it over that export. The base's externs are private to the
 * base, so the wrapper declares its own for every member here, properties included.
 */
internal data class CirBackingInherited(
  /** The base's qualified C# type path (`Interop.Pets.Animal`), for the `Result` twin pass. */
  val ownerPath: String,
  val nativePrefix: String,
  val properties: List<CirProperty>,
  val methods: List<CirMethod>,
)

/** The `renderDispose` flags of the abstract owner, read from the owner the wrapper derives. */
internal data class CirBackingDispose(
  val hasSuspendMethods: Boolean,
  val overridesDisposeAsync: Boolean,
)

/**
 * The internal concrete wrapper nested in an abstract owner, rendered at the depth a class member
 * sits at. C# cannot instantiate the owner, so every handle that materialises as the owner
 * constructs this instead. It overrides the owner's abstract members over their call-through
 * exports (the owner keeps the externs of its abstract properties; methods bring their own) and
 * inherits everything else.
 */
internal fun backingClassBlock(backing: CirBacking): String = buildString {
  val name: String = backing.name
  val arguments: String = if (backing.isHeld) {
    backing.typeParameters.joinToString(", ", prefix = "<", postfix = ">") { it.name }
  } else {
    ""
  }
  val constraints: String = backing.typeParameters
    .filter { parameter -> parameter.bounds.isNotEmpty() }
    .joinToString("") { parameter ->
      " where ${parameter.name} : ${parameter.bounds.joinToString(", ")}"
    }
  appendLine(
    "        internal sealed class $name$arguments : ${backing.ownerName}$arguments$constraints",
  )
  appendLine("        {")
  appendLine(
    "            internal $name(IntPtr handle, out NugetHandleTag tag) : base(handle, out tag)",
  )
  appendLine("            {")
  appendLine("            }")
  appendLine()
  appendLine(
    "            internal $name(NugetKotlinHandle handle, out NugetHandleTag tag) : " +
        "base(handle, out tag)",
  )
  appendLine("            {")
  appendLine("            }")
  appendLine()
  val members: String = buildString {
    backing.properties.forEach { property ->
      // A nested wrapper reads the owner's private property externs; a held one cannot.
      if (backing.isHeld) {
        propertyNativeImports(backing.libraryName, backing.nativePrefix, property)
          .forEach { nativeImport -> renderDllImport(nativeImport) }
      }
      renderProperty(property)
    }
    backing.methods.forEach { method ->
      renderDllImport(
        methodNativeImport(backing.libraryName, backing.nativePrefix, method)
          .hiding(backing.inheritedExternNames),
      )
      renderMethod(method, name)
    }
    backing.inherited.forEach { base ->
      base.properties.forEach { property ->
        propertyNativeImports(backing.libraryName, base.nativePrefix, property)
          .map { nativeImport -> nativeImport.hiding(backing.inheritedExternNames) }
          .forEach { nativeImport -> renderDllImport(nativeImport) }
        renderProperty(property)
      }
      base.methods.forEach { method ->
        renderDllImport(
          methodNativeImport(backing.libraryName, base.nativePrefix, method)
            .hiding(backing.inheritedExternNames),
        )
        renderMethod(method, name)
      }
    }
    val dispose: CirBackingDispose? = backing.dispose
    if (dispose != null) {
      renderDispose(
        nativeImport = CirDllImport(
          libraryName = backing.libraryName,
          entryPoint = "${backing.nativePrefix}_dispose",
          returnType = "void",
          name = "Native_Dispose",
          parameters = listOf(CirParameter("handle", "IntPtr")),
          visibility = CirVisibility.PRIVATE,
        ).hiding(backing.inheritedExternNames),
        hasSuperClass = true,
        hasSuspendMethods = dispose.hasSuspendMethods,
        ownsScope = false,
        overridesDisposeAsync = dispose.overridesDisposeAsync,
      )
    }
  }
  if (members.isNotBlank()) append(members.trimEnd().indentNestedBody()).appendLine()
  appendLine("        }")
  appendLine()
}

/** This abstract class's backing wrapper, or null when it has none. */
internal fun CirClass.backing(): CirBacking? {
  val name: String = backingName ?: return null
  return CirBacking(
    name = name,
    ownerName = this.name,
    libraryName = libraryName,
    nativePrefix = nativePrefix,
    // Derived from the class's members as they stand, after the `Result` twin collision pass, so
    // the wrapper overrides exactly the abstract members (and twins) the class still declares.
    properties = properties.backingOverrides(),
    methods = methods.backingOverrides(),
    // A wrapper nested in the class sees the class's private externs; a held one does not.
    inheritedExternNames = if (typeParameters.isEmpty()) {
      ordinaryNativeImports().map { it.name }.toSet()
    } else {
      emptySet()
    },
    dispose = CirBackingDispose(
      hasSuspendMethods = hasSuspendMethods,
      overridesDisposeAsync = backingOverridesDisposeAsync,
    ),
    inherited = backingInherited,
    typeParameters = typeParameters,
  )
}

/**
 * The wrapper's externs for [CirBacking.inherited]: second imports of entry points the declaring
 * base's own wrapper already imports, from the same plan. The ABI contract requires each to equal
 * an import it checks, rather than counting it as a duplicate C# import of that entry point.
 */
internal fun CirBacking.inheritedNativeImports(): List<CirDllImport> = inherited.flatMap { base ->
  base.properties.flatMap { property ->
    propertyNativeImports(libraryName, base.nativePrefix, property)
  } + base.methods.map { method -> methodNativeImport(libraryName, base.nativePrefix, method) }
}

/** The wrapper's own imports, for the ABI contract: its method externs and its dispose. */
internal fun CirBacking.nativeImports(): List<CirDllImport> = buildList {
  methods.forEach { method -> add(methodNativeImport(libraryName, nativePrefix, method)) }
  if (dispose != null) {
    add(
      CirDllImport(
        libraryName = libraryName,
        entryPoint = "${nativePrefix}_dispose",
        returnType = "void",
        name = "Native_Dispose",
        parameters = listOf(CirParameter("handle", "IntPtr")),
        visibility = CirVisibility.PRIVATE,
      ),
    )
  }
}
