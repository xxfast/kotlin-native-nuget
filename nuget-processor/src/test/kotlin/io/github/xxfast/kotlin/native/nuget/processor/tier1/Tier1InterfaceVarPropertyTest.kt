package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ROADMAP line 28: a `var` on an exported interface renders `{ get; set; }` on the generated
 * `IFoo` (ADR-113's declaration route used to leave every interface property get-only).
 *
 * - ADR-075 independence on `IFoo`: a refused setter (`Throwable?`, ADR-107) stays `{ get; }`,
 *   named in the build log and in a `<remarks>` on the member, reachable or not.
 * - ADR-147 carve-out: a `var item: T` keeps `T Item { get; }`.
 * - Case D: `class TrainingClicker : Scoreboard(), Tally` overrides a base `open val` and an
 *   interface `var` with one `override var`. The public C# property stays a get-only `override`
 *   (CS0546), and the interface setter is an explicit `ITally.Count` member beside it; otherwise
 *   the widened `ITally` is CS0535 on the class.
 */
class Tier1InterfaceVarPropertyTest {

  private val tally: String = """
    package tier1.ivar

    interface Tally {
      var count: Int
      var label: String
      var toy: Pompom?
      var names: List<String>
      var lastSlip: Throwable?
    }

    class Pompom(val colour: String)

    open class Scoreboard {
      open val count: Int = 0
      open val label: String = "scoreboard"
      open val toy: Pompom? = null
      open val names: List<String> = emptyList()
      open val lastSlip: Throwable? = null
    }

    class TrainingClicker : Scoreboard(), Tally {
      override var count: Int = 0
      override var label: String = "clicker"
      override var toy: Pompom? = null
      override var names: List<String> = listOf("Mylo")
      override var lastSlip: Throwable? = null

      fun asTally(): Tally = this
    }

    class Abacus : Tally {
      override var count: Int = 0
      override var label: String = "abacus"
      override var toy: Pompom? = null
      override var names: List<String> = listOf("Oreo")
      override var lastSlip: Throwable? = null
    }

    interface Holder<T> {
      var item: T
    }

    interface Lonely {
      var err: Throwable?
      var level: Int
    }
  """.trimIndent()

  private val result: Tier1Result by lazy { Tier1Harness.run(tally) }

  private fun block(csharp: String, header: String): String {
    val start: Int = csharp.indexOf(header)
    assertTrue(start >= 0, "expected `$header` in:\n$csharp")
    return csharp.substring(start, csharp.indexOf("\n    }", start))
  }

  @Test
  fun `the generated Kotlin compiles`() {
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
  }

  @Test
  fun `a plannable interface var renders a setter and a refused one stays get-only`() {
    val iface: String = block(result.generatedCSharp, "public interface ITally")
    assertContains(iface, "int Count { get; set; }")
    assertContains(iface, "string Label { get; set; }")
    assertContains(iface, "Pompom? Toy { get; set; }")
    assertContains(iface, "IReadOnlyList<string> Names { get; set; }")
    assertContains(iface, "Exception? LastSlip { get; }")
  }

  @Test
  fun `the type-parameter carve-out stays get-only`() {
    val holder: String = block(result.generatedCSharp, "public interface IHolder<T>")
    assertContains(holder, "T Item { get; }")
    assertFalse(holder.contains("T Item { get; set; }"), holder)
  }

  @Test
  fun `a merely-implemented interface names its refused setter in the log and on the member`() {
    val lonely: String = block(result.generatedCSharp, "public interface ILonely")
    assertContains(lonely, "int Level { get; set; }")
    assertContains(lonely, "Exception? Err { get; }")
    val errLine: Int = lonely.indexOf("Exception? Err { get; }")
    assertContains(lonely.substring(0, errLine), "<remarks>")
    assertEquals(
      1,
      result.kspWarnings.count { it.contains("tier1.ivar.Lonely.err") },
      "expected exactly one warning naming Lonely.err; got: ${result.kspWarnings}",
    )
  }

  @Test
  fun `a reachable interface names its refused setter once and remarks the member`() {
    val iface: String = block(result.generatedCSharp, "public interface ITally")
    val slipLine: Int = iface.indexOf("Exception? LastSlip { get; }")
    assertContains(iface.substring(0, slipLine), "<remarks>")
    assertEquals(
      1,
      result.kspWarnings.count { it.contains("tier1.ivar.Tally.lastSlip") },
      "expected exactly one warning naming Tally.lastSlip; got: ${result.kspWarnings}",
    )
  }

  @Test
  fun `case D keeps the public override get-only and implements the setter explicitly`() {
    val clicker: String = block(result.generatedCSharp, "public class TrainingClicker")
    val members: List<Pair<String, String>> = listOf(
      "int" to "Count",
      "string" to "Label",
      "global::Interop.Pompom?" to "Toy",
      "IReadOnlyList<string>" to "Names",
    )
    members.forEach { (type, name) ->
      assertContains(clicker, "public override $type $name\n")
      assertContains(clicker, "$type ITally.$name\n")
    }
    // The public property carries no setter: only the explicit member's `set` exists per name.
    assertEquals(4, Regex("""\n\s+set\b""").findAll(clicker).count(), clicker)
    // The refused `Throwable?` setter gets no explicit member: `ITally.LastSlip` is get-only.
    assertFalse(clicker.contains("ITally.LastSlip"), clicker)
    // The nullable handle setter marshals the value's handle, the value setter passes `value`.
    assertContains(clicker, "Native_Set_count(_handle, value, out IntPtr error)")
    assertContains(clicker, "Native_Set_toy(")
    // The DllImport the explicit setter calls is emitted.
    assertContains(clicker, "EntryPoint = \"library_tier1_ivar__trainingclicker_set_count\"")
    // And the Kotlin half mints the export the import names.
    assertContains(result.generated, "library_tier1_ivar__trainingclicker_set_count")
    // The case D diagnostic says where the setter went.
    assertTrue(
      result.kspWarnings.any {
        it.contains("tier1.ivar.TrainingClicker.count") && it.contains("ITally.Count")
      },
      "expected the CS0546 skip to name ITally.Count; got: ${result.kspWarnings}",
    )
  }

  @Test
  fun `the ordinary implementer keeps its public setters and no explicit member`() {
    val abacus: String = block(result.generatedCSharp, "public class Abacus")
    assertFalse(abacus.contains("ITally.Count"), abacus)
    assertContains(abacus, "public virtual int Count")
  }

  /**
   * Measured 2026-09-26 (scratch net8.0 classlib, TreatWarningsAsErrors, ImplicitUsings off): the
   * bare base-list spelling was CS0246 across namespaces, `TrainingClicker : Scoreboard, ITally`
   * and `IDerived : IBase, IDisposable` alike, since `Interop.Impl` does not see `Interop.Api`.
   * The base lists and the case D explicit member now share one `global::` spelling.
   */
  @Test
  fun `across namespaces the base lists and the explicit member are qualified`() {
    val cross: Tier1Result = Tier1Harness.run(
      mapOf(
        "Api.kt" to """
          package tier1.ivarx.api

          interface Tally {
            var count: Int
          }

          interface Base {
            fun ping(): Int
          }
        """.trimIndent(),
        "Impl.kt" to """
          package tier1.ivarx.impl

          import tier1.ivarx.api.Base
          import tier1.ivarx.api.Tally

          open class Scoreboard {
            open val count: Int = 0
          }

          class TrainingClicker : Scoreboard(), Tally {
            override var count: Int = 0
          }

          class Plain : Tally {
            override var count: Int = 0
          }

          interface Derived : Base {
            fun pong(): Int
          }

          interface Local : Derived
        """.trimIndent(),
      ),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.ivarx"),
    )
    assertTrue(cross.compiledClean, "expected a clean compile; got: ${cross.compileErrors}")
    val csharp: String = cross.generatedCSharp
    val clicker: String = block(csharp, "public class TrainingClicker")
    assertContains(
      clicker,
      "public class TrainingClicker : Scoreboard, global::Interop.Api.ITally\n",
    )
    assertContains(clicker, "public override int Count\n")
    assertContains(clicker, "int global::Interop.Api.ITally.Count\n")
    assertContains(
      csharp,
      "public class Plain : global::Interop.Api.ITally, IDisposable, INugetHandle",
    )
    assertContains(csharp, "public interface IDerived : global::Interop.Api.IBase, IDisposable")
    // Same namespace stays bare.
    assertContains(csharp, "public interface ILocal : IDerived, IDisposable")
  }

  /**
   * A sealed arm is the case D shape too, but its C# base list names only the sealed base (never
   * `ITally`), so there is no interface to implement explicitly (CS0540 if one were rendered) and
   * no CS0535 either. What the arm needed was the ADR-075 read-only-base guard: the sealed route
   * never passed its base, so `override var count` over `open val count` rendered a public setter,
   * CS0546 (measured in the same scratch compile).
   */
  @Test
  fun `a sealed arm over a read-only base keeps its override get-only`() {
    val sealed: Tier1Result = Tier1Harness.run(
      """
      package tier1.ivarseal

      interface Tally {
        var count: Int
      }

      sealed class Perch {
        open val count: Int = 0

        class Arm : Perch(), Tally {
          override var count: Int = 0
        }
      }
      """.trimIndent(),
    )
    assertTrue(sealed.compiledClean, "expected a clean compile; got: ${sealed.compileErrors}")
    val arm: String = block(sealed.generatedCSharp, "public sealed class Arm")
    assertContains(arm, "public sealed class Arm : Perch\n")
    assertContains(arm, "public override int Count\n")
    assertFalse(Regex("""\n\s+set\b""").containsMatchIn(arm), arm)
    assertFalse(arm.contains("ITally.Count"), arm)
    assertTrue(
      sealed.kspWarnings.any {
        it.contains("tier1.ivarseal.Perch.Arm.count") && it.contains("CS0546")
      },
      "expected the arm's refused setter to be named; got: ${sealed.kspWarnings}",
    )
    assertContains(
      block(sealed.generatedCSharp, "public interface ITally"),
      "int Count { get; set; }",
    )
  }

  @Test
  fun `case D names the declaring super-interface and skips a type-parameter member`() {
    val inherited: Tier1Result = Tier1Harness.run(
      """
      package tier1.ivardi

      interface Base {
        var level: Int
      }

      interface Derived : Base {
        fun ping(): Int
      }

      open class Board {
        open val level: Int = 0
        open val item: String = ""
      }

      class Impl : Board(), Derived {
        override var level: Int = 0
        override fun ping(): Int = level
      }

      interface Holder<T> {
        var item: T
      }

      class Box : Board(), Holder<String> {
        override var item: String = "yarn"
      }
      """.trimIndent(),
    )
    assertTrue(inherited.compiledClean, "expected a clean compile; got: ${inherited.compileErrors}")
    val impl: String = block(inherited.generatedCSharp, "public class Impl")
    assertContains(impl, "public override int Level\n")
    assertContains(impl, "int IBase.Level\n")
    assertFalse(impl.contains("IDerived.Level"), impl)
    // `IHolder<T>.Item` is the ADR-147 get-only carve-out: an explicit `set` would be CS0550.
    val box: String = block(inherited.generatedCSharp, "public class Box")
    assertContains(box, "public override string Item\n")
    assertFalse(box.contains("IHolder<string>.Item"), box)
    assertFalse(box.contains("Native_Set_item"), box)
  }

  @Test
  fun `an inherited var renders its setter on the declaring super-interface only`() {
    val inherited: Tier1Result = Tier1Harness.run(
      """
      package tier1.ivarinh

      interface Base {
        var level: Int
        var err: Throwable?
      }

      interface Derived : Base {
        fun ping(): Int
      }

      class Impl : Derived {
        override var level: Int = 0
        override var err: Throwable? = null
        override fun ping(): Int = level
      }

      fun current(): Derived = Impl()
      """.trimIndent(),
    )
    assertTrue(inherited.compiledClean, "expected a clean compile; got: ${inherited.compileErrors}")
    val base: String = block(inherited.generatedCSharp, "public interface IBase")
    assertContains(base, "int Level { get; set; }")
    assertContains(base, "Exception? Err { get; }")
    val derived: String = block(inherited.generatedCSharp, "public interface IDerived : IBase")
    assertFalse(derived.contains("Level"), derived)
    assertEquals(
      0,
      inherited.kspWarnings.count { it.contains("tier1.ivarinh.Derived.err") },
      "the inherited refusal is named on Base only; got: ${inherited.kspWarnings}",
    )
    assertEquals(
      1,
      inherited.kspWarnings.count { it.contains("tier1.ivarinh.Base.err") },
      "expected exactly one warning naming Base.err; got: ${inherited.kspWarnings}",
    )
  }
}
