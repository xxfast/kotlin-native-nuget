package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ADR-163: every forward C entry point is `<lib>_<package relative to rootPackage>__<the name it
 * had>`, always, on every route.
 *
 * Two things were broken and share one fix. Two exported declarations with the same simple name in
 * different packages derived one C symbol, so the build failed with ERROR_C_ENTRY_POINT_COLLISION
 * (verified by spike: `a.Kitten` + `b.Kitten` gives three, one per symbol). And a top-level function
 * whose name happens to be an import-library export (`signal`, `read`, `qsort`, `Beep`) was silently
 * dropped from the DLL's export table by `ld.lld`'s MinGW auto-exporter, so the generated
 * `[DllImport(EntryPoint = "signal")]` threw `EntryPointNotFoundException` with no build diagnostic.
 * A non-empty leading segment on every symbol closes both: the drop is an exact-name match, and
 * `library_signal` is not a name any import library exports.
 *
 * The cells that used to live in `Tier1EntryPointCollisionTest` for the cross-package shapes moved
 * here and flipped: they assert binding, not failure.
 */
class Tier1ExportSymbolSchemeTest {

  /**
   * The cross-package cells declare one simple name in `pkg.a` and `pkg.b`. With no root package
   * both land in C# namespace `Interop`, which is a named collision, so they root at `pkg` and the
   * relative package (`a`, `b`) is what qualifies each symbol.
   */
  private val rootedAtPkg: Map<String, String> = mapOf("nuget.rootPackage" to "pkg")

  private val moodSources: Map<String, String> = mapOf(
    "A.kt" to "package pkg.a\n\nenum class Mood { CURIOUS, SMUG }\n\n" +
        "fun moodOf(name: String): Mood = Mood.SMUG\n\n" +
        "val Mood.pounce: String get() = \"a\"",
    "B.kt" to "package pkg.b\n\nenum class Mood { CURIOUS, SMUG }\n\n" +
        "fun moodOf(name: String): Mood = Mood.CURIOUS\n\n" +
        "val Mood.pounce: String get() = \"b\"",
  )

