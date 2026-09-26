package io.github.xxfast.kotlin.native.nuget

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.gradle.api.GradleException
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A captured request to the fake publish endpoint. */
private class Push(
  val method: String,
  val headers: Map<String, String>,
  val body: ByteArray,
)

class NugetPusherTest {
  private lateinit var server: HttpServer
  private val pushes = mutableListOf<Push>()
  private var indexHits = 0
  private var status = 201
  private var responseBody = ""

  // Bytes outside ASCII and a CRLF, so a text-mangling multipart writer shows up.
  private val nupkg =
    byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x0D, 0x0A, 0xFF.toByte(), 0x00, 0x80.toByte())

  private val serviceIndex: String get() = "http://localhost:${server.address.port}/v3/index.json"

  @BeforeTest
  fun start() {
    server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
    server.createContext("/v3/index.json") { exchange ->
      indexHits++
      val port: Int = server.address.port
      val index = """
        {
          "version": "3.0.0",
          "resources": [
            { "@id": "http://localhost:$port/v3/search", "@type": "SearchQueryService" },
            { "@id": "http://localhost:$port/api/v2/package", "@type": "PackagePublish/2.0.0" }
          ]
        }
      """.trimIndent()
      exchange.respond(200, index)
    }
    server.createContext("/api/v2/package") { exchange ->
      val headers: Map<String, String> = exchange.requestHeaders
        .mapKeys { it.key.lowercase() }
        .mapValues { it.value.first() }
      pushes.add(Push(exchange.requestMethod, headers, exchange.requestBody.readBytes()))
      exchange.respond(status, responseBody)
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

  private fun packageFile(): File {
    val dir: File = Files.createTempDirectory("nupkg").toFile()
    val file = File(dir, "TestLibrary.1.0.0.nupkg")
    file.writeBytes(nupkg)
    return file
  }

  private fun request(
    file: File = packageFile(),
    username: String? = null,
    password: String? = null,
    dryRun: Boolean = false,
    skipDuplicate: Boolean = false,
  ) = NugetPushRequest(
    serviceIndex = serviceIndex,
    packageFile = file,
    apiKey = "secret-key",
    username = username,
    password = password,
    dryRun = dryRun,
    skipDuplicate = skipDuplicate,
  )

  /** The first multipart part: its headers block and its raw content bytes. */
  private fun firstPart(push: Push): Pair<String, ByteArray> {
    val contentType: String = assertNotNull(push.headers["content-type"])
    assertTrue(contentType.startsWith("multipart/form-data"), "content type was $contentType")
    val boundary: String = contentType.substringAfter("boundary=").trim('"')

    // ISO-8859-1 maps every byte to one char, so indices line up with the raw body.
    val body = String(push.body, Charsets.ISO_8859_1)
    val start: Int = body.indexOf("--$boundary\r\n") + "--$boundary\r\n".length
    val headersEnd: Int = body.indexOf("\r\n\r\n", start)
    val end: Int = body.indexOf("\r\n--$boundary", headersEnd)
    return body.substring(start, headersEnd) to push.body.copyOfRange(headersEnd + 4, end)
  }

  @Test
  fun `pushes with PUT and the NuGet headers`() {
    val result: NugetPushResult = NugetPusher().push(request())

    assertEquals(NugetPushResult.PUSHED, result)
    val push: Push = pushes.single()
    assertEquals("PUT", push.method)
    assertEquals("secret-key", push.headers["x-nuget-apikey"])
    assertEquals("4.1.0", push.headers["x-nuget-protocol-version"])
    assertNull(push.headers["authorization"])
  }

  @Test
  fun `first multipart part carries the raw nupkg bytes`() {
    NugetPusher().push(request())

    val (headers: String, content: ByteArray) = firstPart(pushes.single())
    assertTrue(headers.contains("name=\"package\""), "part headers were: $headers")
    assertTrue(
      headers.contains("filename=\"TestLibrary.1.0.0.nupkg\""),
      "part headers were: $headers",
    )
    assertContentEquals(nupkg, content)
  }

  @Test
  fun `username and password send basic auth`() {
    NugetPusher().push(request(username = "xxfast", password = "pat"))

    val expected: String = "Basic " + Base64.getEncoder().encodeToString("xxfast:pat".toByteArray())
    assertEquals(expected, pushes.single().headers["authorization"])
  }

  @Test
  fun `202 accepted succeeds`() {
    status = 202

    assertEquals(NugetPushResult.PUSHED, NugetPusher().push(request()))
  }

  @Test
  fun `400 fails with the response body in the message`() {
    status = 400
    responseBody = "The package manifest is missing the id element."

    val error: GradleException = assertFailsWith { NugetPusher().push(request()) }
    assertTrue(
      error.message.orEmpty().contains("The package manifest is missing the id element."),
      "message was: ${error.message}",
    )
  }

  @Test
  fun `409 fails without skipDuplicate`() {
    status = 409
    responseBody = "A package with ID 'TestLibrary' and version '1.0.0' already exists."

    val error: GradleException = assertFailsWith { NugetPusher().push(request()) }
    assertTrue(error.message.orEmpty().contains("409"), "message was: ${error.message}")
  }

  @Test
  fun `409 is skipped with skipDuplicate`() {
    status = 409

    val result: NugetPushResult = NugetPusher().push(request(skipDuplicate = true))
    assertEquals(NugetPushResult.DUPLICATE_SKIPPED, result)
  }

  @Test
  fun `dryRun resolves the index but never hits the publish endpoint`() {
    val result: NugetPushResult = NugetPusher().push(request(dryRun = true))

    assertEquals(NugetPushResult.DRY_RUN, result)
    assertEquals(1, indexHits)
    assertTrue(pushes.isEmpty(), "dryRun must not PUT")
  }

  @Test
  fun `dryRun still fails on a missing package file`() {
    val missing = File(Files.createTempDirectory("nupkg").toFile(), "TestLibrary.1.0.0.nupkg")

    assertFailsWith<GradleException> { NugetPusher().push(request(file = missing, dryRun = true)) }
    assertTrue(pushes.isEmpty())
  }

  @Test
  fun `service index without PackagePublish fails with a clear message`() {
    server.removeContext("/v3/index.json")
    server.createContext("/v3/index.json") { exchange ->
      exchange.respond(200, """{ "version": "3.0.0", "resources": [] }""")
    }

    val error: GradleException = assertFailsWith { NugetPusher().push(request()) }
    assertTrue(
      error.message.orEmpty().contains("PackagePublish/2.0.0"),
      "message was: ${error.message}",
    )
  }
}
