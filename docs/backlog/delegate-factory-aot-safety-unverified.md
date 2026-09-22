# Delegate factory (ADR-158): AOT/trimmer safety inferred, never spiked

`new TDelegate(holder.Invoke)`, the C# factory a delegate slot generates, is a static `ldftn` +
`newobj` over an instance method: no reflection and no `Marshal.GetDelegateForFunctionPointer`, the
exact family [ADR-102](../adr/102-aot-safe-forward-callbacks.md) removed forward for AOT safety, and
[ADR-094](../adr/094-reflection-free-generic-dispatch.md)'s reflection-free-dispatch argument covers
this shape too. Neither
claim has been run through `AotSmokeTest/`, because no delegate fixture existed there when this was
written. Once one is added, this is the seam to exercise: a NativeAOT-published consumer calling a
`Workshop`-style API with a Kotlin lambda argument.
