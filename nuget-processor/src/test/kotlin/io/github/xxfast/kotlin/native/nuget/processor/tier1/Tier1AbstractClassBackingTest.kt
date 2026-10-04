package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An ordinary `abstract class` at a return position rendered `new Animal(nativeResult, out _)`
 * against `public abstract class Animal`: CS0144 in the generated file, for every function,
 * property or collection element typed as an abstract class. No fixture had that position, so
 * nothing ever compiled it.
 *
 * The invariant is the abstract sealed arm's (`Tier1SealedAbstractArmTest`): C# never constructs
 * the abstract class itself. It constructs the class's internal `Backing` wrapper, which overrides
 * every abstract member over a call-through export (`asStableRef<Animal>().get().legs()`, Kotlin's
 * own virtual dispatch) and implements the abstract `Dispose()` / `DisposeAsync()`. So `pet()`
 * hands back an `Animal` whose members answer as the Kotlin `Dog` behind it.
 *
 * Mylo has four legs and uses about two of them at a time.
 */
class Tier1AbstractClassBackingTest {

  private val source: String =
    """
    package tier1.abstractpet

    interface Feathered {
      val wings: Int
    }

    abstract class Animal : Feathered {
      abstract val name: String
      abstract var age: Int
      abstract fun legs(): Int
      open fun sound(): String = "..."
      suspend fun nap(): Int = 1
    }

    class Dog : Animal() {
      override val wings: Int = 0
      override val name: String = "Mylo"
      override var age: Int = 3
      override fun legs(): Int = 4
      override fun sound(): String = "woof"
    }

    abstract class Puppy : Animal()

    class Bed {
      fun pet(): Animal = Dog()
      fun maybe(): Animal? = null
      val pets: List<Animal> get() = listOf(Dog())
      suspend fun later(): Animal = Dog()
    }
    """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(source, processorOptions = mapOf("nuget.rootPackage" to "tier1"))
  }

  @Test
  fun `nothing constructs the abstract class itself`() {
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp

    assertFalse(
      Regex("""new (global::[\w.]+\.)?Animal\(""").containsMatchIn(cs),
      "an abstract class must never be constructed (CS0144); generated=$cs",
    )
    assertContains(cs, "new global::Interop.Abstractpet.Animal.Backing(nativeResult, out _)")
    assertContains(cs, "new global::Interop.Abstractpet.Animal.Backing(resultPtr, out _)")
    assertContains(
      cs,
      "[typeof(global::Interop.Abstractpet.Animal)] = static handle => " +
        "new global::Interop.Abstractpet.Animal.Backing(handle, out _),",
    )
  }

  @Test
  fun `the wrapper overrides every abstract member over a call-through export`() {
    val cs: String = result.generatedCSharp
    val kotlin: String = result.generated

    // The class keeps its shipped abstract surface.
    assertContains(cs, "        public abstract int Legs();\n")
    assertContains(cs, "        public abstract string Name { get; }\n")
    assertContains(cs, "        public abstract int Age { get; set; }\n")
    assertContains(cs, "        public abstract int Wings { get; }\n")
    assertContains(cs, "        public abstract void Dispose();\n")
    assertContains(cs, "        public abstract ValueTask DisposeAsync();\n")

    val backing: String = cs.substringAfter("        internal sealed class Backing : Animal")
      .substringBefore("\n        }\n")
    assertContains(backing, "public override int Legs()")
    assertContains(backing, "public override string Name")
    assertContains(backing, "public override int Age")
    assertContains(backing, "public override int Wings")
    assertContains(backing, "public override void Dispose()")
    assertContains(backing, "public override ValueTask DisposeAsync()")
    assertContains(backing, "EntryPoint = \"library_abstractpet__animal_legs\"")
    assertContains(backing, "EntryPoint = \"library_abstractpet__animal_dispose\"")

    assertContains(kotlin, "@CName(\"library_abstractpet__animal_legs\")")
    assertContains(kotlin, "asStableRef<tier1.abstractpet.Animal>().get().legs()")
    assertContains(kotlin, "@CName(\"library_abstractpet__animal_get_wings\")")
  }

  @Test
  fun `an abstract class below an abstract base keeps the shipped shape`() {
    val cs: String = result.generatedCSharp

    // `Puppy` inherits `Animal`'s open abstract members, which are planned on `Animal`; a wrapper
    // of its own would have nothing to call for them, so it gets none.
    assertContains(cs, "public abstract class Puppy : Animal")
    assertFalse(
      cs.contains("internal sealed class Backing : Puppy"),
      "an abstract class below an abstract base has no wrapper; generated=$cs",
    )
  }

  /**
   * ADR-195's `TryX` twin on an abstract member. The wrapper is derived from the owner's members
   * after the twin collision pass, so it overrides the abstract twin exactly when the owner still
   * declares it: a surviving twin is implemented (CS0534 otherwise), a twin the pass drops from
   * the override chain (`Crate`, `Bulk`: a subclass's `tryWeigh` property claims the name) is not
   * overridden (CS0115 otherwise). Both owner kinds, the ordinary class and the abstract arm.
   */
  @Test
  fun `the wrapper overrides an abstract Result twin exactly when the owner keeps it`() {
    val twins: Tier1Result = Tier1Harness.run(
      """
      package tier1.abstracttry

      abstract class Scale {
        abstract fun weigh(): Result<Int>
      }

      class Kitchen : Scale() {
        override fun weigh(): Result<Int> = Result.success(4)
      }

      abstract class Crate {
        abstract fun weigh(): Result<Int>
      }

      class Box : Crate() {
        val tryWeigh: Int = 0
        override fun weigh(): Result<Int> = Result.success(9)
      }

      sealed class Load {
        abstract class Heavy : Load() {
          abstract fun weigh(): Result<Int>
        }

        class Light : Load()
      }

      class Anvil : Load.Heavy() {
        override fun weigh(): Result<Int> = Result.failure(IllegalStateException("too heavy"))
      }

      sealed class Freight {
        abstract class Bulk : Freight() {
          abstract fun weigh(): Result<Int>
        }

        class Parcel : Freight()
      }

      class Pallet : Freight.Bulk() {
        val tryWeigh: Int = 0
        override fun weigh(): Result<Int> = Result.success(2)
      }

      class Shed {
        fun scale(): Scale = Kitchen()
        fun crate(): Crate = Box()
        fun load(): Load = Anvil()
        fun freight(): Freight = Pallet()
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    )
    assertTrue(twins.compiledClean, "expected a clean compile; got: ${twins.compileErrors}")
    val cs: String = twins.generatedCSharp

    fun block(header: String): String =
      cs.substringAfter(header).substringBefore("internal sealed class Backing")
    fun backing(owner: String): String =
      cs.substringAfter("internal sealed class Backing : $owner").substringBefore("Dispose()")

    // Surviving twins: declared abstract on the owner, implemented by its wrapper.
    assertContains(block("public abstract class Scale"), "public abstract bool TryWeigh(")
    assertContains(backing("Scale"), "public override bool TryWeigh(")
    assertContains(block("public abstract class Heavy : Load"), "public abstract bool TryWeigh(")
    assertContains(backing("Heavy"), "public override bool TryWeigh(")
    // Dropped twins: gone from the owner, its subclass and the wrapper alike.
    assertFalse("TryWeigh(" in block("public abstract class Crate"), "Crate keeps no twin; $cs")
    assertFalse("TryWeigh(" in backing("Crate"), "Crate's wrapper overrides no twin; $cs")
    assertFalse("TryWeigh(" in block("public abstract class Bulk : Freight"), "Bulk; $cs")
    assertFalse("TryWeigh(" in backing("Bulk"), "Bulk's wrapper overrides no twin; $cs")

    Tier1CSharpCompile.assertCompiles(
      twins,
      """
      using Interop.Abstracttry;

      namespace Consumer
      {
          public static class Probe
          {
              public static int Weigh(Shed shed)
              {
                  using Scale scale = shed.Scale();
                  int total = scale.TryWeigh(out int grams, out System.Exception? _) ? grams : -1;
                  using Load load = shed.Load();
                  if (load is Load.Heavy heavy && !heavy.TryWeigh(out _, out System.Exception? f))
                  {
                      total += f.Message.Length;
                  }
                  using Crate crate = shed.Crate();
                  using Freight freight = shed.Freight();
                  return total + crate.Weigh() + ((Freight.Bulk)freight).Weigh();
              }
          }
      }
      """.trimIndent(),
    )
  }

  @Test
  fun `the generated C# compiles and a consumer reads the members`() {
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System.Threading.Tasks;
      using Interop.Abstractpet;

      namespace Consumer
      {
          public static class Probe
          {
              public static string Describe(Bed bed)
              {
                  using Animal pet = bed.Pet();
                  return pet.Name + pet.Legs() + pet.Wings + pet.Sound() + pet.Age;
              }

              public static async Task<int> Later(Bed bed)
              {
                  await using Animal pet = await bed.LaterAsync();
                  return pet.Legs() + await pet.NapAsync();
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
