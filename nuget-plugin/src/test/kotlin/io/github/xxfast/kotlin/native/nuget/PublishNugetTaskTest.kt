package io.github.xxfast.kotlin.native.nuget

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PublishNugetTaskTest {
  private lateinit var server: HttpServer
  private val keys = mutableListOf<String?>()
  private val methods = mutableListOf<String>()
  private var status = 201

  @BeforeTest
  fun start() {
    server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
    server.createContext("/v3/index.json") { exchange ->
      val port: Int = server.address.port
      val index = """
        {
          "version": "3.0.0",
          "resources": [
            { "@id": "http://localhost:$port/api/v2/package", "@type": "PackagePublish/2.0.0" }
          ]
        }
      """.trimIndent()
      exchange.respond(200, index)
    }
    server.createContext("/api/v2/package") { exchange ->
      methods.add(exchange.requestMethod)
      keys.add(exchange.requestHeaders.getFirst("X-NuGet-ApiKey"))
      exchange.requestBody.readBytes()
      exchange.respond(status, "")
    }
    server.start()
  }

  @AfterTest
  fun stop() {
    server.stop(0)
  }

  private fun HttpExchange.respond(code: Int, body: String) {
    val bytes: ByteArray = body.toByteArray()
    sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    if (bytes.isNotEmpty()) responseBody.write(bytes)
    close()
  }

  private fun task(skipDuplicate: Boolean = false): PublishNugetTask {
    val project: Project = ProjectBuilder.builder().build()
    val file = File(project.layout.buildDirectory.get().asFile, "nuget/TestLibrary.1.0.0.nupkg")
    file.parentFile.mkdirs()
    file.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))

    val task: PublishNugetTask = project.tasks
      .register("publishNugetToFakeRepository", PublishNugetTask::class.java)
      .get()
    task.packageFile.set(file)
    task.repositoryName.set("fake")
    task.repositoryUrl.set("http://localhost:${server.address.port}/v3/index.json")
    task.apiKey.set("secret-key")
    task.skipDuplicate.set(skipDuplicate)
    return task
  }

  @Test
  fun `publish pushes the package with the api key`() {
    task().publish()

    assertEquals(listOf("PUT"), methods)
    assertEquals(listOf<String?>("secret-key"), keys)
  }

  @Test
  fun `publish completes on 409 with skipDuplicate`() {
    status = 409

    task(skipDuplicate = true).publish()

    assertEquals(listOf("PUT"), methods)
  }
}
