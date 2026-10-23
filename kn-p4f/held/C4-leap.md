# C4 held: `ZoomrunSample.LeapAsync`

Removed from the C4 tree because a suspend top-level function taking an interface parameter needs a
feature from another branch. Restore both pieces once that lands.

## Fixture member (`test-library/.../zoomrun/ZoomrunSample.kt`)

```kotlin
/** Awaits a [Pouncer]'s suspend lambda property. */
suspend fun leap(pouncer: Pouncer, height: Int): Int = pouncer.onLeap(height)
```

## Row line (`IntegrationTests/LambdaPropertyOwnerTests.cs`, `Pouncer_CSharpImplemented_KotlinReadsAndWritesTheLambdaProperties`)

```csharp
        Assert.Equal(30, await ZoomrunSample.LeapAsync(wand, 3));
```
