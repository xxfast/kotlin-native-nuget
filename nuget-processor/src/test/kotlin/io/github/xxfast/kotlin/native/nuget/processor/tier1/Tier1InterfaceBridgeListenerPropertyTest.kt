package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ROADMAP Phase 4: a listener interface with a `val` inside an ADR-039 `add*`/`remove*` pair failed
 * the Kotlin compile of `CNameExports.kt` (`Class '<anonymous>' is not abstract and does not
 * implement abstract member: val name: String`): the subscription export's `object : Listener`
 * overrode only the listener's functions.
 *
 * The same interface already binds when a C# implementation is passed at a plain parameter: the
 * ADR-084 bridge factory gives the `val` a getter slot. The pair now carries the same getter slot,
 * ahead of the function slots, and refuses by name the property shapes that factory has no slot
 * for, so the pair never emits an `object` that does not compile.
 */
class Tier1InterfaceBridgeListenerPropertyTest {

  private val dog: String =
    """
    class Dog {
      private val listeners = mutableListOf<BarkListener>()
      fun addBarkListener(l: BarkListener) { listeners.add(l) }
      fun removeBarkListener(l: BarkListener) { listeners.remove(l) }
      fun size(): Int = listeners.size
    }
    """.trimIndent()

  @Test
  fun `a listener val binds as a getter slot on both halves`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.lvprop

      interface BarkListener {
        val name: String
        fun onBark(volume: Int)
      }

      $dog
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    assertContains(result.generated, "override val name: String")
    // Properties first, then functions, on both halves of the one export.
    assertContains(
      result.generated,
      "handle: COpaquePointer,\n  nameGetPtr: COpaquePointer,\n  nameGetCtx: COpaquePointer,\n" +
          "  onBarkPtr: COpaquePointer,",
    )
    assertContains(
      result.generatedCSharp,
      "Native_AddBarkListener(NugetKotlinHandle handle, IntPtr nameGetPtr, IntPtr nameGetCtx, " +
          "IntPtr onBarkPtr, IntPtr onBarkCtx, out IntPtr error)",
    )
    assertContains(
      result.generatedCSharp,
      "NugetBridgeObjectCallback nameGetCb = (IntPtr _) => { string result = listener.Name; " +
          "return NugetMarshal.WrapString(result); };",
    )
    assertContains(result.generatedCSharp, "NugetThunks.NugetBridgeObjectCallbackPtr, k0")
    assertTrue(
      result.kspWarnings.none { warning -> warning.contains("Dog.addBarkListener") },
      "kspWarnings=${result.kspWarnings}",
    )
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System;
      using Interop;

      public sealed class Rex : IBarkListener
      {
          public string Name => "Rex";
          public void OnBark(int volume) { }
          public void Dispose() { }
      }

      public static class Probe
      {
          public static IDisposable Listen(Dog dog) => dog.AddBarkListener(new Rex());
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  /** Every getter wire the ADR-084 factory has, and a listener with no functions at all. */
  @Test
  fun `every getter wire binds, with or without listener functions`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.lvwires

      enum class Mood { CALM, LOUD }

      interface BarkListener {
        val name: String
        val nickname: String?
        val volume: Int
        val loud: Boolean
        val mood: Mood
        val weight: Double
        val ticks: Long
        val ratio: Float
      }

      $dog
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    listOf(
      "override val nickname: String?",
      "override val loud: Boolean",
      "override val mood: tier1.lvwires.Mood",
      "override val ratio: Float",
    ).forEach { override -> assertContains(result.generated, override) }
    assertContains(result.generatedCSharp, "return (byte)(listener.Loud ? 1 : 0);")
    assertContains(result.generatedCSharp, "return (int)listener.Mood;")
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System;
      using Interop;

