package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-133: every public nested `class`, `object`, `interface` and `enum class` under a supported
 * owner is declared as a real C# nested type, at any depth, and its C entry points carry the whole
 * enclosing chain as a prefix (`owner_nested_create`, `owner_middle_inner_create`).
 *
 * This is the Tier 1 counterpart of `NestedTypesTests.cs`: the consumer test can only see the
 * compiled shape, while the two things a nesting implementation is most likely to get wrong are
 * only visible here — *where* the declaration is emitted in the generated C# (nested in its owner's
 * block, not beside it) and *which* C symbol each export takes (the chain, not the bare simple
 * name, which ADR-117 would otherwise turn into an entry-point collision between `Outer.Nested` and
 * a top-level `Nested`).
 *
 * ADR-134 moves three of ADR-133's deferred owners into the admitted set ([admittedSource]): an
 * `interface` owner, a sealed base owner (a sealed class **or** an ADR-112 eligible sealed
 * interface), a sealed *arm* owner, and the nested `value class` candidate.
 *
 * [deferredSource] is the other half and is not a copy of the old skip test: ADR-134 keeps
 * `SKIPPED_NESTED_DECLARATION` permanently for a generic owner and an `enum class` owner, and
 * ADR-141 keeps it for an `inner class` **owner** (inner-of-inner), so the named skip has to
 * survive for those and only those. A fix that declares everything nested passes every presence
 * cell above and fails here.
 *
 * [innerSource] is ADR-141's own half: an `inner class` is a declared C# nested type whose
 * constructor takes the outer instance first.
 *
 * Oreo supervises from the top perch; Mylo runs the registry two levels down.
 */
class Tier1NestedTypesTest {

  private val source: String = """
    package tier1.nestedtypes

    class Owner(val name: String) {
      class Nested(val height: Int) {
        fun describe(): String = "nested@" + height
      }

      object Defaults {
        fun capacity(): Int = 12
      }

      enum class Kind(val label: String) {
        INDOOR("indoor"),
        OUTDOOR("outdoor"),
      }

      interface Listener {
        fun onEvent(): String
      }

      class Middle {
        class Inner(val depth: Int) {
          fun describe(): String = "inner@" + depth
        }
      }

      fun makeNested(height: Int): Nested = Nested(height)
      fun heightOf(nested: Nested): Int = nested.height
      val habitat: Kind = Kind.OUTDOOR
      fun rename(kind: Kind): String = name + "/" + kind.label
      fun announce(listener: Listener): String = listener.onEvent()
      fun inner(depth: Int): Middle.Inner = Middle.Inner(depth)
      val label: String = "owner"
    }

    object Registry {
      class Entry(val id: Int) {
        fun describe(): String = "entry#" + id
      }

      fun lookup(id: Int): Entry = Entry(id)
    }

    class Solo(val tag: String) {
      fun describe(): String = tag
    }
  """.trimIndent()

  private val deferredSource: String = """
    package tier1.nesteddeferred

    class Box<T>(val item: T) {
      class Lid(val tight: Boolean)

      @JvmInline
      value class Seal(val stamped: Boolean)
    }

    enum class Season {
      WINTER,
      SUMMER,
      ;

      class Almanac(val year: Int)

      @JvmInline
      value class Stamp(val code: Int)
    }

    class Host(val name: String) {
      // ADR-141: `Guest` itself is declared now (the candidate arm is gone); `Deep` is the
      // inner-of-inner the OWNER arm still defers, and the only shape that can reach it -- Kotlin
      // forbids a non-inner class inside an inner class (NESTED_CLASS_NOT_ALLOWED).
      inner class Guest(val visits: Int) {
        inner class Deep(val depth: Int)
      }
    }

    class Reader(val name: String) {
      fun sealOf(): Box.Seal = Box.Seal(true)
      fun codeOf(stamp: Season.Stamp): Int = stamp.code
    }
  """.trimIndent()

  /**
   * ADR-141: `Guest` is the primitive-only inner constructor, which is the silent-loss shape -- a
   * receiver that reaches the public parameter list but not the projection's input list leaves the
   * constructor on the trivial path and hands `Native_Create` a `Host` where an `IntPtr` slot is.
   * `Tag` is the converted-parameter inner beside an ADR-091 trailing default, so its truncated
   * overload has to keep the outer and nothing else.
   */
  private val innerSource: String = """
    package tier1.nestedinner

    class Host(val name: String) {
      inner class Guest(val visits: Int) {
        fun greeting(): String = this@Host.name + " welcomes guest #" + visits
      }

      inner class Tag(val text: String = "plain") {
        fun label(): String = this@Host.name + "/" + text
      }

      fun guestAt(visits: Int): Guest = Guest(visits)
      fun visitsOf(guest: Guest): Int = guest.visits
    }
  """.trimIndent()

