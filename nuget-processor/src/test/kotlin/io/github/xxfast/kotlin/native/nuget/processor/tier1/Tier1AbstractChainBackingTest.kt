package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The abstract-class backing wrapper below its first level, and the routes it met there. Each cell
 * compiles both generated halves for real, the Kotlin and the C#, because every bug here was a
 * compile break the text alone did not show:
 *
 * - `abstract class Puppy : Animal()` got no wrapper, so returning one rendered `new Puppy(...)`
 *   (CS0144). Its wrapper now overrides the members the whole abstract chain leaves open, each over
 *   the export of the base that declared it.
 * - `abstract fun ticks(): Flow<Int>` failed the ABI contract: the Kotlin half dropped abstract
 *   Flow members while the C# half declared them. An `override` of a base's Flow member, abstract
 *   or `open`, then hid the base's (CS0108).
 * - an arm's `fun backing()` beside a `class Backing` nested in the sealed base hid that type
 *   (CS0108) with nothing naming it.
 * - a generic abstract owner (the one abstract owner left with no wrapper) never declared a planned
 *   lambda-parameter member its subclasses `override` (CS0115), and declared one their plans
 *   refuse (CS0534).
 *
 * Mylo naps between walks; Oreo naps instead of them.
 */
class Tier1AbstractChainBackingTest {

