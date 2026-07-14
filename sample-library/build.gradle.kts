import io.github.xxfast.kotlin.native.nuget.NugetGenTask
import io.github.xxfast.kotlin.native.nuget.PackNugetTask
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.time.Instant
import javax.inject.Inject

@DisableCachingByDefault(because = "every fixture build requires a new version")
abstract class WriteFixtureVersion : DefaultTask() {
  @get:OutputFile
  abstract val outputFile: RegularFileProperty

  init {
    outputs.upToDateWhen { false }
  }

  @TaskAction
  fun write() {
    outputFile.get().asFile.writeText("1.0.0-fixture.${Instant.now().toEpochMilli()}\n")
  }
}

@DisableCachingByDefault(because = "the props file is generated for the current fixture version")
abstract class WriteFixtureVersions : DefaultTask() {
  @get:Input
  abstract val fixtureVersion: Property<String>

  @get:OutputFile
  abstract val outputFile: RegularFileProperty

  @TaskAction
  fun write() {
    val file = outputFile.get().asFile
    file.parentFile.mkdirs()
    file.writeText(
      """
      |<Project>
      |  <PropertyGroup>
      |    <SampleLibraryVersion>${fixtureVersion.get()}</SampleLibraryVersion>
      |  </PropertyGroup>
      |</Project>
      """.trimMargin() + "\n"
    )
  }
}

@DisableCachingByDefault(because = "the fixture package is consumed from the local NuGet feed")
abstract class PackSampleDependency @Inject constructor(
  private val execOperations: ExecOperations,
) : DefaultTask() {
  @get:InputFile
  abstract val csprojFile: RegularFileProperty

  @get:Input
  abstract val fixtureVersion: Property<String>

  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  @TaskAction
  fun pack() {
    val outDir = outputDir.get().asFile
    outDir.mkdirs()
    execOperations.exec {
      commandLine(
        "dotnet", "pack",
        csprojFile.get().asFile.absolutePath,
        "-c", "Release",
        "-o", outDir.absolutePath,
        "-p:PackageVersion=${fixtureVersion.get()}",
      )
    }
  }
}

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  id("io.github.xxfast.kotlin.native.nuget")
}

kotlin {
  mingwX64 {
    binaries {
      sharedLib {
        baseName = "sample"
      }
    }
  }

  macosArm64 {
    binaries {
      sharedLib {
        baseName = "sample"
      }
    }
  }

  sourceSets {
    nativeMain.dependencies {
      implementation(libs.kotlinx.coroutines.core)
    }
    nativeTest.dependencies {
      implementation(libs.kotlin.test)
    }
  }
}

val fixtureVersionFile: Provider<RegularFile> = layout.buildDirectory.file("fixture-version.txt")
val fixturePackageVersion: Provider<String> = providers.fileContents(fixtureVersionFile)
  .asText
  .map { it.trim() }
val fixtureVersionsFile = rootProject.file("sample-app/build/FixtureVersions.props")

val writeFixtureVersion by tasks.registering(WriteFixtureVersion::class) {
  group = "nuget"
  description = "Creates a unique version shared by the local NuGet fixtures"
  outputFile.set(fixtureVersionFile)
}

val writeFixtureVersions by tasks.registering(WriteFixtureVersions::class) {
  group = "nuget"
  description = "Writes the current fixture package version for sample-app consumers"
  fixtureVersion.set(fixturePackageVersion)
  outputFile.set(fixtureVersionsFile)
  dependsOn(writeFixtureVersion)
}

// Pack the local SampleDependency fixture into build/nuget before dotnet restore runs.
// This ensures the .nupkg is present in the local NuGet feed (declared in nuget.config) even
// after :clean wipes build/. The task is declared before afterEvaluate so the task provider is
// available when nugetRestore is wired up below.
val packSampleDependency by tasks.registering(PackSampleDependency::class) {
  group = "nuget"
  description = "Packs SampleDependency.nupkg into build/nuget so dotnet restore can find it"
  csprojFile.set(rootProject.file("sample-dependency/SampleDependency.csproj"))
  fixtureVersion.set(fixturePackageVersion)
  outputDir.set(layout.buildDirectory.dir("nuget"))
  dependsOn(writeFixtureVersion)
}

// Wire packSampleDependency before nugetRestore so the local feed is populated first.
// nugetRestore is registered in afterEvaluate by the nuget plugin, so the wiring must also be
// in afterEvaluate (registered second, runs after the plugin's afterEvaluate).
afterEvaluate {
  tasks.matching { it.name == "nugetRestore" }.configureEach { dependsOn(packSampleDependency) }

  val nugetGen = tasks.named("nugetGen", NugetGenTask::class.java).get()
  nugetGen.dependsOn(writeFixtureVersion)
  nugetGen.dependencyVersions.set(
    fixturePackageVersion.map { version ->
      mapOf(
        "MimeMapping" to "4.0.0",
        "SampleDependency" to version,
      )
    }
  )

  val packNuget = tasks.named("packNuget", PackNugetTask::class.java).get()
  packNuget.packageVersion.set(fixturePackageVersion)
  packNuget.dependsOn(writeFixtureVersions)
}

nuget {
  publish {
    packageId = "SampleLibrary"
    version = "1.0.0"
    authors = "xxfast"
    description = "A sample Kotlin/Native library packaged as NuGet"
    rootPackage = "io.github.xxfast.kotlin.native.nuget.sample"
  }

  dependencies {
    dependency("MimeMapping", version = "4.0.0") {
      bind {
        packageName = "mimemapping"
        include("MimeMapping")
      }
    }
    dependency("SampleDependency", version = "1.0.0") {
      bind {
        include("Sample.Text")
        alias("Sample.Text", "sample.text")
        include("Sample.Enums")
        alias("Sample.Enums", "sample.enums")
        include("Sample.Nullability")
        alias("Sample.Nullability", "sample.nullability")
        include("Sample.Household")
        alias("Sample.Household", "sample.household")
        include("Sample.Overloads")
        alias("Sample.Overloads", "sample.overloads")
        include("Sample.Structs")
        alias("Sample.Structs", "sample.structs")
        include("Sample.Nested")
        alias("Sample.Nested", "sample.nested")
      }
    }
  }
}
