package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

abstract class PublishNugetTask : DefaultTask() {
  @get:InputFile
  abstract val packageFile: RegularFileProperty

  @get:Input
  abstract val repositoryName: Property<String>

  @get:Input
  abstract val repositoryUrl: Property<String>

  @get:Internal
  abstract val apiKey: Property<String>

  @get:Internal
  abstract val username: Property<String>

  @get:Internal
  abstract val password: Property<String>

  @get:Input
  @get:Optional
  @get:Option(
    option = "dryRun",
    description = "Resolves the feed and validates the package without pushing it",
  )
  abstract val dryRun: Property<Boolean>

  @get:Input
  @get:Optional
  @get:Option(
    option = "skipDuplicate",
    description = "Treats an already-published version (HTTP 409) as a warning",
  )
  abstract val skipDuplicate: Property<Boolean>

  init {
    // A push is a side effect on a remote feed, never up to date.
    outputs.upToDateWhen { false }
  }

  @TaskAction
  fun publish() {
    val name: String = repositoryName.get()
    val key: String = requireNotNull(apiKey.orNull) {
      "No API key for NuGet repository '$name'. Set `apiKey` in its nuget(\"$name\") { } block, " +
          "or the Gradle property `${name}ApiKey` " +
          "(-P${name}ApiKey=..., or ORG_GRADLE_PROJECT_${name}ApiKey)"
    }

    val request = NugetPushRequest(
      serviceIndex = repositoryUrl.get(),
      packageFile = packageFile.get().asFile,
      apiKey = key,
      username = username.orNull,
      password = password.orNull,
      dryRun = dryRun.getOrElse(false),
      skipDuplicate = skipDuplicate.getOrElse(false),
    )

    val result: NugetPushResult = NugetPusher().push(request)
    val file: String = request.packageFile.name
    val url: String = request.serviceIndex
    if (result == NugetPushResult.DRY_RUN) {
      logger.lifecycle("[nuget] Dry run: would push $file to '$name' ($url)")
    }
    if (result == NugetPushResult.DUPLICATE_SKIPPED) {
      logger.warn("w: [nuget] $file already exists on '$name' ($url); skipped")
    }
    if (result == NugetPushResult.PUSHED) {
      logger.lifecycle("[nuget] Pushed $file to '$name' ($url)")
    }
  }
}
