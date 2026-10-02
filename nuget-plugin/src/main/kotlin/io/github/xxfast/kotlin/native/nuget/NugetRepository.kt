package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * A named NuGet feed `publishNuget` pushes to. Unset credentials resolve from the Gradle
 * properties `<name>ApiKey` / `<name>Username` / `<name>Password` when the task runs.
 */
@NugetDsl
public abstract class NugetRepository @Inject constructor(private val name: String) : Named {
  override fun getName(): String = name

  public abstract val url: Property<String>
  public abstract val apiKey: Property<String>
  public abstract val username: Property<String>
  public abstract val password: Property<String>

  // ADR-165: a 409 (version already published) warns instead of failing; `--skipDuplicate` also
  // sets it.
  public abstract val skipDuplicate: Property<Boolean>

  init {
    skipDuplicate.convention(false)
  }

  // The Gradle properties an unset credential resolves from (`-P`, gradle.properties,
  // `ORG_GRADLE_PROJECT_*`).
  public val apiKeyProperty: String get() = "${name}ApiKey"
  public val usernameProperty: String get() = "${name}Username"
  public val passwordProperty: String get() = "${name}Password"
}

@NugetDsl
public class NugetRepositoriesScope internal constructor(
  private val repositories: NamedDomainObjectContainer<NugetRepository>,
) {
  /** Declares the feed [name], or configures it again: a second call for the same name merges. */
  public fun nuget(name: String, action: Action<in NugetRepository>) {
    action.execute(repositories.maybeCreate(name))
  }
}
