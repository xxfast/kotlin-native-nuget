package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-173: an exported interface at an ERASED type-argument position (a lambda `KotlinFunc<...>`,
 * or an exported generic class `Box<...>`) is spelled as the projected interface `IFoo`, never its
 * ADR-040 backing wrapper `Foo`, and an interface reached ONLY there still gets its backing
 * wrapper, its interface-keyed `Factories` entry and its `NugetBridge.HandleFor` arm.
 *
 * Mylo's squeaky mouse goes through the relay, and his chew toy comes in a box.
 */
class Tier1ErasedInterfaceIdentityTest {

  private val source: String =
    """
    package tier1.erasedidentity

    interface Squeaker {
      val squeak: String
    }

    fun squeakerRelay(): (Squeaker) -> Squeaker = { it }

    interface Chewer {
      val chew: String
    }

    class Box<T>(val value: T)

    fun chewerBox(): Box<Chewer> = Box(object : Chewer {
      override val chew: String = "nom"
    })
    """.trimIndent()

  @Test
  fun `an interface lambda type argument is spelled as the projected interface`() {
    val result = Tier1Harness.run(source)
    assertTrue(result.compiledClean, "expected the fixture to bind; got: ${result.compileErrors}")

    val cs: String = result.generatedCSharp
    val relay: String = cs.lines().first { line -> "SqueakerRelay()" in line && "public" in line }
    assertTrue(
      Regex(
        """KotlinFunc<(global::[\w.]+\.)?ISqueaker, """ +
          """(global::[\w.]+\.)?ISqueaker> SqueakerRelay\(\)""",
      ).containsMatchIn(relay),
      "expected KotlinFunc<ISqueaker, ISqueaker>; got: $relay",
    )
    assertFalse(
      Regex("""[<.\s]Squeaker[,>]""").containsMatchIn(relay),
      "the backing wrapper leaked: $relay",
    )
  }

  @Test
  fun `an interface generic-class type argument is spelled as the projected interface`() {
    val result = Tier1Harness.run(source)
    val cs: String = result.generatedCSharp
    val box: String = cs.lines().first { line -> "ChewerBox()" in line && "public" in line }
    assertTrue(
      Regex("""Box<(global::[\w.]+\.)?IChewer> ChewerBox\(\)""").containsMatchIn(box),
      "got: $box",
    )
  }

  @Test
  fun `an interface reached only at an erased position gets its wrapper, factory and bridge`() {
    val result = Tier1Harness.run(source)
    val cs: String = result.generatedCSharp
    for (name in listOf("Squeaker", "Chewer")) {
      assertTrue(
        Regex("""sealed class $name : I$name\b""").containsMatchIn(cs),
        "expected the ADR-040 backing wrapper for $name",
      )
      assertTrue(
        Regex(
          """\[typeof\((global::)?[\w.]*I$name\)\] = static handle => """ +
            """new (global::)?[\w.]*\b$name\(handle, out _\),""",
        ).containsMatchIn(cs),
        "expected the interface-keyed Factories entry for I$name",
      )
      assertTrue(
        Regex("""declared == typeof\([\w.]*I$name\)""").containsMatchIn(cs),
        "expected the NugetBridge.HandleFor arm for I$name",
      )
    }
  }

  @Test
  fun `Materialize probes the token before an interface factory and Wrap falls back to the bridge`() {
    val cs: String = Tier1Harness.run(source).generatedCSharp
    val probe: Int = cs.indexOf("TryResolveCSharpObject(handle, out object original)")
    val factory: Int =
      cs.indexOf("if (Factories.TryGetValue(key, out Func<IntPtr, object>? factory))")
    assertTrue(probe in 0 until factory, "the token probe must run before the Factories lookup")
    assertTrue(
      "IntPtr bridged = HandleOf((object)value!, typeof(T));" in cs,
      "Wrap<T> bridge fallback",
    )
    assertFalse("to a Kotlin collection\")" in cs, "the old Wrap<T> throw must be gone")
  }
}
