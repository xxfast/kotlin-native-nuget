package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-124's flow route on an arm of a GENERIC sealed hierarchy (ADR-199). The arm is the only
 * carrier a flow member has there: a generic sealed base projects none (ADR-147, kept), so a flow
 * property the base declares binds on every arm, the pre-ADR-175 rule.
 *
 * Both halves spelled the arm by its bare name. Kotlin read `asStableRef<Dish.Hot>()`, which does
 * not compile for `Hot<T>` ("One type argument expected", then every member of the receiver
 * unresolved); C# baked `nameof(Hot)` into the disposed guard, which is CS0305 inside `Hot<T>`.
 * The C# half keys on the C# arm, so a non-generic Kotlin arm a phantom parameter makes generic
 * (`Bare : Plate<Nothing>`, rendered `Bare<T>`) broke there too, its `suspend` member included.
 *
 * `libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore)` is load-bearing: without `Flow` on
 * the KSP libraries path every member here is skipped and the assertions pass vacuously.
 */
class Tier1GenericSealedFlowPropertyTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.menu

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.MutableSharedFlow
      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow
      import kotlinx.coroutines.flow.flowOf

      sealed class Dish<T> {
        // Declared on the generic base: bound on every arm, generic or not. `served` is a `var` on
        // purpose: its dropped setter is named once, for the base, and never once per arm.
        var served: Flow<Int> = flowOf(1)
        val heat: StateFlow<Int> = MutableStateFlow(2)

        class Hot<T>(val item: T) : Dish<T>() {
          val steam: Flow<Int> = flowOf(3)
          val gauge: StateFlow<Int> = MutableStateFlow(4)
          // A `var` on purpose: the arm binds the getter and names the setter it drops.
          var dial: MutableStateFlow<Int> = MutableStateFlow(5)
          val bell: MutableSharedFlow<Int> = MutableSharedFlow()
          val items: Flow<T> = flowOf(item)
          val maybe: Flow<Int>? = null
          fun wafts(): Flow<Int> = flowOf(6)
          fun level(times: Int): StateFlow<Int> = MutableStateFlow(times)
        }

        // The control: an arm that fixes the argument is not generic on either half.
        class Cold : Dish<Int>() {
          val frost: Flow<Int> = flowOf(7)
        }

        // An intermediate arm closing the invariant `T`: plain `Dish.Reheated : Dish<int>` in C#.
        sealed class Reheated : Dish<Int>() {
          class Twice(val times: Int) : Reheated() {
            val pings: Flow<Int> = flowOf(times)
          }
        }
      }

      interface Pet { val name: String }
      interface Loud { fun noise(): String }

      sealed class Bowl<T : Pet> {
        class Full<T : Pet>(val pet: T) : Bowl<T>() {
          val names: Flow<String> = flowOf(pet.name)
          val pets: Flow<T> = flowOf(pet)
        }
      }

      sealed class Tray<T> where T : Pet, T : Loud {
        class Laden<T>(val pet: T) : Tray<T>() where T : Pet, T : Loud {
          val noises: Flow<String> = flowOf(pet.noise())
        }
      }

      sealed class Plate<out T> {
        class Heaped<T>(val item: T) : Plate<T>()

        // Not generic in Kotlin, `Bare<T>` in C#: the fixed variant argument is a phantom.
        class Bare(val id: Int) : Plate<Nothing>() {
          // A `var` on purpose: the phantom arm names its own dropped setter, once.
          var crumbs: Flow<Int> = flowOf(id)
          fun drips(): Flow<Int> = flowOf(id)
          suspend fun wipe(): Int = id
        }

        // The phantom carries down an intermediate arm: `Plate.Chipped.Cracked<T>` in C#.
        sealed class Chipped : Plate<Nothing>() {
          class Cracked(val id: Int) : Chipped() {
            val shards: Flow<Int> = flowOf(id)
          }
        }
      }

      class Kitchen {
        fun hot(): Dish<Int> = Dish.Hot(1)
        fun cold(): Dish<Int> = Dish.Cold()
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  /** The export body of [entryPoint], from its `@CName` to the next one. */
  private fun export(entryPoint: String): String {
    val generated: String = result.generated
    val start: Int = generated.indexOf("@CName(\"$entryPoint\")")
    assertTrue(start >= 0, "expected an export named $entryPoint; got: $generated")
    val end: Int = generated
      .indexOf("@CName(", start + 1)
      .let { next -> if (next < 0) generated.length else next }
    return generated.substring(start, end)
  }

  @Test
  fun `the generated Kotlin compiles`() {
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
  }

  @Test
  fun `every flow export of a generic arm reads the erased receiver`() {
    val hot: String = "handle.asStableRef<tier1.menu.Dish.Hot<Any?>>().get()"
    listOf(
      // Flow and StateFlow, declared on the arm and on the generic base.
      "dish_hot_get_steam_collect", "dish_hot_get_served_collect",
      "dish_hot_get_gauge_collect", "dish_hot_get_gauge_value",
      "dish_hot_get_heat_collect", "dish_hot_get_heat_value",
      // The MutableStateFlow write pair and the SharedFlow surface.
      "dish_hot_set_dial_value", "dish_hot_compare_and_set_dial_value",
      "dish_hot_get_bell_replay_cache", "dish_hot_get_bell_subscription_count",
      "dish_hot_emit_bell", "dish_hot_try_emit_bell",
      // An element that is the type parameter, the nullable member's probe, and the method half.
      "dish_hot_get_items_collect", "dish_hot_get_maybe_has_value",
      "dish_hot_wafts_collect", "dish_hot_level_collect", "dish_hot_level_value",
    ).forEach { name ->
      assertContains(export("library_menu__$name"), hot, message = "the receiver of $name")
    }
    // A bound erases to itself; a multi-bound parameter has no erased argument and is projected.
    assertContains(
      export("library_menu__bowl_full_get_pets_collect"),
      "handle.asStableRef<tier1.menu.Bowl.Full<tier1.menu.Pet>>().get()",
    )
    assertContains(
      export("library_menu__tray_laden_get_noises_collect"),
      "handle.asStableRef<tier1.menu.Tray.Laden<*>>().get()",
    )
    // The control arm keeps its bare name: it has one.
    assertContains(
      export("library_menu__dish_cold_get_served_collect"),
      "handle.asStableRef<tier1.menu.Dish.Cold>().get()",
    )
    assertFalse(
      "asStableRef<tier1.menu.Dish.Hot>()" in result.generated,
      "no export may spell the generic arm bare",
    )
  }

  @Test
  fun `the arm carries the non-generic arm's C# surface and a consumer uses it`() {
    val cs: String = result.generatedCSharp
    listOf(
      "public KotlinFlow<int> Steam", "public KotlinStateFlow<int> Gauge",
      "public KotlinMutableStateFlow<int> Dial", "public KotlinMutableSharedFlow<int> Bell",
      "public KotlinFlow<T> Items", "public KotlinFlow<int>? Maybe",
      "public KotlinFlow<int> Served", "public KotlinStateFlow<int> Heat",
      "public KotlinFlow<int> Wafts()", "public KotlinStateFlow<int> Level(int times)",
      "public KotlinFlow<int> Frost", "public KotlinFlow<T> Pets",
      "public KotlinFlow<string> Noises", "public KotlinFlow<int> Crumbs",
      "public KotlinFlow<int> Drips()", "public Task<int> WipeAsync(",
      "public KotlinFlow<int> Pings", "public KotlinFlow<int> Shards",
    ).forEach { member -> assertContains(cs, member) }
    // The disposed guard names the arm as the arm is declared: `nameof(Hot)` is CS0305 in `Hot<T>`.
    listOf(
      "nameof(Hot<T>)", "nameof(Full<T>)", "nameof(Laden<T>)", "nameof(Bare<T>)",
      "nameof(Cracked<T>)", "nameof(Cold)", "nameof(Twice)",
    ).forEach { guard -> assertContains(cs, guard) }
    listOf("nameof(Hot)", "nameof(Full)", "nameof(Laden)", "nameof(Bare)", "nameof(Cracked)")
      .forEach { guard ->
      assertFalse(guard in cs, "$guard does not bind inside the generic arm")
    }
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System.Threading.Tasks;
      using Interop.Menu;
      using Interop;
      using Kotlin.Native.Interop;

      namespace Consumer
      {
          public static class Probe
          {
              public static async Task<string> Run()
              {
                  using var hot = new Dish.Hot<int>(1);
                  KotlinFlow<int> steam = hot.Steam;
                  KotlinStateFlow<int> gauge = hot.Gauge;
                  KotlinMutableStateFlow<int> dial = hot.Dial;
                  dial.Value = gauge.Value;
                  KotlinMutableSharedFlow<int> bell = hot.Bell;
                  KotlinFlow<int> items = hot.Items;
                  KotlinFlow<int>? maybe = hot.Maybe;
                  KotlinFlow<int> served = hot.Served;
                  KotlinStateFlow<int> heat = hot.Heat;
                  KotlinFlow<int> wafts = hot.Wafts();
                  KotlinStateFlow<int> level = hot.Level(2);
                  using var cold = new Dish.Cold();
                  KotlinFlow<int> frost = cold.Frost;
                  KotlinStateFlow<int> coldHeat = cold.Heat;
                  using var bare = new Plate.Bare<string>(3);
                  KotlinFlow<int> crumbs = bare.Crumbs;
                  KotlinFlow<int> drips = bare.Drips();
                  string said = "";
                  await foreach (int item in items)
                  {
                      said += item;
                  }
                  return said + await bare.WipeAsync() + heat.Value + level.Value + coldHeat.Value;
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  @Test
  fun `the generic base carries none of them`() {
    // ADR-147, kept: the generic base has no flow route, so `Served` is each arm's, not
    // `Dish<T>`'s.
    val lines: List<String> = result.generatedCSharp.lines()
    val start: Int = lines.indexOfFirst { "public abstract class Dish<T>" in it }
    assertTrue(start >= 0, "expected the generic sealed base; got: ${result.generatedCSharp}")
    val indent: String = lines[start].takeWhile { it == ' ' }
    val end: Int = (start + 1 until lines.size).first { lines[it] == "$indent}" }
    val base: String = lines.subList(start, end + 1).joinToString("\n")
    listOf("Served", "Heat").forEach { member ->
      assertFalse("> $member" in base, "expected no $member on Dish<T>; got:\n$base")
    }
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
  }

  /**
   * The flow property route meets the named setter skip of a public `var`
   * (`Tier1FlowVarPropertySetterTest`): the getter binds on the arm exactly as a `val`'s does, and
   * the setter the route cannot bind is named once, for the declaration that owns the `var`. The
   * whole list is compared, so a second line for the same property (one per arm of an inherited
   * base member, or one for the erased and one for the declared arm) fails here, and so does any
   * other skip: every member of the fixture binds.
   */
  @Test
  fun `a var flow property keeps its getter and names its dropped setter once`() {
    val coldReason: String =
      "its setter is not generated because a Flow property binds read-only: its getter hands " +
          "out the Kotlin flow, and no setter route turns a C# stream back into one"
    val hotReason: String =
      "its setter is not generated because a MutableStateFlow property binds read-only: its " +
          "getter hands out the Kotlin flow, and C# has no source for a synchronous current " +
          "value or a replay cache to set it from"
    fun coldHint(publicName: String): String =
      "the C# property $publicName is read-only; to let C# replace the flow, expose a function " +
          "taking a `Flow<T>` parameter instead, which binds as `IAsyncEnumerable<T>` for a " +
          "primitive or String element"
    val hotHint: String =
      "the C# property Dial is read-only; to let C# replace the flow, expose a function " +
          "instead: declare the parameter as `Flow<T>` and call `stateIn(scope)` (or `shareIn`) " +
          "on it in Kotlin"
    val code: String = "[nuget:SKIPPED_UNSUPPORTED_INPUT]"
    // Each diagnostic's own line: the `at <file>:<line>` location KSP appends is dropped.
    val skips: List<String> = result.kspWarnings
      .flatMap { warning -> warning.lines() }
      .filter { line -> line.startsWith("[nuget:SKIPPED") }

    assertEquals(
      listOf(
        // The generic arm: named as the arm, never as the erased `Hot<Any?>` or the base.
        "$code Skipping tier1.menu.Dish.Hot.dial: $hotReason. $hotHint",
        // The generic base: one line for the declaration, though every arm binds the getter.
        "$code Skipping tier1.menu.Dish.served: $coldReason. ${coldHint("Served")}",
        // The phantom arm: generic in C# only, and named once like any other arm.
        "$code Skipping tier1.menu.Plate.Bare.crumbs: $coldReason. ${coldHint("Crumbs")}",
      ),
      skips.sorted(),
      "kspWarnings=${result.kspWarnings}",
    )

    // The getter half is untouched: the exports a `val` has, and no setter export beside them.
    listOf(
      "dish_hot_get_dial_collect", "dish_hot_get_dial_value", "dish_hot_get_served_collect",
      "dish_cold_get_served_collect", "plate_bare_get_crumbs_collect",
    ).forEach { name -> export("library_menu__$name") }
    val setterExports: List<String> = result.generated.lines().filter { line ->
      val isExport: Boolean = line.startsWith("@CName(")
      val isPropertySetter: Boolean =
        listOf("_set_dial\")", "_set_served\")", "_set_crumbs\")").any { suffix -> suffix in line }
      isExport && isPropertySetter
    }
    assertEquals(emptyList(), setterExports)
  }
}
