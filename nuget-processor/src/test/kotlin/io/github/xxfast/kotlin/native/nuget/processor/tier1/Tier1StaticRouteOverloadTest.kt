package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-095: overloads on the four *static* export routes (object, companion, top-level, extension).
 *
 * The end-to-end half lives in `GroomingSample.kt` / `StaticRouteOverloadTests.cs`, which is where
 * numbering, dispatch and the one-natural-overload-set C# surface are arbitrated. What is only
 * reachable here is the ADR-034 signature collision on each of the four *generated C# containers*:
 * C# cannot overload on reference nullability alone, and the correct outcome is a **failed**
 * generation, so none of these can live in the shipped fixture library.
 */
class Tier1StaticRouteOverloadTest {

  private fun assertCollides(source: String) {
    val result = Tier1Harness.run(source)
    assertTrue(
      result.kspErrors.any {
        it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name)
      },
      "expected the reference-nullability collision to fail generation; " +
          "kspErrors=${result.kspErrors}",
    )
  }

  /** The object's generated C# class. */
  @Test
  fun `object overloads differing only in reference nullability fail generation`() {
    assertCollides(
      """
      package tier1.objectoverloadcollision

      object Parlour {
        fun tag(cat: String): String = cat
        fun tag(cat: String?): String = cat ?: "stray"
      }
      """.trimIndent(),
    )
  }

  /** The companion's statics, on the owning class's generated C# class. */
  @Test
  fun `companion overloads differing only in reference nullability fail generation`() {
    assertCollides(
      """
      package tier1.companionoverloadcollision

      class Groomer(val name: String) {
        companion object {
          fun of(name: String): Groomer = Groomer(name)
          fun of(name: String?): Groomer = Groomer(name ?: "stray")
        }
      }
      """.trimIndent(),
    )
  }

  /**
   * A companion static and an instance method land on the *same* C# class, and static-ness is not
   * part of a C# signature, so the two are checked together.
   */
  @Test
  fun `a companion static colliding with an instance method fails generation`() {
    assertCollides(
      """
      package tier1.companioninstancecollision

      class Groomer(val name: String) {
        fun tag(cat: String): String = cat

        companion object {
          fun tag(cat: String): String = cat
        }
      }
      """.trimIndent(),
    )
  }

  /** The (namespace, file class) static class top-level functions render onto. */
  @Test
  fun `top-level overloads differing only in reference nullability fail generation`() {
    assertCollides(
      """
      package tier1.toplevelverloadcollision

      fun bookGrooming(cat: String): String = cat
      fun bookGrooming(cat: String?): String = cat ?: "stray"
      """.trimIndent(),
    )
  }

  /** The `{Receiver}Extensions` class, whose first parameter is the receiver. */
  @Test
  fun `extension overloads differing only in reference nullability fail generation`() {
    assertCollides(
      """
      package tier1.extensionoverloadcollision

      class Mitten(val name: String)

      fun Mitten.pat(style: String): String = "${'$'}name ${'$'}style"
      fun Mitten.pat(style: String?): String = "${'$'}name ${'$'}{style ?: "gentle"}"
      """.trimIndent(),
    )
  }

  /**
   * The counterpart: two same-name extensions on *different* receivers in one package share the
   * package-scoped plan symbol (so they crashed generation before ADR-095), but land on different
   * `{Receiver}Extensions` classes and must not be reported as a collision.
   */
  @Test
  fun `same-name extensions on different receivers are not a collision`() {
    val result = Tier1Harness.run(
      """
      package tier1.extensioncrossreceiver

      class Mitten(val name: String)
      class Tomcat(val name: String)

      fun Mitten.pat(): String = "${'$'}name purrs"
      fun Tomcat.pat(): String = "${'$'}name tolerates one pat"
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected both receivers' extensions to compile; got: ${result.compileErrors}",
    )
    assertTrue(
      result.kspErrors.isEmpty(),
      "cross-receiver namesakes are not a C# collision; kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.generated.contains("@CName(\"mitten_pat\")") &&
          result.generated.contains("@CName(\"tomcat_pat_2\")"),
      "expected package-scoped numbering across receivers; generated=${result.generated}",
    )
  }
}
