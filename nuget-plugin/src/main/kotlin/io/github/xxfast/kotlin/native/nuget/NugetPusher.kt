package io.github.xxfast.kotlin.native.nuget

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.GradleException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.UUID

private const val PUBLISH_RESOURCE = "PackagePublish/2.0.0"
private const val PROTOCOL_VERSION = "4.1.0"

// ADR-165: `dotnet nuget push`'s default timeout.
private val TIMEOUT: Duration = Duration.ofSeconds(300)

/** One push of a `.nupkg` to a NuGet V3 feed, identified by its service index URL. */
data class NugetPushRequest(
  val serviceIndex: String,
  val packageFile: File,
  val apiKey: String,
  val username: String? = null,
  val password: String? = null,
  val dryRun: Boolean = false,
  val skipDuplicate: Boolean = false,
) {
  // The key never reaches a log line, including through a stray string template.
  override fun toString(): String =
    "NugetPushRequest(serviceIndex=$serviceIndex, packageFile=$packageFile, dryRun=$dryRun, " +
        "skipDuplicate=$skipDuplicate)"
}

enum class NugetPushResult { PUSHED, DUPLICATE_SKIPPED, DRY_RUN }

/**
 * Pushes a package over plain JVM HTTP (ADR-165): GET the service index, pick the
 * `PackagePublish/2.0.0` resource, PUT the package as multipart/form-data. Throws
 * `GradleException` on failure.
 */
class NugetPusher(private val userAgent: String = "kotlin-native-nuget/$PLUGIN_VERSION") {
  private val client: HttpClient = HttpClient.newBuilder()
    .connectTimeout(TIMEOUT)
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

  fun push(request: NugetPushRequest): NugetPushResult {
    val file: File = request.packageFile
    if (!file.isFile) {
      throw GradleException("NuGet package not found at ${file.absolutePath}; run packNuget first")
    }

    val endpoint: URI = publishEndpoint(request.serviceIndex)
    if (request.dryRun) return NugetPushResult.DRY_RUN

    val boundary = "nuget-${UUID.randomUUID()}"
    val builder: HttpRequest.Builder = HttpRequest.newBuilder(endpoint)
      .timeout(TIMEOUT)
      .header("Content-Type", "multipart/form-data; boundary=$boundary")
      .header("X-NuGet-ApiKey", request.apiKey)
      .header("X-NuGet-Protocol-Version", PROTOCOL_VERSION)
      .header("User-Agent", userAgent)
      .PUT(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, file)))

    if (request.username != null && request.password != null) {
      val credentials: String = "${request.username}:${request.password}"
      val encoded: String = Base64.getEncoder().encodeToString(credentials.toByteArray())
      builder.header("Authorization", "Basic $encoded")
    }

    val response: HttpResponse<String> = send(builder.build(), endpoint)
    val status: Int = response.statusCode()
    if (status == 201 || status == 202) return NugetPushResult.PUSHED
    if (status == 409 && request.skipDuplicate) return NugetPushResult.DUPLICATE_SKIPPED

    val reason: String =
      if (status == 409) "${file.name} is already published" else "push of ${file.name} failed"
    val body: String = response.body().take(2000)
    throw GradleException("NuGet $reason: HTTP $status from $endpoint: $body")
  }

  private fun publishEndpoint(serviceIndex: String): URI {
    val uri: URI = URI.create(serviceIndex)
    val get: HttpRequest = HttpRequest.newBuilder(uri)
      .timeout(TIMEOUT)
      .header("User-Agent", userAgent)
      .GET()
      .build()

    val response: HttpResponse<String> = send(get, uri)
    if (response.statusCode() != 200) {
      val body: String = response.body().take(2000)
      val status: Int = response.statusCode()
      throw GradleException("NuGet service index $uri returned HTTP $status: $body")
    }

    val index: JsonObject = Json.parseToJsonElement(response.body()).jsonObject
    val resources = index["resources"]?.jsonArray.orEmpty().map { it.jsonObject }
    val publish: JsonObject =
      resources.firstOrNull { it["@type"]?.jsonPrimitive?.content == PUBLISH_RESOURCE }
        ?: throw GradleException(
          "NuGet service index $uri has no $PUBLISH_RESOURCE resource; is it a v3 feed?",
        )

    val id: String = requireNotNull(publish["@id"]?.jsonPrimitive?.content) {
      "NuGet service index $uri lists $PUBLISH_RESOURCE without an @id"
    }
    return URI.create(id)
  }

  private fun send(request: HttpRequest, uri: URI): HttpResponse<String> {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (e: IOException) {
      throw GradleException("NuGet request to $uri failed: ${e.message}", e)
    }
  }

  // Built as bytes: the package is binary, so it must never pass through a String.
  private fun multipart(boundary: String, file: File): ByteArray {
    val out = ByteArrayOutputStream()
    val head: String = "--$boundary\r\n" +
        "Content-Disposition: form-data; name=\"package\"; filename=\"${file.name}\"\r\n" +
        "Content-Type: application/octet-stream\r\n\r\n"
    out.write(head.toByteArray(Charsets.UTF_8))
    out.write(file.readBytes())
    out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
    return out.toByteArray()
  }
}
