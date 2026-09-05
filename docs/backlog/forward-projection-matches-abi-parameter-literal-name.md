# A forward projection matches an ABI parameter by its literal name, not by position

**A user-declared Kotlin parameter that happens to be named `handle`, `value`, `receiver`,
`errorOut`, or `valueOut` is silently misrouted rather than escaped or renamed**, because the forward
projection layer identifies the generator's own synthetic ABI parameters by string-matching their
literal name rather than by position or a marker type: `ForwardCirPlanProjection.kt:95`,
`ForwardCirPropertyProjection.kt:139`, and `ForwardKotlinPlanEmitter.kt:359` / `:487` all look up
`"handle"` / `"value"` / `"receiver"` / `"errorOut"` / `"valueOut"` by name. A user constructor
parameter literally named `handle` collides outright with the generated `IntPtr handle` local in the
constructor wrapper; a user parameter named one of the other four is at risk of being read as the
generator's own slot and dropped from the `[DllImport]` rather than treated as ordinary user data.
Unlike issue [#65](https://github.com/xxfast/kotlin-native-nuget/issues/65)'s keyword-escaping gap,
`@`-escaping would not fix this family at all: `handle`/`value`/`receiver`/`errorOut`/`valueOut` are
not C# keywords, so nothing before this class of collision would ever flag them. Source-reading only,
not verified by a fixture; the #65 fixture deliberately excluded these five names to avoid conflating
the two defect classes. `error` is the member of this family already tracked and being fixed
separately as issue [#66](https://github.com/xxfast/kotlin-native-nuget/issues/66); do not duplicate
it here. Found alongside issue [#65](https://github.com/xxfast/kotlin-native-nuget/issues/65).
