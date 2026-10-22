package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-236: a lambda-typed property binds on every owner through the property plan, as a BCL
 * delegate C# property (`Func<...>`, `Action<...>`, the ADR-210 `Func<..., CancellationToken,
 * Task<...>>` for a `suspend` one). The getter binds the delegate to the lambda's `KotlinFunc`
 * wrapper; the setter takes any delegate, a Kotlin-bound one passing its own handle back. Before
 * it, only an ordinary class and a sealed arm bound one (get-only, non-null, `KotlinFunc` typed),
 * and every other owner named a skip.
 *
 * One fixture per owner the class route never reached: an `object`, a default and a named
 * companion, the top level, an enum's body and its companion, a generic class, and an interface
 * on both halves. Plus a nullable and a `var` on an ordinary class, and a nested generic payload.
 * A sealed arm binds on the same plan, setter included.
 *
 * Oreo sprints the hallway at 3 a.m.; Mylo times him from the couch.
 */
class Tier1LambdaPropertyOwnersTest {

  private val source: String = """
    package tier1.zoom

    object Hallway {
      val onLap: (Int) -> Unit = {}
      var format: (Double) -> String = { "${'$'}it m" }
      var onBump: ((String) -> String)? = null
      var onSettle: (suspend (String) -> String)? = null
    }

    class Track(val runner: String) {
      var onDash: ((Int) -> String)? = null
      var unwind: () -> Tote<Tote<Skein>> = { Tote(Tote(Skein("red"))) }

      companion object {
        var announce: (String) -> String = { it }
      }
    }

    class Den(val owner: String) {
      companion object Warren {
        val onDig: (Int) -> Int = { it * 3 }
      }
    }

    var zoomFormat: (Int) -> String = { "${'$'}it zooms" }

    enum class Gait {
      TROT, GALLOP;

      var cheer: (String) -> String = { it }

      companion object {
        var fallback: ((Gait) -> String)? = null
      }
    }

    class Relay<T>(var baton: T) {
      val onPass: (T) -> T = { it }
    }

    class Skein(val colour: String)

    class Tote<T>(val item: T)

    interface Pouncer {
      val onPounce: (Int) -> String
      var cue: ((String) -> String)?
      val onLeap: suspend (Int) -> Int
    }

    fun pounce(pouncer: Pouncer, times: Int): String = pouncer.onPounce(times)

    sealed class Feed {
      class Live(val label: String) : Feed() {
        val onTick: (Int) -> Int = { it }
        var onDrop: ((Int) -> Int)? = null
      }
    }
  """.trimIndent()

  private val result: Tier1Result by lazy { Tier1Harness.run(source) }

  private val csharp: String by lazy { result.generatedCSharp }

  private val diagnostics: List<String> by lazy {
    (result.kspErrors + result.kspWarnings)
      .flatMap { entry -> entry.lines() }
      .filter { line -> line.startsWith("[nuget:") }
  }

  /** The declaration lines of [csharp], `///` doc lines excluded (CLAUDE.md: docs name skips). */
  private val declarations: List<String> by lazy {
    csharp.lines().filterNot { line -> line.trimStart().startsWith("///") }
  }

  private fun declares(fragment: String): Boolean = declarations.any { line -> fragment in line }

  @Test
  fun `every owner's lambda property binds, and the generated Kotlin compiles`() {
    assertTrue(result.kspSucceeded, "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    val skipped: List<String> = diagnostics.filter { line ->
      "Skipping tier1.zoom." in line && "Feed" !in line
    }
    assertEquals(emptyList(), skipped, "no lambda property may be skipped; got $skipped")
  }

  @Test
  fun `an object's lambda properties are static wrapper properties with setters`() {
    assertTrue(declares("public static Action<int> OnLap"), "OnLap")
    assertTrue(declares("public static Func<double, string> Format"), "Format")
    assertTrue(declares("public static Func<string, string>? OnBump"), "OnBump")
    val settle = "public static Func<string, global::System.Threading.CancellationToken, " +
      "global::System.Threading.Tasks.Task<string>>? OnSettle"
    assertTrue(declares(settle), "OnSettle")
    assertTrue(declares("hallway_set_format\""), "format setter")
  }

  @Test
  fun `a nullable lambda reads the null pointer as null`() {
    assertTrue(
      declares(
        "return nativeResult == IntPtr.Zero ? null : " +
          "new KotlinFunc<string, string>(nativeResult).Invoke;",
      ),
      "expected the null-pointer guard on a nullable lambda getter",
    )
    assertTrue(
      declares("((KotlinFunc<string, string>?)value)?._handle ?? NugetKotlinHandle.Null"),
      "expected a null C# value to cross as the null handle",
    )
  }

  @Test
  fun `the Kotlin setter reads the borrowed lambda handle as the property's own type`() {
    assertTrue(
      "nugetUncheckedCast(value.asStableRef<Any>().get())" in result.generated,
      "expected the non-null setter's read",
    )
    assertTrue(
      "if (value == null) null else nugetUncheckedCast(value.asStableRef<Any>().get())" in
        result.generated,
      "expected the nullable setter's read",
    )
  }

  @Test
  fun `companions, the top level and an enum bind on their static and extension spellings`() {
    assertTrue(declares("public static Func<string, string> Announce"), "Track.Announce")
    assertTrue(declares("public static Func<int, int> OnDig"), "Den.OnDig")
    assertTrue(declares("public static Func<int, string> ZoomFormat"), "ZoomFormat")
    assertTrue(declares("Func<string, string> Cheer(this "), "Gait.Cheer()")
    assertTrue(declares("SetCheer(this "), "Gait.SetCheer(...)")
    assertTrue(declares("Gait, string>? Fallback"), "Gait companion")
  }

  @Test
  fun `a generic class lambda payload is typed by its T, and a nested generic payload is named`() {
    assertTrue(declares("public Func<T, T> OnPass"), "Relay<T>.OnPass")
    assertTrue(declares("Skein>>> Unwind") && declares("public Func<global::"), "Track.Unwind")
  }

  @Test
  fun `an interface's lambda properties bind on both halves`() {
    assertTrue(declares("Func<int, string> OnPounce { get; }"), "IPouncer.OnPounce")
    assertTrue(declares("Func<string, string>? Cue { get; set; }"), "IPouncer.Cue")
    assertTrue(
      diagnostics.none { line -> "SKIPPED_UNIMPLEMENTABLE_INTERFACE" in line && "Pouncer" in line },
      "a C# implementation of IPouncer must be passable; got $diagnostics",
    )
    assertTrue(
      "override var cue: ((kotlin.String) -> kotlin.String)?" in result.generated,
      "expected the bridge object's override of the lambda var",
    )
  }

  @Test
  fun `every wrapper converts implicitly from its BCL delegate`() {
    assertTrue(
      declares("public static implicit operator KotlinFunc<T1, TResult>(Func<T1, TResult>? fn)"),
      "KotlinFunc conversion",
    )
    assertTrue(
      declares("public static implicit operator KotlinAction<T1>(Action<T1>? fn)"),
      "KotlinAction conversion",
    )
    assertTrue(
      declares(
        "public static implicit operator KotlinSuspendFunc<T1, TResult>(Func<T1, " +
          "global::System.Threading.CancellationToken, global::System.Threading.Tasks.Task<TResult>>? fn)",
      ),
      "KotlinSuspendFunc conversion",
    )
  }

  @Test
  fun `a sealed arm binds on the plan, its var setter included`() {
    assertTrue(declares("public Func<int, int> OnTick"), "expected Feed.Live.OnTick")
    assertTrue(declares("public Func<int, int>? OnDrop"), "expected Feed.Live.OnDrop")
    assertTrue(declares("feed_live_set_onDrop\""), "expected the arm's setter extern")
  }

  @Test
  fun `a delegate a getter bound to a Kotlin lambda passes that lambda straight back`() {
    assertTrue(
      declares(
        "if (fn.Target is KotlinFunc<T1, TResult> kotlin && " +
          "fn.Method.Name == \"Invoke\") return kotlin;",
      ),
      "expected the identity pass-through in the conversion",
    )
  }
}
