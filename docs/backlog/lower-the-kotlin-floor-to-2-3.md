# Lower the Kotlin floor from 2.4.0 to 2.3

Deferred at the [ADR-195](../adr/195-kotlin-version-range.md) gate (2026-10-04).

The floor is 2.4.0 because `nuget-runtime` and `nuget-annotations` are built at klib ABI 2.4. Lowering it needs:

- The two klibs built at the 2.3 ABI with `-language-version 2.3 -XXLanguage:+ExportKlibToOlderAbiVersion`. The flag is unstable and supports one version back only. Inferred from KT-85359, not spiked.
- The `kotlin.uuid.Uuid` opt-in sorted for Kotlin 2.3 ([ADR-106](../adr/106-uuid-mapping.md)); generated code currently has no `@OptIn`.
- An answer on whether Gradle 9.1 is a real floor. The published module metadata carries `org.gradle.jvm.version: 17` and no `org.gradle.plugin.api-version`, so nothing in it rejects an older Gradle; whether the plugin runs on Gradle 8 is untested.

Fallback: per-Kotlin-version runtime artifacts selected by the plugin (the SKIE model), a larger publish matrix.
