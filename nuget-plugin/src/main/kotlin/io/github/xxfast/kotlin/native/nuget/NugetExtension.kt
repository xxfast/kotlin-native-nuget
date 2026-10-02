package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import javax.inject.Inject

/**
 * ADR-180: one `publish` instance and one dependency container, both created by Gradle. A
 * repeated `publish {}` or `dependency("X")` configures the same instance, so blocks merge.
 * Tasks that exist only when a block is declared are registered by the first call to that block,
 * through the internal hooks below, instead of in `afterEvaluate`.
 */
@NugetDsl
abstract class NugetExtension @Inject constructor(objects: ObjectFactory) {
  /**
   * ADR-184: the lowest .NET a C# consumer can use. Names the reverse restore project, the
   * `project.assets.json` target read for bound DLLs, the pre-pack compile check, and the packed
   * `contentFiles/cs/<tfm>/` + `lib/<tfm>/_._` layout, so a lower consumer gets NU1202. Exactly
   * `netX.0` with X >= 10; the plugin reads it through [validatedTargetFramework].
   */
  abstract val targetFramework: Property<String>

  /** [targetFramework], validated lazily: a bad value fails the first task that reads it. */
  internal val validatedTargetFramework: Provider<String>
    get() = targetFramework.map(::validateTargetFramework)

  init {
    targetFramework.convention(DEFAULT_TARGET_FRAMEWORK)
  }

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

/** ADR-184 / ADR-188: net10.0 is the floor because C# 14 is its default language version. */
internal const val DEFAULT_TARGET_FRAMEWORK = "net10.0"

private const val MINIMUM_TARGET_FRAMEWORK_MAJOR = 10

private val TARGET_FRAMEWORK_FORM = Regex("""net(\d+)\.0""")

/**
 * ADR-184: exactly `netX.0`, X >= 10. The exact form keeps the `project.assets.json` target key
 * identical to the csproj value; platform TFMs, netstandard and .NET Framework are out of scope.
 */
internal fun validateTargetFramework(value: String): String {
  val major: Int? = TARGET_FRAMEWORK_FORM.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()
  if (major != null && major >= MINIMUM_TARGET_FRAMEWORK_MAJOR) return value
  throw GradleException(
    "[nuget] targetFramework '$value' is not supported. Use the form netX.0 with " +
      "X >= $MINIMUM_TARGET_FRAMEWORK_MAJOR, for example nuget { targetFramework = " +
      "\"$DEFAULT_TARGET_FRAMEWORK\" }. The generated C# needs C# 14 (ADR-188), the default " +
      "language of net10.0, so net10.0 is the floor; platform TFMs, netstandard and .NET " +
      "Framework are not supported (ADR-184)."
  )
}
