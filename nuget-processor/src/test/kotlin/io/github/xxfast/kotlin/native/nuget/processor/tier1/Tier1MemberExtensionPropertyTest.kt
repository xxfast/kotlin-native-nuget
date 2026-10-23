package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-238: an extension property declared inside a class binds as instance accessor methods with
 * the receiver first, Kotlin/JVM's own lowering (`GetNick(Loafer receiver)`, `SetMood(...)`), a
 * `Flow` typed one as a flow method. The plain member property and the member extension function
 * beside them keep their shapes.
 */
class Tier1MemberExtensionPropertyTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.memberextprop

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      class Loafer(val name: String)

      class Bindle<T>(var top: T)

      class Groomhand(val name: String) {
        var plain: Int = 0

        fun Loafer.brush(strokes: Int): String = name + strokes

        val Loafer.nick: String get() = name

        val Loafer?.nickOrNone: String get() = this?.name ?: "none"

        var Loafer.mood: Int
          get() = 1
          set(value) {}

        val <T> Bindle<T>.peek: T get() = top

        val Loafer.stream: Flow<Int> get() = flowOf(1)
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  @Test
  fun `every member extension property binds and the generated Kotlin compiles`() {
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    assertTrue(
      result.kspWarnings.none { "tier1.memberextprop" in it },
      "kspWarnings=${result.kspWarnings}",
    )
    val cs: String = result.generatedCSharp
    assertContains(cs, "public string GetNick(global::Interop.Loafer receiver)")
    assertContains(cs, "public int GetMood(global::Interop.Loafer receiver)")
    assertContains(cs, "public void SetMood(global::Interop.Loafer receiver, int value)")
    assertContains(cs, "public KotlinFlow<int> Stream(global::Interop.Loafer receiver)")
    assertContains(result.generated, ".mood = value_ }")
  }

  @Test
  fun `a generic receiver member extension property is a generic accessor`() {
    assertContains(
      result.generatedCSharp,
      "public T GetPeek<T>(global::Interop.Bindle<T> receiver)",
    )
  }

  @Test
  fun `a nullable receiver member extension property takes the null`() {
    assertContains(
      result.generatedCSharp,
      "public string GetNickOrNone(global::Interop.Loafer? receiver)",
    )
    assertContains(
      result.generated,
      "(receiver_?.asStableRef<tier1.memberextprop.Loafer>()?.get())",
    )
  }

  @Test
  fun `the plain member property and the member extension function are unaffected`() {
    val cs: String = result.generatedCSharp
    assertContains(cs, "public int Plain")
    assertContains(cs, "public string Brush(global::Interop.Loafer receiver, int strokes)")
    assertTrue("public string Nick" !in cs, "no receiverless property; generatedCSharp=$cs")
  }

  /** ADR-238: a member extension FUNCTION binds on every owner kind with the receiver first. */
  @Test
  fun `a member extension function binds on every owner with the receiver first`() {
    val owners: Tier1Result = Tier1Harness.run(
      """
      package tier1.memberextowners

      class Loafer(val name: String)

      object Hold {
        fun Loafer.inHold(n: Int): String = name + n
      }

      class Den {
        companion object {
          fun Loafer.inDen(n: Int): String = name + n
        }
      }

      sealed class Nap {
        fun Loafer.inNap(n: Int): String = name + n

        class Deep : Nap() {
          fun Loafer.inDeep(n: Int): String = name + n
        }
      }

      interface Lair {
        fun Loafer.inLair(n: Int): String = name + n
      }

      class Burrow : Lair

      enum class Mood {
        CALM;

        fun Loafer.inMood(n: Int): String = name + n
      }

      @JvmInline
      value class Tag(val id: String) {
        fun Loafer.inTag(n: Int): String = name + id + n
      }
      """.trimIndent(),
    )

    assertTrue(owners.compiledClean, "compileErrors=${owners.compileErrors}")
    val cs: String = owners.generatedCSharp
    listOf("InHold", "InDen").forEach { name ->
      assertContains(cs, "public static string $name(global::Interop.Loafer receiver, int n)")
    }
    listOf("InNap", "InDeep", "InLair", "InTag").forEach { name ->
      assertContains(cs, "string $name(global::Interop.Loafer receiver, int n)")
    }
    assertContains(
      cs,
      "public static string InMood(" +
        "this global::Interop.Mood mood, global::Interop.Loafer receiver, int n)",
    )
  }
}
