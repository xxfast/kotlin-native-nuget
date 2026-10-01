# A nullable class or sealed handle parameter on a suspend or Flow member is refused (#365)

Issue [#365](https://github.com/xxfast/kotlin-native-nuget/issues/365). A member `suspend fun water(id: PlantId, dose: Dose?)`,
with `Dose` a sealed interface or a plain class, is skipped whole as `SKIPPED_UNSUPPORTED_INPUT`. The
diagnostic (`NugetProcessor.kt` `refusedParameter`) says the member "can take a ... sealed-type handle,
but not dose: Dose?" and then suggests passing a sealed type, so it never names nullability as the reason.

The refusal is `legacyParameterShape` in `ForwardLegacyRouteCollections.kt`: `ObjectHandle` lands on
`Handle`, `Nullable` of a scalar or enum lands on `NullableScalar` (#299's 2026-09-26 amendment to ADR-122),
and `Nullable(ObjectHandle)` falls to `Refused`. `sealedAsHandle()` already rewrites a nullable sealed
type into `Nullable(ObjectHandle)`, so sealed and plain classes share the path.

[ADR-122](../adr/122-handle-parameters-on-the-legacy-routes.md) refuses it for a procedural reason
inherited from ADR-114 (nullable threading on the legacy routes is done once or not at all), with no
wire or lifetime objection. Scalars and `String` were threaded by #299, so a nullable handle is the
only shape left under that rule.

Expected: `Task WaterAsync(PlantId id, Dose? dose, CancellationToken cancellationToken = default)`;
C# `null` crosses as `IntPtr.Zero` and Kotlin rebuilds it as `null`. Same four legacy routes #299
threaded: suspend member, sealed arm, top-level suspend, `Flow`/`StateFlow` member. The sync plan
route already does this (`ForwardCirPlanProjection.kt` emits `x?._handle ?? IntPtr.Zero`,
`ForwardKotlinPlanEmitter.kt` emits `x?.asStableRef<T>()?.get()`), and a nullable handle stays one
pointer slot, so no `HasValue` slot, no CIR model change and no DllImport fan-out is needed.

Not wanted: a `HasValue` slot beside the handle, mapping `null` to a default or sentinel instance,
or fixing only the diagnostic (what happened to #131).
