package io.github.xxfast.kotlin.native.nuget.processor.cir

/**
 * ADR-133: [nested] renders the enum body only. A nested enum is re-indented into its owner's
 * block by the caller, while its extension class stays at namespace level (CS1109 forbids
 * extension methods in a nested class) and is emitted by `renderNamespace`'s post-pass.
 */
internal fun StringBuilder.renderEnum(enum: CirEnum, nested: Boolean = false) {
  renderDoc(enum.doc, generated = enum.remarks)
  appendLine("    public enum ${enum.name}")
  appendLine("    {")

  for (entry in enum.entries) {
    renderDoc(entry.doc, "        ")
    appendLine("        ${entry.name} = ${entry.ordinal},")
  }

  appendLine("    }")

  if (enum.extensionMembers.isNotEmpty() && !nested) {
    appendLine()
    renderEnumExtensions(enum)
  }
}

/**
 * ADR-132 (2026-09-20): `partial`, and load-bearing. An enum that has properties of its own already
 * owns a `{Enum}Extensions` class here, while *any* extension declared over that enum -- function
 * or property -- merges into a static class of the same name in the same namespace
 * (`CirTranslator`'s extension loops, rendered `public static partial class` by
 * `CirClassRenderer`). Two declarations of one class with only one of them `partial` is CS0260,
 * which fails the whole `Interop.cs` compile. Latent since ADR-132 shipped the enum receiver on
 * the extension-FUNCTION route: nothing in the fixture set declared an extension over an enum
 * until `Mood.rallyCry()` / `Mood.emoji` did.
 */
internal fun StringBuilder.renderEnumExtensions(enum: CirEnum) {
  val className: String = "${enum.csName.replace(".", "")}Extensions"
  appendLine("    public static partial class $className")
  appendLine("    {")

  // ADR-006 amendment: projected off the enum's ENUM_MEMBER property plans, so each extern carries
  // the error slot, each getter throws the mapped Kotlin exception, a `var` binds `SetX`, and the
  // author's KDoc renders. The member renderer is the one every other projected member uses.
  enum.extensionMembers.forEach { member -> renderMember(member, className) }

  appendLine("    }")
}

