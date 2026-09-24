package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.provider.Provider

/**
 * A named NuGet feed `publishNuget` pushes to. Unset credentials resolve from the Gradle
 * properties `<name>ApiKey` / `<name>Username` / `<name>Password` when the task runs.
 */
class NugetRepository(val name: String) {
  var url: String? = null
  var apiKey: Provider<String>? = null
  var username: Provider<String>? = null
  var password: Provider<String>? = null

  // ADR-165: a 409 (version already published) warns instead of failing; `--skipDuplicate` also
  // sets it.
  var skipDuplicate: Boolean = false

  // The Gradle properties an unset credential resolves from (`-P`, gradle.properties,
  // `ORG_GRADLE_PROJECT_*`).
  val apiKeyProperty: String get() = "${name}ApiKey"
  val usernameProperty: String get() = "${name}Username"
  val passwordProperty: String get() = "${name}Password"
}

class NugetRepositoriesScope(private val repositories: MutableList<NugetRepository>) {
  fun nuget(name: String, configure: NugetRepository.() -> Unit) {
    val repository = NugetRepository(name)
    repository.configure()
    repositories.add(repository)
  }
}
