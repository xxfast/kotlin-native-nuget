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
  appendLine("        internal sealed class $name : ${backing.ownerName}")
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
    backing.properties.forEach { property -> renderProperty(property) }
    backing.methods.forEach { method ->
      renderDllImport(
        methodNativeImport(backing.libraryName, backing.nativePrefix, method)
          .hiding(backing.inheritedExternNames),
      )
      renderMethod(method, name)
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
    // The wrapper is nested in the class, so the class's private externs are visible to it.
    inheritedExternNames = ordinaryNativeImports().map { it.name }.toSet(),
    dispose = CirBackingDispose(
      hasSuspendMethods = hasSuspendMethods,
      overridesDisposeAsync = backingOverridesDisposeAsync,
    ),
  )
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