  /** The primary shape (backlog shape 1): the class route, three symbols each, none colliding. */
  @Test
  fun `two classes with one simple name in two packages both bind`() {
    val result = Tier1Harness.run(
      mapOf(
        "A.kt" to "package pkg.a\n\nclass Kitten(val name: String) { fun greet(): String = \"a\" }",
        "B.kt" to "package pkg.b\n\nclass Kitten(val name: String) { fun greet(): String = \"b\" }",
      ),
      // Without a root package both `Kitten`s land in namespace `Interop`: CS0101.
      processorOptions = rootedAtPkg,
    )

    assertTrue(
      result.kspErrors.none { message ->
        message.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name)
      },
      "package qualification must remove the collision; kspErrors=${result.kspErrors}",
    )
    Tier1CSharpCompile.assertCompiles(
      result,
      "public static class Probe {\n" +
          "  public static string A(global::Interop.A.Kitten k) => k.Greet();\n" +
          "  public static string B(global::Interop.B.Kitten k) => k.Greet();\n}",
    )
    assertContains(result.generated, "@CName(\"library_a__kitten_create\")")
    assertContains(result.generated, "@CName(\"library_b__kitten_create\")")
    assertContains(result.generatedCSharp, "EntryPoint = \"library_a__kitten_greet\"")
    assertContains(result.generatedCSharp, "EntryPoint = \"library_b__kitten_greet\"")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
  }

  /**
   * Backlog shape 6, and the spike that proved the cname alone is not enough: with only the symbol
   * qualified, KSP exits OK and the generated file fails to compile with `Overload resolution
   * ambiguity between candidates: fun rollCall(): String / fun rollCall(): String`, because both
   * declarations were imported by simple name and the call site was bare. So the emitter has to
   * spell a top-level call fully qualified, and [compiledClean] is the assertion that catches it.
   */
  @Test
  fun `two top-level functions with one name in two packages both bind and compile`() {
    val result = Tier1Harness.run(
      mapOf(
        "A.kt" to "package pkg.a\n\nfun rollCall(): String = \"a\"",
        "B.kt" to "package pkg.b\n\nfun rollCall(): String = \"b\"",
      ),
    )

    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    assertContains(result.generated, "@CName(\"library_pkg_a__rollCall\")")
    assertContains(result.generated, "@CName(\"library_pkg_b__rollCall\")")
    assertContains(result.generated, "pkg.a.rollCall()")
    assertContains(result.generated, "pkg.b.rollCall()")
  }

  /**
   * The DEFAULT package: a file with no `package` line at all. It is the one shape where the
   * ADR-163 qualified call has nothing to qualify with, and the emitter cannot fall back on
   * `symbol.substringBeforeLast('.')`: a root-package declaration's qualified name has no `.`, so
   * that expression returns the FUNCTION's own name and emitted `rollCall.rollCall()`. Kotlin also
   * has no import for a root-package declaration, so the simple-name import ADR-163 dropped has to
   * come back for this one package: `compiledClean` is the assertion that says the call resolves.
   *
   * The fixture covers the PLAN route and the legacy `suspend` route in one file, because each keeps
   * its own copy of the call spelling: a fix applied to the plan route alone leaves the legacy ones
   * emitting an unresolvable bare name.
   *
   * No CLASS is declared here on purpose. A default-package class is broken for an unrelated reason
   * that predates this scheme: the plan emitter spells an owner type by `qualifiedName`, which in the
   * default package IS the bare simple name, and nothing imports it, so
   * `handle.asStableRef<Crate<Any?>>()` does not resolve either. That is its own item.
   */
  @Test
  fun `a top-level function in the default package is called by its bare name and compiles`() {
    val result = Tier1Harness.run(
      "fun rollCall(): String = \"root\"\n\n" +
          "suspend fun rollCallLater(): String = \"root\"",
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    assertContains(result.generated, "@CName(\"library_rollCall\")")
    assertContains(result.generated, "rollCall()")
    assertTrue(
      !result.generated.contains("rollCall.rollCall()"),
      "the default package has no qualifier to spell; generated=${result.generated}",
    )
  }

  /**
   * ROADMAP line 48. The function is in the ROOT of `nuget.rootPackage`, so its relative package is
   * empty and package qualification alone would leave the symbol bare: this is the cell that forces
   * the library-name segment to exist at all.
   */
  @Test
  fun `a top-level function named signal takes the library segment and is never bare`() {
    val result = Tier1Harness.run(
      "package tier1.tower\n\nprivate var last: Int = 0\n\n" +
          "fun signal(dbm: Int): Int { last = dbm; return last }\n\nfun lastSignal(): Int = last",
      processorOptions = mapOf("nuget.rootPackage" to "tier1.tower"),
    )

    assertContains(result.generated, "@CName(\"library_signal\")")
    assertContains(result.generatedCSharp, "EntryPoint = \"library_signal\"")
    assertTrue(
      !result.generated.contains("@CName(\"signal\")") &&
          !result.generatedCSharp.contains("EntryPoint = \"signal\""),
      "a bare `signal` is the symbol ld.lld drops from the export table; " +
          "generated=${result.generated}",
    )
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
  }

  /**
   * Memo finding 9 / the extension grouping key: two `Mood` enums in two packages, each with an
   * extension property of the same name. Pins WHERE the two `pounce` getters land, because the
   * merged extension class groups by `(extensionNamespace, receiver key)` and the receiver key is
   * the simple name: before this scheme the two symbols collided outright.
   */
  @Test
  fun `two enum receivers in two packages take distinct extension property symbols`() {
    val result = Tier1Harness.run(moodSources, processorOptions = rootedAtPkg)

    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    // The package part of an extension symbol is the EXTENSION's own package (ADR-095's counter
    // scope) relative to rootPackage, and the owner chain is the receiver's simple name.
    assertContains(result.generated, "@CName(\"library_a__mood_get_pounce\")")
    assertContains(result.generated, "@CName(\"library_b__mood_get_pounce\")")
    assertContains(result.generatedCSharp, "EntryPoint = \"library_a__mood_get_pounce\"")
    assertContains(result.generatedCSharp, "EntryPoint = \"library_b__mood_get_pounce\"")
    // The two enums, and the two `Pounce` extensions, are distinct C# declarations that build.
    Tier1CSharpCompile.assertCompiles(
      result,
      "using Interop.A;\nusing Interop.B;\n\npublic static class Probe {\n" +
          "  public static string A => global::Interop.A.Mood.Smug.Pounce;\n" +
          "  public static string B => global::Interop.B.Mood.Smug.Pounce;\n}",
    )
  }

  /**
   * The same two `Mood`s with `rootPackage` unset (the plugin default): every package maps to
   * namespace `Interop`, so the generated C# declares `enum Mood` twice. Compiling that output is
   * CS0101; the processor names the collision and the fix instead of leaving the author with it.
   */
  @Test
  fun `two same-named enums in one CSharp namespace are a named collision`() {
    val result = Tier1Harness.run(moodSources)

    val collision: String? = result.kspErrors.firstOrNull { message ->
      message.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name)
    }
    assertNotNull(collision, "expected a named collision; kspErrors=${result.kspErrors}")
    assertContains(collision, "'pkg.a.Mood'")
    assertContains(collision, "'pkg.b.Mood'")
    assertContains(collision, "namespace 'Interop'")
    assertContains(collision, "rootPackage")
    // What the named error replaces: the raw output does not build.
    val build: Tier1CSharpBuild = Tier1CSharpCompile.compile(result, "")
    assertFalse(build.succeeded, "expected the unrooted output to fail; log=${build.log}")
    assertContains(build.log, "CS0101")
  }

  /**
   * A root package does not separate everything: a package outside it, admitted through
   * `includePackages`, keeps its whole name, so `a` and `pkg.a` under root `pkg` both map to
   * namespace `Interop.A`. Setting rootPackage is no longer the fix, so the hint says rename.
   */
  @Test
  fun `a same-named type outside rootPackage that maps onto a rooted namespace is a collision`() {
    val result = Tier1Harness.run(
      mapOf(
        "A.kt" to "package pkg.a\n\nenum class Mood { CURIOUS, SMUG }",
        "Outside.kt" to "package a\n\nenum class Mood { CURIOUS, SMUG }",
      ),
      processorOptions = rootedAtPkg + ("nuget.includePackages" to "pkg,a"),
    )

    val collision: String? = result.kspErrors.firstOrNull { message ->
      message.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name)
    }
    assertNotNull(collision, "expected a named collision; kspErrors=${result.kspErrors}")
    assertContains(collision, "'a.Mood'")
    assertContains(collision, "'pkg.a.Mood'")
    assertContains(collision, "namespace 'Interop.A'")
    assertContains(collision, "rename one of the declarations")
  }

  /**
   * The check is about two DIFFERENT types under one C# name. Output that is merged on purpose must
   * not trip it: `{Receiver}Extensions` classes for an unexported receiver merged across packages,
   * a sealed arm that is also an exported top-level class (one type reached twice), and `Box`
   * beside `Box<T>`, which C# declares side by side by arity.
   */
  @Test
  fun `merged extension classes, a type reached twice and distinct arities are no collision`() {
    val result = Tier1Harness.run(
      mapOf(
        "A.kt" to "package pkg.a\n\nfun String.purr(): String = this\n\n" +
            "sealed class Shape\n\nclass Circle(val r: Int) : Shape()\n\n" +
            "class Box<T>(val value: T)",
        "B.kt" to "package pkg.b\n\nfun String.hiss(): String = this\n\nclass Box(val size: Int)",
      ),
    )

    assertTrue(
      result.kspErrors.none { message ->
        message.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name)
      },
      "kspErrors=${result.kspErrors}",
    )
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    Tier1CSharpCompile.assertCompiles(
      result,
      "using Interop;\n\npublic static class Probe {\n" +
          "  public static string P => \"x\".Purr() + \"y\".Hiss();\n}",
    )
  }

  /**
   * ADR-127 reserves the `nuget_` leading segment for the runtime's own fixed ABI, and every symbol
   * this scheme mints starts with the library segment, so a library called `nuget` would mint into
   * that space. Refused by name, before anything is planned.
   */
  @Test
  fun `a library named nuget is a named error and generates nothing`() {
    val result = Tier1Harness.run(
      "package tier1.reservedlib\n\nclass Cat(val name: String)",
      processorOptions = mapOf("nuget.libraryName" to "Nuget"),
    )

    assertTrue(
      result.kspErrors.any { message ->
        message.contains(ForwardDiagnosticKind.ERROR_RESERVED_LIBRARY_NAME.name) &&
            message.contains("nuget")
      },
      "expected the reserved-library-name error; kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.generatedFiles.keys.none { name -> name.endsWith("CNameExports.kt") },
      "the round must stop before any symbol is minted; files=${result.generatedFiles.keys}",
    )
  }

  /**
   * Memo spike f: the qualified symbol is a PRIVATE contract between the two generated files, so it
   * must appear in `Interop.cs` only inside `EntryPoint = "..."` and never as part of a C# identifier.
   * One assertion closes that question for every route at once, which is what spike d could not do
   * by reading two routes. The legacy top-level route was the real hazard: it derived its public C#
   * method name and its `${csName}_native` extern FROM the cname (memo finding 11).
   */
  @Test
  fun `the qualified symbol appears in the C# only inside an EntryPoint`() {
    val result = Tier1Harness.run(
      mapOf(
        "A.kt" to "package pkg.a\n\nclass Kitten(val name: String) { fun greet(): String = \"a\" }\n\n" +
            "fun rollCall(): String = \"a\"\n\nval tally: Int get() = 1\n\n" +
            "val Kitten.loudness: Int get() = 2",
      ),
    )

    val offending: List<String> = result.generatedCSharp.lines()
      .filter { line -> line.contains("library_pkg_a__") }
      .filterNot { line -> line.contains("EntryPoint = \"library_pkg_a__") }
    assertEquals(
      emptyList(), offending,
      "the C entry point must not leak into any C# identifier or type name",
    )
  }

  /**
   * The Flow legacy route, both halves of it (`{prefix}_get_{prop}_collect` and
   * `{prefix}_{method}_collect`). This route bypasses the ADR-062 plan, so it is one of the three
   * families that had to be swept by hand; a cell here is the only thing that proves it was.
   */
  @Test
  fun `flow property and method exports in two packages both bind`() {
    val result = Tier1Harness.run(
      mapOf(
        "A.kt" to """
          package pkg.a

          import kotlinx.coroutines.flow.Flow
          import kotlinx.coroutines.flow.flowOf

          class Radio {
            val purrs: Flow<Int> = flowOf(1)
            fun stream(): Flow<Int> = flowOf(1)
          }
        """.trimIndent(),
        "B.kt" to """
          package pkg.b

          import kotlinx.coroutines.flow.Flow
          import kotlinx.coroutines.flow.flowOf

          class Radio {
            val purrs: Flow<Int> = flowOf(2)
            fun stream(): Flow<Int> = flowOf(2)
          }
        """.trimIndent(),
      ),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
      processorOptions = rootedAtPkg,
    )

    assertTrue(
      result.kspErrors.none { message ->
        message.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name)
      },
      "kspErrors=${result.kspErrors}",
    )
    Tier1CSharpCompile.assertCompiles(result, "", allowUnsafe = true)
    assertContains(result.generated, "@CName(\"library_a__radio_get_purrs_collect\")")
    assertContains(result.generated, "@CName(\"library_b__radio_get_purrs_collect\")")
    assertContains(result.generated, "@CName(\"library_a__radio_stream_collect\")")
    assertContains(result.generated, "@CName(\"library_b__radio_stream_collect\")")
    assertContains(result.generatedCSharp, "EntryPoint = \"library_a__radio_get_purrs_collect\"")
    assertContains(result.generatedCSharp, "EntryPoint = \"library_b__radio_get_purrs_collect\"")
  }

  /**
   * The sealed route, which mints its prefix at four processor-level sites that read the base's raw
   * lowercased simple name rather than `nativePrefix()` (memo finding 1, bypass family (a)). The
   * discriminator and the arm's generated `equals` are the two shapes that used to collide.
   */
  @Test
  fun `a sealed class in two packages binds its discriminator and its arms`() {
    val result = Tier1Harness.run(
      mapOf(
        "A.kt" to "package pkg.a\n\nsealed class LoadState {\n" +
            "  data class Ready(val bowls: Int) : LoadState()\n}",
        "B.kt" to "package pkg.b\n\nsealed class LoadState {\n" +
            "  data class Ready(val bowls: Int) : LoadState()\n}",
      ),
      processorOptions = rootedAtPkg,
    )

    assertTrue(
      result.kspErrors.none { message ->
        message.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name)
      },
      "kspErrors=${result.kspErrors}",
    )
    Tier1CSharpCompile.assertCompiles(result, "")
    assertContains(result.generated, "@CName(\"library_a__loadstate_get_type\")")
    assertContains(result.generated, "@CName(\"library_b__loadstate_get_type\")")
    assertContains(result.generated, "@CName(\"library_a__loadstate_ready_equals\")")
    assertContains(result.generated, "@CName(\"library_b__loadstate_ready_equals\")")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
  }

  /**
   * The two generic families, both on legacy routes: a generic CLASS (`box_create`, `box_get_value`)
   * and a generic top-level FUNCTION, whose per-variant exports (`wrap_string`, `wrap_object`) are
   * minted by `GenericFunctionExports`. The function cell is the one that also needs the Kotlin call
   * site qualified: that route imports by simple name too, so `compiledClean` is load-bearing here.
   */
  @Test
  fun `generic class and generic top-level function in two packages both bind`() {
    val result = Tier1Harness.run(
      mapOf(
        "A.kt" to "package pkg.a\n\nclass Box<T>(val value: T)\n\nfun <T> wrap(treat: T): T = treat",
        "B.kt" to "package pkg.b\n\nclass Box<T>(val value: T)\n\nfun <T> wrap(treat: T): T = treat",
      ),
      processorOptions = rootedAtPkg,
    )

    assertTrue(
      result.kspErrors.none { message ->
        message.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name)
      },
      "kspErrors=${result.kspErrors}",
    )
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    Tier1CSharpCompile.assertCompiles(result, "")
    assertContains(result.generated, "@CName(\"library_a__box_create\")")
    assertContains(result.generated, "@CName(\"library_b__box_create\")")
    assertContains(result.generated, "@CName(\"library_a__wrap_string\")")
    assertContains(result.generated, "@CName(\"library_b__wrap_string\")")
  }

  /**
   * ROADMAP line 76: a top-level function returning a generic class declared in ANOTHER Kotlin
   * package spelled the OUTER type by its simple name, so the consumer's build failed with CS0246.
   * The cell must set `nuget.rootPackage`, because with no root package both types land in one
   * namespace and the defect is invisible.
   */
  @Test
  fun `a top-level generic return from another package is namespace-qualified`() {
    val result = Tier1Harness.run(
      mapOf(
        "A.kt" to "package tier1.gen.a\n\nclass Box<T>(val v: T)",
        "B.kt" to "package tier1.gen.b\n\nimport tier1.gen.a.Box\n\nfun make(): Box<Int> = Box(1)",
      ),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.gen"),
    )

    assertContains(result.generatedCSharp, "global::Interop.A.Box<int>")
    assertTrue(
      !result.generatedCSharp.contains("return new Box<int>("),
      "the outer type name must be qualified; csharp=${result.generatedCSharp}",
    )
  }
}
