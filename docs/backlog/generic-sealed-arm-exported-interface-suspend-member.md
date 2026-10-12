# A generic sealed arm implementing an exported interface with a `suspend` member breaks the C# build

Found with the [ADR-147](../adr/147-generic-class-methods.md) 2026-10-12 amendment (generic sealed arm members). Verified with a Tier 1 probe, since deleted; nothing in that change touches it. The snippet is the shape probed, not a fixture.

```kotlin
interface Feed { suspend fun fetch(): Int }

sealed class Dish<out T> {
  class Hot<T>(val item: T) : Dish<T>(), Feed {
    override suspend fun fetch(): Int = 1
  }
}
```

The generated C# does not compile: CS0535 twice. `Dish.Hot<T>` lists `IFeed` but implements neither `IFeed.FetchAsync` nor `IAsyncDisposable.DisposeAsync`.

- **Cause.** The arm path never calls `forwardAsyncInterfaceForwards`. [ADR-174](../adr/174-interface-async-members.md)'s explicit interface forwards exist for ordinary classes only, so an arm that implements an exported interface with a `suspend` member gets the `IFeed` base list and none of the members it requires.
- **Second symptom.** `Hot<T>.fetch` is also dropped without a diagnostic. The arm naming pass added for [ADR-147](../adr/147-generic-class-methods.md)'s 2026-10-12 amendment exempts a member an exported interface carries, and on an arm nothing carries it.

