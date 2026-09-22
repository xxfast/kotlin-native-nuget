# The generic-witness route has no cross-package resolver import for a cross-namespace interface return.

Same gap as the interface-route import miss, on the generic route: `genericWitnessObjectFileContent`
(`NugetGenerateBindingsTask.kt`, near `:1282`) imports the plain interface type for a member
returning an interface from another bound namespace, but not the `nuget{Name}Value` resolver
function the generated body calls.

Inferred by reading, no fixture: no generic-class member in `TestDependency` returns an interface
from a different bound namespace today.
