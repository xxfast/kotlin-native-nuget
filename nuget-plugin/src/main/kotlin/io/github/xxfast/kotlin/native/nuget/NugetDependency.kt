package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

@NugetDsl
public abstract class NugetDependency @Inject constructor(
  private val name: String,
  objects: ObjectFactory,
) : Named {
  override fun getName(): String = name

  /** The NuGet package id; the container key. */
  public val id: String get() = name

  public abstract val version: Property<String>
  public abstract val source: Property<String>

  public val bind: NugetBindConfig = objects.newInstance(NugetBindConfig::class.java)

  // ADR-180: `bind {}` is a declaration, not only configuration: a dependency with an empty
  // `bind { }` is bound, one without the block is resolve-only.
  internal var bound: Boolean = false
    private set

  private val bindHooks: MutableList<() -> Unit> = mutableListOf()

  public fun bind(action: Action<in NugetBindConfig>) {
    action.execute(bind)
    if (bound) return
    bound = true
    bindHooks.forEach { hook -> hook() }
  }

  /** Runs [hook] once, on the first `bind {}` call, or now if that already happened. */
  internal fun whenBound(hook: () -> Unit) {
    if (bound) hook() else bindHooks.add(hook)
  }
}
