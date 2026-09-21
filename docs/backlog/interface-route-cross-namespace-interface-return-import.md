# A bound interface member returning an interface from another bound namespace has no cross-package resolver import.

The class route's import collector was fixed to emit the cross-package `nuget{Name}Value` resolver
import for a read-position interface reference (method return, property type, collection element),
alongside the plain type import. The bound-**interface** route's own import collector
(`NugetGenerateBindingsTask.kt`, near `:5840`) was not, so an interface whose member returns an
interface bound in a different Kotlin package still imports only the type, not the resolver.

Inferred by reading, no fixture: every interface member returning another interface in
`TestDependency` lands in the same Kotlin package today. Fixing this needs either a second
namespace alias on an interface fixture or a second bound package.
