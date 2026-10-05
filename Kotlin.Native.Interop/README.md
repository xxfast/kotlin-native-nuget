# Xxfast.Kotlin.Native.Interop

![kotlin-native-nuget](https://raw.githubusercontent.com/xxfast/kotlin-native-nuget/main/Kotlin.Native.Interop/icon.png)

[![Stability](https://kotl.in/badges/experimental.svg)](https://kotlinlang.org/docs/components-stability.html#stability-of-subcomponents)
[![CI](https://github.com/xxfast/kotlin-native-nuget/actions/workflows/ci.yml/badge.svg)](https://github.com/xxfast/kotlin-native-nuget/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/xxfast/kotlin-native-nuget/graph/badge.svg?token=PEDQUEBEV8)](https://codecov.io/gh/xxfast/kotlin-native-nuget)
[![NuGet](https://img.shields.io/nuget/v/Xxfast.Kotlin.Native.Interop)](https://www.nuget.org/packages/Xxfast.Kotlin.Native.Interop)

The shared types behind NuGet packages built from Kotlin/Native libraries with
[kotlin-native-nuget](https://github.com/xxfast/kotlin-native-nuget).

You don't reference this package yourself. Every package the plugin builds depends on it, so it
arrives with the first Kotlin-built package you install. Because they all share this one assembly,
an exception thrown by any of them is the same .NET type, whichever package it came from.

## What's in it

All types live in the `Kotlin.Native.Interop` namespace.

- `KotlinException`: a Kotlin exception with no closer .NET equivalent.
- Mapped exceptions that keep their usual .NET base type, such as `KotlinArgumentException`
  (`ArgumentException`), `KotlinInvalidOperationException` (`InvalidOperationException`) and
  `KotlinIOException` (`IOException`).
- `IKotlinException`: implemented by all of the above. It carries the Kotlin class name and the
  Kotlin stack trace.
- `KotlinOptional<T>`: a parameter you can leave unset so Kotlin evaluates its default.
- `KotlinNothing`: the C# spelling of Kotlin's `Nothing` as a type argument.

## Catching a Kotlin exception

```csharp
using Kotlin.Native.Interop;

try
{
    MappedExceptions.CheckOreoWeight(10);
}
catch (KotlinArgumentException ex)
{
    Console.WriteLine(ex.Message);
}
```

A mapped exception is also caught by its .NET base type, so `catch (ArgumentException)` works too.

## Versioning

This package is released with the plugin, at the plugin's version. A package built by plugin `X`
depends on this one from `X` up to the next major, so packages built by different plugin versions
restore onto a single copy.

## Links

- [Exceptions](https://xxfast.github.io/kotlin-native-nuget/exceptions.html)
- [Documentation](https://xxfast.github.io/kotlin-native-nuget/)
- [Source and issues](https://github.com/xxfast/kotlin-native-nuget)
