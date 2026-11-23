package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #610 / ADR-243: a returned read-only `List` whose element carries a handle is a
 * `KotlinList<E>` that releases the elements it handed out when disposed. The static return type
 * narrows only at a direct return C# cannot implement or override; everywhere else it stays
 * `IReadOnlyList<E>` and only the runtime object changes. A handle-free element list is untouched.
 */
class Tier1OwnedHandleListTest {

  private val source: String =
    """
    package tier1.ownedlist

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flowOf

    class Kitten(val name: String)

    @JvmInline
    value class Tag(val kitten: Kitten)

    interface Pet {
      val name: String
      fun litter(): List<Kitten>
    }

    interface Shelter {
      val residents: List<Kitten>
      fun intake(): List<Kitten>
    }

    abstract class Animal {
      abstract fun herd(): List<Kitten>
      open fun pack(): List<Kitten> = emptyList()
    }

    class Dog : Animal() {
      override fun herd(): List<Kitten> = emptyList()
      override fun pack(): List<Kitten> = emptyList()
    }

    class Home : Shelter {
      override val residents: List<Kitten> = listOf(Kitten("Oreo"))
      override fun intake(): List<Kitten> = residents
    }

    // A final sealed arm cannot be `virtual`, so only `overridesNothing` keeps its interface
    // implementations off `KotlinList` (CS0738 against `IShelter` otherwise).
    sealed class Spot {
      class Shelf : Spot(), Shelter {
        override val residents: List<Kitten> = emptyList()
        override fun intake(): List<Kitten> = emptyList()
      }
    }

    class Crate<T>(val items: List<T>)

    class Centre {
      val kittens: List<Kitten> = listOf(Kitten("Oreo"), Kitten("Mylo"))
      var shortlist: List<Kitten> = emptyList()
      val maybe: List<Kitten>? = null
      val holes: List<Kitten?> = listOf(null, Kitten("Oreo"))
      val litters: List<List<Kitten>> = listOf(kittens)
      val names: List<String> = listOf("Oreo")
      val failures: List<Throwable> = emptyList()

      fun adopt(): List<Kitten> = kittens
      fun pets(): List<Pet> = emptyList()
      fun group(): Set<Kitten> = kittens.toSet()
      fun byName(): Map<String, Kitten> = kittens.associateBy { it.name }
      fun draft(): MutableList<Kitten> = kittens.toMutableList()
      suspend fun later(): List<Kitten> = kittens
      fun stream(): Flow<List<Kitten>> = flowOf(kittens)
      fun crate(): Crate<Kitten> = Crate(kittens)
      fun tags(): List<Tag> = kittens.map { Tag(it) }

      companion object {
        fun all(): List<Kitten> = emptyList()
      }
    }

    object Registry {
      val kittens: List<Kitten> = emptyList()
      fun every(): List<Kitten> = emptyList()
    }

    fun everyKitten(): List<Kitten> = emptyList()

    fun Centre.extra(): List<Kitten> = kittens
    """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))
  }

  /** The generated C# without `///` doc lines, so a doc mention cannot satisfy an assertion. */
  private val code: String by lazy {
    result.generatedCSharp.lines()
      .filterNot { line -> line.trimStart().startsWith("///") }
      .joinToString("\n")
  }

  private fun assertSpelled(signature: String) {
    assertTrue(signature in code, "expected `$signature` in the generated C#; cs=$code")
  }

  private fun assertAbsent(signature: String) {
    assertFalse(signature in code, "expected no `$signature` in the generated C#")
  }

  @Test
  fun `the fixture compiles`() {
    assertTrue(result.compiledClean, "expected clean Kotlin; got: ${result.compileErrors}")
  }

  @Test
  fun `a direct non-overridable return of a handle list narrows to KotlinList`() {
    assertSpelled("public KotlinList<global::Interop.Kitten> Kittens")
    assertSpelled("public KotlinList<global::Interop.Kitten> Adopt()")
    assertSpelled("public KotlinList<global::Interop.IPet> Pets()")
    assertSpelled("public static KotlinList<global::Interop.Kitten> EveryKitten()")
    assertSpelled("public static KotlinList<global::Interop.Kitten> Extra(this global::Interop.Centre")
    assertSpelled("public static KotlinList<global::Interop.Kitten> Every()")
    assertSpelled("public static KotlinList<global::Interop.Kitten> Kittens")
    // A companion function is on the plan route too, a static C# member like the rest.
    assertSpelled("public static KotlinList<global::Interop.Kitten> All()")
    // Nullable list, nullable element, and a nested list whose inner level stays read-only.
    assertSpelled("public KotlinList<global::Interop.Kitten>? Maybe")
    assertSpelled("public KotlinList<global::Interop.Kitten?> Holes")
    assertSpelled("public KotlinList<IReadOnlyList<global::Interop.Kitten>> Litters")
  }

  @Test
  fun `an overridable, implementable or settable member keeps IReadOnlyList`() {
    // A `var`: one C# property type serves the setter, which keeps accepting any IReadOnlyList.
    assertSpelled("public IReadOnlyList<global::Interop.Kitten> Shortlist")
    // abstract / open, and their overrides.
    assertSpelled("public abstract IReadOnlyList<global::Interop.Kitten> Herd()")
    assertSpelled("public virtual IReadOnlyList<global::Interop.Kitten> Pack()")
    assertSpelled("public override IReadOnlyList<global::Interop.Kitten> Herd()")
    assertSpelled("public override IReadOnlyList<global::Interop.Kitten> Pack()")
    // An interface member, its backing wrapper, and a class implementing it.
    assertSpelled("IReadOnlyList<global::Interop.Kitten> Litter()")
    assertAbsent("KotlinList<global::Interop.Kitten> Litter()")
    assertSpelled("public virtual IReadOnlyList<global::Interop.Kitten> Residents")
    assertAbsent("KotlinList<global::Interop.Kitten> Residents")
    assertSpelled("public virtual IReadOnlyList<global::Interop.Kitten> Intake()")
    assertAbsent("KotlinList<global::Interop.Kitten> Intake()")
  }

  @Test
  fun `other collection kinds, handle-free elements and protocol positions are untouched`() {
    assertSpelled("public IReadOnlySet<global::Interop.Kitten> Group()")
    assertSpelled("public IReadOnlyDictionary<string, global::Interop.Kitten> ByName()")
    assertSpelled("public IList<global::Interop.Kitten> Draft()")
    assertSpelled("public IReadOnlyList<string> Names")
    assertSpelled("public IReadOnlyList<global::System.Exception> Failures")
    assertSpelled("Task<IReadOnlyList<global::Interop.Kitten>> LaterAsync(")
    assertSpelled("KotlinFlow<IReadOnlyList<global::Interop.Kitten>> Stream(")
    assertSpelled("public IReadOnlyList<T> Items")
    // A value class over a handle is a record struct nothing can release or `Keep`: deferred.
    assertSpelled("public IReadOnlyList<global::Interop.Tag> Tags()")
    assertSpelled("NugetMarshal.ReadList<global::Interop.Tag>(")
    assertAbsent("KotlinList<global::Interop.Tag>")
    assertAbsent("ReadOwnedList<global::Interop.Tag>")
    assertAbsent("KotlinList<string>")
    assertAbsent("KotlinList<global::System.Exception>")
    assertAbsent("KotlinList<T>>")
  }

  @Test
  fun `the runtime object is owned wherever the element carries a handle`() {
    assertSpelled("NugetMarshal.ReadOwnedList<global::Interop.Kitten>(")
    // The inner level of a nested list is owned too, so its own Dispose honours its own Keep.
    assertSpelled("NugetMarshal.ReadOwnedList<IReadOnlyList<global::Interop.Kitten>>(")
    // A handle-free list keeps the shipped read.
    assertSpelled("NugetMarshal.ReadList<string>(")
    assertSpelled("public sealed class KotlinList<T> : global::System.Collections.ObjectModel.ReadOnlyCollection<T>, IDisposable")
    assertSpelled("public T Keep(T element)")
  }

  @Test
  fun `the narrowed members compile beside every shipped consumer shape`() {
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System;
      using System.Collections.Generic;
      using System.Linq;
      using Interop;

      namespace Consumer
      {
          public static class Probe
          {
              private static int Count(IEnumerable<Kitten> kittens) => kittens.Count();

              public static int Touch(Centre centre)
              {
                  IReadOnlyList<Kitten> old = centre.Kittens;
                  var inferred = centre.Adopt();
                  int total = old.Count + inferred.Count;
                  foreach (Kitten kitten in centre.Kittens) total += kitten.Name.Length;
                  total += centre.Kittens.Select(k => k.Name).ToList().Count;
                  total += Count(centre.Adopt());
                  using (var owned = centre.Kittens)
                  {
                      Kitten kept = owned.Keep(owned[0]);
                      total += kept.Name.Length;
                      kept.Dispose();
                  }
                  using var maybe = centre.Maybe;
                  total += maybe?.Count ?? 0;
                  using KotlinList<Kitten?> holes = centre.Holes;
                  total += holes.Count(k => k is not null);
                  using KotlinList<IReadOnlyList<Kitten>> litters = centre.Litters;
                  total += litters[0].Count;
                  IReadOnlyList<Kitten> manual = centre.Adopt();
                  try { total += manual.Count; }
                  finally { foreach (Kitten kitten in manual) kitten.Dispose(); }
                  centre.Shortlist = new List<Kitten>();
                  Func<IReadOnlyList<Kitten>> later = centre.Adopt;
                  return total + later().Count + Registry.Every().Count + Fixture.EveryKitten().Count;
              }
          }
      }
      """.trimIndent(),
    )
  }
}
