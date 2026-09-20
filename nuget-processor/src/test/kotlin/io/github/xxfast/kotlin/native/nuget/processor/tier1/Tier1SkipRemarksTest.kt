package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-064 amendment (issue #249): every member-level skip is named on the generated declaration it
 * left a hole in, as a `<remarks>` paragraph, so a C# consumer meets the absence in `Interop.cs`
 * itself instead of at the call site with `NugetDiagnostics.json` nowhere in reach.
 *
 * One cell per OWNER kind (class, object, interface, sealed arm, nested class, file holder, and the
 * ADR-075 partial skip on a surviving property), plus the assertions that catch this feature's one
 * silent failure -- a paragraph attached to the WRONG declaration, which compiles fine and is still
 * true of something. The consumer-side twin is `IntegrationTests/XmlDocTests.cs`, which reads the
 * same text back out of a real `IntegrationTests.xml`.
 *
 * Every skipped shape here is stably unsupported: a `Map<String?, Int>` parameter (ADR-083 excludes
 * a nullable map key by name), a `Sequence` property (a stdlib type with no C# mapping), a
 * `List<List<String>?>` parameter (ADR-099's nullable nested component) and `var t: Throwable?`
 * (ADR-107 refuses the setter alone). None of them can quietly start binding.
 */
class Tier1SkipRemarksTest {

  private val fixture: String = """
    package tier1.remarks

    class Post(val name: String) {
      fun shred(): String = name
      val height: Int = 40
      val weave: Sequence<String> get() = sequenceOf("sisal")
      fun rankPerches(scores: Map<String?, Int>): Int = scores.size
      var lastTumble: Throwable? = null
    }

    object Bin {
      fun scoop(): String = "one"
      fun tallyShelves(counts: Map<String?, Int>): Int = counts.size
    }

    interface Pounceable {
      fun pounce(): String
      fun rankTargets(scores: Map<String?, Int>): Int
    }

    sealed class Scamper {
      data class Dash(val metres: Int) : Scamper() {
        fun speed(): String = "fast"
        fun rankRoutes(scores: Map<String?, Int>): Int = scores.size
      }

      data class Skid(val tiles: Int) : Scamper() {
        fun stop(): String = "skid"
      }
    }

    class Gantry(val name: String) {
      fun survey(): String = name

      class Rung(val height: Int) {
        fun perch(): String = "perched"
        fun rankHeights(scores: Map<String?, Int>): Int = scores.size
      }
    }

    fun countStrips(): Int = 2

    fun sortStrips(litters: List<List<String>?>): Int = litters.size
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(fixture, fileName = "Post.kt")

  /**
   * The ordinary class owner, and the "named as dropped AND genuinely absent" pair ADR-064 calls
   * the honest skip: the remark names `weave` and `rankPerches` in their KOTLIN spelling, the
   * generated code declares neither, and the survivors beside them are not named at all.
   */
  @Test
  fun `a dropped property and a dropped method are named on their class`() {
    val result = run()
    val remarks: String = result.generatedCSharp.docCommentsAbove("public class Post")

    assertTrue(
      "SKIPPED_UNSUPPORTED_PROPERTY" in remarks && "`weave`" in remarks,
      "expected the dropped property named with its kind on Post; remarks=$remarks",
    )
    assertTrue(
      "SKIPPED_UNSUPPORTED_INPUT" in remarks && "`rankPerches`" in remarks,
      "expected the dropped method named with its kind on Post; remarks=$remarks",
    )
    // The C# name never existed, so naming it would send a consumer after a spelling that was
    // never generated either.
    assertFalse("RankPerches" in remarks, "expected the Kotlin spelling only; remarks=$remarks")
    assertFalse("shred" in remarks, "expected survivors to go unnamed; remarks=$remarks")
    assertFalse("height" in remarks, "expected survivors to go unnamed; remarks=$remarks")

    val code: String = result.generatedCSharp.withoutDocComments()
    assertFalse("Weave" in code, "expected no member for the dropped property; code=$code")
    assertFalse("RankPerches" in code, "expected no member for the dropped method; code=$code")
    assertTrue("Shred" in code, "expected the survivor to bind; code=$code")
  }

  /**
   * ADR-075's partial skip is the ONE per-member remark in the feature: the property survives
   * read-only, so naming it on the class would report a member as absent that a consumer can call.
   */
  @Test
  fun `a dropped setter is named on the surviving property, not on its class`() {
    val result = run()

    val onProperty: String = result.generatedCSharp
      .docCommentsAbove("public global::System.Exception? LastTumble")
    assertTrue(
      "`lastTumble`" in onProperty && "setter" in onProperty,
      "expected the partial skip on the C# property itself; docs=$onProperty",
    )
    assertFalse(
      "lastTumble" in result.generatedCSharp.docCommentsAbove("public class Post"),
      "expected the class NOT to report a member a consumer can still call; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
  }

  /** An `object` owner: its generated static class carries the paragraph. */
  @Test
  fun `a dropped object method is named on the objects static class`() {
    val remarks: String = run().generatedCSharp.docCommentsAbove("public static class Bin")

    assertTrue(
      "SKIPPED_UNSUPPORTED_INPUT" in remarks && "`tallyShelves`" in remarks,
      "expected the object's dropped method named on it; remarks=$remarks",
    )
    assertFalse("scoop" in remarks, "expected the survivor to go unnamed; remarks=$remarks")
  }

  /**
   * The interface hole this item also closed at the producer: before it,
   * `interfaceDeclarationCatalog`'s dropped callables reached no channel at all, so a consumer
   * implementing `IPounceable` never learnt the member had been refused.
   */
  @Test
  fun `a dropped interface member is named on the interface`() {
    val result = run()
    val remarks: String = result.generatedCSharp.docCommentsAbove("public interface IPounceable")

    assertTrue(
      "SKIPPED_UNSUPPORTED_INPUT" in remarks && "`rankTargets`" in remarks,
      "expected the interface's own dropped member named on it; remarks=$remarks",
    )
    assertTrue(
      result.kspWarnings.any { "rankTargets" in it },
      "expected a named diagnostic too, not only a remark; kspWarnings=${result.kspWarnings}",
    )
    assertFalse(
      "RankTargets" in result.generatedCSharp.withoutDocComments(),
      "expected the member to stay absent; generatedCSharp=${result.generatedCSharp}",
    )
  }

  /**
   * The REACHABLE interface (something returns it), which is planned into a different catalog from
   * the merely-implemented one above -- so both catalogs have to stamp the owner or this shape,
   * the one a consumer actually meets, silently loses its paragraph while the log still shows the
   * skip.
   *
   * It is also the one shape where two C# declarations share a Kotlin name: ADR-040 generates a
   * `sealed class Pounceable` backing wrapper beside `IPounceable`. The paragraph belongs to the
   * interface the member was refused from, and saying it twice on two types for one Kotlin
   * declaration would be noise.
   */
  @Test
  fun `a reachable interface carries the remark and its backing wrapper does not`() {
    val csharp: String = Tier1Harness.run(
      """
      package tier1.reachable

      interface Pounceable {
        fun pounce(): String
        fun rankTargets(scores: Map<String?, Int>): Int
      }

      fun adopt(): Pounceable = object : Pounceable {
        override fun pounce(): String = "pounce"
        override fun rankTargets(scores: Map<String?, Int>): Int = scores.size
      }
      """.trimIndent(),
      fileName = "Reachable.kt",
    ).generatedCSharp

    assertTrue(
      "`rankTargets`" in csharp.docCommentsAbove("public interface IPounceable"),
      "expected the reachable interface's own drop named on it; generatedCSharp=$csharp",
    )
    assertFalse(
      "rankTargets" in csharp.docCommentsAbove("public sealed class Pounceable"),
      "expected the ADR-040 backing wrapper to stay silent: one Kotlin declaration, one " +
          "paragraph; generatedCSharp=$csharp",
    )
  }

  /**
   * The wrong-owner negatives, which are the whole reason the owner is carried explicitly rather
   * than parsed back out of a display string: a sealed ARM's drop lands on the arm, and neither the
   * base nor the clean sibling arm says anything. Same for a nested type against its outer.
   */
  @Test
  fun `an arm and a nested type carry their own drops and no sibling or owner does`() {
    val csharp: String = run().generatedCSharp

    assertTrue(
      "`rankRoutes`" in csharp.docCommentsAbove("public sealed class Dash"),
      "expected the arm's own drop on the arm; generatedCSharp=$csharp",
    )
    assertFalse(
      "rankRoutes" in csharp.docCommentsAbove("public abstract class Scamper"),
      "expected the sealed BASE to stay clean; generatedCSharp=$csharp",
    )
    assertFalse(
      "rankRoutes" in csharp.docCommentsAbove("public sealed class Skid"),
      "expected the sibling arm to stay clean; generatedCSharp=$csharp",
    )

    assertTrue(
      "`rankHeights`" in csharp.docCommentsAbove("public class Rung"),
      "expected the nested type's drop on the nested type; generatedCSharp=$csharp",
    )
    assertFalse(
      "rankHeights" in csharp.docCommentsAbove("public class Gantry"),
      "expected the OUTER type to stay clean; generatedCSharp=$csharp",
    )
  }

  /**
   * ADR-007's file holder, under the name ADR-007 actually gave it: `class Post` claims `Post`, so
   * the top-level functions live on `PostKt` and that is where the dropped one is named. Resolved
   * by the post-pass itself, with no dependence on `INFO_FILE_CLASS_RENAMED` (which does not fire
   * for this shape at all).
   */
  @Test
  fun `a dropped top-level function is named on its renamed file holder`() {
    val csharp: String = run().generatedCSharp
    val remarks: String = csharp.docCommentsAbove("public static partial class PostKt")

    assertTrue(
      "SKIPPED_UNSUPPORTED_INPUT" in remarks && "`sortStrips`" in remarks,
      "expected the dropped top-level function named on its holder; remarks=$remarks",
    )
    assertFalse("countStrips" in remarks, "expected the survivor to go unnamed; remarks=$remarks")
    assertTrue(
      "CountStrips" in csharp.withoutDocComments(),
      "expected the surviving top-level function to bind; generatedCSharp=$csharp",
    )
  }

  /**
   * ROADMAP Phase 4 x issue #249: the object PROPERTY walk is the newest of the four static walks
   * and was the one added without an owner scope, so a `StateFlow` property on an object --
   * a named skip since Phase 4, with no adapter on a static owner -- reached
   * `NugetDiagnostics.json` and never the generated type. The consumer-side twin is
   * `XmlDocTests.TreatPantry_DroppedStateFlowProperty_IsNamedOnTheObjectsStaticClass`.
   */
  @Test
  fun `a dropped object property is named on the objects static class`() {
    val result = staticPositions()
    val remarks: String = result.generatedCSharp.docCommentsAbove("public static class Pantry")

    assertTrue(
      "SKIPPED_UNSUPPORTED_PROPERTY" in remarks && "`level`" in remarks,
      "expected the object's dropped property named on its static class; remarks=$remarks",
    )
    // Exactly one producer for one drop: the plan records it once and the post-pass attaches once.
    assertEquals(
      1,
      remarks.split("`level`").size - 1,
      "expected exactly one paragraph for one dropped property; remarks=$remarks",
    )
    assertFalse("count" in remarks, "expected the survivor to go unnamed; remarks=$remarks")
    assertFalse(
      "Level" in result.generatedCSharp.withoutDocComments(),
      "expected no member for the dropped property; generatedCSharp=${result.generatedCSharp}",
    )
  }

  /**
   * The two sibling positions the same Phase 4 commit turned into named skips when it narrowed
   * `recordDropped`'s silence exemption to `position == CLASS`. Both already ride an owner scope,
   * and this cell is what keeps them doing so: a companion's members render as its class's statics
   * (ADR-013), so the hole is on the CLASS, and a top-level property's is on its ADR-007 holder.
   */
  @Test
  fun `a dropped companion property lands on its class and a top-level one on its file holder`() {
    val csharp: String = staticPositions().generatedCSharp

    val onClass: String = csharp.docCommentsAbove("public class Hutch")
    assertTrue(
      "SKIPPED_UNSUPPORTED_PROPERTY" in onClass && "`wattage`" in onClass,
      "expected the companion's dropped property on its class; remarks=$onClass",
    )
    assertFalse(
      "Companion" in csharp,
      "a companion renders as its class's statics, so no Companion type may exist to carry the " +
          "paragraph; generatedCSharp=$csharp",
    )

    val onHolder: String = csharp.docCommentsAbove("public static partial class Statics")
    assertTrue(
      "SKIPPED_UNSUPPORTED_PROPERTY" in onHolder && "`ambient`" in onHolder,
      "expected the top-level dropped property on its file holder; remarks=$onHolder",
    )
    // Wrong-owner negatives, both ways: neither position may borrow the other's paragraph.
    assertFalse("ambient" in onClass, "expected the class to stay clean; remarks=$onClass")
    assertFalse("wattage" in onHolder, "expected the holder to stay clean; remarks=$onHolder")
  }

  /**
   * The inherited case, which is why the owner is stamped per WALK rather than read off
   * `parentDeclaration`. An object flattens its inherited members (a C# static class cannot extend
   * anything), so the hole an inherited `StateFlow` leaves is on the OBJECT.
   *
   * The base is the asymmetry that proves it: at a CLASS position a Flow property is legacy-routed
   * and genuinely re-emitted by the flow adapter, so `Shed` keeps the member and says nothing.
   */
  @Test
  fun `an inherited dropped member of an object is owned by the object, not by its supertype`() {
    val csharp: String = staticPositions().generatedCSharp

    val onObject: String = csharp.docCommentsAbove("public static class Annexe")
    assertTrue(
      "SKIPPED_UNSUPPORTED_PROPERTY" in onObject && "`gauge`" in onObject,
      "expected the inherited drop named on the object that flattened it; remarks=$onObject",
    )
    assertFalse(
      "gauge" in csharp.docCommentsAbove("public class Shed"),
      "expected the supertype -- where the class route still binds the property -- to stay " +
          "clean; generatedCSharp=$csharp",
    )
  }

  /**
   * The method-route twin of the cell above. An object flattens its inherited METHODS on the same
   * predicate its properties use (`isForwardPlannableMemberOf(obj, superClass = null)`), so an
   * inherited `Flow`-returning method is dropped by the OBJECT and named on it.
   *
   * `Shed` is the control: at a CLASS position the same method is legacy-routed and genuinely
   * re-emitted by the flow adapter, so the base keeps it and says nothing about it. That is the
   * asymmetry that distinguishes "owned by the walk" from "owned by `parentDeclaration`".
   */
  @Test
  fun `an inherited dropped method of an object is owned by the object, not by its supertype`() {
    val csharp: String = staticPositions().generatedCSharp

    val onObject: String = csharp.docCommentsAbove("public static class Annexe")
    assertTrue(
      "`readings`" in onObject,
      "expected the inherited dropped method named on the object; remarks=$onObject",
    )
    assertFalse(
      "readings" in csharp.docCommentsAbove("public class Shed"),
      "expected the supertype -- which still binds the method through the flow adapter -- to " +
          "stay clean; generatedCSharp=$csharp",
    )
  }

  /**
   * ADR-075's partial skip at the object position, which issue #249's owner stamping made
   * REACHABLE: before the object walk had an owner scope the record was ownerless and discarded
   * early, and after it the record is `Property`-owned and had no branch to attach to -- an
   * object's statics live in `CirObject.methods`, not in a `properties` slot.
   *
   * ADR-107 refuses a `Throwable` setter (C# cannot mint a typed Kotlin throwable), so the property
   * survives read-only and the paragraph belongs on the C# PROPERTY, never on the static class:
   * naming it there would report a member as absent that a consumer can still read.
   */
  @Test
  fun `a dropped object setter is named on the surviving static property, not on the object`() {
    val csharp: String = Tier1Harness.run(
      """
      package tier1.objectsetter

      object Ledger {
        var lastError: Throwable? = null
      }
      """.trimIndent(),
      fileName = "Ledger.kt",
    ).generatedCSharp

    val onProperty: String =
      csharp.docCommentsAbove("public static global::System.Exception? LastError")
    assertTrue(
      "`lastError`" in onProperty && "setter" in onProperty,
      "expected the partial skip on the object's own C# property; docs=$onProperty",
    )
    assertFalse(
      "lastError" in csharp.docCommentsAbove("public static class Ledger"),
      "expected the static class NOT to report a member a consumer can still read; " +
          "generatedCSharp=$csharp",
    )
  }

  /**
   * The static-position drops in one module: an object's own property, an inherited property, an
   * inherited method, a companion's property and a top-level one. `Flow`/`StateFlow` throughout,
   * which is stably unsupported at every static position -- no adapter exists for a flow on a
   * static owner.
   */
  private fun staticPositions(): Tier1Result = Tier1Harness.run(
    """
    package tier1.statics

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.flow

    object Pantry {
      var count: Int = 4
      val level: StateFlow<Int> = MutableStateFlow(0)
    }

    open class Shed(val origin: String) {
      val gauge: StateFlow<Int> = MutableStateFlow(1)
      fun readings(): Flow<Int> = flow { emit(1) }
    }

    object Annexe : Shed("garden")

    class Hutch(val name: String) {
      companion object {
        val wattage: StateFlow<Int> = MutableStateFlow(2)
      }
    }

    val ambient: StateFlow<Int> = MutableStateFlow(3)
    """.trimIndent(),
    fileName = "Statics.kt",
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  /**
   * The paragraph is built from the diagnostic's KIND and raw REASON, never from its formatted
   * `message`: that embeds `at <absolute path>:<line>` and an author-only hint, and every consumer
   * of the package would read both in a tooltip. KSP spells the path with forward slashes even on
   * Windows, so both separators are checked.
   */
  @Test
  fun `no remark ships a producer path, a file name or an author hint`() {
    val csharp: String = run().generatedCSharp
    val docs: String = csharp.lines()
      .filter { line -> line.trimStart().startsWith("///") }
      .joinToString("\n")

    assertTrue("SKIPPED_" in docs, "expected generated remarks in the pile; docs=$docs")
    assertFalse(":\\" in docs, "a Windows producer path shipped; docs=$docs")
    assertFalse(":/" in docs, "a forward-slashed producer path shipped; docs=$docs")
    assertFalse(".kt" in docs, "a fixture source file name shipped; docs=$docs")
    assertFalse(" at " in docs, "the message's location suffix shipped; docs=$docs")
    assertFalse(
      "expose a bridgeable" in docs,
      "the author-facing hint shipped to consumers; docs=$docs",
    )
  }

  /**
   * One `<remarks>` per declaration whatever it is made of (ADR-150's amendment), and one paragraph
   * per (owner, member, kind). ADR-149 synthesizes an omitting overload even when the declared
   * entry was skipped, so a dropped function with a defaulted parameter records the same
   * diagnostic more than once and would otherwise render the same sentence twice.
   */
  @Test
  fun `a repeated diagnostic renders one paragraph and one remarks element`() {
    val result = Tier1Harness.run(
      """
      package tier1.dedupe

      fun sortStrips(litters: List<List<String>?>, limit: Int = 1): Int = litters.size + limit
      """.trimIndent(),
      fileName = "Sorter.kt",
    )
    val csharp: String = result.generatedCSharp

    assertEquals(
      1,
      csharp.split("Not generated from Kotlin `sortStrips`").size - 1,
      "expected exactly one paragraph for one dropped member; generatedCSharp=$csharp",
    )
    assertEquals(
      1,
      csharp.split("<remarks>").size - 1,
      "expected exactly one <remarks> element on the holder; generatedCSharp=$csharp",
    )
  }
}

/**
 * The `///` block immediately above the line declaring [declaration], as one string. Adjacency is
 * the assertion that matters: a paragraph on the wrong declaration is this feature's one silent
 * failure, and a whole-file `contains` would never see it.
 */
private fun String.docCommentsAbove(declaration: String): String {
  val lines: List<String> = lines()
  val index: Int = lines.indexOfFirst { line -> line.trimStart().startsWith(declaration) }
  if (index < 0) return ""
  return lines.take(index)
    .reversed()
    .takeWhile { line -> line.trimStart().startsWith("///") }
    .reversed()
    .joinToString("\n")
}
