package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-179 rules 2 and 5: a keyword `@CSharpName` renders `@`-escaped on every route that renders a
 * C# member, while every extern stem derived from the member keeps an unescaped identifier. One
 * cell per route. A plain declared name on the legacy Flow, stored-callback and interface-bridge
 * routes is honoured too, and a class Flow property's extern is declared under the name it is
 * called by.
 */
class Tier1CSharpNameKeywordTest {

  private fun run(body: String): Tier1Result {
    val result: Tier1Result = Tier1Harness.run(
      "package tier1.csnamekw\n\n" +
          "import io.github.xxfast.kotlin.native.nuget.annotations.CSharpName\n" +
          "import kotlinx.coroutines.flow.Flow\n" +
          "import kotlinx.coroutines.flow.MutableStateFlow\n" +
          "import kotlinx.coroutines.flow.StateFlow\n" +
          "import kotlinx.coroutines.flow.flowOf\n\n" + body,
      libraries = listOf(csharpNameLibrary, Tier1Classpath.kotlinxCoroutinesCore),
    )
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    val escapedExtern: MatchResult? = Regex("Native_[A-Za-z0-9_]*@").find(result.generatedCSharp)
    assertTrue(
      escapedExtern == null,
      "escaped extern ${escapedExtern?.value}\n${result.generatedCSharp}",
    )
    return result
  }

  @Test
  fun `cell 1 a keyword flow property is escaped and its extern is declared as called`() {
    val cs: String = run(
      """
      class C {
        @CSharpName("event") val updates: Flow<Int> = flowOf(1)
      }
      """.trimIndent(),
    ).generatedCSharp
    assertContains(cs, "KotlinFlow<int> @event")
    assertContains(cs, "extern IntPtr Native_GetUpdatesCollect(")
    assertContains(cs, "Native_GetUpdatesCollect(_handle")
  }

  @Test
  fun `cell 2 a keyword mutable state flow property is escaped`() {
    val cs: String = run(
      """
      class C {
        @CSharpName("event") val state: MutableStateFlow<Int> = MutableStateFlow(1)
      }
      """.trimIndent(),
    ).generatedCSharp
    assertContains(cs, "KotlinMutableStateFlow<int> @event")
    assertContains(cs, "Native_GetStateValue(")
    assertContains(cs, "Native_SetStateValue(")
  }

  @Test
  fun `cell 3 a keyword flow method takes its declared name`() {
    val cs: String = run(
      """
      class C {
        @CSharpName("event") fun watch(n: Int): Flow<Int> = flowOf(n)
      }
      """.trimIndent(),
    ).generatedCSharp
    assertContains(cs, "KotlinFlow<int> @event(int n)")
    assertContains(cs, "Native_WatchCollect")
  }

  @Test
  fun `cell 4 keyword state flow and held mutable state flow methods take their declared names`() {
    val cs: String = run(
      """
      class C {
        @CSharpName("event") fun watch(n: Int): StateFlow<Int> = MutableStateFlow(n)
        @CSharpName("lock") fun hold(n: Int): MutableStateFlow<Int> = MutableStateFlow(n)
      }
      """.trimIndent(),
    ).generatedCSharp
    assertContains(cs, "KotlinStateFlow<int> @event(int n)")
    assertContains(cs, "KotlinMutableStateFlow<int> @lock(int n)")
  }

  @Test
  fun `cell 5 keyword lambda properties are escaped on a class and on a sealed arm`() {
    val cs: String = run(
      """
      class C {
        @CSharpName("event") val onTick: (Int) -> Unit = {}
      }
      sealed class S {
        class A : S() {
          @CSharpName("event") val onTick: (Int) -> Unit = {}
        }
      }
      """.trimIndent(),
    ).generatedCSharp
    assertEquals(2, Regex("KotlinAction<int> @event").findAll(cs).count(), cs)
  }

  @Test
  fun `cell 6 a keyword stored callback add takes its declared name`() {
    val cs: String = run(
      """
      class C {
        @CSharpName("event") fun addListener(listener: (Int) -> Unit) = Unit
        fun removeListener(listener: (Int) -> Unit) = Unit
      }
      """.trimIndent(),
    ).generatedCSharp
    assertContains(cs, "IDisposable @event(")
    assertContains(cs, "Native_AddListener(")
  }

