# The per-call callback route releases a handle-passed payload twice

**A per-call lambda-parameter method (ADR-036) with a handle-passed argument (`String`, or an
exported object) leaks one live handle *below* baseline per crossing: the tracked count drops by
one for every call rather than returning to it.** `LeakTests`' `AssertNoLeak` catches this as a
negative delta, not a positive one, since the payload is released twice rather than never.

Kotlin's callback-argument unwrap retains the argument before invoking the C# lambda and releases
it again afterwards
(`nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/exports/LambdaParameterExports.kt:119,125,133`,
`NugetHandles.release(arg0Ref!!)`), on the assumption that the C# side only *reads* the handle
during the call. But C#'s own unwrap already disposes it: `NugetMarshal.FromHandle<T>`'s `string`
branch calls `Native_dispose(handle)` right after reading the UTF8 payload
(`nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirMarshalRenderer.kt:~220`).
So the handle is disposed once on the C# side and released again on the Kotlin side, one call after
the C# thunk returns: a use-after-release, measured as `-1` live handle per crossing.

For an exported-object payload the picture flips: `FromHandle<T>` falls through to
`Materialize<T>`, which does **not** dispose, so the Kotlin-side release is the only one, the
handle is genuinely freed once, and no leak shows there, only for the `String` marshalled kind. The
stored-callback and interface-bridge routes
(`CirClassTranslator.kt:~2863` and `:~2987`) go the other way: their generated `nativeCallbackBody`
adds an *explicit* `NugetMarshal.Dispose(arg${i}Ptr)` right after `FromHandle<$csType>`, on top of
`FromHandle`'s own `string` dispose, so a `String` argument on either of those routes is disposed
twice on the C# side alone, before Kotlin's own release runs at all: three releases of one handle.

It went unnoticed because no leak row ever exercised the per-call callback route's argument side
before Row 8g: the existing rows (`Cat.DescribeWith` and kin) are functionally exercised by
`IntegrationTests`, but `LeakTests/LiveHandleTests.cs` had no row asserting the handle count returns
to baseline across a callback crossing until this feature added one.

Discovered alongside [ADR-116](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/116-sealed-subclass-methods-on-the-callable-plan.md)'s
2026-09-11 amendment, while adding `LambdaParameter_OnASealedArm_StringInAndOut_ReturnsToBaseline`
(Row 8g). Verified by a leak probe: the row is committed but skipped
(`[Fact(Skip = "pre-existing double release on the per-call callback route, see ROADMAP")]`) because
it fails on baseline `Cat.DescribeWith` too, not only on the sealed arm, confirming the bug predates
this feature and is not specific to a sealed receiver.
