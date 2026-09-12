import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.SharedLibrary

plugins {
  kotlin("multiplatform") version "2.4.10"
  id("io.github.xxfast.kotlin.native.nuget")
}

kotlin {
  macosArm64 {
    binaries {
      sharedLib { baseName = "smoke" }
    }
  }
}

nuget {
  publish {
    packageId = "SmokeTest"
    version = "1.0.0"
    authors = "xxfast"
    description = "Fixture that consumes the plugin by coordinate"
    rootPackage = "io.github.xxfast.smoke"
  }
}

// The assertion. Resolving the KSP processor classpath forces `NugetPlugin`'s maven-coordinate
// fallback to fetch `nuget-processor` from the local repo. A `dependencies` report would print
// FAILED and still exit 0, so resolve the files and check for the jar directly.
//
// `kspMacosArm64` is a declarable bucket (canBeResolved=false); the resolvable configuration KSP
// actually feeds the compiler is `kspKotlinMacosArm64ProcessorClasspath`.
val verifyProcessorResolvesByCoordinate by tasks.registering {
  group = "verification"
  description = "Fails unless nuget-processor resolves from a maven coordinate, as a real consumer would"

  val processorJars: Provider<Set<File>> =
    configurations.named("kspKotlinMacosArm64ProcessorClasspath").map { configuration ->
      configuration.files.filter { it.name.startsWith("nuget-processor") }.toSet()
    }

  doLast {
    val jars: Set<File> = processorJars.get()
    check(jars.isNotEmpty()) {
      "nuget-processor did not resolve from a maven coordinate. The fallback at NugetPlugin.kt is broken."
    }
    logger.lifecycle("Resolved nuget-processor by coordinate: ${jars.joinToString { it.name }}")
  }
}

// ADR-127: the same fallback, one artifact over. The runtime is the half the ADR could not spike
// by coordinate (the spike used `project(":runtime")`), and the claim it rests on is that
// `export()` of a published coordinate behaves like `export()` of a project. Resolving the shared
// library's own export configuration is what proves it: a klib in there is a klib the linker will
// pull the `nuget_*` symbols from.
val verifyRuntimeResolvesByCoordinate by tasks.registering {
  group = "verification"
  description = "Fails unless nuget-runtime resolves by coordinate and is exported from " +
    "the sharedLib"

  val exportedFiles: Provider<Set<File>> = providers.provider {
    val target = kotlin.targets.getByName("macosArm64") as KotlinNativeTarget
    val lib = target.binaries.filterIsInstance<SharedLibrary>().first()
    configurations.getByName(lib.exportConfigurationName).files.toSet()
  }

  doLast {
    val exported: Set<File> = exportedFiles.get()
    val runtime: List<File> = exported.filter { it.name.startsWith("nuget-runtime") }
    check(runtime.isNotEmpty()) {
      "nuget-runtime did not resolve into the sharedLib export configuration. " +
        "The ADR-127 wiring at NugetPlugin.kt is broken, or export() of a published coordinate " +
        "does not behave like export(project(...)). Resolved: ${exported.joinToString { it.name }}"
    }
    logger.lifecycle("Resolved nuget-runtime by coordinate: ${runtime.joinToString { it.name }}")
  }
}
