# Design Goals

1. **Plug and play** — just add the plugin, configure the target and publish your library as a NuGet package
2. **Native** — generated bridge should be C# idiomatic
3. **Comprehensive** — support as many Kotlin features as possible, including OOP constructs, Collections, Generics and Coroutines
4. **Bidirectional** — support generating bindings from Kotlin → C# and C# → Kotlin
5. **Part of the Ecosystem** — a two-way citizen of both package ecosystems
   1. Plugin-generated C# bindings should be compatible with existing C# libraries and frameworks, including .NET 8+ and C# 12+
   2. The plugin should be able to consume existing C# libraries from NuGet package registries and generate Kotlin-idiomatic bindings for them — the same way the Kotlin CocoaPods and SPM plugins bring pods and Swift packages into a Kotlin build
   3. Generated packages should be cross-platform consumable on any .NET consumer regardless of OS/arch
