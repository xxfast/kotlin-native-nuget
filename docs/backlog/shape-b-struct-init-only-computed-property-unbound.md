# An `init`-only computed property on a Shape B struct is silently unbound.

`NugetMetadataReader/Program.cs:1012` computes a struct computed property's `isReadOnly` the same
way the class/interface property loop used to (`accessors.Setter.IsNil`), then `continue`s past
anything with a public setter. An `init`-only computed property therefore looks writable and is
skipped outright, rather than admitted as a read-only getter, the same shape the class/interface fix
now handles correctly.

Verified by reading only. Applying the same `IsExternalInit` detection here would newly admit the
property as a getter; not part of the compile-break fix, since a struct's own state constructor
already reaches every stored component through object-initializer reconstruction and this is a
computed (non-stored) property specifically.
