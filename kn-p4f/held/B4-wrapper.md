# Held from B4: a returned lambda over a generic value class on a class member

Re-add with C5 (returned lambdas on every owner).

Kotlin, inside `class StickerCourier` in `test-library/.../test/stickers/StickerSwaps.kt`:

```kotlin
  /** A member's own `T` inside the struct as a returned lambda's result. */
  fun <T> wrapper(): (T) -> Sticker<T> = { Sticker(it) }
```

C#, in `IntegrationTests/GenericValueClassErasedTests.cs` (lambdas section):

```csharp
    [Fact]
    public void OwnTypeParameter_ReturnedLambdaResult_CrossesBoxed()
    {
        using var courier = new StickerCourier("Oreo's desk");
        using KotlinFunc<string, Sticker<string>> wrapper = courier.Wrapper<string>();

        Assert.Equal(new Sticker<string>("Oreo"), wrapper.Invoke("Oreo"));
    }
```
