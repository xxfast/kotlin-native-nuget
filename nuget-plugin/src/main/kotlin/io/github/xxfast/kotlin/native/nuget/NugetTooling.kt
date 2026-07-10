package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.GradleException
import java.io.File

internal fun findExecutable(name: String, searchPath: String? = System.getenv("PATH")): String? {
  val paths: String = searchPath ?: return null
  val extensions: List<String> = listOf("", ".exe", ".cmd", ".bat")
  return paths.split(File.pathSeparator)
    .flatMap { dir -> extensions.map { ext -> File(dir, "$name$ext") } }
    .firstOrNull { it.canExecute() }
    ?.absolutePath
}

internal fun requireDotnet(purpose: String, searchPath: String? = System.getenv("PATH")): String {
  val dotnet: String? = findExecutable("dotnet", searchPath)
  if (dotnet == null) {
    throw GradleException(
      "[nuget] dotnet is required to $purpose but was not found on PATH. " +
        "Install the .NET SDK 8.0 or later from https://dot.net/download, then re-run."
    )
  }

  return dotnet
}
