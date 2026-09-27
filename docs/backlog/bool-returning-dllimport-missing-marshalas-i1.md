# 104 of 204 bool-returning `DllImport`s in the generated `Interop.cs` have no `[return: MarshalAs(UnmanagedType.I1)]`.

Examples: every `Native_Equals`, `Identity_bool_native`, `Put_bool_native`, the
`*HasValue`/`out valueOut` two-call imports, and `NugetMarshal.Native_unwrap_bool` (renderer
`cir/CirMarshalRenderer.kt:39-40`). The .NET default marshalling for a `bool` return on a
`DllImport` is a 4-byte Win32 `BOOL`, while the Kotlin export returns a 1-byte C `bool`. Inferred
harmless on win-x64 today: all tests pass, including a `false` round trip through
`NugetMarshal.Native_unwrap_bool`. Not verified on any other ABI (`osx-arm64`, `linux-x64`, ...),
where the default marshalling or the native calling convention may differ. Fixing it touches
several renderers, since the same `bool`-returning `DllImport` shape is emitted from more than one
site. Discovered alongside the legacy per-call/stored-callback builtin-payload item (2026-09-27),
while reading the generated `Interop.cs` snapshot to verify a stored `Boolean` listener's `bool`
payload; the payload direction (`FromHandle<bool>`) was not affected, only bool-returning imports.
