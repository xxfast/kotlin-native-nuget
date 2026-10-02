package io.github.xxfast.kotlin.native.nuget.processor.cir

/** ADR-187: the C# type of a wrapper's owned handle, and of every DllImport slot that takes it. */
internal const val KOTLIN_HANDLE: String = "NugetKotlinHandle"

internal class CirRenderer {
  fun render(file: CirFile): String =
    renderFile(file).withUtf8StringParameters().checkSpellableInCSharp()

  private fun renderFile(file: CirFile): String = buildString {
    appendLine("#nullable enable")
    appendLine()

    for (using in (file.usings + "Kotlin.Native.Interop").distinct()) {
      appendLine("using $using;")
    }

    appendLine()

    // CirTranslator may reuse a root namespace after another package's namespace.
    val root: Int = file.namespaces.indexOfFirst { it.name == file.rootNamespace }
    check(root >= 0 || file.namespaces.isEmpty()) {
      "CIR file has no publisher root namespace '${file.rootNamespace}'."
    }
    file.namespaces.forEachIndexed { index, namespace ->
      renderNamespace(namespace, index == root)
    }
  }

  private fun StringBuilder.renderHandleHelpers() {
    // ADR-178: one internal interface per publisher root, so only its own wrappers satisfy it.
    // Every class that declares `internal NugetKotlinHandle _handle` implements it explicitly,
    // which is how erased-generic code extracts a handle without `GetField("_handle")`.
    // ADR-187: the pointer stays `IntPtr`; whoever consumes it keeps the wrapper alive past the
    // native call (`GC.KeepAlive`), because a raw pointer read does not.
    appendLine("internal interface INugetHandle")
    appendLine("{")
    appendLine("    IntPtr Handle { get; }")
    appendLine("}")
    appendLine()
    renderKotlinHandle()
    // ROADMAP line 26: `Interop.cs` compiles into the consumer, so `internal` does not hide the
    // handle constructor from overload resolution. A trailing `out` parameter of this type is one
    // no ordinary call binds, so `new Tag('O')` and `new Circle(5)` always reach the public one.
    appendLine("/// <summary>")
    appendLine("/// Marks internal constructors that adopt an existing Kotlin handle, so no")
    appendLine("/// ordinary call binds one accidentally.")
    appendLine("/// </summary>")
    appendLine("internal readonly struct NugetHandleTag")
    appendLine("{")
    appendLine("}")
    appendLine()
  }

  /**
   * ADR-187: the one owned-handle type per publisher root. Every wrapper holds its Kotlin
   * `StableRef` in one of these, so a wrapper dropped without `Dispose()` is released when the GC
   * finalizes the handle, and every DllImport slot that takes a wrapper's handle is typed as it, so
   * the marshaller keeps it alive for the call. The release is `nuget_dispose` (every per-type
   * `_dispose` export is the same `NugetHandles.release`).
   *
   * [Null] is the zero handle: the argument for a null wrapper (a null `SafeHandle` argument is an
   * `ArgumentNullException` in the marshaller) and the value a disposed wrapper's field is swapped
   * to, so a disposed wrapper still reads as zero exactly as the raw field did. It is never closed,
   * because every release path swaps it in and returns early on an invalid handle.
   */
  private fun StringBuilder.renderKotlinHandle() {
    appendLine("/// <summary>")
    appendLine("/// The Kotlin handle a generated wrapper owns. Disposing the wrapper releases it promptly; a")
    appendLine("/// wrapper dropped without disposing is released when the GC finalizes this handle.")
    appendLine("/// </summary>")
    appendLine("internal class NugetKotlinHandle : SafeHandle")
    appendLine("{")
    appendLine("    /// <summary>The zero handle: a null argument, and a disposed wrapper's field. Never released.</summary>")
    appendLine("    internal static readonly NugetKotlinHandle Null = new NugetKotlinHandle(IntPtr.Zero);")
    appendLine()
    appendLine("    internal NugetKotlinHandle(IntPtr handle) : base(IntPtr.Zero, ownsHandle: true) => SetHandle(handle);")
    appendLine()
    appendLine("    public override bool IsInvalid => handle == IntPtr.Zero;")
    appendLine()
    appendLine("    protected override bool ReleaseHandle()")
    appendLine("    {")
    appendLine("        NugetMarshal.Dispose(handle);")
    appendLine("        return true;")
    appendLine("    }")
    appendLine("}")
    appendLine()
  }

  private fun StringBuilder.renderNamespace(namespace: CirNamespace, root: Boolean) {
    appendLine("namespace ${namespace.name}")
    appendLine("{")

    if (root) renderHandleHelpers()

    for (declaration in namespace.declarations) {
      renderDeclaration(declaration)
    }

    // ADR-133: an extension class cannot be nested (CS1109), so a NESTED enum's extensions are
    // hoisted here, named for the whole chain (`OwnerKindExtensions`), while the enum itself
    // renders inside its owner's block. A top-level enum still renders its own, in `renderEnum`.
    namespace.declarations.forEach { declaration ->
      nestedEnumsOf(declaration)
        .filter { it.extensionMembers.isNotEmpty() }
        .forEach { nestedEnum ->
          appendLine()
          renderEnumExtensions(nestedEnum)
        }
    }

    appendLine("}")
    appendLine()
  }
}

/**
 * ADR-133/ADR-134: the declarations nested inside [declaration], whatever owner kind it is. A
 * missing arm here is SILENT: a nested enum under that owner keeps its declaration and loses the
 * namespace-level extension class its properties are read through (CS1109 forbids nesting one).
 */
