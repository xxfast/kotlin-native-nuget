package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-147 amendment: an unconstrained Kotlin `T` has upper bound `Any?`, so `Box<String?>(null)` is
 * legal Kotlin. A bare `T` crosses on the null-pointer wire (ADR-083) at every position of the
 * generic-class route and keeps its bare C# spelling; `T : Any` stays non-null and says so with
 * `where T : notnull`. Also covers the two neighbours folded into the same item: a type parameter
 * whose C# name collides with a member's (CS0102), and the legacy generic-function route's
 * nullable value-type argument.
 */
class Tier1GenericNullableTypeArgumentTest {

  @Test
  fun `a bare unconstrained T carries null at every generic-class position`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullabletypearg

      class Crate<T>(val item: T) {
        fun pick(other: T): T = other
      }

      class Tin<T : Any>(val value: T)
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected clean compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    // The owner erases to `Any?`, the argument decodes through the null-safe lowering, and the
    // getter and the method return take the nullable result body.
    assertContains(kotlin, "tier1.nullabletypearg.Crate<Any?>(item?.asStableRef<Any>()?.get())")
    assertContains(kotlin, "item: COpaquePointer?")
    assertContains(kotlin, "pick(other?.asStableRef<Any>()?.get())")
    assertContains(kotlin, "if (result == null) null else NugetHandles.retain(result)")
    assertFalse(
      kotlin.contains("Crate<Any>>"),
      "an unconstrained owner must not erase to the non-null Any; generated=$kotlin",
    )
    // `T : Any` keeps the non-null crossing.
    assertContains(kotlin, "tier1.nullabletypearg.Tin<Any>(value_.asStableRef<Any>().get())")
    assertContains(
      kotlin,
      "NugetHandles.retain(handle.asStableRef<tier1.nullabletypearg.Tin<Any>>().get().value)",
    )

    val cs: String = result.generatedCSharp
    // C# spells a bare `T` as `T`: `Crate<string>.Item` is no more nullable than Kotlin's.
    assertContains(cs, "public Crate(T item)")
    assertContains(cs, "public T Item")
    assertContains(cs, "public T Pick(T other)")
    assertFalse(cs.contains("T? Item"), "bare T must not spell T?; generated=$cs")
    assertFalse(
      cs.contains("class Crate<T> : IDisposable, INugetHandle where"),
      "an unconstrained T takes no constraint; generated=$cs",
    )
    assertContains(cs, "public class Tin<T> : IDisposable, INugetHandle where T : notnull")
  }

  @Test
  fun `a type parameter renamed around a member that PascalCases onto it`() {
    val result = Tier1Harness.run(
      """
      package tier1.duo

      class Duo<A, B>(val a: A, val b: B)
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected clean compile; got: ${result.compileErrors}")

    val cs: String = result.generatedCSharp
    // The properties keep their consumer-facing names; the type parameters give way, everywhere.
    assertContains(cs, "public class Duo<TA, TB> : IDisposable, INugetHandle")
    assertContains(cs, "public Duo(TA a, TB b)")
    assertContains(cs, "public TA A")
    assertContains(cs, "public TB B")
    assertContains(cs, "NugetMarshal.Wrap<TA>(")
    assertContains(cs, "return NugetMarshal.FromHandle<TB>(nativeResult);")
    assertFalse(cs.contains("class Duo<A, B>"), "the clashing spelling is CS0102; generated=$cs")
  }

  @Test
  fun `the generic function route dispatches a nullable value type and carries null`() {
    val result = Tier1Harness.run(
      """
      package tier1.identity

      fun <T> identity(value: T): T = value
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected clean compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(kotlin, "`value`: COpaquePointer?")
    assertContains(
      kotlin,
      "tier1.identity.identity(value?.asStableRef<Any>()?.get())?.let { result -> " +
          "NugetHandles.retain(result) }",
    )

    val cs: String = result.generatedCSharp
    // `int?` is `Nullable<int>`, which never equals `typeof(int)`.
    assertContains(cs, "Type width = Nullable.GetUnderlyingType(typeof(T)) ?? typeof(T);")
    assertContains(cs, "if (present && width == typeof(int))")
    // A null string used to cross the string width into a non-null Kotlin `String` parameter.
    assertContains(cs, "if (present && width == typeof(string))")
    // ADR-173: the object arm writes through the shared `Wrap<T>`, which answers a null with the
    // null pointer (ADR-083) and disposes a minted handle on `owned`.
    assertContains(cs, "IntPtr handle = NugetMarshal.Wrap<T>(value!, out bool owned);")
    assertContains(cs, "if (owned) NugetMarshal.Dispose(handle);")
    assertContains(
      cs,
      "return result == IntPtr.Zero ? default! : NugetMarshal.Materialize<T>(result);",
    )
  }

  @Test
  fun `an Any-bound generic function renders notnull and keeps its width dispatch`() {
    val result = Tier1Harness.run(
      """
      package tier1.handback

      fun <T : Any> handBack(treat: T): T = treat

      fun <V> echo(value: V): V = value
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected clean compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    // `T : Any` is non-null on the Kotlin half: the object variant dereferences as before.
    assertContains(kotlin, "tier1.handback.handBack(treat.asStableRef<Any>().get())")
    assertContains(kotlin, "@CName(\"library_tier1_handback__handBack_int\")")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public static T HandBack<T>(T treat) where T : notnull")
    assertContains(cs, "if (present && width == typeof(int))")
    assertContains(
      cs,
      "private static extern int HandBack_int_native(int treat, out IntPtr error);",
    )
    // A type parameter not named `T` is spelled by its own name throughout the body.
    assertContains(cs, "public static V Echo<V>(V value)")
    assertContains(cs, "Type width = Nullable.GetUnderlyingType(typeof(V)) ?? typeof(V);")
    assertContains(cs, "NugetMarshal.Materialize<V>(result)")
    assertFalse(cs.contains("Echo<V>(T value)"), "the body must not hard-code T; generated=$cs")
  }

  @Test
  fun `a diagnostic quotes a renamed type parameter by its Kotlin name`() {
    val result = Tier1Harness.run(
      """
      package tier1.duovar

      class Duo<A, B>(var a: A, val b: B)
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected clean compile; got: ${result.compileErrors}")
    assertContains(result.generatedCSharp, "public class Duo<TA, TB>")

    // `var a: A` binds read-only (ADR-147 v1); the dropped-setter note names the type the author
    // wrote, `A`, never the C# rename `TA`.
    val note: String = result.kspWarnings.single { warning ->
      "Duo" in warning && "setter" in warning
    }
    assertFalse(note.contains("TA"), "expected the Kotlin spelling; got: $note")
    assertContains(note, "A")
  }

  @Test
  fun `a nullable class bound carries null and says so in its constraint`() {
    val result = Tier1Harness.run(
      """
      package tier1.kennel

      open class Pet

      class Kennel<T : Pet?>(val pet: T)
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected clean compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(
      kotlin,
      "tier1.kennel.Kennel<tier1.kennel.Pet?>(pet?.asStableRef<tier1.kennel.Pet>()?.get())",
    )

    val cs: String = result.generatedCSharp
    val header: String = cs.lines().single { line -> "public class Kennel<T>" in line }
    assertTrue(header.trimEnd().endsWith("Pet?"), "expected a nullable constraint; got: $header")
  }
}