  private fun run(source: String): Tier1Result = Tier1Harness.run(
    source,
    processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  /** The wrapper declared as `internal sealed class [name] : [owner]`, to its `Dispose`. */
  private fun String.wrapper(name: String, owner: String): String =
    substringAfter("internal sealed class $name : $owner").substringBefore("Dispose()")

  private val chain: Tier1Result by lazy {
    run(
      """
      package tier1.abstractchain

      abstract class Animal {
        abstract val name: String
        abstract fun legs(): Int
        open fun sound(): String = "..."
        abstract suspend fun nap(): Int
      }

      abstract class Puppy : Animal() {
        abstract fun wag(): Int
        override fun legs(): Int = 4
      }

      class Pup : Puppy() {
        override val name: String = "Mylo"
        override suspend fun nap(): Int = 2
        override fun wag(): Int = 3
      }

      sealed class Mood {
        abstract class Low : Mood() {
          abstract fun depth(): Int
        }

        class High : Mood()
      }

      abstract class Gloom : Mood.Low() {
        abstract fun shade(): String
      }

      class Murk : Gloom() {
        override fun depth(): Int = 2
        override fun shade(): String = "grey"
      }

      class Kennel {
        fun pup(): Puppy = Pup()
        fun gloom(): Gloom = Murk()
      }
      """.trimIndent(),
    )
  }

  @Test
  fun `an abstract class below an abstract base or arm materialises through its own wrapper`() {
    assertTrue(chain.compiledClean, "expected a clean compile; got: ${chain.compileErrors}")
    val cs: String = chain.generatedCSharp

    assertFalse(
      Regex("""new (global::[\w.]+\.)?(Puppy|Gloom)\(""").containsMatchIn(cs),
      "an abstract class must never be constructed (CS0144); generated=$cs",
    )
    // `Animal.Backing` and `Mood.Low.Backing` are inherited, so the derived wrappers step aside.
    assertContains(cs, "new global::Interop.Abstractchain.Puppy.Backing_(nativeResult, out _)")
    assertContains(cs, "new global::Interop.Abstractchain.Gloom.Backing_(nativeResult, out _)")

    val puppy: String = cs.wrapper("Backing_", "Puppy")
    assertContains(puppy, "public override int Wag()")
    assertContains(puppy, "EntryPoint = \"library_abstractchain__puppy_wag\"")
    // Inherited from `Animal`, called over `Animal`'s own export, which dispatches virtually.
    assertContains(puppy, "public override string Name")
    assertContains(puppy, "EntryPoint = \"library_abstractchain__animal_get_name\"")
    // `Puppy` implements `legs()` itself, so its wrapper does not override it again.
    assertFalse("Legs()" in puppy, "Puppy's own Legs() covers it; wrapper=$puppy")

    val gloom: String = cs.wrapper("Backing_", "Gloom")
    assertContains(gloom, "public override string Shade()")
    assertContains(gloom, "public override int Depth()")
    assertContains(gloom, "EntryPoint = \"library_abstractchain__mood_low_depth\"")
  }

  @Test
  fun `the chain's generated C# compiles and a consumer reads every inherited member`() {
    Tier1CSharpCompile.assertCompiles(
      chain,
      """
      using System.Threading.Tasks;
      using Interop.Abstractchain;

      namespace Consumer
      {
          public static class Probe
          {
              public static async Task<string> Run(Kennel kennel)
              {
                  await using Puppy pup = kennel.Pup();
                  using Gloom gloom = kennel.Gloom();
                  int nap = await pup.NapAsync();
                  return pup.Name + pup.Legs() + pup.Wag() + nap + gloom.Depth() + gloom.Shade();
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  @Test
  fun `an abstract Flow member exports a call-through and an override does not hide it`() {
    val flows: Tier1Result = run(
      """
      package tier1.abstractflow

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      abstract class Ticker {
        abstract fun ticks(): Flow<Int>
        abstract suspend fun wait(): Int
      }

      class Clock : Ticker() {
        override fun ticks(): Flow<Int> = flowOf(1, 2)
        override suspend fun wait(): Int = 3
      }

      open class Clockwork {
        open fun ticks(): Flow<Int> = flowOf(0)
      }

      class Watch : Clockwork() {
        override fun ticks(): Flow<Int> = flowOf(5)
      }

      class Shop {
        fun ticker(): Ticker = Clock()
        fun watch(): Clockwork = Watch()
      }
      """.trimIndent(),
    )
    assertTrue(flows.kspErrors.isEmpty(), "no ABI contract failure; kspErrors=${flows.kspErrors}")
    assertTrue(flows.compiledClean, "expected a clean compile; got: ${flows.compileErrors}")
    val kotlin: String = flows.generated
    val cs: String = flows.generatedCSharp

    assertContains(kotlin, "@CName(\"library_abstractflow__ticker_ticks_collect\")")
    assertContains(cs, "EntryPoint = \"library_abstractflow__ticker_ticks_collect\"")
    // One declaration per chain, on the base, for the abstract and the open overridee alike.
    assertEquals(2, Regex("""public KotlinFlow<int> Ticks\(\)""").findAll(cs).count(), cs)
    assertFalse("library_abstractflow__clock_ticks_collect" in kotlin, kotlin)
    assertFalse("library_abstractflow__watch_ticks_collect" in kotlin, kotlin)

    Tier1CSharpCompile.assertCompiles(
      flows,
      """
      using System.Threading.Tasks;
      using Interop.Abstractflow;

      namespace Consumer
      {
          public static class Probe
          {
              public static async Task<object> Run(Shop shop)
              {
                  await using Ticker ticker = shop.Ticker();
                  using Clockwork watch = shop.Watch();
                  return (ticker.Ticks(), await ticker.WaitAsync(), watch.Ticks());
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  @Test
  fun `a member hiding a type nested in the sealed base is named, once, at the arm`() {
    val hidden: Tier1Result = run(
      """
      package tier1.abstractden

      sealed class Den {
        class Backing
        abstract class Burrow : Den() {
          abstract fun backing(): Int
        }
        class Nest : Den()
      }

      class Fox : Den.Burrow() {
        override fun backing(): Int = 1
      }

      class Wood {
        fun den(): Den = Fox()
      }
      """.trimIndent(),
    )
    val collisions: List<String> =
      hidden.kspErrors.filter { "ERROR_CSHARP_NAME_COLLISION" in it }
    assertEquals(1, collisions.size, "one error, at the arm; kspErrors=${hidden.kspErrors}")
    assertContains(collisions.single(), "Burrow.Backing")
    assertContains(collisions.single(), "Den.Backing")
    assertContains(collisions.single(), "CS0108")
    assertContains(collisions.single(), "or give the member a different `@CSharpName`")
  }

  @Test
  fun `the wrapper steps aside from a type nested in the sealed base`() {
    val named: Tier1Result = run(
      """
      package tier1.abstractlair

      sealed class Lair {
        class Backing
        abstract class Burrow : Lair() {
          abstract fun depth(): Int
        }
        class Nest : Lair()
      }

      class Fox : Lair.Burrow() {
        override fun depth(): Int = 1
      }

      class Wood {
        fun lair(): Lair = Fox()
      }
      """.trimIndent(),
    )
    assertTrue(named.compiledClean, "expected a clean compile; got: ${named.compileErrors}")
    assertContains(named.generatedCSharp, "internal sealed class Backing_ : Burrow")
    Tier1CSharpCompile.assertCompiles(
      named,
      """
      using Interop.Abstractlair;

      namespace Consumer
      {
          public static class Probe
          {
              public static int Run(Wood wood)
              {
                  using Lair lair = wood.Lair();
                  return ((Lair.Burrow)lair).Depth();
              }
          }
      }
      """.trimIndent(),
    )
  }

  @Test
  fun `a generic abstract owner declares exactly what its subclasses' plans override`() {
    val pets: Tier1Result = run(
      """
      package tier1.abstractpet

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      abstract class Animal {
        abstract fun legs(): Int
      }

      abstract class Hound : Animal() {
        abstract fun onPet(block: (String) -> Unit)
        abstract fun onTreat(): (String) -> String
        abstract suspend fun nap(): Int
      }

      class Dog : Hound() {
        override fun onPet(block: (String) -> Unit) = block("pat")
        override fun onTreat(): (String) -> String = { it }
        override suspend fun nap(): Int = 1
        override fun legs(): Int = 4
      }

      abstract class Crate<T> {
        abstract fun onPet(block: (String) -> Unit)
        abstract fun onTreat(): (String) -> String
        abstract fun ticks(): Flow<Int>
        abstract suspend fun nap(): Int
        abstract fun open(): T
      }

      class Box : Crate<Int>() {
        override fun onPet(block: (String) -> Unit) = block("pat")
        override fun onTreat(): (String) -> String = { it }
        override fun ticks(): Flow<Int> = flowOf(1)
        override suspend fun nap(): Int = 1
        override fun open(): Int = 1
      }

      class Bed {
        fun dog(): Dog = Dog()
        fun hound(): Hound = Dog()
        fun box(): Box = Box()
      }
      """.trimIndent(),
    )
    assertTrue(pets.compiledClean, "expected a clean compile; got: ${pets.compileErrors}")
    val cs: String = pets.generatedCSharp

    val crate: String = cs.substringAfter("public abstract class Crate<T>")
      .substringBefore("public class Box")
    assertContains(crate, "public abstract void OnPet(Action<string> block);")
    // Every subclass's plan refuses the lambda return, so the owner must not promise it (CS0534).
    assertFalse(Regex("""\bOnTreat\(""").containsMatchIn(crate), "Crate<T> declares no OnTreat")
    assertTrue(
      pets.kspWarnings.any { "SKIPPED_UNSUPPORTED_RETURN" in it && "Crate.onTreat" in it },
      "the refusal is named on Crate; kspWarnings=${pets.kspWarnings}",
    )
    // A generic base projects no Flow or suspend member, so its subclass carries them.
    val box: String = cs.substringAfter("public class Box : Crate<int>")
    assertContains(box, "public KotlinFlow<int> Ticks()")
    assertContains(box, "public Task<int> NapAsync(")

    Tier1CSharpCompile.assertCompiles(
      pets,
      """
      using System.Threading.Tasks;
      using Interop.Abstractpet;

      namespace Consumer
      {
          public static class Probe
          {
              public static async Task<object> Run(Bed bed)
              {
                  await using Dog dog = bed.Dog();
                  await using Hound hound = bed.Hound();
                  await using Box box = bed.Box();
                  dog.OnPet(s => { });
                  hound.OnPet(s => { });
                  box.OnPet(s => { });
                  return (box.Ticks(), await box.NapAsync(), box.Open(), await hound.NapAsync());
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  /**
   * ADR-195's twin on an inherited abstract member: the derived wrapper overrides it exactly when
   * the base still declares it. `Box`'s `tryWeigh` property drops `Crate`'s twin with its whole
   * chain, so `Box`'s wrapper must not override it (CS0505 against the property otherwise).
   */
  @Test
  fun `an inherited abstract Result twin is overridden exactly when its base keeps it`() {
    val twins: Tier1Result = run(
      """
      package tier1.abstractresult

      abstract class Scale {
        abstract fun weigh(): Result<Int>
        abstract val grams: Int
      }

      abstract class Kitchen : Scale()

      class Digital : Kitchen() {
        override fun weigh(): Result<Int> = Result.success(4)
        override val grams: Int = 3
      }

      abstract class Crate {
        abstract fun weigh(): Result<Int>
      }

      abstract class Box : Crate() {
        val tryWeigh: Int = 0
      }

      class Bin : Box() {
        override fun weigh(): Result<Int> = Result.success(9)
      }

      class Shed {
        fun kitchen(): Kitchen = Digital()
        fun box(): Box = Bin()
      }
      """.trimIndent(),
    )
    assertTrue(twins.compiledClean, "expected a clean compile; got: ${twins.compileErrors}")
    val cs: String = twins.generatedCSharp
    assertContains(cs.wrapper("Backing_", "Kitchen"), "public override bool TryWeigh(")
    assertContains(cs.wrapper("Backing_", "Box"), "public override int Weigh()")
    assertFalse("TryWeigh(" in cs.wrapper("Backing_", "Box"), "Box's wrapper has no twin; $cs")

    Tier1CSharpCompile.assertCompiles(
      twins,
      """
      using Interop.Abstractresult;

      namespace Consumer
      {
          public static class Probe
          {
              public static int Run(Shed shed)
              {
                  using Kitchen kitchen = shed.Kitchen();
                  using Box box = shed.Box();
                  int grams = kitchen.TryWeigh(out int g, out System.Exception? _) ? g : 0;
                  return kitchen.Weigh() + kitchen.Grams + box.Weigh() + grams;
              }
          }
      }
      """.trimIndent(),
    )
  }

  /**
   * ADR-197's override rule and ADR-179's keyword escape through a wrapper one level down: a
   * generic abstract member restates no constraint and keeps `where T : default` for `T?`, and a
   * keyword parameter is escaped, exactly as on the base's own wrapper.
   */
  @Test
  fun `a deeper wrapper overrides a generic and a keyword-named member as the base wrapper does`() {
    val deep: Tier1Result = run(
      """
      package tier1.abstractgeneric

      abstract class Groomer {
        abstract fun <T> groom(item: T): T
        abstract fun <T> spare(item: T?): T?
        abstract fun stash(string: String, lock: Int): Int
      }

      abstract class Brusher : Groomer() {
        abstract fun bristles(): Int
      }

      class Comb : Brusher() {
        override fun <T> groom(item: T): T = item
        override fun <T> spare(item: T?): T? = item
        override fun stash(string: String, lock: Int): Int = string.length + lock
        override fun bristles(): Int = 9
      }

      class Salon {
        fun brusher(): Brusher = Comb()
      }
      """.trimIndent(),
    )
    assertTrue(deep.compiledClean, "expected a clean compile; got: ${deep.compileErrors}")
    val brusher: String = deep.generatedCSharp.wrapper("Backing_", "Brusher")
    assertContains(brusher, "public override T Groom<T>(T item)")
    assertContains(brusher, "public override T? Spare<T>(T? item) where T : default")
    assertContains(brusher, "public override int Stash(string @string, int @lock)")
    assertContains(brusher, "EntryPoint = \"library_abstractgeneric__groomer_groom\"")

    Tier1CSharpCompile.assertCompiles(
      deep,
      """
      using Interop.Abstractgeneric;

      namespace Consumer
      {
          public static class Probe
          {
              public static int Run(Salon salon)
              {
                  using Brusher brusher = salon.Brusher();
                  string? spared = brusher.Spare<string>(null);
                  return brusher.Groom(3) + brusher.Stash("ab", 1) + brusher.Bristles() +
                      (spared?.Length ?? 0);
              }
          }
      }
      """.trimIndent(),
    )
  }

  /**
   * A subclass inherits its base's nested wrapper, so a subclass member named like it hid it
   * (CS0108). The base's wrapper is named past every public subclass's members, and a deeper
   * wrapper past both.
   */
  @Test
  fun `a subclass member named like the base's wrapper does not hide it`() {
    val hidden: Tier1Result = run(
      """
      package tier1.abstracthide

      abstract class Animal {
        abstract fun legs(): Int
      }

      class Dog : Animal() {
        override fun legs(): Int = 4
        fun backing(): Int = 1
      }

      abstract class Puppy : Animal() {
        fun backing(): Int = 2
      }

      class Pup : Puppy() {
        override fun legs(): Int = 3
      }

      class Bed {
        fun pet(): Animal = Dog()
        fun pup(): Puppy = Pup()
      }
      """.trimIndent(),
    )
    assertTrue(hidden.compiledClean, "expected a clean compile; got: ${hidden.compileErrors}")
    val cs: String = hidden.generatedCSharp
    assertContains(cs, "internal sealed class Backing_ : Animal")
    assertContains(cs, "internal sealed class Backing__ : Puppy")
    Tier1CSharpCompile.assertCompiles(
      hidden,
      """
      using Interop.Abstracthide;

      namespace Consumer
      {
          public static class Probe
          {
              public static int Run(Bed bed)
              {
                  using Animal pet = bed.Pet();
                  using Puppy pup = bed.Pup();
                  using Dog dog = new Dog();
                  return pet.Legs() + pup.Legs() + pup.Backing() + dog.Backing();
              }
          }
      }
      """.trimIndent(),
    )
  }

  /**
   * A lambda-typed property a kept base binds is reached through the base's getter, which Kotlin
   * dispatches to the override; the subclass re-declared it with no `override` and hid it (CS0108),
   * on an abstract and an `open` base alike. A generic base binds none, so its subclass still does.
   */
  @Test
  fun `an overriding lambda property is bound once, on the base that declares it`() {
    val lambdas: Tier1Result = run(
      """
      package tier1.abstractsniff

      abstract class Hound {
        abstract val onSniff: (String) -> String
      }

      class Dog : Hound() {
        override val onSniff: (String) -> String = { it + "!" }
      }

      open class Cat {
        open val onPet: (String) -> String = { it }
      }

      class Kitten : Cat() {
        override val onPet: (String) -> String = { it + "?" }
      }

      abstract class Crate<T> {
        abstract val onOpen: (String) -> String
      }

      class Box : Crate<Int>() {
        override val onOpen: (String) -> String = { it }
      }
      """.trimIndent(),
    )
    assertTrue(lambdas.compiledClean, "expected a clean compile; got: ${lambdas.compileErrors}")
    val cs: String = lambdas.generatedCSharp
    val kotlin: String = lambdas.generated
    assertEquals(1, Regex("""KotlinFunc<string, string> OnSniff\b""").findAll(cs).count(), cs)
    assertEquals(1, Regex("""KotlinFunc<string, string> OnPet\b""").findAll(cs).count(), cs)
    assertEquals(1, Regex("""KotlinFunc<string, string> OnOpen\b""").findAll(cs).count(), cs)
    assertFalse("library_abstractsniff__dog_get_onSniff" in kotlin, kotlin)
    assertFalse("library_abstractsniff__kitten_get_onPet" in kotlin, kotlin)
    assertContains(kotlin, "@CName(\"library_abstractsniff__box_get_onOpen\")")
    // Bound, so never reported as skipped (the lambda-property rule, ADR-064); only the generic
    // base's, which carries none (ADR-147), is named, and `Box` binds its own.
    val skipped: List<String> = lambdas.kspWarnings.filter { "SKIPPED_UNSUPPORTED_PROPERTY" in it }
    assertEquals(1, skipped.size, "only Crate.onOpen is reported; ${lambdas.kspWarnings}")
    assertContains(skipped.single(), "Crate.onOpen")

    Tier1CSharpCompile.assertCompiles(
      lambdas,
      """
      using Interop.Abstractsniff;

      namespace Consumer
      {
          public static class Probe
          {
              public static string Run()
              {
                  using Dog dog = new Dog();
                  using Kitten kitten = new Kitten();
                  using Box box = new Box();
                  using var sniff = dog.OnSniff;
                  using var pet = kitten.OnPet;
                  using var open = box.OnOpen;
                  return sniff.Invoke("a") + pet.Invoke("b") + open.Invoke("c");
              }
          }
      }
      """.trimIndent(),
    )
  }

  /** CS0542: a member named like the type that declares it is a named error, not broken C#. */
  @Test
  fun `a member named like its own type is a named error`() {
    val named: Tier1Result = run(
      """
      package tier1.ownname

      sealed class Handler {
        class OnTap(val onTap: (Int) -> String) : Handler()
      }

      class Cat(val cat: Int)
      """.trimIndent(),
    )
    val errors: List<String> = named.kspErrors.filter { "ERROR_CSHARP_NAME_COLLISION" in it }
    assertTrue(
      errors.any { "OnTap.OnTap" in it && "CS0542" in it },
      "the arm's own-name property is named; kspErrors=${named.kspErrors}",
    )
    assertTrue(
      errors.any { "Cat.Cat" in it && "CS0542" in it },
      "the class's own-name property is named; kspErrors=${named.kspErrors}",
    )
  }
}
