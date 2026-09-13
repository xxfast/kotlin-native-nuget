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
 * [deferredSource] is the other half and is not a copy of the old skip test: ADR-133 keeps
 * `SKIPPED_NESTED_DECLARATION` for exactly the owner shapes it defers (generic owner, `enum class`
 * owner, `interface` owner, `inner class`), so the named skip has to survive for those and only
 * those. A fix that declares everything nested passes every presence cell above and fails here.
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
    }

    enum class Season {
      WINTER,
      SUMMER,
      ;

      class Almanac(val year: Int)
    }

    interface Cage {
      class Bar(val gauge: Int)
    }

    class Host(val name: String) {
      inner class Guest(val visits: Int)
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
    assertContains(csharp, "public static class OwnerKindExtensions")
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
      // generic owner: `Box<T>.Lid` is a generic nested type in C#
      "tier1.nesteddeferred.Box.Lid",
      // interface owner: an interface has no C# declaration block to nest into here
      "tier1.nesteddeferred.Cage.Bar",
      // inner class: its constructor needs the outer instance
      "tier1.nesteddeferred.Host.Guest",
    ).forEach { declaration ->
      assertTrue(
        warnings.any { it.contains(declaration) },
        "expected $declaration to still skip named; warnings=$warnings",
      )
    }
    listOf("Lid", "Bar", "Guest").forEach { name ->
      assertFalse(
        result.generatedCSharp.contains(name),
        "expected no declaration of, or dangling reference to, $name; csharp=" +
            "${result.generatedCSharp.lines().filter { it.contains(name) }}",
      )
    }
  }

  @Test
  fun `a class nested in an enum owner is deferred, and must be named like the others`() {
    val result = Tier1Harness.run(deferredSource, fileName = "Deferred.kt")

    // Its own cell, because it is red for a different reason from the other three: the ADR-064
    // 2026-09-11 amendment made an `enum class` a *candidate* but never an *owner*, so the
    // declaration walk does not descend into one and `Season.Almanac` is skipped SILENTLY today,
    // where `Box.Lid`, `Cage.Bar` and `Host.Guest` are all named. ADR-133 keeps the enum owner in
    // the deferred set and says the deferred set stays named, so the walk has to descend into an
    // enum owner for the diagnostic even though it never declares anything there. Worth settling in
    // the ADR rather than inheriting the silence.
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
}
