# An interface-bridge `add`/`remove` pair over a generic listener interface generates Kotlin that does not compile

Found with the [ADR-147](../adr/147-generic-class-methods.md) 2026-10-12 amendment (generic sealed arm members). Verified with a Tier 1 probe, since deleted. Not specific to arms. The snippet is the shape probed, not a fixture.

```kotlin
interface Ear<T> { fun hear(item: T) }

class Booth {
  fun addEar(listener: Ear<Int>) { /* ... */ }
  fun removeEar(listener: Ear<Int>) { /* ... */ }
}
```

The same pair with `Ear<T>` on a generic arm (`Hot<T>`) fails the same way. The generated Kotlin fails to compile ("One type argument expected for 'interface Ear<T>'") and the whole module's build stops.

- **Cause.** `exports/InterfaceBridgeExports.kt` spells the listener interface by its bare name, while the C# half drops the member, so the two halves disagree.

- **Coverage gap.** The interface-bridge pair has no generic-listener fixture; the [ADR-147](../adr/147-generic-class-methods.md) 2026-10-03 amendment covers a non-generic listener on a generic class only.
