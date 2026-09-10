# A suspend method returning a sealed base itself does not compile (CS0144)

**What breaks**: a `suspend fun` whose return type is a sealed **base** class, not one of its arms
(`suspend fun next(): Job`), generates a completion that tries to construct the abstract base
directly:

```C#
t.SetResult(new Job(resultPtr));
```

`Job` is `public abstract class Job`, so this is `CS0144`, no accessible constructor: the whole
generated `Interop.cs` fails to compile.

**Root cause**: `legacyReturnShape` (`ForwardLegacyRouteCollections.kt:157-161`) only classifies a
*generic* return (`expanded.arguments.isEmpty()` returns `Plain` immediately); every object return,
sealed base included, falls through as `ForwardLegacyReturnShape.Plain` and is never routed through
[ADR-105](../adr/105-sealed-property-position.md)'s `sealedAsHandle()`, the rewrite the property
planner, the sealed-return plan route, and the parameter route already use to turn a sealed base
into a handle read through `FromHandle`. The suspend route's completion renderer then constructs
straight off the return type name with no branch for "this name is an abstract sealed base."

**Why it went unnoticed**: no fixture had a `suspend fun` returning the sealed base type itself
before this session; every existing suspend-return fixture returned a scalar, a string, an object
class, or (as of this feature) a nested sealed **arm**, never the base. The 2026-09-11 nested-arm
fix (see below) closes the sibling gap, a suspend return that names an arm by simple name, but does
not touch this one: an arm name resolves to a concrete constructor, a base name does not.

**Discovered alongside** [ADR-118](../adr/118-suspend-route-sealed-arm-owners-and-overload-numbering.md)'s
2026-09-11 amendment, which fixed the nested-sealed-*arm* suspend return
(`Task<Job.Running>` / `new Job.Running(resultPtr)`) and named this sealed-*base* case explicitly as
a separate, unfixed gap in the same route. The fix is the same shape as the arm fix, but needs
`legacyReturnShape` to recognise a sealed-base return and route it through `sealedAsHandle()` /
`FromHandle` instead of `Plain`, a distinct mapping decision from the arm-name lookup, not a
one-line follow-on.
