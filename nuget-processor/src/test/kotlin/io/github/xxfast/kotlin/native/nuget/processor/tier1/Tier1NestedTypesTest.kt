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
      // Load-bearing: `Tier1Harness` puts only `kotlin-stdlib` on the KSP `libraries` path
      // (`coroutinesOnCompileClasspath` governs the *compile* step alone), so without this a
      // `Flow` return resolves to `<ERROR TYPE: Flow>` and every Flow member drops.
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
      // Load-bearing: `Tier1Harness` puts only `kotlin-stdlib` on the KSP `libraries` path
      // (`coroutinesOnCompileClasspath` governs the *compile* step alone), so without this a
      // `Flow` return resolves to `<ERROR TYPE: Flow>` and every Flow member drops.
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    val csharp: String = result.generatedCSharp
    // `qualifiedElementCsType` spells a Flow element with the wrapper too. Worse than a cosmetic
    // difference: the stream is read through `NugetMarshal.FromHandle<T>`, whose Activator branch
    // cannot construct an interface, so the corrected spelling needs an explicit read lambda.
    //
    // Measured 2026-09-13, and the reason this cell first read as an environment disagreement:
    // the harness call above omitted `libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore)`,
    // and `Tier1Harness` only ever puts `kotlin-stdlib` on the KSP `libraries` path -- its
    // `coroutinesOnCompileClasspath` flag governs the *compile* step, not resolution. `Flow` was
    // therefore `<ERROR TYPE: Flow>` to KSP and EVERY Flow member dropped, `Flow<String>`
    // included, with the generic `SKIPPED_UNSUPPORTED_TYPE ... its UNSUPPORTED type combination
    // is not supported` that names neither the Flow nor its element. With coroutines on the KSP
    // path the harness agrees with the real test-library build, which emits the wrapper spelling
    // `public KotlinFlow<global::TestLibrary.Nested.Aviary.Keeper> Keepers()` this cell is about.
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
      // Load-bearing: `Tier1Harness` puts only `kotlin-stdlib` on the KSP `libraries` path
      // (`coroutinesOnCompileClasspath` governs the *compile* step alone), so without this a
      // `Flow` return resolves to `<ERROR TYPE: Flow>` and every Flow member drops.
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
      // Load-bearing: `Tier1Harness` puts only `kotlin-stdlib` on the KSP `libraries` path
      // (`coroutinesOnCompileClasspath` governs the *compile* step alone), so without this a
      // `Flow` return resolves to `<ERROR TYPE: Flow>` and every Flow member drops.
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
}
