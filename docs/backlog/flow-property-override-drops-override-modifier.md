# An overriding `Flow` property renders without `override` (CS0108)

Found with issue #613 (ADR-116 amendment of 2026-11-09). Verified in a kotlin-dev scratch Tier 1 variant.

`open class Fruit { open val ticks: Flow<Int> }` with `class Quince : Fruit() { override val ticks }` generates `Quince.Ticks` without `override`, so C# reports CS0108 "'Quince.Ticks' hides inherited member 'Fruit.Ticks'". It affects any subclass of an open class, top-level included, so it fails the warnings-as-errors pack.

The site is the flow branch of the property loop in `cir/CirClassRenderer.kt` (`renderFlowPropertyNativeImports` and `renderProperty`). Where `isOverride` is dropped is not traced.
