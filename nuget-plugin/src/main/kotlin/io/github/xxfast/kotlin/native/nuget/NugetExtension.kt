package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

/**
 * ADR-180: one `publish` instance and one dependency container, both created by Gradle. A
 * repeated `publish {}` or `dependency("X")` configures the same instance, so blocks merge.
 * Tasks that exist only when a block is declared are registered by the first call to that block,
 * through the internal hooks below, instead of in `afterEvaluate`.
 */
@NugetDsl
abstract class NugetExtension @Inject constructor(objects: ObjectFactory) {
  val publish: NugetPublishConfig = objects.newInstance(NugetPublishConfig::class.java)

  // Keyed by package id. Iterates in id order, not declaration order.
  val dependencies: NamedDomainObjectContainer<NugetDependency> =
    objects.domainObjectContainer(NugetDependency::class.java)

  internal var publishDeclared: Boolean = false
    private set

  private val publishHooks: MutableList<() -> Unit> = mutableListOf()

  fun publish(action: Action<in NugetPublishConfig>) {
    action.execute(publish)
    if (publishDeclared) return
    publishDeclared = true
    publishHooks.forEach { hook -> hook() }
  }

  // An explicit function, not only the container: without it `dependencies { }` inside `nuget {}`
  // resolves to `Project.dependencies` (verified for `repositories` in ADR-180's spike).
  fun dependencies(action: Action<in NugetDependencyScope>) {
    action.execute(NugetDependencyScope(dependencies))
  }

  /** Runs [hook] once, on the first `publish {}` call, or now if that already happened. */
  internal fun whenPublishDeclared(hook: () -> Unit) {
    if (publishDeclared) hook() else publishHooks.add(hook)
  }
}

@NugetDsl
class NugetDependencyScope internal constructor(
  private val dependencies: NamedDomainObjectContainer<NugetDependency>,
) {
  /** Declares [id], or configures it again: a second call for the same id merges. */
  @JvmOverloads
  fun dependency(
    id: String,
    version: String? = null,
    action: Action<in NugetDependency> = Action { },
  ) {
    val dependency: NugetDependency = dependencies.maybeCreate(id)
    if (version != null) dependency.version.set(version)
    action.execute(dependency)
  }
}
