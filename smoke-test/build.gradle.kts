plugins {
  kotlin("multiplatform") version "2.4.0"
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
