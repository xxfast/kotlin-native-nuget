package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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

    listOf(
      "`fun arityZero` takes `prepare: suspend () -> String`,",
      "`fun arityOne` takes `prepare: suspend (Int) -> String`,",
      "`fun arityTwo` takes `prepare: suspend (Int, Int) -> String`,",
      "`fun arityThree` takes `prepare: suspend (Int, Int, Int) -> String`,",
      "`fun nullable` takes `prepare: (suspend (Int) -> String)?`,",
    ).forEach { expected -> assertContains(line, expected) }
  }

  @Test
  fun `an unimplementable interface names a suspend lambda result and property the same way`() {
    val line: String =
      diagnostic(ForwardDiagnosticKind.SKIPPED_UNIMPLEMENTABLE_INTERFACE, "tier1.spelling.Pet")

    listOf(
      "`fun handsOut` returns `suspend (Int) -> String`,",
      "`fun handsOutNullable` returns `(suspend (Int) -> String)?`,",
      "`val held: suspend (Int) -> String` has a type the bridge cannot carry",
      "`val heldNullable: (suspend (Int) -> String)?` has a type the bridge cannot carry",
      "For `val held: suspend (Int) -> String`, use one of",
    ).forEach { expected -> assertContains(line, expected) }
  }

  /** A plain function type was printed `Function1<Int, String>` by the same three sites. */
  @Test
  fun `an unimplementable interface names a plain lambda as the source spells it`() {
    val line: String =
      diagnostic(ForwardDiagnosticKind.SKIPPED_UNIMPLEMENTABLE_INTERFACE, "tier1.spelling.Pet")

    listOf(
      "`fun plain` takes `prepare: (Int) -> String`,",
      "`fun plainNullable` takes `prepare: ((Int) -> String)?`,",
      "`fun handsOutPlain` returns `(Int) -> String`,",
      "`val heldPlain: (Int) -> String` has a type the bridge cannot carry",
    ).forEach { expected -> assertContains(line, expected) }
  }

  /** `KSType.kotlinSpelling()`, read by the refused legacy suspend route. */
  @Test
  fun `a refused suspend member names its suspend lambda parameter as the source spells it`() {
    val input: ForwardDiagnosticKind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT

    assertContains(
      diagnostic(input, "Chef.maybe"),
      "but not prepare: (suspend (Int) -> String)?. ",
    )
    assertContains(diagnostic(input, "Chef.initial"), "but not pick: suspend (Int) -> Char. ")
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

    val nullable: String = diagnostic(returned, "Chef.makeNullable")
    assertContains(
      nullable,
      "its nullable return type `(suspend (Int) -> String)?` has no supported wire",
    )
    assertContains(
      nullable,
      "the nullable return type `(suspend (Int) -> String)?` has no wire at a return",
    )
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
  fun `a refused flow element and listener property name a suspend lambda the same way`() {
    assertContains(
      diagnostic(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY, "Chef.heldFlow"),
      "but not the generic type suspend (Int) -> String. ",
    )
    assertContains(
      diagnostic(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN, "Bus.addListener"),
      "its listener property `Listener.val held: suspend (Int) -> String` has a type",
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
    assertContains(diagnostic(type, "crateStar"), "`Crate<*>` is a use-site projection")
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
