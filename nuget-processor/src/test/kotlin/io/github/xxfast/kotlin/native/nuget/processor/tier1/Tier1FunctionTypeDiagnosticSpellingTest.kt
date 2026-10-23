package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A diagnostic names a function type as Kotlin source spells it: `suspend (Int) -> String`, never
 * the `SuspendFunction1<Int, String>` a raw `KSType.toString()` prints, and `(Int) -> String`,
 * never `Function1<Int, String>`.
 *
 * Two renderers shipped the misspelling. The interface-bridge refusal, the generic type-argument
 * refusals and the unexported-supertype skip interpolated the `KSType` itself; and the shared
 * `KSType.kotlinSpelling()` knew the arrow form for `kotlin.Function0..3` only, so every caller of
 * it printed a `suspend` function type (and a plain one of arity four) by its class name.
 *
 * Mylo fetches the treat; Oreo decides, asynchronously, whether he may have it.
 */
class Tier1FunctionTypeDiagnosticSpellingTest {

  private val fixture: String = """
    package tier1.spelling

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.StateFlow

    interface Pet {
      val name: String
      fun arityZero(prepare: suspend () -> String): Int
      fun arityOne(prepare: suspend (Int) -> String): Int
      fun arityTwo(prepare: suspend (Int, Int) -> String): Int
      fun arityThree(prepare: suspend (Int, Int, Int) -> String): Int
      fun nullable(prepare: (suspend (Int) -> String)?): Int
      fun plain(prepare: (Int) -> String): Int
      fun plainNullable(prepare: ((Int) -> String)?): Int
      fun handsOut(): suspend (Int) -> String
      fun handsOutNullable(): (suspend (Int) -> String)?
      fun handsOutPlain(): (Int) -> String
      val held: suspend (Int) -> String
      val heldNullable: (suspend (Int) -> String)?
      val heldPlain: (Int) -> String
      fun pairNullable(prepare: (suspend (Int) -> Pair<Int, Int>)?): Int
      fun pairPlainNullable(prepare: ((Int) -> Pair<Int, Int>)?): Int
    }

    class Holder { fun take(pet: Pet) = Unit }

    interface Listener {
      val held: suspend (Int) -> String
    }

    class Bus {
      fun addListener(listener: Listener) = Unit
      fun removeListener(listener: Listener) = Unit
    }

    class Crate<T>(val item: T)

    interface Handler<T> { fun handle(item: T) }

    class TreatHandler : Handler<suspend (Int) -> String> {
      override fun handle(item: suspend (Int) -> String) = Unit
    }

    class Kennel<T : Handler<suspend (Int) -> String>>(val item: T)

    class Chef {
      suspend fun maybe(prepare: (suspend (Int) -> String)?): Int = if (prepare == null) 0 else 1
      suspend fun initial(pick: suspend (Int) -> Char): Int = 0
      suspend fun crowd(pick: suspend (Int, Int, Int, Int) -> Int): Int = 0
      suspend fun crowdPlain(pick: (Int, Int, Int, Int) -> Int): Int = 0
      fun makeNullable(): (suspend (Int) -> String)? = null
      fun pairOut(): Pair<Int, suspend (Int) -> String>? = null
      suspend fun makeSuspended(): suspend (Int) -> String = { "" }
      fun nested(taste: (suspend (Int) -> String) -> Unit): Int = 0
      fun nestedOut(taste: (Int) -> suspend (Int) -> String): Int = 0
      val heldFlow: StateFlow<suspend (Int) -> String> get() = TODO()
      val heldNullables: List<((Int) -> String)?> = emptyList()
    }

    fun crateOfSuspend(): Crate<suspend (Int) -> Int> = TODO()
    fun crateOfNullableSuspend(): Crate<(suspend (Int) -> Int)?> = TODO()
    fun crateOfNullablePlain(): Crate<((Int) -> Int)?> = TODO()
    fun crateOfFlow(): Crate<Flow<suspend (Int) -> Int>> = TODO()
    fun crateProjected(): Crate<out suspend (Int) -> Int> = TODO()
    fun crateConsumer(): Crate<in Int> = TODO()
    fun crateStar(): Crate<*> = TODO()
  """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      fixture,
      fileName = "SpellingSample.kt",
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  /** The one diagnostic of [kind] whose `Skipping` declaration ends in [declaration]. */
  private fun diagnostic(kind: ForwardDiagnosticKind, declaration: String): String {
    val lines: List<String> = result.kspWarnings.filter { line ->
      line.startsWith("[nuget:${kind.name}] ") && line.contains("$declaration: ")
    }
    assertEquals(
      1,
      lines.size,
      "expected one ${kind.name} for $declaration; got ${result.kspWarnings}",
    )
    return lines.single()
  }

