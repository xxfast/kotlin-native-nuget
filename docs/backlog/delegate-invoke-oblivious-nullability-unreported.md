# Delegate `Invoke` positions (ADR-158): oblivious nullability is not reported

Every other annotatable position the reader decodes reports `info_oblivious_nullability` when a
reference-type position carries no `Nullable`/`NullableContext` annotation at all (a
pre-nullable-era assembly, or `<Nullable>disable</Nullable>`), so the consumer knows the binding
guessed non-null rather than reading a real annotation. `CustomDelegateType`'s per-position
resolution (`NugetMetadataReader/Program.cs`, the `Resolve` local function that walks a
package-declared delegate's `Invoke` parameters and return) has no equivalent check: an oblivious
custom delegate's reference-type positions bind non-null silently, unlike an ordinary method
parameter or return in the same assembly.