  @Test
  fun `cell 6b a plain declared name on a stored callback add is honoured`() {
    val result: Tier1Result = run(
      """
      class C {
        @CSharpName("Subscribe") fun addListener(listener: (Int) -> Unit) = Unit
        @CSharpName("Unsubscribe") fun removeListener(listener: (Int) -> Unit) = Unit
      }
      """.trimIndent(),
    )
    val cs: String = result.generatedCSharp
    assertContains(cs, "IDisposable Subscribe(")
    assertFalse(Regex("IDisposable AddListener\\(").containsMatchIn(cs), cs)
    // The remove half names no C# member: its annotation is inert, not a diagnostic.
    assertTrue(result.kspWarnings.isEmpty(), "kspWarnings=${result.kspWarnings}")
    assertFalse(Regex("\\bUnsubscribe\\(").containsMatchIn(cs), cs)
  }

  @Test
  fun `cell 7 a keyword interface bridge add and listener member are escaped`() {
    val cs: String = run(
      """
      interface L {
        @CSharpName("event") fun onMeow(n: Int)
      }
      class C {
        @CSharpName("lock") fun addListener(listener: L) = Unit
        fun removeListener(listener: L) = Unit
      }
      """.trimIndent(),
    ).generatedCSharp
    assertContains(cs, "IDisposable @lock(")
    assertContains(cs, "listener.@event(arg0)")
    assertContains(cs, "void @event(int n);")
  }

  @Test
  fun `cell 8 keyword abstract members are escaped`() {
    val cs: String = run(
      """
      abstract class B {
        @CSharpName("event") abstract fun tag(): Int
        @CSharpName("lock") abstract val size: Int
      }
      """.trimIndent(),
    ).generatedCSharp
    assertContains(cs, "abstract int @event();")
    assertContains(cs, "abstract int @lock { get; }")
  }

  @Test
  fun `cell 9 keyword generic interface and generic class members are escaped`() {
    val cs: String = run(
      """
      interface G<T> {
        @CSharpName("event") fun get(): T
        @CSharpName("lock") val item: T
      }
      class Box<T>(@CSharpName("namespace") val item: T)
      """.trimIndent(),
    ).generatedCSharp
    assertContains(cs, "T @event();")
    assertContains(cs, "T @lock { get; }")
    assertContains(cs, "public T @namespace")
  }

  @Test
  fun `cell 10 keyword planned properties are escaped`() {
    val cs: String = run(
      """
      class P(@CSharpName("event") var name: String)
      @CSharpName("event") val top: Int = 1
      object O {
        @CSharpName("event") val x: Int = 1
      }
      class K {
        companion object {
          @CSharpName("event") val count: Int = 1
        }
      }
      """.trimIndent(),
    ).generatedCSharp
    assertContains(cs, "string @event")
    assertEquals(3, Regex("static int @event").findAll(cs).count(), cs)
  }

  @Test
  fun `cell 11 keyword enum member and extension properties are escaped`() {
    val cs: String = run(
      """
      class P(val name: String)
      enum class E {
        A;
        @CSharpName("lock") val p: Int get() = 1
      }
      @CSharpName("ref") val P.extp: Int get() = 1
      """.trimIndent(),
    ).generatedCSharp
    assertContains(cs, "int @lock(this")
    assertContains(cs, "public int @ref")
  }

  @Test
  fun `cell 12 a keyword interface property is escaped`() {
    val cs: String = run(
      """
      interface I {
        @CSharpName("lock") val tag: Int
      }
      class Impl : I {
        override val tag: Int = 1
      }
      """.trimIndent(),
    ).generatedCSharp
    assertContains(cs, "int @lock { get; }")
  }

  @Test
  fun `cell 13 a csharp implemented interface bridge calls the escaped member`() {
    val cs: String = run(
      """
      interface Pet {
        @CSharpName("event") fun f(): Int
      }
      class Cat : Pet {
        override fun f(): Int = 1
        fun befriend(pet: Pet) = Unit
      }
      """.trimIndent(),
    ).generatedCSharp
    assertContains(cs, "impl.@event(")
  }

  @Test
  fun `cell 15 keyword suspend and top-level members are escaped by the renderer`() {
    val cs: String = run(
      """
      class C {
        @CSharpName("event") suspend fun fetch(): Int = 1
      }
      @CSharpName("lock") suspend fun load(): Int = 1
      @CSharpName("ref") fun count(): Int = 1
      """.trimIndent(),
    ).generatedCSharp
    assertContains(cs, "Task<int> @event(")
    assertContains(cs, "Task<int> @lock(")
    assertContains(cs, "int @ref()")
  }

  @Test
  fun `cell 14 a declared name on a flow property keeps its extern stem`() {
    val cs: String = run(
      """
      class C {
        @CSharpName("Stream") val updates: Flow<Int> = flowOf(1)
      }
      """.trimIndent(),
    ).generatedCSharp
    assertContains(cs, "KotlinFlow<int> Stream")
    assertContains(cs, "extern IntPtr Native_GetUpdatesCollect(")
    assertFalse("Native_GetStreamCollect" in cs, cs)
  }
}