  /**
   * ADR-134's admitted owner kinds, one declaration per cell, each with a member returning it.
   *
   * `Cage` is the ADR-133 reversal: an `interface` owner declares its child inside
   * `public interface ICage`, and the `I` attaches to every interface segment of the chain.
   * `Signal` carries both sealed owners (the base, beside the arms, and the arm `On`). `Pulse` is
   * the gate-order cell: an ADR-112 **eligible** sealed interface renders as `public abstract class
   * Pulse`, so its child belongs there and nowhere else — the owner walk tests `INTERFACE` before
   * it tests sealed, and if the interface arm claims `Pulse` its child lands in a `CirInterface`
   * slot the sealed renderer never reads and is lost with no diagnostic at all. `Crate.Weight` is
   * the nested `value class`.
   *
   * Member names are `barAt`/`detailOf`/`traceOf`/`weightOf`, not `bar`/`detail`/`trace`/`weight`:
   * the latter PascalCase onto their own nested type's name, which is CS0102 and is pinned by the
   * owner-scope collision cell below, so it would make every cell here red for the wrong reason.
   */
  private val admittedSource: String = """
    package tier1.nestedowners

    interface Cage {
      class Bar(val gauge: Int)
      fun barAt(): Bar
    }

    class WireCage(val gauge: Int) : Cage {
      override fun barAt(): Cage.Bar = Cage.Bar(gauge)
    }

    sealed class Signal {
      class Detail(val text: String)

      data class On(val level: Int) : Signal() {
        class Trace(val at: Int)
        fun traceOf(): Trace = Trace(level)
      }

      data object Off : Signal()

      fun detailOf(): Detail = Detail("signal")
    }

    sealed interface Pulse {
      class X(val beats: Int)

      data class Beat(val bpm: Int) : Pulse {
        fun xOf(): X = X(bpm)
      }

      data object Flat : Pulse
    }

    class Crate(val id: String) {
      // `@JvmInline` is a requirement of this JVM harness only (Kotlin/Native takes a bare
      // `value class`, as `test-library`'s `CatId` and `Hamper.Weight` do); `Modifier.VALUE` is
      // what the processor reads either way.
      @JvmInline
      value class Weight(val grams: Int) {
        fun isHeavy(): Boolean = grams > 1000
      }

      fun weightOf(): Weight = Weight(id.length)
      fun gramsOf(weight: Weight): Int = weight.grams
    }
  """.trimIndent()

  /** Returns the body of the `public class|static class|interface $name` block, brace-matched. */
  private fun blockBody(csharp: String, header: String): String {
    val start: Int = csharp.indexOf(header)
    require(start >= 0) { "expected `$header` in the generated C#; csharp=$csharp" }
    val open: Int = csharp.indexOf('{', start)
    require(open >= 0) { "expected a body after `$header`" }
    var depth = 0
    var index = open
    while (index < csharp.length) {
      when (csharp[index]) {
        '{' -> depth++
        '}' -> {
          depth--
          if (depth == 0) return csharp.substring(open + 1, index)
        }
      }
      index++
    }
    error("unbalanced braces after `$header`")
  }

  @Test
  fun `every nested kind is declared inside its owner's block`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val csharp: String = result.generatedCSharp
    val owner: String = blockBody(csharp, "public class Owner")

