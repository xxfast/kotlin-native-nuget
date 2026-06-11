# kotlin-native-nuget

A plugin that allows you to publish your Kotlin/Native libraries as NuGet packages to be consumed by .NET projects.

## Design Goals

1. Plug and play; just add the plugin, configure the target and publish your library as a NuGet package
2. Native; Generated bridge should be C# idiomatic
3. Comprehensive; Support as many Kotlin features as possible, including OOP constructs, Collections, Generics and Coroutines

## Where are we now? 

[ ] Generate bridging `ClangSharpPInvokeGenerator`
[ ] Research memory management on the bridge
[ ] Map non-primitives types
[ ] Map OOP constructs
[ ] Map Collections types
[ ] Map Generics
[ ] Map Suspend functions
