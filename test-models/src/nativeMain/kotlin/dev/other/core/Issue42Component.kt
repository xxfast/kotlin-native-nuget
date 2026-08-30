package dev.other.core

/**
 * Issue #42 supertype guard. Deliberately outside `:test-library`'s `rootPackage`
 * (`io.github.xxfast.kotlin.native.nuget.test`), like `Advertisement` next door, but reached as a
 * *supertype* rather than as a member type: an exported class implements it, so today the class
 * renders `: IIssue42Component` and `Interop.cs` fails with CS0246. It must instead be skipped
 * with `SKIPPED_UNEXPORTED_SUPERTYPE`, leaving the implementing class and its own members
 * exported, and never a build break. Everything here is default-implemented on purpose: an
 * unexported supertype carries no members the C# side could call, so dropping it loses nothing.
 */
interface Issue42Component {
  fun componentTag(): String = "issue42"
}
