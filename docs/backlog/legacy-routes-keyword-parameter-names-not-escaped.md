# A keyword-named parameter on a legacy C# route still generates invalid C#

**A Kotlin parameter named after a C# reserved word (`ref`, `params`, `abstract`, ...) still renders
as a bare identifier, and fails to compile, on every C# route that predates the ordinary synchronous
forward callable plan.** Issue [#65](https://github.com/xxfast/kotlin-native-nuget/issues/65) fixed
the escape for the ADR-062 plan (constructors including data-class `Copy`, class methods, top-level
and extension functions, value-class members) via a render-time `csharpParameterName()` helper in
`ForwardCirPlanProjection.kt`, but every other family still constructs its own `CirParameter` (or
prints a name with no `CirParameter` node at all) straight from the raw KSP identifier: `CirClassTranslator.kt:575, 603, 663, 730, 1497, 1671, 1819` and `CirFunctionTranslator.kt:649` /
`CirTranslator.kt:653` all call `CirParameter(param.name?.asString() ?: "_", …)` with no escape, and
the raw-text renderers `CirFlowRenderer`, `CirCallbackRenderer`, and `CirSealedRenderer` print a
parameter name directly with nothing to intercept. A keyword-named parameter on a suspend method,
a `Flow<T>`-returning member, a lambda/callback parameter, a sealed-class member, a generic member,
or an interface-bridge member therefore still emits invalid C# (the same CS1001/CS1041 class #65
fixed for the ordinary plan). Grep-verified only: no fixture reproduces any of these shapes, since
the #65 fixture deliberately scoped to the shared ordinary plan. There is no single choke point that
would close every site at once; the fix is either a sweep touching every listed call site and
renderer, or moving `CirParameter` construction itself to always escape at creation time. Found
alongside issue [#65](https://github.com/xxfast/kotlin-native-nuget/issues/65).