      public sealed class Rex : IBarkListener
      {
          public string Name => "Rex";
          public string? Nickname => null;
          public int Volume => 3;
          public bool Loud => true;
          public Mood Mood => Mood.Loud;
          public double Weight => 1.5;
          public long Ticks => 2L;
          public float Ratio => 0.5f;
          public void Dispose() { }
      }

      public static class Probe
      {
          public static IDisposable Listen(Dog dog) => dog.AddBarkListener(new Rex());
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  /** The refusal's hint below, followed: an inherited `val` redeclared on the listener binds. */
  @Test
  fun `an inherited listener val redeclared on the listener binds`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.lvredeclare

      interface Named { val name: String }

      interface BarkListener : Named {
        override val name: String
        fun onBark(volume: Int)
      }

      $dog
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    assertContains(result.generated, "override val name: String")
    assertTrue(
      result.kspWarnings.none { warning -> warning.contains("Dog.addBarkListener") },
      "kspWarnings=${result.kspWarnings}",
    )
    Tier1CSharpCompile.assertCompiles(result, "", allowUnsafe = true)
  }

  /** A sealed arm's pair goes through the same two builders, so it binds the same way. */
  @Test
  fun `a listener val binds on a sealed arm's pair`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.lvarm

      interface BarkListener {
        val name: String
        fun onBark(volume: Int)
      }

      sealed class Shelter {
        class Kennel : Shelter() {
          private val listeners = mutableListOf<BarkListener>()
          fun addBarkListener(l: BarkListener) { listeners.add(l) }
          fun removeBarkListener(l: BarkListener) { listeners.remove(l) }
        }
        class Empty : Shelter()
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    assertContains(result.generated, "override val name: String")
    Tier1CSharpCompile.assertCompiles(result, "", allowUnsafe = true)
  }

  private fun assertRefused(
    pkg: String,
    listener: String,
    property: String,
    kind: ForwardDiagnosticKind,
    extra: String = "",
    supertype: String = "",
  ) {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.$pkg

      $extra

      interface BarkListener$supertype {
        $listener
        fun onBark(volume: Int)
      }

      $dog
      """.trimIndent(),
    )
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    assertFalse(
      result.generated.contains("_dog_addBarkListener") ||
          result.generated.contains("_dog_removeBarkListener"),
      "no Kotlin subscription export; generated=${result.generated}",
    )
    assertFalse(
      result.generatedCSharp.withoutDocComments().contains("AddBarkListener("),
      "no C# subscription method; cs=${result.generatedCSharp}",
    )
    assertContains(result.generated, "_dog_size")
    listOf("addBarkListener", "removeBarkListener").forEach { pairMember ->
      assertTrue(
        result.kspWarnings.any { warning ->
          warning.contains(kind.name) &&
              warning.contains("Dog.$pairMember") &&
              warning.contains("`addBarkListener` / `removeBarkListener`") &&
              warning.contains(property)
        },
        "expected $pairMember to be named with $property; kspWarnings=${result.kspWarnings}",
      )
    }
    Tier1CSharpCompile.assertCompiles(result, "", allowUnsafe = true)
  }

  @Test
  fun `a listener var refuses the pair by name`() {
    assertRefused(
      "lvvar",
      "var name: String",
      "`BarkListener.var name: String` is a `var`",
      ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT,
    )
  }

  @Test
  fun `a listener val of a type with no getter slot refuses the pair by name`() {
    assertRefused(
      "lvlist",
      "val tags: List<String>",
      "`BarkListener.val tags: List<String>`",
      ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN,
    )
  }

  @Test
  fun `a nullable Int listener val refuses the pair by name`() {
    assertRefused(
      "lvnint",
      "val volume: Int?",
      "`BarkListener.val volume: Int?`",
      ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN,
    )
  }

  @Test
  fun `an inherited listener val refuses the pair by name`() {
    assertRefused(
      "lvinherit",
      "",
      "`BarkListener.val name: String` is inherited from `Named`",
      ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT,
      extra = "interface Named { val name: String }",
      supertype = " : Named",
    )
  }
}
