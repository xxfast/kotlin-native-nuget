package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Tier1SuspendFlowTest {
  private val result by lazy {
    Tier1Harness.run("""
      package tier1.suspendflow
      import kotlinx.coroutines.flow.*
      enum class Mood { NAPPING, HUNGRY }
      @JvmInline value class Tag(val label: String)
      interface Menu { suspend fun specials(): Flow<String> }
      class Box<T>(val item: T) : Menu { override suspend fun specials(): Flow<String> = flowOf("tuna") }
      fun menu(): Menu = Box(17)
      open class Owner { suspend fun acquired(): Flow<Int> = flowOf(17) }
      class Inherited : Owner() { suspend fun labels(): Flow<String?> = flowOf(null) }
      open class Quiet
      class First : Quiet() { suspend fun first(): Flow<Int> = flowOf(29) }
      abstract class Abstract { abstract suspend fun abstractFlow(): Flow<Int> }
      sealed class Nap {
        suspend fun dreams(): Flow<Int> = flowOf(17)
        class Loaf : Nap() { suspend fun purrs(): Flow<Int> = flowOf(29) }
        object Curl : Nap() { suspend fun curls(): Flow<Int> = flowOf(41) }
      }
      class Source {
        suspend fun hostile(flowHandle: Int, collectScope: Int, flowOnNext: Int, flowOnComplete: Int, flowOnError: Int, flowUserData: Int): Flow<Int> = flowOf(flowHandle)
        suspend fun hostileCollection(h: Int, h1: Int): Flow<List<List<Mood>>> = flowOf(listOf(listOf(Mood.NAPPING)))
        suspend fun moods(): Flow<List<Mood>> = flowOf(listOf(Mood.NAPPING))
        suspend fun tags(): Flow<Set<Tag>> = flowOf(setOf(Tag("Oreo")))
        suspend fun bytes(): Flow<ByteArray?> = flowOf(null)
        suspend fun nested(): Flow<List<List<Int>>> = flowOf(listOf(listOf(17)))
        suspend fun nullableContainer(): Flow<Int>? = null
        suspend fun nullableCollection(): Flow<List<Int>?> = flowOf(null)
        suspend fun unsupported(): Flow<Pair<Int, Int>> = flowOf(1 to 2)
        suspend fun shared(): SharedFlow<Int> = MutableSharedFlow()
        suspend fun stateNullable(): StateFlow<String?> = MutableStateFlow(null)
        suspend fun stateCollection(): StateFlow<List<Int>> = MutableStateFlow(emptyList())
        suspend fun input(flow: Flow<Int>): Int = 17
      }
      class Generic<T> { suspend fun own(): Flow<Int> = flowOf(17) }
      object Standalone { suspend fun ownObject(): Flow<Int> = flowOf(17) }
      suspend fun String.extensionFlow(): Flow<Int> = flowOf(length)
      suspend fun top(): Flow<Int> = flowOf(17)
      suspend fun top(seed: Int): Flow<Int> = flowOf(seed)
    """.trimIndent(), libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))
  }

  @Test fun `generated acquired collectors compile with every routed owner`() {
    assertTrue(result.compiledClean, result.compileErrors.toString())
    val cs = result.generatedCSharp
    listOf("AcquiredAsync", "LabelsAsync", "FirstAsync", "AbstractFlowAsync", "DreamsAsync", "PurrsAsync", "CurlsAsync", "SpecialsAsync", "TopAsync").forEach {
      assertTrue(it in cs, "$it absent from $cs")
    }
    assertTrue("MenuNative.Native_SpecialsAsyncCollect" in cs, cs)
    assertTrue("Task<KotlinFlow<int>>" in cs, cs)
    assertTrue("public class KotlinFlow<T> : IAsyncEnumerable<T>, IDisposable" in cs, cs)
    assertTrue("flowHandle.asStableRef<Flow<List<Mood>>>()" in result.generated, result.generated)
    assertTrue("flowHandle.asStableRef<Flow<Set<Tag>>>()" in result.generated, result.generated)
  }

  @Test fun `acquired collection items use the ordinary conversion and nested spelling`() {
    val kt = result.generated
    val cs = result.generatedCSharp
    assertTrue(".ordinal" in kt, kt)
    assertTrue(".label" in kt, kt)
    assertTrue("NugetMarshal.ReadList" in cs, cs)
    assertTrue("NugetMarshal.ReadSet" in cs, cs)
    assertTrue("KotlinFlow<byte[]?>" in cs, cs)
    assertTrue("KotlinFlow<IReadOnlyList<IReadOnlyList<int>>>" in cs, cs)
  }

  @Test fun `collection captures original scope and avoids acquisition name collisions`() {
    val cs = result.generatedCSharp
    val hostile = cs.substringAfter(" HostileAsync(").substringBefore("private static extern")
    assertTrue("var flowHandle_ = new NugetKotlinHandle(resultPtr)" in hostile, hostile)
    assertTrue("NugetKotlinHandle collectScope_ = GetOrCreateScope()" in hostile, hostile)
    assertTrue("(flowOnNext_, flowOnComplete_, flowOnError_, flowUserData_)" in hostile, hostile)
    assertTrue(hostile.indexOf("collectScope_ = GetOrCreateScope()") < hostile.indexOf("GCHandle.Alloc"), hostile)
    assertTrue("read: static h_ =>" in cs, cs)
    assertTrue("h1_ =>" in cs, cs)
  }

  @Test fun `refused combinations name their refusal and emit neither half`() {
    val refused = listOf("nullableContainer", "nullableCollection", "unsupported", "shared", "stateNullable", "stateCollection", "input", "extensionFlow")
    refused.forEach { name ->
      assertTrue(result.kspWarnings.any { name in it }, "missing warning for $name: ${result.kspWarnings}")
      assertFalse("${name}_collect" in result.generated, result.generated)
      assertFalse("${name.replaceFirstChar { it.uppercase() }}Async(" in result.generatedCSharp, result.generatedCSharp)
    }
    assertFalse("Generic_own_collect" in result.generated, result.generated)
  }

  @Test fun `a suspend member on an object is absent and named exactly once`() {
    val declarations: List<String> = result.generatedCSharp.lines()
      .filterNot { line -> line.trimStart().startsWith("//") }
    assertTrue(
      declarations.none { line -> Regex("\\bOwnObject(Async)?\\s*\\(").containsMatchIn(line) },
      result.generatedCSharp,
    )
    listOf("tier1.suspendflow.Standalone.ownObject", "tier1.suspendflow.Generic.own")
      .forEach { member ->
        val named: List<String> = result.kspWarnings
          .filter { warning -> "Skipping $member:" in warning }
        assertEquals(1, named.size, "$member must be named once; kspWarnings=${result.kspWarnings}")
      }
  }
}
