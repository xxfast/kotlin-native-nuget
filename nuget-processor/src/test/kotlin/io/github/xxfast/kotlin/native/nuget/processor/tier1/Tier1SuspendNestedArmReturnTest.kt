package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-118: a `suspend fun` returning a **nested sealed arm** has to spell that arm with its
 * enclosing base, `Task<Shape.Circle>` and `t.SetResult(new Shape.Circle(resultPtr))`.
 *
 * Both suspend routes built the C# return type off the declaration's *simple* name
 * (`CirClassTranslator`'s `suspendMembers` for a class or arm member, `CirFunctionTranslator`'s
 * `translateSuspendFunction` for a top-level one), so an arm came out as a bare `Circle` at
 * namespace scope, where ADR-009 declares it nested inside `public abstract class Shape`: CS0246,
 * and the *whole* `Interop.cs` fails to compile, not just that member. The fix is the shipped
 * ADR-105 speller, `KSClassDeclaration.nestedCsName()`.
 *
 * A top-level type must stay bare: this route has never qualified a return, and
 * [Tier1SuspendNullableReturnTest] pins `new Cat(resultPtr)`. `nestedCsName()` walks only class
 * parents, so a file-level declaration is unchanged.
 *
 * Deliberately not covered here: a suspend return of the sealed **base** (`suspend fun next():
 * Shape`), which still renders `new Shape(resultPtr)` and fails CS0144 on an abstract class. That
 * is a separate mapping decision (ADR-105's `sealedAsHandle()` on the legacy suspend route), split
 * out rather than folded in.
 *
 * `libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore)` is load-bearing: without coroutines on
 * the KSP libraries path there is no suspend surface to project and every assertion below passes
 * for the wrong reason.
 */
class Tier1SuspendNestedArmReturnTest {

  private val fixture: String = """
    package tier1.suspendnestedarm

    sealed class Shape {
      data class Circle(val radius: Int) : Shape()

      data object Dot : Shape()
    }

    class ShapeFactory {
      // The cell: an ordinary class's suspend member returning a nested arm.
      suspend fun circleLater(radius: Int): Shape.Circle = Shape.Circle(radius)

      // The same on a `data object` arm.
      suspend fun dotLater(): Shape.Dot = Shape.Dot
    }

    // The second route: the same return from a top-level suspend fun (ADR-007 static class).
    suspend fun anyCircleLater(radius: Int): Shape.Circle = Shape.Circle(radius)
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    fixture,
    fileName = "ShapeSample.kt",
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  /** The Kotlin half is unaffected by the speller, so this is the control. */
  @Test
  fun `a nested arm suspend return compiles clean on the Kotlin half`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected the generated suspend exports to compile; got: " +
          "${result.compileErrors} ${result.kspErrors}",
    )
  }

  @Test
  fun `both suspend routes spell a nested arm return with its enclosing base`() {
    val csharp: String = run().generatedCSharp

    assertContains(csharp, "Task<Shape.Circle> CircleLaterAsync")
    assertContains(csharp, "Task<Shape.Dot> DotLaterAsync")
    // The top-level route, `CirFunctionTranslator.translateSuspendFunction`.
    assertContains(csharp, "Task<Shape.Circle> AnyCircleLaterAsync")

    assertContains(csharp, "new Shape.Circle(resultPtr)")
    assertContains(csharp, "new Shape.Dot(resultPtr)")

    // The defect: a bare arm name at namespace scope is unresolvable (CS0246).
    assertFalse(
      csharp.contains("new Circle(resultPtr)") || csharp.contains("new Dot(resultPtr)"),
      "expected no bare arm construction; got: ${linesFor(csharp, "resultPtr")}",
    )
    assertFalse(
      csharp.contains("Task<Circle>") || csharp.contains("Task<Dot>"),
      "expected no bare arm Task type; got: ${linesFor(csharp, "Async")}",
    )
  }

  private fun linesFor(csharp: String, needle: String): List<String> =
    csharp.lines().filter { it.contains(needle) }.map(String::trim)
}