private fun nestedDeclarationsOf(declaration: CirDeclaration): List<CirDeclaration> =
  when (declaration) {
    is CirClass -> declaration.nestedDeclarations
    is CirObject -> declaration.nestedDeclarations
    // ADR-134's new owner kinds. A sealed base owns both its own children and every arm's.
    is CirInterface -> declaration.nestedDeclarations
    is CirSealedClass ->
      declaration.nestedDeclarations + declaration.subclasses.flatMap { it.nestedDeclarations }
    else -> emptyList()
  }

/** ADR-133: every nested `CirEnum` under [declaration], at any depth. */
private fun nestedEnumsOf(declaration: CirDeclaration): List<CirEnum> =
  nestedDeclarationsOf(declaration).flatMap { nested ->
    listOfNotNull(nested as? CirEnum) + nestedEnumsOf(nested)
  }

/**
 * ADR-133: the one dispatch over [CirDeclaration], called at namespace level and recursively from
 * [renderClass] / [renderObject] for a nested declaration.
 *
 * [nested] reaches only the enum, whose extension class is hoisted to namespace level by
 * `renderNamespace` (CS1109); every other kind renders identically wherever it lives and is
 * re-indented by [renderNestedDeclarations].
 */
internal fun StringBuilder.renderDeclaration(declaration: CirDeclaration, nested: Boolean = false) {
  when (declaration) {
    is CirMarshalHelper -> renderMarshalHelper(declaration)
    is CirListHelper -> renderListHelper(declaration)
    is CirBytesHelper -> renderBytesHelper(declaration)
    is CirMapHelper -> renderMapHelper(declaration)
    is CirSetHelper -> renderSetHelper(declaration)
    is CirFuncNativeHelper -> renderFuncNativeHelper(declaration)
    is CirFuncHelper -> renderFuncHelper(declaration)
    is CirSuspendFuncNativeHelper -> renderSuspendFuncNativeHelper(declaration)
    is CirSuspendFuncHelper -> renderSuspendFuncHelper(declaration)
    is CirAsyncHelper -> renderAsyncHelper(declaration)
    is CirScopeHelper -> renderScopeHelper(declaration)
    is CirJobHelper -> renderJobHelper(declaration)
    is CirErrorHelper -> renderErrorHelper(declaration)
    is CirRuntimeHelper -> renderRuntimeHelper(declaration)
    is CirFlowHelper -> renderFlowHelper(declaration)
    is CirStateFlowHandleHelper -> renderStateFlowHandleHelper(declaration)
    is CirCallbackDelegateHelper -> renderCallbackDelegateHelper(declaration)
    is CirSubscriptionHelper -> renderSubscriptionHelper(declaration)
    is CirBridgeHelper -> renderBridgeHelper(declaration)
    is CirStaticClass -> renderStaticClass(declaration)
    is CirInterface -> renderInterface(declaration)
    is CirClass -> renderClass(declaration)
    is CirEnum -> renderEnum(declaration, nested = nested)
    is CirSealedClass -> renderSealedClass(declaration)
    is CirObject -> renderObject(declaration)
    is CirValueClass -> renderValueClass(declaration)
  }
}

/**
 * ADR-133: renders each nested declaration one level in, through the same `indentNestedBody()`
 * re-indent ADR-009 uses for a sealed arm. Called from the owner's own block.
 */
internal fun StringBuilder.renderNestedDeclarations(declarations: List<CirDeclaration>) {
  declarations.forEach { declaration ->
    appendLine()
    append(buildString { renderDeclaration(declaration, nested = true) }.indentNestedBody())
  }
}

// Issue #223: `$` is legal in C# source only inside an interpolated string, so string literals are
// stripped before the scan. A `$` anywhere else is an identifier no C# compiler can read, whatever
// route emitted it.
private val CSHARP_STRING_LITERAL = Regex("[@\\$]?\"(?:[^\"\\\\]|\\\\.)*\"")

private val CSHARP_DOLLAR_IDENTIFIER = Regex("[\\w\\$]*\\$[\\w\\$]*")

/**
 * Issue #223: the single structural guard that no emitted identifier contains `$`.
 *
 * kotlinx.serialization's compiler plugin synthesizes a nested `$serializer` object on every
 * `@Serializable` declaration, and the ADR-134 nested-declaration walk used to declare it. The walk
 * refuses synthetic declarations now; this check is what makes any OTHER route reaching such a name
 * fail at generation time rather than in the consumer's `dotnet build`, where it reads as six parse
 * errors per site with nothing naming the Kotlin declaration behind them.
 */
private fun String.checkSpellableInCSharp(): String {
  val offending: String? = lineSequence()
    // ADR-150: a `///` doc comment carries the author's prose, where `$` is legal C# and common
    // (a KDoc that quotes a Kotlin template, `"${'$'}name"`, is prose, not an identifier).
    .filterNot { line -> line.trimStart().startsWith("//") }
    .map { line -> CSHARP_STRING_LITERAL.replace(line, "") }
    .firstNotNullOfOrNull { line -> CSHARP_DOLLAR_IDENTIFIER.find(line)?.value }

  check(offending == null) {
    "Generated C# declares the identifier `$offending`, which contains a '\$'. A name that " +
      "cannot be spelled in C# is a generator defect: the declaration behind it is " +
      "compiler-synthesized and must not be walked, and must not be renamed into something legal."
  }

  return this
}
