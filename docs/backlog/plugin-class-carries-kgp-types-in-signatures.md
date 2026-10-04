# `NugetPlugin` fails to apply when Kotlin Gradle plugin is only in a child project

Discovered alongside [ADR-195](../adr/195-kotlin-version-range.md), which made KGP `compileOnly`.

Symptom, verified by execution: with this plugin declared `apply false` in the root project and `kotlin("multiplatform")` only in a child project, the build fails with `Could not create plugin of type 'NugetPlugin' > Could not generate a decorated class for type NugetPlugin > org/jetbrains/kotlin/gradle/dsl/KotlinMultiplatformExtension`. It fails before `apply()` runs, so the Kotlin version check cannot explain it.

Cause, verified: Gradle's `AbstractClassGenerator.inspectType` reflects every declared method of `NugetPlugin`, and `private fun registerPublish(` (`NugetPlugin.kt`, near line 567) plus its local funs and lambdas carry KGP types in their signatures. The root plugin's classloader cannot see KGP, which only the child project loads.

Fix: move roughly 165 lines out of the plugin class into a separate file, so `NugetPlugin` itself mentions no KGP type in a declared member. Workaround, verified: declare both plugins `apply false` in the root project. The workaround is documented in `docs/topics/prerequisites.md`.

Coverage gap: no test applies the plugin from a parent scope with KGP only in a child.
