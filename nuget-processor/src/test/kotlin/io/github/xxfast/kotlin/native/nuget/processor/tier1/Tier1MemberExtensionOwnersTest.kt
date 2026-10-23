package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-238: an extension property declared inside an object, a companion, an enum, a value class,
 * a sealed base, a sealed arm or an interface binds as accessor methods with the receiver first
 * (`GetNick(Loafer receiver)`, `SetMood(Loafer receiver, int value)`), a `Flow` typed one as a
 * flow method, as on a class. A `suspend` or `Flow` returning extension function inside an object,
 * a companion, an enum or a value class takes the receiver as a leading slot beside the owner's
 * own. Each owner is its own run, so one owner's compile error cannot hide another's.
 */
class Tier1MemberExtensionOwnersTest {

  private val members: String =
    """
    val Loafer.nick: String get() = name
    var Loafer.mood: Int
      get() = name.length
      set(value) {}
    val Loafer.stream: Flow<Int> get() = flowOf(1)
    """.trimIndent()

  private val asyncMembers: String =
    """
    suspend fun Loafer.nap(minutes: Int): String = name + minutes
    fun Loafer.ticks(count: Int): Flow<Int> = flowOf(count)
    """.trimIndent()

  private fun run(owner: String): Tier1Result = Tier1Harness.run(
    """
    package tier1.memberextowner

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flowOf

    class Loafer(val name: String)

    $owner
    """.trimIndent(),
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  private fun Tier1Result.assertBinds(vararg declarations: String) {
    assertTrue(compiledClean, "compileErrors=$compileErrors")
    assertTrue(
      kspWarnings.none { "tier1.memberextowner" in it && "SKIPPED_" in it },
      "kspWarnings=$kspWarnings",
    )
    declarations.forEach { declaration -> assertContains(generatedCSharp, declaration) }
    // A duplicate parameter name (CS0100) only shows when the C# half is compiled.
    Tier1CSharpCompile.assertCompiles(this, "public static class Consumer { }", allowUnsafe = true)
  }

  private fun indent(text: String): String = text.lines().joinToString("\n") { "  $it" }

  @Test
  fun `an object's member extensions bind as static methods taking the receiver first`() {
    run("object Hold {\n${indent(members)}\n${indent(asyncMembers)}\n}").assertBinds(
      "public static string GetNick(global::Interop.Loafer receiver)",
      "public static void SetMood(global::Interop.Loafer receiver, int value)",
      "public static KotlinFlow<int> Stream(global::Interop.Loafer receiver)",
      "public static Task<string> NapAsync(global::Interop.Loafer receiver, int minutes",
      "public static KotlinFlow<int> Ticks(global::Interop.Loafer receiver, int count)",
    )
  }

  @Test
  fun `a companion's member extensions bind as static methods on the owner`() {
    val body: String = indent(indent(members)) + "\n" + indent(indent(asyncMembers))
    run("class Den {\n  companion object {\n$body\n  }\n}")
      .assertBinds(
        "public static string GetNick(global::Interop.Loafer receiver)",
        "public static void SetMood(global::Interop.Loafer receiver, int value)",
        "public static KotlinFlow<int> Stream(global::Interop.Loafer receiver)",
        "public static Task<string> NapAsync(global::Interop.Loafer receiver, int minutes",
        "public static KotlinFlow<int> Ticks(global::Interop.Loafer receiver, int count)",
      )
  }

  @Test
  fun `an enum's member extensions take the receiver after the enum value`() {
    run("enum class Drowse {\n  CATNAP;\n\n${indent(members)}\n${indent(asyncMembers)}\n}")
      .assertBinds(
        "GetNick(this global::Interop.Drowse",
        "SetMood(this global::Interop.Drowse",
        "Stream(this global::Interop.Drowse",
        "NapAsync(this global::Interop.Drowse",
        "GetNick(this global::Interop.Drowse drowse, global::Interop.Loafer receiver)",
        // The static routes name the enum value `receiver`, so the extension's moves off it.
        "Ticks(this global::Interop.Drowse receiver, global::Interop.Loafer receiver_, int count)",
        "NapAsync(this global::Interop.Drowse receiver, " +
          "global::Interop.Loafer receiver_, int minutes",
      )
  }

  @Test
  fun `a value class's member extensions are instance methods on the struct`() {
    val body: String = indent(members) + "\n" + indent(asyncMembers)
    run("@JvmInline\nvalue class Tag(val id: String) {\n$body\n}")
      .assertBinds(
        "public string GetNick(global::Interop.Loafer receiver)",
        "public void SetMood(global::Interop.Loafer receiver, int value)",
        "public KotlinFlow<int> Stream(global::Interop.Loafer receiver_)",
        "public Task<string> NapAsync(global::Interop.Loafer receiver_, int minutes",
        "public KotlinFlow<int> Ticks(global::Interop.Loafer receiver_, int count)",
      )
  }

  @Test
  fun `a sealed base's member extension properties are accessor methods`() {
    run("sealed class Nap {\n${indent(members)}\n\n  class Deep : Nap()\n}").assertBinds(
      "string GetNick(global::Interop.Loafer receiver)",
      "void SetMood(global::Interop.Loafer receiver, int value)",
      "KotlinFlow<int> Stream(global::Interop.Loafer receiver)",
    )
  }

  @Test
  fun `a sealed arm's member extension properties are accessor methods`() {
    val body: String = indent(indent(members))
    run("sealed class Nap {\n  class Deep : Nap() {\n$body\n  }\n}").assertBinds(
      "string GetNick(global::Interop.Loafer receiver)",
      "void SetMood(global::Interop.Loafer receiver, int value)",
      "KotlinFlow<int> Stream(global::Interop.Loafer receiver)",
    )
  }


  private val lair: String =
    """
    interface Lair {
      val Loafer.nick: String
      var Loafer.mood: Int
    }

    class Burrow : Lair {
      override val Loafer.nick: String get() = name
      override var Loafer.mood: Int
        get() = name.length
        set(value) {}
    }

    fun lairOf(lair: Lair, loafer: Loafer): String = with(lair) { loafer.nick }
    """.trimIndent()

  private val chant: String =
    """
    interface Chant {
      val String.shout: String
      var String.volume: Int
      fun String.echo(times: Int): String
    }

    class Choir : Chant {
      override val String.shout: String get() = uppercase()
      override var String.volume: Int
        get() = length
        set(value) {}
      override fun String.echo(times: Int): String = repeat(times)
    }

    fun sing(chant: Chant, line: String): String = with(chant) { line.shout + line.echo(2) }
    """.trimIndent()

  @Test
  fun `an interface's member extension properties over a class are slots a C# implementer fills`() {
    val result: Tier1Result = run(lair)
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    listOf(
      "string GetNick(global::Interop.Loafer receiver);",
      "void SetMood(global::Interop.Loafer receiver, int value);",
      "public string GetNick(global::Interop.Loafer receiver)",
    ).forEach { declaration -> assertContains(result.generatedCSharp, declaration) }
    // ADR-233: a bridge slot carries a class handle, so a C# implementation of an interface whose
    // member extension extends a `Loafer` crosses, the receiver its first argument.
    assertTrue(
      result.kspWarnings.none { "SKIPPED_UNIMPLEMENTABLE_INTERFACE" in it },
      "kspWarnings=${result.kspWarnings}",
    )
    assertContains(result.generatedCSharp, "string result = impl.GetNick(value0);")
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      public sealed class Den : global::Interop.ILair
      {
          public string GetNick(global::Interop.Loafer receiver) => "den " + receiver.Name;
          public int GetMood(global::Interop.Loafer receiver) => 1;
          public void SetMood(global::Interop.Loafer receiver, int value) { }
          public void Dispose() { }
      }

      public static class Consumer
      {
          public static string Read(global::Interop.Loafer loafer) =>
              global::Interop.Fixture.LairOf(new Den(), loafer);
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  @Test
  fun `an interface's member extensions are bridge slots a C# implementer fills`() {
    run(chant).assertBinds(
      "string GetShout(string receiver);",
      "void SetVolume(string receiver, int value);",
      "string Echo(string receiver, int times);",
      "string result = impl.GetShout(value0);",
      "impl.SetVolume(value0, value1);",
    )
  }
}
