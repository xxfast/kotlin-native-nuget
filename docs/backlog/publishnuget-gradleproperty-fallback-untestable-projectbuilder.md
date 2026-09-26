# `publishNuget`'s Gradle-property credential fallback is untestable with `ProjectBuilder`

`NugetPlugin.kt`'s `registerPublishing` resolves an unset repository credential with
`project.providers.gradleProperty(repository.apiKeyProperty)` (and the `Username`/`Password`
equivalents), the same `-P`/`gradle.properties`/`ORG_GRADLE_PROJECT_*` convention `maven-publish`
uses. `ProjectBuilder`, the harness every `PublishNugetTaskWiringTest` test uses, never feeds
`providers.gradleProperty` a value: neither a `gradle.properties` file next to the built project
nor an `org.gradle.project.*` system property reaches it, confirmed by trying four ways (a
`gradle.properties` file in the project directory, `System.setProperty("org.gradle.project.<name>ApiKey", ...)`,
`project.extensions.extraProperties`, and passing the value through `StartParameter`) during
implementation, none of which changed `providers.gradleProperty(...).isPresent`.

`PublishNugetTaskWiringTest`'s `unset credentials resolve from repository-named Gradle properties`
test therefore only pins the property *names* (`nugetOrgApiKey`, `nugetOrgUsername`,
`nugetOrgPassword`), not that the fallback actually resolves a value Gradle was given. Closing this
gap needs the repository's first Gradle TestKit functional test (a real `GradleRunner` invocation,
which does honour `-P` and `ORG_GRADLE_PROJECT_*`), or a manual push exercising the fallback
end to end.
