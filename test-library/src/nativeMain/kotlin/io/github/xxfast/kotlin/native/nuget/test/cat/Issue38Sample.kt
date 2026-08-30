package io.github.xxfast.kotlin.native.nuget.test.cat

/**
 * Issue #38 (ROADMAP line 63): nullable properties on a sealed subclass — a class nested inside its
 * sealed parent — lose their `?` in the generated `CNameExports.kt`, so `compileKotlinMingwX64`
 * rejects the generated file with `Return type mismatch: expected 'String', actual 'String?'`. The
 * nested path also drops the `errorOut` parameter the top-level path carries.
 *
 * [Issue38State.Loaded] is the shape that blocks the idiomatic
 * `sealed class UiState { data class Success(val error: String? = null) : UiState() }` pattern,
 * since sealed subclasses are almost always nested. It carries one `String?`, one `Int?`, and one
 * non-null `Int` — the non-null scalar is the control: it must keep working while the two nullable
 * ones are what the export drops the `?` from.
 *
 * The report's other nesting shape, a plain class nesting a `data class`, is deliberately NOT here:
 * such a class is never collected at all (every root bucket in `NugetProcessor.kt` filters
 * `parentDeclaration == null`), which is a separate missing capability rather than this bug.
 */
sealed class Issue38State {
  data class Loaded(val error: String?, val retries: Int?, val code: Int) : Issue38State()
  data object Idle : Issue38State()
}

/**
 * Sealed subclasses only get an `internal` C# constructor (they arrive through `FromHandle`), so
 * the C# consumer needs a factory to get hold of one — the same shape `Observation.kt` uses with
 * `openBox`/`peekBox`. One `Int` discriminator drives all three cases so this fixture adds no
 * nullable top-level parameters of its own.
 */
fun issue38State(state: Int): Issue38State = when (state) {
  0 -> Issue38State.Loaded("Oreo knocked the water bowl over", 3, 7)
  1 -> Issue38State.Loaded(null, null, 7)
  else -> Issue38State.Idle
}
