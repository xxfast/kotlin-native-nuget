package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ROADMAP Phase 4, "`interface Derived : Base` flattens": an exported interface extending another
 * renders `IDerived : IBase, IDisposable` and declares its own members only. The ADR-040 backing
 * class implements every member (own and inherited); an unexported super is dropped from the base
 * list with `SKIPPED_UNEXPORTED_SUPERTYPE` and its members re-homed onto `IDerived`; a covariant
 * override is a named skip.
 *
 * Structural (Tier 1 never compiles the C#): the runtime half is `InterfaceSuperInterfaceTests.cs`
 * over the `lineage` fixture.
 */
class Tier1InterfaceSuperInterfacesTest {

  private val hidden: String = """
    package tier1hidden

    interface Tagged {
      val tag: String
      fun retag(t: String): String
    }
  """.trimIndent()

  private val fixture: String = """
    package tier1.lineage

    import tier1hidden.Tagged

    interface Named {
      val name: String
      fun greet(): String
    }
    interface Aged { val age: Int }
    interface Pet : Named, Aged { fun feed(food: String): String }
    interface HouseCat : Pet {
      fun purr(times: Int): String
      override fun greet(): String = "Purr, I'm " + name
    }
    interface Holder<T> {
      val size: Int
      fun peek(): T
    }
    interface IntHolder : Holder<Int> { fun shake(): String }
    interface Shy : Tagged { fun hello(): String }

    class KibbleJar(override val size: Int) : Holder<Int> {
      override fun peek(): Int = size - 1
    }

    class Tuxedo(override val name: String, override val age: Int) : HouseCat {
      override fun feed(food: String): String = food
      override fun purr(times: Int): String = "purr"
    }

    fun adoptHouseCat(): HouseCat = Tuxedo("Oreo", 5)
    fun adoptPet(): Pet = Tuxedo("Mylo", 4)
    fun treatTin(): IntHolder = object : IntHolder {
      override val size: Int = 12
      override fun peek(): Int = 7
      override fun shake(): String = "rattle"
    }
    fun adoptShy(): Shy = object : Shy {
      override val tag: String = "t"
      override fun retag(t: String): String = t
      override fun hello(): String = "hi"
    }
    fun letIn(cat: HouseCat): String = cat.name
    fun callOut(named: Named): String = named.greet()
  """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      mapOf("Hidden.kt" to hidden, "Lineage.kt" to fixture),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.lineage"),
    )
  }

  private fun interfaceBlock(csharp: String, header: String): String {
    val start: Int = csharp.indexOf("public interface $header")
    assertTrue(start >= 0, "expected `public interface $header` in:\n$csharp")
    return csharp.substring(start, csharp.indexOf("\n    }", start))
  }

  private fun classBlock(csharp: String, header: String): String {
    val start: Int = csharp.indexOf(header)
    assertTrue(start >= 0, "expected `$header` in:\n$csharp")
    return csharp.substring(start, csharp.indexOf("\n    }", start))
  }

  @Test
  fun `the generated Kotlin compiles`() {
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
  }

  @Test
  fun `multiple supers render in the base list and only own members are declared`() {
    val pet: String = interfaceBlock(result.generatedCSharp, "IPet : INamed, IAged, IDisposable")
    assertContains(pet, "string Feed(string food);")
    assertFalse(pet.contains(" Name "), "IPet must inherit Name, not redeclare it:\n$pet")
    assertFalse(pet.contains(" Age "), "IPet must inherit Age, not redeclare it:\n$pet")
    assertFalse(pet.contains("Greet("), "IPet must inherit Greet, not redeclare it:\n$pet")
  }

  @Test
  fun `a multi-level hierarchy names only its direct super and omits the identical override`() {
    val cat: String = interfaceBlock(result.generatedCSharp, "IHouseCat : IPet, IDisposable")
    assertContains(cat, "string Purr(int times);")
    // `override fun greet()` restates Named.greet: redeclaring it is CS0108 (warnings as errors).
    assertFalse(cat.contains("Greet("), "IHouseCat must not redeclare Greet:\n$cat")
  }

  @Test
  fun `a generic super renders with its type argument and the substituted member is not redeclared`() {
    val holder: String =
      interfaceBlock(result.generatedCSharp, "IIntHolder : IHolder<int>, IDisposable")
    assertContains(holder, "string Shake();")
    assertFalse(holder.contains("Peek("), "IIntHolder must inherit Peek:\n$holder")
    assertFalse(holder.contains("Size"), "IIntHolder must inherit Size:\n$holder")
  }

  @Test
  fun `a class implementing a generic interface names its type argument`() {
    assertContains(result.generatedCSharp, "public class KibbleJar : IHolder<int>, IDisposable")
  }

  @Test
  fun `an unexported super is dropped with SKIPPED_UNEXPORTED_SUPERTYPE and its members re-homed`() {
    val shy: String = interfaceBlock(result.generatedCSharp, "IShy : IDisposable")
    assertContains(shy, "string Tag { get; }")
    assertContains(shy, "string Retag(string t);")
    assertContains(shy, "string Hello();")
    assertFalse(result.generatedCSharp.contains("ITagged"), "no ITagged may be named")
    val warning: String? = result.kspWarnings.firstOrNull { warning ->
      warning.contains(ForwardDiagnosticKind.SKIPPED_UNEXPORTED_SUPERTYPE.name) &&
          warning.contains("Shy : Tagged")
    }
    assertNotNull(warning, "expected the dropped super named; kspWarnings=${result.kspWarnings}")
    assertContains(warning, "declared on IShy directly")
  }

  @Test
  fun `the backing class implements inherited members under the derived prefix`() {
    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"library_housecat_get_name\")")
    assertContains(kotlin, "@CName(\"library_housecat_get_age\")")
    assertContains(kotlin, "@CName(\"library_housecat_greet\")")
    assertContains(kotlin, "@CName(\"library_housecat_feed\")")
    assertContains(kotlin, "@CName(\"library_housecat_purr\")")
    assertContains(kotlin, "@CName(\"library_intholder_peek\")")
    val backing: String =
      classBlock(result.generatedCSharp, "public sealed class HouseCat : IHouseCat")
    assertContains(backing, "public string Name")
    assertContains(backing, "public int Age")
    assertContains(backing, "public string Greet()")
    assertContains(backing, "public string Feed(string food)")
  }

  @Test
  fun `a C# object crossing at a base parameter is bridged by the declared type`() {
    val csharp: String = result.generatedCSharp
    assertContains(csharp, "internal static IntPtr HandleFor(object impl, Type declared)")
    assertContains(csharp, "internal static IntPtr HandleOf<T>(T value, out bool owned)")
    assertContains(csharp, "HandleOf((object)value!, typeof(T))")
  }

  @Test
  fun `the generated Kotlin retains no bare StableRef`() {
    assertFalse(result.generated.contains(" StableRef.create("), "bare StableRef.create found")
  }

  /** Step 2 decision: a covariant override is a named skip, callable through the super. */
  @Test
  fun `a covariant override is left off the derived interface and named`() {
    val covariant = Tier1Harness.run(
      """
      package tier1.covariant

      interface Animal { fun twin(): Animal }
      interface Cat : Animal { override fun twin(): Cat }

      class Tabby : Cat { override fun twin(): Tabby = Tabby() }

      fun adopt(): Cat = Tabby()
      """.trimIndent(),
    )
    assertTrue(covariant.compiledClean, "expected a clean compile; got: ${covariant.compileErrors}")
    val cat: String = interfaceBlock(covariant.generatedCSharp, "ICat : IAnimal, IDisposable")
    assertFalse(cat.contains("Twin("), "ICat must not redeclare the covariant Twin:\n$cat")
    val warning: String? = covariant.kspWarnings.firstOrNull { warning ->
      warning.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_COMBINATION.name) &&
          warning.contains("ICat.Twin")
    }
    assertNotNull(warning, "expected the covariant override named; ${covariant.kspWarnings}")
    // The backing class implements IAnimal.Twin at the super's type, or it is CS0738.
    val backing: String = classBlock(covariant.generatedCSharp, "public sealed class Cat : ICat")
    assertContains(backing, "public global::Interop.IAnimal Twin()")
  }

  /** The inherited overload pair: numbered on the backing class and in the bridge slots. */
  @Test
  fun `an inherited overload gets a numbered export on the backing class`() {
    val overload = Tier1Harness.run(
      """
      package tier1.ifaceinh

      interface Animal { fun speak(): String }
      interface Pet : Animal { fun speak(times: Int): String }

      class Cat : Pet {
        override fun speak(): String = "Meow"
        override fun speak(times: Int): String = "Meow".repeat(times)
      }

      fun adopt(): Pet = Cat()
      fun take(pet: Pet): String = pet.speak(2)
      """.trimIndent(),
    )
    assertTrue(overload.compiledClean, "expected a clean compile; got: ${overload.compileErrors}")
    val kotlin: String = overload.generated
    assertContains(kotlin, "@CName(\"library_tier1_ifaceinh__pet_speak\")")
    assertContains(kotlin, "@CName(\"library_tier1_ifaceinh__pet_speak_2\")")
    val pet: String = interfaceBlock(overload.generatedCSharp, "IPet : IAnimal, IDisposable")
    assertContains(pet, "string Speak(int times);")
    assertFalse(pet.contains("string Speak();"), "IPet must inherit Speak():\n$pet")
  }

  /**
   * Diamond: two unrelated kept supers both declare the member. Inheriting it leaves `m.Twitch()`
   * ambiguous (CS0121) and the ADR-084 bridge's `impl.Twitch()` off `IMoggy` fails to compile, so
   * `IMoggy` redeclares it with `new` -- both the override Kotlin forces (`twitch`, defaulted in
   * both) and the abstract member Kotlin merges without one (`whiskers`).
   */
  @Test
  fun `a member two kept supers both declare is redeclared with new`() {
    val diamond = Tier1Harness.run(
      """
      package tier1.diamond

      interface Whiskered {
        val whiskers: Int
        fun twitch(): String = "twitch"
        fun blink(): Int
      }
      interface Tailed {
        val whiskers: Int
        fun twitch(): String = "swish"
        fun blink(): Int
      }
      interface Moggy : Whiskered, Tailed {
        override fun twitch(): String = "both"
        fun nap(): String
      }
      interface Kitten : Moggy { fun mew(): String }

      fun adopt(): Moggy = object : Moggy {
        override val whiskers: Int = 24
        override fun blink(): Int = 2
        override fun nap(): String = "zzz"
      }
      fun settle(moggy: Moggy): String = moggy.twitch() + moggy.whiskers
      """.trimIndent(),
    )
    assertTrue(
      diamond.compiledClean,
      "expected a clean compile; got: ${diamond.compileErrors}\n${diamond.generated}",
    )
    val csharp: String = diamond.generatedCSharp
    val moggy: String = interfaceBlock(csharp, "IMoggy : IWhiskered, ITailed, IDisposable")
    assertContains(moggy, "new int Whiskers { get; }")
    assertContains(moggy, "new string Twitch();")
    assertContains(moggy, "new int Blink();")
    assertContains(moggy, "string Nap();")
    // One level down the ambiguity is gone: IMoggy's `new` members hide both, so IKitten inherits.
    val kitten: String = interfaceBlock(csharp, "IKitten : IMoggy, IDisposable")
    assertFalse(kitten.contains("Twitch"), "IKitten must inherit Twitch:\n$kitten")
    assertFalse(kitten.contains("Whiskers"), "IKitten must inherit Whiskers:\n$kitten")
    // The backing class implements the one member once (a second would be CS0111).
    val backing: String = classBlock(csharp, "public sealed class Moggy : IMoggy")
    assertTrue(
      backing.split("public string Twitch()").size == 2,
      "expected exactly one Twitch on the backing class:\n$backing",
    )
  }

  /** A declared method named like an inherited property hides it (CS0108): fatal, like CS0102. */
  @Test
  fun `a declared method named like an inherited property fires ERROR_CSHARP_NAME_COLLISION`() {
    val collision = Tier1Harness.run(
      """
      package tier1.inhcollide

      interface Named { val tag: String }
      interface Pet : Named { fun tag(code: Int): String }
      interface Toy { fun label(): String }
      interface Ball : Toy { val label: String }
      """.trimIndent(),
    )
    val errors: List<String> = collision.kspErrors.filter {
      it.contains(ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION.name)
    }
    assertTrue(errors.any { it.contains("IPet.Tag") }, "expected IPet.Tag named; got $errors")
    assertTrue(errors.any { it.contains("IBall.Label") }, "expected IBall.Label named; got $errors")
  }

  /** ADR-113's carve-out renders `T Peek()` on `IHolder<T>`, so no drop may be reported for it. */
  @Test
  fun `a member the type-parameter carve-out restores is not reported as dropped`() {
    assertContains(interfaceBlock(result.generatedCSharp, "IHolder<T> : IDisposable"), "T Peek();")
    val falsePositive: String? = result.kspWarnings.firstOrNull { warning ->
      warning.contains("Holder.peek")
    }
    assertTrue(
      falsePositive == null,
      "expected no drop warning for Holder.peek; got $falsePositive",
    )
  }
}
