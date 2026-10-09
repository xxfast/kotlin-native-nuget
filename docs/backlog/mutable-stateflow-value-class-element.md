# `MutableStateFlow<ValueClass>` would take the object-handle write arm

`isMutableStateFlowElementSupported` (`cir/CirTypeMapping.kt`) admits any element whose
declaration kind is `CLASS`, and a Kotlin value class is a `CLASS` to KSP. The settable `.Value`
write seam then classifies it as an object element (`isMutableStateFlowElementObject`,
`mutableStateFlowValueParameter` in `exports/FlowExports.kt`) and unwraps it with
`asStableRef<T>()`. A value class is exported to C# as a record struct, not a handle-carrying
object, so the C# side has no handle to pass and the write would be wrong without a diagnostic.

- Status: verified by reading only. No public fixture declares a `MutableStateFlow` of a value
  class, so no test reaches it.
- A named refusal for this element kind is being added with the `MutableStateFlow<SomeEnum>` setter
  item. The mapping itself (a by-value write arm, as for the enum) remains open.
- Related: ADR-071 scoped the write seam to primitives, `String` and ordinary class elements.