  @Test
  fun `the fixture compiles`() {
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compileErrors.isEmpty(), "compileErrors=${result.compileErrors}")
  }

  /** The reported symptom: `SKIPPED_UNIMPLEMENTABLE_INTERFACE`, at arities zero to three. */
  @Test
  fun `an unimplementable interface names a suspend lambda parameter as the source spells it`() {
    val line: String =
      diagnostic(ForwardDiagnosticKind.SKIPPED_UNIMPLEMENTABLE_INTERFACE, "tier1.spelling.Pet")

    // ADR-233: a suspend lambda parameter of any arity bridges, nullable too (ADR-239), so the
    // one named carries a `Pair` result the callback vocabulary refuses.
    assertFalse("`fun nullable` " in line, line)
    listOf(
      "`fun pairNullable` takes `prepare: (suspend (Int) -> Pair<Int, Int>)?`,",
    ).forEach { expected -> assertContains(line, expected) }
  }

  @Test
  fun `an unimplementable interface names a suspend lambda result and property the same way`() {
    val line: String =
      diagnostic(ForwardDiagnosticKind.SKIPPED_UNIMPLEMENTABLE_INTERFACE, "tier1.spelling.Pet")

    // ADR-239: a lambda RESULT is a bridge slot on every owner, so the refusal names none of them.
    listOf("`fun handsOut` ", "`fun handsOutNullable` ").forEach { member ->
      assertFalse(member in line, line)
    }
    // ADR-236: a lambda-typed property is a bridge slot now, so the refusal names none of them.
    assertFalse("`val held" in line, line)
  }

  /** A plain function type was printed `Function1<Int, String>` by the same three sites. */
  @Test
  fun `an unimplementable interface names a plain lambda as the source spells it`() {
    val line: String =
      diagnostic(ForwardDiagnosticKind.SKIPPED_UNIMPLEMENTABLE_INTERFACE, "tier1.spelling.Pet")

    // ADR-233: a plain lambda bridges in and out, nullable too (ADR-236), so the one named
    // carries a `Pair` result the callback vocabulary refuses.
    assertFalse("`fun plainNullable` " in line, line)
    listOf(
      "`fun pairPlainNullable` takes `prepare: ((Int) -> Pair<Int, Int>)?`,",
    ).forEach { expected -> assertContains(line, expected) }
    assertFalse("`fun handsOutPlain` " in line, line)
  }

  /** `KSType.kotlinSpelling()`, read by the refused legacy suspend route. */
  @Test
  fun `a refused suspend member names its suspend lambda parameter as the source spells it`() {
    val input: ForwardDiagnosticKind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT

    // ADR-239: a nullable suspend lambda binds, so `maybe` is named nowhere.
    assertTrue(result.kspWarnings.none { "Chef.maybe: " in it }, result.kspWarnings.toString())
    // ADR-239: a `Char` result binds, so `initial` is named nowhere.
    assertTrue(result.kspWarnings.none { "Chef.initial: " in it }, result.kspWarnings.toString())
    // The spelling does not stop at the arity the route binds.
    assertContains(
      diagnostic(input, "Chef.crowd"),
      "but not pick: suspend (Int, Int, Int, Int) -> Int. ",
    )
  }

  /** The arrow form stopped at `kotlin.Function3`, so arity four read `Function4<...>`. */
  @Test
  fun `a plain lambda of arity four is spelled as an arrow too`() {
    val all: String = result.kspWarnings.filter { line -> "crowdPlain" in line }.joinToString("\n")

    assertTrue("Function4" !in all, all)
  }

  @Test
  fun `a refused return names a suspend lambda as the source spells it`() {
    val returned: ForwardDiagnosticKind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN

    // ADR-239: a nullable `suspend` lambda return binds, so `makeNullable` is named nowhere.
    val warnings: List<String> = result.kspWarnings
    assertTrue(warnings.none { "Chef.makeNullable: " in it }, warnings.toString())
    assertContains(
      diagnostic(returned, "Chef.pairOut"),
      "its nullable return type `Pair<Int, suspend (Int) -> String>?` has no supported wire",
    )
    assertContains(
      diagnostic(returned, "Chef.makeSuspended"),
      "but not the generic type suspend (Int) -> String. ",
    )
  }

  @Test
  fun `a refused callback names a suspend lambda payload and result as the source spells it`() {
    val payload: String =
      diagnostic(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT, "Chef.nested")
    assertContains(
      payload,
      "but not `taste: (suspend (Int) -> String) -> Unit`, whose payload " +
          "`suspend (Int) -> String` is a Kotlin builtin",
    )

    val returned: String =
      diagnostic(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN, "Chef.nestedOut")
    assertContains(
      returned,
      "but `taste: (Int) -> suspend (Int) -> String` returns `suspend (Int) -> String`. ",
    )
  }

  @Test
  fun `a refused flow element names a suspend lambda as the source spells it`() {
    assertContains(
      diagnostic(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY, "Chef.heldFlow"),
      "but not the generic type suspend (Int) -> String. ",
    )
    // ADR-236: a listener's lambda property is a bridge slot now, so `Bus.addListener` binds.
    assertTrue(
      result.kspWarnings.none { line -> "Skipping tier1.spelling.Bus.addListener" in line },
      "Bus.addListener binds",
    )
  }

  /** The classifier's generic-class refusals interpolated the `KSType` argument itself. */
  @Test
  fun `a refused generic type argument names a function type as the source spells it`() {
    val type: ForwardDiagnosticKind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE

    assertContains(
      diagnostic(type, "crateOfSuspend"),
      "the erased wire cannot read its type argument `suspend (Int) -> Int`. ",
    )
    assertContains(
      diagnostic(type, "crateOfNullableSuspend"),
      "the erased wire cannot read its type argument `(suspend (Int) -> Int)?`. ",
    )
    assertContains(
      diagnostic(type, "crateOfNullablePlain"),
      "the erased wire cannot read its type argument `((Int) -> Int)?`. ",
    )
    assertContains(
      diagnostic(type, "crateOfFlow"),
      "the erased wire cannot read its type argument `Flow<suspend (Int) -> Int>`: ",
    )
    // The projection is the point of this one, so it is kept.
    assertContains(
      diagnostic(type, "crateProjected"),
      "`Crate<out suspend (Int) -> Int>` is a use-site projection",
    )
    // Every projection the raw `KSType` printed is still printed.
    assertContains(diagnostic(type, "crateConsumer"), "`Crate<in Int>` is a use-site projection")
    // ADR-237: `Crate<*>` reads at its bound, `Crate<object?>`, so it binds and is not named.
    assertTrue(result.kspWarnings.none { "crateStar" in it }, "crateStar binds")
  }

  @Test
  fun `an unexported supertype names a function type argument as the source spells it`() {
    val line: String = diagnostic(
      ForwardDiagnosticKind.SKIPPED_UNEXPORTED_SUPERTYPE,
      "TreatHandler : Handler<suspend (Int) -> String>",
    )

    assertContains(line, "super-interface 'Handler<suspend (Int) -> String>' has a type argument")
  }

  /** The dropped-bound note had its own speller, with no arrow form either. */
  @Test
  fun `a dropped bound names a function type argument as the source spells it`() {
    assertContains(
      diagnostic(ForwardDiagnosticKind.INFO_DROPPED_BOUND, "Kennel<T>"),
      "bound 'Handler<suspend (Int) -> String>' on this type parameter has a type argument",
    )
  }

  /** `BridgeType.diagnosticTypeName()`: the `?` belongs to the function type, not its result. */
  @Test
  fun `a nullable lambda collection element keeps its parentheses`() {
    assertContains(
      diagnostic(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY, "Chef.heldNullables"),
      "its type Collection (element type ((Int) -> String)?) has no property getter",
    )
  }

  /**
   * The net under the named cells: no diagnostic for this fixture carries a `KSType.toString()`
   * function type. The angle bracket is what tells it from a bare qualified name
   * (`kotlin.coroutines.SuspendFunction1`), which a property and a value-class refusal still print.
   */
  @Test
  fun `no diagnostic spells a function type by its class name and type arguments`() {
    val classSpelling = Regex("""\b(Suspend)?Function\d+<""")
    val offenders: List<String> =
      (result.kspErrors + result.kspWarnings).filter { line -> classSpelling.containsMatchIn(line) }

    assertEquals(emptyList(), offenders)
  }
}
