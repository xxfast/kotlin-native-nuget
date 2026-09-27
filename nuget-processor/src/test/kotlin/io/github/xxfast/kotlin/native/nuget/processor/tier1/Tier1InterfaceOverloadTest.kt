package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-090 amendment (2026-09-26): the interface route numbers same-name members the way the class
 * route does. Before it, a REACHABLE interface (returned per ADR-040, or accepted per ADR-084) with
 * an overload pair aborted generation on a duplicate plan, and the ADR-084 bridge factory declared
 * `speakPtr` twice.
 *
 * The end-to-end half lives in the `Brusher` fixture / `InterfaceOverloadTests.cs`. What is only
 * reachable here: structural naming on both halves, the unreachable and async shapes, and the
 * reference-nullability pair whose correct outcome is a failed generation.
 */
class Tier1InterfaceOverloadTest {

  /** Plain, reachable through a return position (ADR-040). */
  @Test
  fun `a returned interface numbers its overloads on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifaceov

      interface Pet {
        fun speak(): String
        fun speak(times: Int): String
      }

      class Cat : Pet {
        override fun speak(): String = "Meow"
        override fun speak(times: Int): String = "Meow".repeat(times)
        fun friend(): Pet = this
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"library_tier1_ifaceov__pet_speak\")")
    assertContains(kotlin, "@CName(\"library_tier1_ifaceov__pet_speak_2\")")
    assertFalse(kotlin.contains(".speak_2("), "call sites say the declared name; generated=$kotlin")

    val cs: String = result.generatedCSharp
    assertContains(cs, "string Speak();")
    assertContains(cs, "string Speak(int times);")
    assertContains(cs, "EntryPoint = \"library_tier1_ifaceov__pet_speak_2\"")
    assertContains(cs, "private static extern IntPtr Native_Speak_2(IntPtr handle, int times")
    assertContains(cs, "public string Speak(int times)")
  }

  /**
   * Bridged (ADR-084): the factory's same-name function slots get the suffix on their ABI names,
   * while the Kotlin `override fun` keeps the declared name.
   */
  @Test
  fun `a bridged interface numbers its same-name slots but not its overrides`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifaceovb

      interface Pet {
        fun speak(): String
        fun speak(times: Int): String
      }

      class Cat : Pet {
        override fun speak(): String = "Meow"
        override fun speak(times: Int): String = "Meow".repeat(times)
        fun friend(): Pet = this
        fun befriend(pet: Pet): String = pet.speak(2)
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(kotlin, "speakPtr: COpaquePointer")
    assertContains(kotlin, "speak_2Ptr: COpaquePointer")
    assertContains(kotlin, "speak_2Fn.invoke(")
    assertEquals(
      2,
      Regex("""override fun speak\(""").findAll(kotlin).count(),
      "both overrides keep the declared name; generated=$kotlin",
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "IntPtr speakPtr, IntPtr speakCtx, IntPtr speak_2Ptr, IntPtr speak_2Ctx")
    assertContains(cs, "impl.Speak(value0)")
  }

  /**
   * `Int` and an enum share the `int` wire, so only the extern name and the slot tell them apart.
   */
  @Test
  fun `an Int and enum pair get distinct externs and a cast in the bridge slot`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifaceovf

      enum class Mood { HAPPY, SAD }

      interface Pet {
        fun speak(times: Int): String
        fun speak(mood: Mood): String
      }

      class Cat : Pet {
        override fun speak(times: Int): String = "Meow".repeat(times)
        override fun speak(mood: Mood): String = mood.name
        fun friend(): Pet = this
        fun befriend(pet: Pet): String = pet.speak(Mood.SAD)
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")

    val cs: String = result.generatedCSharp
    // The backing `Pet` class: a numbered extern for the enum overload, beside the bare one.
    assertContains(cs, "EntryPoint = \"library_tier1_ifaceovf__pet_speak_2\"")
    assertContains(cs, "Native_Speak(_handle, times, out IntPtr error)")
    assertContains(cs, "Native_Speak_2(_handle, (int)mood, out IntPtr error)")
    // One `Native_Speak(` extern per generated class (`Cat` and the `Pet` backing class), never two
    // in one class, which is the CS0111 the number prevents.
    assertEquals(
      2,
      Regex("""static extern .*Native_Speak\(""").findAll(cs).count(),
      "one extern name per class (CS0111); generatedCSharp=$cs",
    )
    assertContains(cs, "(global::Interop.Mood)arg0")
  }

  /** ADR-164: each defaulted overload keeps its own mask dispatch under its own numbered export. */
  @Test
  fun `defaulted overloads each widen under their own numbered export`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifaceovd

      interface Pet {
        fun speak(times: Int = 1): String
        fun speak(word: String, times: Int = 1): String
      }

      class Cat : Pet {
        override fun speak(times: Int): String = "Meow".repeat(times)
        override fun speak(word: String, times: Int): String = word.repeat(times)
        fun friend(): Pet = this
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"library_tier1_ifaceovd__pet_speak\")")
    assertContains(kotlin, "@CName(\"library_tier1_ifaceovd__pet_speak_2\")")

    val cs: String = result.generatedCSharp
    assertContains(cs, "string Speak(int? times = null);")
    assertContains(cs, "string Speak(string word, int? times = null);")
  }

  /**
   * Suspend and Flow members get no interface export (pre-existing: `IFoo` declares no async
   * member), but a skipped `suspend` namesake still consumes its number, as on the class route, so
   * the plain overload after it is `_2`.
   */
  @Test
  fun `a skipped suspend namesake still consumes its number`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifaceovc

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      interface Pet {
        suspend fun fetch(): String
        fun fetch(item: String): String
        fun ticks(): Flow<Int>
        fun ticks(n: Int): Flow<Int>
      }

      class Cat : Pet {
        override suspend fun fetch(): String = "x"
        override fun fetch(item: String): String = item
        override fun ticks(): Flow<Int> = flowOf(1)
        override fun ticks(n: Int): Flow<Int> = flowOf(n)
        fun friend(): Pet = this
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"library_tier1_ifaceovc__pet_fetch_2\")")
    assertFalse(
      kotlin.contains("@CName(\"library_tier1_ifaceovc__pet_fetch\")"),
      "the suspend overload has no interface export; generated=$kotlin",
    )
    // The implementing class's own async routes already number (ADR-118, issue #97).
    assertContains(kotlin, "library_tier1_ifaceovc__cat_ticks_2_collect")
  }

  /** An interface that is only implemented already worked, and must keep working. */
  @Test
  fun `an unreachable interface declares both overloads on IFoo`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifaceovg

      interface Pet {
        fun speak(): String
        fun speak(times: Int): String
      }

      class Cat : Pet {
        override fun speak(): String = "Meow"
        override fun speak(times: Int): String = "Meow".repeat(times)
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")

    val cs: String = result.generatedCSharp
    assertContains(cs, "string Speak();")
    assertContains(cs, "string Speak(int times);")
    assertContains(cs, "EntryPoint = \"library_tier1_ifaceovg__cat_speak_2\"")
    assertFalse(cs.contains("__pet_speak"), "no dispatch exports when unreachable; cs=$cs")
  }

  /**
   * An implementer that inherits a default-bodied member plans the SAME declaration node on the
   * class route, so the interface lookups must be owner-keyed, not node-keyed.
   */
  @Test
  fun `an inherited default member beside an overload pair binds once per owner`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifaceovdefault

      interface Pet {
        fun speak(): String
        fun speak(times: Int): String
        fun purr(): String = "purr"
      }

      class Cat : Pet {
        override fun speak(): String = "Meow"
        override fun speak(times: Int): String = "Meow".repeat(times)
        fun friend(): Pet = this
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"library_tier1_ifaceovdefault__pet_purr\")")
    assertContains(kotlin, "@CName(\"library_tier1_ifaceovdefault__cat_purr\")")
  }

  /**
   * Overload inherited from a super-interface: the bridge slot walk includes inherited members,
   * so the slot names must be numbered there too (the Kotlin factory compiled `greetPtr` twice).
   */
  @Test
  fun `an overload inherited from a super-interface gets a numbered bridge slot`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifaceove

      interface Animal { fun speak(): String }
      interface Pet : Animal { fun speak(times: Int): String }

      class Cat : Pet {
        override fun speak(): String = "Meow"
        override fun speak(times: Int): String = "Meow".repeat(times)
        fun friend(): Pet = this
        fun animal(): Animal = this
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    assertContains(kotlin, "speak_2Ptr: COpaquePointer")
  }

  /**
   * Diagnostic. `String` / `String?` render one C# signature on `IFoo` (and on the backing
   * class), which was CS0111 with no KSP error. Deliberately in-process only: the outcome is a
   * failed build.
   */
  @Test
  fun `reference-nullability-only interface overloads fire ERROR_CSHARP_SIGNATURE_COLLISION`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifaceovh

      interface Pet {
        fun tag(s: String): String
        fun tag(s: String?): String
      }

      fun make(): Pet = object : Pet {
        override fun tag(s: String): String = s
        override fun tag(s: String?): String = s ?: ""
      }
      """.trimIndent(),
    )

    val collision: String? = result.kspErrors.firstOrNull {
      it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name)
    }
    assertTrue(
      collision != null,
      "expected the pair to fail generation by name; kspErrors=${result.kspErrors}",
    )
    assertContains(collision, "IPet.Tag")
  }

  /** Regression: an interface with no same-name members keeps exactly the shipped names. */
  @Test
  fun `an interface without overloads keeps its unsuffixed naming`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifacenoov

      interface Pet {
        fun speak(): String
        fun greet(who: String): String
      }

      class Cat : Pet {
        override fun speak(): String = "Meow"
        override fun greet(who: String): String = "Hi " + who
        fun friend(): Pet = this
        fun befriend(pet: Pet): String = pet.speak()
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"library_tier1_ifacenoov__pet_speak\")")
    assertContains(kotlin, "@CName(\"library_tier1_ifacenoov__pet_greet\")")
    assertFalse(kotlin.contains("_2Ptr"), "no numbered slot without overloads; generated=$kotlin")
    assertFalse(kotlin.contains("pet_speak_2"), "no numbering without overloads; generated=$kotlin")
  }

  /**
   * The ADR-039 `add*`/`remove*` subscription route names one callback slot per listener member
   * name, so an overloaded listener generated Kotlin that did not compile (`Conflicting
   * declarations: onMeowFn`). That route is legacy and is not numbered: the pair is refused by name
   * on both halves instead.
   */
  @Test
  fun `a subscription pair over an overloaded listener is refused by name`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifaceovlistener

      interface MeowListener {
        fun onMeow()
        fun onMeow(times: Int)
      }

      class Cat {
        private val listeners = mutableListOf<MeowListener>()
        fun addMeowListener(listener: MeowListener) { listeners.add(listener) }
        fun removeMeowListener(listener: MeowListener) { listeners.remove(listener) }
      }
      """.trimIndent(),
    )
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    listOf("addMeowListener", "removeMeowListener").forEach { member ->
      assertTrue(
        result.kspWarnings.any { warning ->
          warning.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) &&
              warning.contains("Cat.$member") &&
              warning.contains("declares `onMeow` more than once")
        },
        "expected $member to be named; kspWarnings=${result.kspWarnings}",
      )
    }
    assertFalse(
      result.generated.contains("cat_addMeowListener"),
      "no Kotlin subscription export; generated=${result.generated}",
    )
    assertFalse(
      result.generatedCSharp.contains("cat_addMeowListener"),
      "no C# subscription import; cs=${result.generatedCSharp}",
    )
  }

  /**
   * ADR-113 carve-out: a type-parameter member keeps its place on `IFoo` beside a planned namesake.
   * It deduped by (name, arity), so `peek(x: T)` vanished beside a planned `peek(x: Int)`, a legal
   * C# overload pair.
   */
  @Test
  fun `a type-parameter member survives beside a planned namesake`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifaceovgeneric

      interface Readable<T> {
        fun read(): T
        fun read(x: Int): String
        fun peek(x: T): String
        fun peek(x: Int): String
      }

      class Box : Readable<String> {
        override fun read(): String = "a"
        override fun read(x: Int): String = "b"
        override fun peek(x: String): String = x
        override fun peek(x: Int): String = "c"
      }
      """.trimIndent(),
    )
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp
    val iface: String = cs.substringAfter("interface IReadable").substringBefore("}")
    assertContains(iface, "string Read(int x);")
    assertContains(iface, "T Read();")
    assertContains(iface, "string Peek(int x);")
    assertContains(iface, "string Peek(T x);")
  }
}