    listOf(
      "public class Nested",
      "public static class Defaults",
      "public enum Kind",
      "public interface IListener",
      "public class Middle",
    ).forEach { declaration ->
      assertContains(
        owner,
        declaration,
        message = "expected `$declaration` inside the owner's block; owner block=$owner",
      )
    }
    // Depth 2: Inner lives in Middle, which lives in Owner.
    assertContains(blockBody(owner, "public class Middle"), "public class Inner")
    // An `object` owner carries nested declarations too, through the CirObject path.
    assertContains(blockBody(csharp, "public static class Registry"), "public class Entry")
  }

  @Test
  fun `no nested declaration is also emitted at namespace level`() {
    val result = Tier1Harness.run(source)

    val csharp: String = result.generatedCSharp
    // Namespace-level declarations are indented exactly four spaces inside `namespace { }`; a
    // nested one is deeper. A flattened twin (the pre-2026-09-07 route, CS0426 against every
    // reference) or a second declaration from another walk (CS0101) shows up as a four-space hit.
    listOf("Nested", "Defaults", "Kind", "IListener", "Middle", "Inner", "Entry").forEach { name ->
      assertFalse(
        Regex("""^ {4}public (?:sealed )?(?:static )?(?:class|enum|interface) $name\b""", RegexOption.MULTILINE)
          .containsMatchIn(csharp),
        "expected no namespace-level declaration of $name; csharp=" +
            "${csharp.lines().filter { it.contains(name) }}",
      )
    }
  }

  @Test
  fun `the enum's extension class stays at namespace level, named for the chain`() {
    val result = Tier1Harness.run(source)

    val csharp: String = result.generatedCSharp
    // CS1109: extension methods cannot be declared in a nested class, so `Kind`'s extensions are
    // the top-level `OwnerKindExtensions`. The name carries the chain so two owners' `Kind`s do
    // not collide at namespace level.
    assertContains(csharp, "public static partial class OwnerKindExtensions")
    assertFalse(
      blockBody(csharp, "public class Owner").contains("Extensions"),
      "expected no extension class inside the owner (CS1109); csharp=$csharp",
    )
  }

  @Test
  fun `every export prefix is the enclosing chain, and top-level prefixes are unchanged`() {
    val result = Tier1Harness.run(source)

    val kotlin: String = result.generated
    listOf(
      "@CName(\"owner_nested_create\")",
      "@CName(\"owner_nested_get_height\")",
      "@CName(\"owner_defaults_capacity\")",
      "@CName(\"owner_middle_inner_create\")",
      "@CName(\"registry_entry_create\")",
      // The owner's own members keep their single-segment prefix.
      "@CName(\"owner_makeNested\")",
      "@CName(\"owner_heightOf\")",
    ).forEach { export ->
      assertContains(kotlin, export, message = "expected $export; generated=$kotlin")
    }
    // ADR-133's "no existing entry point changes" claim: a top-level declaration has a one-element
    // chain, so its prefix is exactly what it was before the feature.
    assertContains(kotlin, "@CName(\"solo_create\")")
    assertContains(kotlin, "@CName(\"solo_describe\")")
    assertFalse(
      kotlin.contains("@CName(\"nested_create\")") || kotlin.contains("@CName(\"entry_create\")"),
      "expected no unchained entry point (ADR-117 collision risk); generated=$kotlin",
    )
  }

  @Test
  fun `members typed with a nested declaration bind instead of skipping`() {
    val result = Tier1Harness.run(source)

    listOf(
      "export_owner_makeNested",
      "export_owner_heightOf",
      "export_owner_get_habitat",
      "export_owner_rename",
      "export_owner_inner",
      "export_owner_get_label",
    ).forEach { export ->
      assertContains(result.generated, export, message = "expected $export to bind; generated=${result.generated}")
    }
    assertFalse(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_NESTED_DECLARATION.name) &&
            it.contains("tier1.nestedtypes")
      },
      "expected no nested-declaration skip for a supported owner; warnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `a deferred owner shape still skips named, and is spelled nowhere`() {
    val result = Tier1Harness.run(deferredSource, fileName = "Deferred.kt")

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val warnings: List<String> = result.kspWarnings
      .filter { it.contains(ForwardDiagnosticKind.SKIPPED_NESTED_DECLARATION.name) }
    listOf(
      // generic owner: `Box<T>.Lid` is a generic nested type in C#, one per `T`
      "tier1.nesteddeferred.Box.Lid",
      // ADR-141: an `inner class` OWNER is still deferred (inner-of-inner), though the inner
      // class itself is declared now.
      "tier1.nesteddeferred.Host.Guest.Deep",
    ).forEach { declaration ->
      assertTrue(
        warnings.any { it.contains(declaration) },
        "expected $declaration to still skip named; warnings=$warnings",
      )
    }
    listOf("Lid", "Deep").forEach { name ->
      assertFalse(
        result.generatedCSharp.contains(name),
        "expected no declaration of, or dangling reference to, $name; csharp=" +
            "${result.generatedCSharp.lines().filter { it.contains(name) }}",
      )
    }
  }

  @Test
  fun `an inner class is declared, and its constructor takes the outer instance first`() {
    val result = Tier1Harness.run(innerSource, fileName = "Inner.kt")

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertFalse(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_NESTED_DECLARATION.name) &&
            it.contains("tier1.nestedinner.Host.Guest")
      },
      "expected no nested-declaration skip for an inner class; warnings=${result.kspWarnings}",
    )
    assertContains(result.generated, "@CName(\"host_guest_create\")")
    // The outer is parameter zero on the wire, borrowed, and the Kotlin call is receiver-qualified.
    assertContains(
      result.generated,
      """
      public fun export_host_guest_create(
        outer: COpaquePointer,
        visits: Int,
        errorOut: COpaquePointer?,
      ): COpaquePointer?
      """.trimIndent(),
    )
    assertContains(
      result.generated,
      "outer.asStableRef<tier1.nestedinner.Host>().get().Guest(visits)",
    )
    // The C# half of the same plan: the nested declaration, the outer first and named `outer`, and
    // the handle lowering that the trivial constructor path would have skipped (CS1503).
    assertContains(result.generatedCSharp, "public Guest(Host outer, int visits)")
    assertContains(
      result.generatedCSharp,
      "Native_Create(outer._handle, visits, out IntPtr error)",
    )
    assertContains(
      result.generatedCSharp,
      "private static extern IntPtr Native_Create(IntPtr outer, int visits, out IntPtr error);",
    )
  }

  @Test
  fun `an inner class omitting overload keeps the outer instance`() {
    val result = Tier1Harness.run(innerSource, fileName = "Inner.kt")

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    // ADR-091 truncates the trailing defaulted parameter; the receiver is not a plan parameter, so
    // it survives the truncation and the overload is `(outer)`, never `()`.
    assertContains(
      result.generated,
      "public fun export_host_tag_create_2(outer: COpaquePointer, errorOut: COpaquePointer?):",
    )
    assertContains(
      result.generated,
      "outer.asStableRef<tier1.nestedinner.Host>().get().Tag()",
    )
    assertContains(result.generatedCSharp, "public Tag(Host outer)")
    assertContains(result.generatedCSharp, "public Tag(Host outer, string text)")
  }

  @Test
  fun `a class nested in an enum owner is deferred, and must be named like the others`() {
    val result = Tier1Harness.run(deferredSource, fileName = "Deferred.kt")

    // Its own cell, because it is red for a different reason from the other three: the ADR-064
    // 2026-09-11 amendment made an `enum class` a *candidate* but never an *owner*, so the
    // declaration walk does not descend into one and `Season.Almanac` is skipped SILENTLY today,
    // where `Box.Lid`, `Cage.Bar` and `Host.Guest.Deep` are all named. ADR-133 keeps the enum
    // owner in the deferred set and says the deferred set stays named, so the walk has to descend
    // into an enum owner for the diagnostic even though it never declares anything there. Worth
    // settling in the ADR rather than inheriting the silence.
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_NESTED_DECLARATION.name) &&
            it.contains("tier1.nesteddeferred.Season.Almanac")
      },
      "expected the class nested in an enum owner to skip NAMED, not silently; " +
          "kspWarnings=${result.kspWarnings}",
    )
    assertFalse(
      result.generatedCSharp.contains("Almanac"),
      "expected no declaration of Almanac; csharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Almanac") }}",
    )
  }

  /**
   * The value-class twin of the two cells above, and the one shape that reached the *member*
   * position with no gate at all: `ForwardBridgeTypeClassifier.valueClass()` spelled
   * `nestedCsName()` unconditionally, so `Reader.sealOf()` was emitted as
   * `global::Interop.Box.Seal SealOf()` against a `readonly record struct` nothing declares, and
   * the consumer's `Interop.cs` could not compile (CS0426/CS0234). The declaration-level skip was
   * already correct; only the member was silent.
   */
  @Test
  fun `a value class nested in a deferred owner skips its members named, and is spelled nowhere`() {
    val result = Tier1Harness.run(deferredSource, fileName = "Deferred.kt")

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    listOf(
      "tier1.nesteddeferred.Box.Seal",
      "tier1.nesteddeferred.Season.Stamp",
    ).forEach { declaration ->
      assertTrue(
        result.kspWarnings.any {
          it.contains(ForwardDiagnosticKind.SKIPPED_NESTED_DECLARATION.name) &&
              it.contains(declaration)
        },
        "expected $declaration to skip named at the declaration; warnings=${result.kspWarnings}",
      )
    }
    listOf(
      "tier1.nesteddeferred.Reader.sealOf" to "tier1.nesteddeferred.Box.Seal",
      "tier1.nesteddeferred.Reader.codeOf" to "tier1.nesteddeferred.Season.Stamp",
    ).forEach { (member, declaration) ->
      assertTrue(
        result.kspWarnings.any {
          it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE.name) &&
              it.contains(member) && it.contains("UNDECLARED_VALUE_CLASS") &&
              it.contains(declaration)
        },
        "expected $member to skip named as UNDECLARED_VALUE_CLASS; warnings=${result.kspWarnings}",
      )
    }
    listOf("Seal", "Stamp").forEach { name ->
      assertFalse(
        result.generatedCSharp.contains(name),
        "expected no declaration of, or dangling reference to, $name; csharp=" +
            "${result.generatedCSharp.lines().filter { it.contains(name) }}",
      )
    }
  }

  @Test
  fun `a nested type whose name collides inside its owner is skipped with a named error`() {
    // ADR-133 surface 6: C# forbids a member and a nested type sharing a name in the same declaring
    // type (CS0102), and Kotlin's `class Config` + `val config: Config` PascalCases into exactly
    // that. The rest of this file dodges the collision by naming its members around it; this cell
    // is the one that pins the rule, so an implementation cannot ship a generator that emits
    // uncompilable C# for the most idiomatic Kotlin shape there is.
    val result = Tier1Harness.run(
      """
      package tier1.nestedcollision

      class Owner {
        class Config(val level: Int)

        val config: Config = Config(1)
        val label: String = "owner"
      }
      """.trimIndent(),
      fileName = "Collision.kt",
    )

    assertTrue(
      (result.kspErrors + result.kspWarnings).any {
        it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name) &&
            it.contains("tier1.nestedcollision.Owner.Config")
      },
      "expected the owner-scoped collision to be diagnosed by name; " +
          "kspErrors=${result.kspErrors} kspWarnings=${result.kspWarnings}",
    )
    assertFalse(
      result.generatedCSharp.contains("class Config"),
      "expected the colliding nested type to be skipped, not emitted; csharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Config") }}",
    )
    assertContains(
      result.generated,
      "export_owner_get_label",
      message = "expected the owner's other members to survive; generated=${result.generated}",
    )
  }

  /**
   * ADR-133 surface 6, the CS0542 arm: a nested type named exactly like its owner. C# forbids it
   * (`class Owner { class Owner }` is CS0542 "type names cannot be the same as their enclosing
   * type", verified in a scratch classlib 2026-09-14), Kotlin allows it, and nothing but this cell
   * reaches that arm. A pin, green as written.
   */
  @Test
  fun `a nested type named like its owner is skipped with a named error (CS0542)`() {
    val result = Tier1Harness.run(
      """
      package tier1.ownername

      class Owner(val name: String) {
        class Owner(val level: Int)

        val label: String = "owner"
      }
      """.trimIndent(),
      fileName = "OwnerName.kt",
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      (result.kspErrors + result.kspWarnings).any {
        it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name) &&
            it.contains("tier1.ownername.Owner.Owner")
      },
      "expected the owner-name collision to be diagnosed by name; " +
          "kspErrors=${result.kspErrors} kspWarnings=${result.kspWarnings}",
    )
    // The outer declaration survives: only the child is skipped.
    assertContains(result.generatedCSharp, "public class Owner")
    assertFalse(
      blockBody(result.generatedCSharp, "public class Owner").contains("class Owner"),
      "expected no nested `Owner` inside the owner's block; csharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Owner") }}",
    )
    assertContains(
      result.generated,
      "export_owner_get_label",
      message = "expected the owner's other members to survive; generated=${result.generated}",
    )
  }

  /**
   * The same CS0102 arm as the `Owner.Config` cell above, reached through the two owner kinds
   * ADR-134 admitted: a sealed *base* (`Purr.Detail` beside `fun detail()`) and a sealed *arm*
   * (`Purr.On.Trace` beside `fun trace()`). Both are rendered by different functions from the plain
   * class owner, so neither is covered by that cell. Pins, green as written.
   */
  private val sealedCollisionSource: String = """
    package tier1.sealedcollision

    sealed class Purr {
      class Detail(val text: String)

      data class On(val level: Int) : Purr() {
        class Trace(val at: Int)

        fun trace(): Trace = Trace(level)
      }

      data object Off : Purr()

      fun detail(): Detail = Detail("purr")
    }
  """.trimIndent()

  @Test
  fun `a nested type colliding with a sealed base's member is skipped with a named error`() {
    val result = Tier1Harness.run(sealedCollisionSource, fileName = "SealedCollision.kt")

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      (result.kspErrors + result.kspWarnings).any {
        it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name) &&
            it.contains("tier1.sealedcollision.Purr.Detail")
      },
      "expected the sealed base's owner-scope collision to be diagnosed by name; " +
          "kspErrors=${result.kspErrors} kspWarnings=${result.kspWarnings}",
    )
    assertFalse(
      result.generatedCSharp.contains("class Detail"),
      "expected the colliding nested type to be skipped, not emitted; csharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Detail") }}",
    )
  }

  @Test
  fun `a nested type colliding with a sealed arm's member is skipped with a named error`() {
    val result = Tier1Harness.run(sealedCollisionSource, fileName = "SealedCollision.kt")

    assertTrue(
      (result.kspErrors + result.kspWarnings).any {
        it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name) &&
            it.contains("tier1.sealedcollision.Purr.On.Trace")
      },
      "expected the sealed arm's owner-scope collision to be diagnosed by name; " +
          "kspErrors=${result.kspErrors} kspWarnings=${result.kspWarnings}",
    )
    assertFalse(
      result.generatedCSharp.contains("class Trace"),
      "expected the colliding nested type to be skipped, not emitted; csharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Trace") }}",
    )
  }

  /**
   * The CS0102 arm with a **value class** candidate: `Hamper.Weight` is declared as a nested
   * `readonly record struct`, which shares the one member-name scope with `fun weight()`. A pin:
   * the record struct route reaches the collision check through the same candidate list, and the
   * C# rule does not care that the nested type is a struct.
   */
  @Test
  fun `a nested value class colliding with a member is skipped with a named error`() {
    val result = Tier1Harness.run(
      """
      package tier1.valuecollision

      class Hamper(val id: String) {
        @JvmInline
        value class Weight(val grams: Int)

        fun weight(): Weight = Weight(id.length)
      }
      """.trimIndent(),
      fileName = "ValueCollision.kt",
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      (result.kspErrors + result.kspWarnings).any {
        it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name) &&
            it.contains("tier1.valuecollision.Hamper.Weight")
      },
      "expected the value class's owner-scope collision to be diagnosed by name; " +
          "kspErrors=${result.kspErrors} kspWarnings=${result.kspWarnings}",
    )
    assertFalse(
      result.generatedCSharp.contains("record struct Weight"),
      "expected the colliding record struct to be skipped, not emitted; csharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Weight") }}",
    )
  }

  /**
   * The gap the four pins above flush out: ADR-013 folds a companion's members into the owner's C#
   * class as statics, so `companion object { fun config(): Config }` emits
   * `public static Config Config()` *in the same declaring type* as the nested `public class
   * Config`. That is CS0102 in the consumer, and the check only ever read the owner's own members,
   * so nothing was said. Red before the companion arm was added.
   */
  @Test
  fun `a nested type colliding with a companion member is skipped with a named error`() {
    val result = Tier1Harness.run(
      """
      package tier1.companioncollision

      class Owner {
        class Config(val n: Int)

        val label: String = "owner"

        companion object {
          fun config(): Config = Config(1)
        }
      }
      """.trimIndent(),
      fileName = "CompanionCollision.kt",
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      (result.kspErrors + result.kspWarnings).any {
        it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name) &&
            it.contains("tier1.companioncollision.Owner.Config")
      },
      "expected the companion-member collision to be diagnosed by name; " +
          "kspErrors=${result.kspErrors} kspWarnings=${result.kspWarnings}",
    )
    assertFalse(
      result.generatedCSharp.contains("class Config"),
      "expected the colliding nested type to be skipped, not emitted; csharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Config") }}",
    )
    assertContains(
      result.generated,
      "export_owner_get_label",
      message = "expected the owner's other members to survive; generated=${result.generated}",
    )
  }

  /**
   * The not-an-error twin of the CS0542 cell, and why that arm cannot compare Kotlin simple names.
   * ADR-134 declares an `interface` owner's child inside `public interface ICage`, so
   * `interface Cage { class Cage }` is `ICage.Cage` in C#: two different names, and
   * `public interface ICage { public class Cage { } }` compiles clean (verified in a scratch
   * classlib 2026-09-14). An eligible sealed interface renders as `public abstract class Beam` with
   * no `I`, so `Beam.Beam` stays a real CS0542 and the arm above stays live.
   */
  @Test
  fun `a nested type named like its interface owner is not a collision`() {
    val result = Tier1Harness.run(
      """
      package tier1.interfaceownername

      interface Cage {
        class Cage(val n: Int)

        fun cageAt(): Cage
      }

      class WireCage(val n: Int) : Cage {
        override fun cageAt(): Cage.Cage = Cage.Cage(n)
      }
      """.trimIndent(),
      fileName = "InterfaceOwnerName.kt",
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertFalse(
      (result.kspErrors + result.kspWarnings).any {
        it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name) &&
            it.contains("tier1.interfaceownername")
      },
      "expected `ICage.Cage` to be legal C#, not a collision; " +
          "kspErrors=${result.kspErrors} kspWarnings=${result.kspWarnings}",
    )
    assertContains(
      blockBody(result.generatedCSharp, "public interface ICage"),
      "public class Cage",
      message = "expected the nested Cage inside the ICage block; csharp=${result.generatedCSharp}",
    )
  }

  /**
   * ADR-133's object-position gate. A Kotlin `object` is declared in C# as a *static* class
   * wherever it lives, and C# forbids a static type at a parameter or return position (CS0722), so
   * a member typed with one is skipped -- before this ADR the nested gate hid the nested case and a
   * top-level object return emitted uncompilable C#.
   *
   * The cell pins the *message*, not just the absence: the skip used to reach
   * `NugetDiagnostics.json` as the generic "its UNSUPPORTED type combination is not supported",
   * which names neither the object nor the C# rule the author has to work around.
   */
  @Test
  fun `a member typed with an object is skipped with a reason naming the object`() {
    val result = Tier1Harness.run(
      """
      package tier1.objectposition

      class Owner {
        object Marker

        fun single(): Marker = Marker
        fun takes(marker: Marker): String = "x"
        val label: String = "owner"
      }

      object TopLevel

      class Plain {
        fun top(): TopLevel = TopLevel
        val tag: String = "plain"
      }
      """.trimIndent(),
      fileName = "ObjectPosition.kt",
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    listOf(
      "tier1.objectposition.Owner.single" to "tier1.objectposition.Owner.Marker",
      "tier1.objectposition.Plain.top" to "tier1.objectposition.TopLevel",
    ).forEach { (member, objectName) ->
      assertTrue(
        result.kspWarnings.any {
          it.contains(member) && it.contains(objectName) && it.contains("CS0722")
        },
        "expected the drop of $member to name $objectName and the C# rule; " +
            "kspWarnings=${result.kspWarnings}",
      )
    }
    assertFalse(
      result.kspWarnings.any {
        it.contains("tier1.objectposition.Owner.single") &&
            it.contains("UNSUPPORTED type combination")
      },
      "expected the named reason, not the generic combination sentence; " +
          "kspWarnings=${result.kspWarnings}",
    )
    // The object itself is still declared as a nested static class, and the controls still bind.
    assertContains(result.generatedCSharp, "public static class Marker")
    assertContains(result.generated, "export_owner_get_label")
    assertContains(result.generated, "export_plain_get_tag")
  }

  /**
   * ADR-133 amendment, single receiver: an extension whose **receiver is a nested type** binds
   * under the enclosing chain, like every member of that type already does.
   *
   * This is the discriminating cell, because it is red on its own: the member route puts
   * `Nested.describe()` behind `owner_nested_describe`, while an extension on the same receiver
   * takes `receiver.simpleName.lowercase()` and lands behind `nested_summarize` in a class called
   * `NestedExtensions`. Nothing fails today for one owner -- it simply binds under a name that
   * belongs to whichever `Nested` got there first, which is why the collision cell below exists.
   *
   * The C# receiver *type* is already spelled `Owner.Nested` (it goes through the same nested
   * name mapping as any other position), so a fix that only renames the class must keep that
   * spelling: it is asserted here rather than assumed.
   *
   * Oreo takes the high perch; the extension agrees with him.
   */
  @Test
  fun `an extension on a nested receiver binds under the owner chain`() {
    val result = Tier1Harness.run(
      """
      package tier1.nestedextension

      class Owner(val name: String) {
        class Nested(val height: Int) {
          fun describe(): String = "nested@" + height
        }

        fun makeNested(height: Int): Nested = Nested(height)
      }

      fun Owner.Nested.summarize(): String = "nested@" + height + " (ext)"
      val Owner.Nested.isHigh: Boolean get() = height > 5
      """.trimIndent(),
      fileName = "NestedExtension.kt",
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    listOf(
      "@CName(\"owner_nested_summarize\")",
      "@CName(\"owner_nested_get_isHigh\")",
      // The control: the member route on the same receiver, which already chains.
      "@CName(\"owner_nested_describe\")",
    ).forEach { export ->
      assertContains(kotlin, export, message = "expected $export; generated=$kotlin")
    }
    assertFalse(
      kotlin.contains("@CName(\"nested_summarize\")") ||
          kotlin.contains("@CName(\"nested_get_isHigh\")"),
      "expected no unchained extension entry point (ADR-117 collision risk); generated=$kotlin",
    )

    val csharp: String = result.generatedCSharp
    // CS1109 forbids nesting an extension class, so the chain travels into the *name*, exactly as
    // ADR-133 already does for a nested enum's `OwnerKindExtensions`.
    assertContains(csharp, "public static partial class OwnerNestedExtensions")
    assertFalse(
      csharp.contains("class NestedExtensions"),
      "expected no bare-simple-name extension class; csharp=" +
          "${csharp.lines().filter { it.contains("Extensions") }}",
    )
    listOf(
      Regex("""Summarize\(this global::[\w.]*Owner\.Nested receiver\)"""),
      // ADR-013 spells an extension property as `Get{Name}`.
      Regex("""GetIsHigh\(this global::[\w.]*Owner\.Nested receiver\)"""),
    ).forEach { signature ->
      assertTrue(
        signature.containsMatchIn(csharp),
        "expected a signature matching $signature; csharp=" +
            "${csharp.lines().filter { it.contains("Summarize") || it.contains("IsHigh") }}",
      )
    }
  }

  /**
   * ADR-133 amendment, two owners: the reason the unchained name is a bug and not a naming taste.
   *
   * `Coop.Inner` and `Roost.Inner` are distinct C# types, and their members already export
   * distinctly (`coop_inner_get_depth` / `roost_inner_get_depth`). Their *extensions* both derive
   * `inner_describe` today.
   *
   * Measured, 2026-09-13, and **not** what this cell was written to expect: the round does not
   * fail with `ERROR_C_ENTRY_POINT_COLLISION`. The duplicate is absorbed silently by the numbering
   * suffix, so the two extensions ship as `@CName("inner_describe")` and
   * `@CName("inner_describe_2")` -- which of the two owners gets the unsuffixed symbol is not
   * pinned here (presumably visit order), i.e. the published ABI of an untouched declaration can
   * move when an unrelated type is added elsewhere. That is a stronger argument for chaining than
   * the predicted hard error was, and it is why the collision assertion below is kept even though
   * it is green today:
   * after the fix it must *stay* green for the right reason (no duplicate to absorb), not because
   * the suffix hid one.
   *
   * After the chain is applied each takes its own symbol and its own `{Chain}Extensions` class.
   *
   * Functions only, no extension property: two same-package nested `Inner`s sharing an extension
   * *property* name trip a different guard (the ADR-074 duplicate-symbol `require` in the property
   * catalog), which would make this cell red for a second reason and hide the first.
   *
   * Mylo's coop and Oreo's roost each get their own `describe`.
   */
  @Test
  fun `two owners' nested receivers take distinct extension symbols and classes`() {
    val result = Tier1Harness.run(
      """
      package tier1.nestedextcollision

      class Coop(val name: String) {
        class Inner(val depth: Int)
      }

      class Roost(val name: String) {
        class Inner(val depth: Int)
      }

      fun Coop.Inner.describe(): String = "coop@" + depth
      fun Roost.Inner.describe(): String = "roost@" + depth
      """.trimIndent(),
      fileName = "NestedExtensionCollision.kt",
    )

    assertFalse(
      result.kspErrors.any {
        it.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name)
      },
      "two nested receivers under different owners must not claim one C entry point; " +
          "kspErrors=${result.kspErrors}",
    )
    val kotlin: String = result.generated
    listOf(
      "@CName(\"coop_inner_describe\")",
      "@CName(\"roost_inner_describe\")",
    ).forEach { export ->
      assertContains(kotlin, export, message = "expected $export; generated=$kotlin")
    }

    val csharp: String = result.generatedCSharp
    assertContains(csharp, "public static partial class CoopInnerExtensions")
    assertContains(csharp, "public static partial class RoostInnerExtensions")
    assertFalse(
      csharp.contains("class InnerExtensions"),
      "expected no shared bare-simple-name extension class; csharp=" +
          "${csharp.lines().filter { it.contains("Extensions") }}",
    )
  }

  /**
   * ADR-040 x ADR-019 x ADR-084, all reachable from one fixture: an **interface** returned on the
   * legacy suspend route, the same interface as a `Flow` element, a second nested interface with
   * the same simple name under a different owner, and a generic bound on a nested interface.
   *
   * Its own source string, not the shared [source]: these cells need coroutines and a second
   * `Keeper`, and appending them to the fixture every other cell brace-matches through would make
   * five unrelated tests move for reasons that have nothing to do with them.
   *
   * Oreo's keeper arrives later; Mylo's keeps the registry.
   */
  private val asyncSource: String = """
    package tier1.nestedasync

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flowOf

    class Owner(val name: String) {
      interface Keeper {
        fun greet(): String
      }

      fun greetVia(keeper: Keeper): String = keeper.greet()
      fun currentKeeper(): Keeper = object : Keeper {
        override fun greet(): String = "hi from " + name
      }
      suspend fun currentKeeperLater(): Keeper = currentKeeper()
      fun keepers(): Flow<Keeper> = flowOf(currentKeeper(), currentKeeper())
      val label: String = "owner"
    }

    object Registry {
      interface Keeper {
        fun greet(): String
      }

      fun greetVia(keeper: Keeper): String = keeper.greet()
      fun currentKeeper(): Keeper = object : Keeper {
        override fun greet(): String = "registry keeper"
      }
      fun label(): String = "registry"
    }

    interface Pet {
      fun speak(): String
    }

    fun strayPet(): Pet = object : Pet {
      override fun speak(): String = "Mrrp?"
    }

    suspend fun strayPetLater(): Pet = strayPet()

    class Aviary<T : Owner.Keeper>(val item: T) {
      fun greetItem(): String = item.greet()
    }
  """.trimIndent()

  @Test
  fun `a suspend function returning an interface is typed with the interface, not the wrapper`() {
    val result = Tier1Harness.run(
      asyncSource,
      fileName = "Async.kt",
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val csharp: String = result.generatedCSharp
    // ADR-040: a consumer never sees the ADR-040 backing wrapper at a declared position. The
    // legacy suspend route spells its completion result with `nestedCsName()`, which for an
    // interface is exactly that wrapper (`Task<Owner.Keeper>` + `new Owner.Keeper(resultPtr)`),
    // and spells it bare, without `global::`.
    assertContains(
      csharp,
      "public Task<global::Interop.Owner.IKeeper> CurrentKeeperLaterAsync(",
      message = "expected the interface spelling on the member suspend route; csharp=" +
          "${csharp.lines().filter { it.contains("CurrentKeeperLater") }}",
    )
    // The top-level route (CirFunctionTranslator) has the same defect, and a top-level interface
    // is where ADR-040's own `Task<IPet>` example lives, so an owner-chain-only fix is not enough.
    assertContains(
      csharp,
      "public static Task<global::Interop.IPet> StrayPetLaterAsync(",
      message = "expected the interface spelling on the top-level suspend route; csharp=" +
          "${csharp.lines().filter { it.contains("StrayPetLater") }}",
    )
    // The completion reads the handle through the wrapper -- that part is correct, it is the
    // declared type that must be the interface.
    val wrapperTypedTaskLines: List<String> = csharp.lines()
      .filter { it.contains("Task<") && it.contains("Keeper") || it.contains("Task<Pet>") }
    assertFalse(
      csharp.contains("Task<global::Interop.Owner.Keeper>") ||
          csharp.contains("Task<Owner.Keeper>") ||
          csharp.contains("Task<Pet>"),
      "expected no wrapper-typed Task; csharp=$wrapperTypedTaskLines",
    )
  }

  @Test
  fun `a Flow whose element is an interface is typed with the interface`() {
    val result = Tier1Harness.run(
      asyncSource,
      fileName = "Async.kt",
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    val csharp: String = result.generatedCSharp
    assertContains(
      csharp,
      "KotlinFlow<global::Interop.Owner.IKeeper> Keepers(",
      message = "expected the interface element spelling; csharp=" +
          "${csharp.lines().filter { it.contains("Keepers") }} warnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `each owner's interface bridge state is named for its chain`() {
    val result = Tier1Harness.run(
      asyncSource,
      fileName = "Async.kt",
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    val csharp: String = result.generatedCSharp
    // ADR-084 names the state class from the simple name alone and renders every one of them into
    // the ROOT namespace's CirBridgeHelper, so `Owner.Keeper` and `Registry.Keeper` both emit
    // `KeeperBridgeState` (CS0101) plus two `keeperImpl` pattern variables in one block (CS0128).
    // Both are at a parameter position, so both plans are real.
    listOf("OwnerKeeperBridgeState", "RegistryKeeperBridgeState").forEach { state ->
      assertContains(
        csharp,
        "internal sealed class $state : NugetBridgeState",
        message = "expected $state; csharp=${csharp.lines().filter { it.contains("BridgeState") }}",
      )
    }
    assertFalse(
      csharp.contains("class KeeperBridgeState"),
      "expected no bare-simple-name bridge state (CS0101 between the two owners); csharp=" +
          "${csharp.lines().filter { it.contains("BridgeState") }}",
    )
    // The pattern variable in `HandleFor` is derived from the same name, so two bare `keeperImpl`
    // declarations land in one block (CS0128). A top-level interface keeps its bare spelling:
    // Tier1InterfaceBridgeFactoryTest pins `PetBridgeState` / `petImpl` and must stay green.
    assertFalse(
      Regex("""\bkeeperImpl\b""").findAll(csharp).count() > 0,
      "expected no bare `keeperImpl` pattern variable (CS0128, see comment above); csharp=" +
          "${csharp.lines().filter { it.contains("Impl") }}",
    )
    assertContains(csharp, "internal sealed class PetBridgeState : NugetBridgeState")
  }

  @Test
  fun `a generic bound on a nested interface carries the owner chain`() {
    val result = Tier1Harness.run(
      asyncSource,
      fileName = "Async.kt",
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    val csharp: String = result.generatedCSharp
    // The legacy bound spellers build "I" + simpleName, which for a nested interface is a bare
    // `IKeeper` at namespace scope: CS0246, nothing in that scope is called that.
    assertContains(
      csharp,
      "where T : global::Interop.Owner.IKeeper",
      message = "expected the bound to carry the chain; csharp=" +
          "${csharp.lines().filter { it.contains("where T") }}",
    )
  }

  // --- ADR-134: the owner kinds ADR-133 deferred and this ADR admits ---

  @Test
  fun `an interface owner declares its nested type inside the I-prefixed interface block`() {
    val result = Tier1Harness.run(admittedSource, fileName = "Owners.kt")

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val csharp: String = result.generatedCSharp
    assertContains(
      blockBody(csharp, "public interface ICage"),
      "public class Bar",
      message = "expected Bar inside the ICage block; csharp=$csharp",
    )
    // ADR-134's one reversal of ADR-133: the `I` is on every interface segment of the chain, so
    // the child is `ICage.Bar`. The child itself is a class and keeps its bare name.
    assertFalse(
      csharp.contains("IBar"),
      "expected the child of an interface to keep its own name; csharp=" +
          "${csharp.lines().filter { it.contains("Bar") }}",
    )
  }

  @Test
  fun `a sealed base and a sealed arm each declare their nested types in their own block`() {
    val result = Tier1Harness.run(admittedSource, fileName = "Owners.kt")

    val csharp: String = result.generatedCSharp
    val signal: String = blockBody(csharp, "public abstract class Signal")
    assertContains(
      signal,
      "public class Detail",
      message = "expected Detail beside the arms in the sealed base's block; signal=$signal",
    )
    // The arm's block is rendered by a different function from the base's, so a base-only
    // implementation passes the assertion above and loses Trace.
    assertContains(
      blockBody(signal, "class On"),
      "public class Trace",
      message = "expected Trace inside the arm's block; signal=$signal",
    )
  }

  @Test
  fun `an eligible sealed interface owns its nested type as the abstract class, never losing it`() {
    val result = Tier1Harness.run(admittedSource, fileName = "Owners.kt")

    val csharp: String = result.generatedCSharp
    // The gate-order cell, and the one failure mode in ADR-134 that is SILENT if wrong: the owner
    // walk tests `INTERFACE` before it tests sealed, and an ADR-112 eligible sealed interface is
    // collected as a sealed base and rendered `public abstract class Pulse`. If the relaxed
    // interface arm claims it, `X` is partitioned into a `CirInterface` slot the sealed renderer
    // never reads: no twin, no CS0101, no diagnostic, just gone.
    assertContains(
      blockBody(csharp, "public abstract class Pulse"),
      "public class X",
      message = "expected X declared under the abstract class the eligible sealed interface " +
          "renders as; csharp=$csharp",
    )
    assertFalse(
      csharp.contains("IPulse"),
      "expected no `IPulse` anywhere (issue #54); csharp=" +
          "${csharp.lines().filter { it.contains("IPulse") }}",
    )
  }

  @Test
  fun `a nested value class is declared as a nested record struct`() {
    val result = Tier1Harness.run(admittedSource, fileName = "Owners.kt")

    assertContains(
      blockBody(result.generatedCSharp, "public class Crate"),
      "public readonly record struct Weight",
      message = "expected the nested value class as a nested record struct; " +
          "csharp=${result.generatedCSharp}",
    )
  }

  @Test
  fun `every admitted owner's nested export prefix is the enclosing chain`() {
    val result = Tier1Harness.run(admittedSource, fileName = "Owners.kt")

    val kotlin: String = result.generated
    listOf(
      // interface owner
      "@CName(\"cage_bar_create\")",
      // sealed base owner
      "@CName(\"signal_detail_create\")",
      // sealed arm owner: base + arm + child, three segments
      "@CName(\"signal_on_trace_create\")",
      // eligible sealed interface owner
      "@CName(\"pulse_x_create\")",
      // nested value class: no handle, but its members carry the chain
      "@CName(\"crate_weight_isHeavy\")",
    ).forEach { export ->
      assertContains(kotlin, export, message = "expected $export; generated=$kotlin")
    }
    assertFalse(
      listOf(
        "\"bar_create\"",
        "\"detail_create\"",
        "\"trace_create\"",
        "\"x_create\"",
        "\"weight_isHeavy\"",
      ).any { kotlin.contains("@CName($it)") },
      "expected no unchained entry point (ADR-117 collision risk); generated=$kotlin",
    )
  }

  @Test
  fun `no nested type of an admitted owner is also emitted at namespace level, and none skips`() {
    val result = Tier1Harness.run(admittedSource, fileName = "Owners.kt")

    val csharp: String = result.generatedCSharp
    listOf("Bar", "Detail", "Trace", "X", "Weight").forEach { name ->
      val namespaceLevelDeclaration = Regex(
        """^ {4}public (?:sealed )?(?:readonly )?(?:record )?(?:static )?""" +
            """(?:class|struct|enum|interface) $name\b""",
        RegexOption.MULTILINE,
      )
      assertFalse(
        namespaceLevelDeclaration.containsMatchIn(csharp),
        "expected no namespace-level twin of $name (CS0101); csharp=" +
            "${csharp.lines().filter { it.contains(name) }}",
      )
    }
    assertFalse(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_NESTED_DECLARATION.name) &&
            it.contains("tier1.nestedowners")
      },
      "expected no nested-declaration skip for an admitted owner; warnings=${result.kspWarnings}",
    )
  }
}
