package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-233: a C#-implemented interface slot carries every type an ordinary class member does. The
 * slot wire is the plan's wire: a class handle, a collection, a value class, nullable primitives
 * and enums, `Char`, `Instant`, `Duration`, `Uuid` and `ByteArray` ride one box, a Kotlin lambda
 * crosses as the plan's lambda wrapper, a C# lambda as its registered delegate, and a member takes
 * any number of parameters.
 */
class Tier1InterfaceSlotVocabularyTest {

  private val unimplementable: String = ForwardDiagnosticKind.SKIPPED_UNIMPLEMENTABLE_INTERFACE.name

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.slotvocabulary

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.toList
      import kotlin.time.Duration
      import kotlin.time.Instant
      import kotlin.uuid.Uuid

      class Kitten(val name: String)

      @kotlin.jvm.JvmInline
      value class Whisker(val length: Int)

      enum class Coat { TUXEDO, CARAMEL }

      interface Sitter {
        fun adopt(name: String): Kitten
        fun foster(kitten: Kitten?): Kitten?
        fun names(): List<String>
        fun weigh(weights: Map<String, Int>): Int
        fun trim(whisker: Whisker): Whisker
        fun treats(cat: String): Int?
        fun coat(cat: String): Coat?
        fun initial(cat: String): Char
        fun lastFed(at: Instant): Instant
        fun nap(length: Duration): Duration
        fun chip(id: Uuid): Uuid
        fun photo(bytes: ByteArray): ByteArray
        fun onPurr(callback: (String) -> Int): Int
        fun purrer(): (Int) -> String
        fun groom(cat: String, strokes: Int, minutes: Double, gentle: Boolean): String
        fun kittens(): Flow<Kitten>
        fun maybe(): Flow<String>?
        var feed: Flow<String>
      }

      fun adoption(sitter: Sitter): String = sitter.adopt("Oreo").name

      suspend fun census(sitter: Sitter): Int = sitter.kittens().toList().size
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  @Test
  fun `every classifier-admitted slot type plans the bridge on both halves`() {
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    assertTrue(result.kspWarnings.none { it.contains(unimplementable) }, "${result.kspWarnings}")
    assertContains(result.generated, "sitter_bridge_create")
    assertContains(result.generated, "override fun purrer(): (kotlin.Int) -> kotlin.String")
    assertContains(result.generated, "purrerPtr_lambda")
    val cs: String = result.generatedCSharp
    assertContains(cs, "Kitten result = impl.Adopt(value0); return NugetMarshal.WrapOwned<")
    assertContains(cs, "value0 = NugetMarshal.FromHandle<")
    assertContains(cs, "Func<int, string> Purrer()")
    assertContains(cs, "impl.Feed = value0")
  }

  @Test
  fun `the generated C# compiles against a C# implementation`() {
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System;
      using System.Collections.Generic;
      using System.Linq;
      using System.Threading;
      using System.Threading.Tasks;

      namespace Consumer
      {
          public sealed class Nanny : global::Interop.ISitter
          {
              public global::Interop.Kitten Adopt(string name) => throw new NotSupportedException();
              public global::Interop.Kitten? Foster(global::Interop.Kitten? kitten) => kitten;
              public IReadOnlyList<string> Names() => ["Oreo"];
              public int Weigh(IReadOnlyDictionary<string, int> weights) => weights.Values.Sum();
              public global::Interop.Whisker Trim(global::Interop.Whisker whisker) => whisker;
              public int? Treats(string cat) => null;
              public global::Interop.Coat? Coat(string cat) => null;
              public char Initial(string cat) => cat[0];
              public DateTimeOffset LastFed(DateTimeOffset at) => at;
              public TimeSpan Nap(TimeSpan length) => length;
              public Guid Chip(Guid id) => id;
              public byte[] Photo(byte[] bytes) => bytes;
              public int OnPurr(Func<string, int> callback) => callback("Mylo");
              public Func<int, string> Purrer() => times => "purr" + times;
              public string Groom(string cat, int strokes, double minutes, bool gentle) => cat;
              public global::Interop.KotlinFlow<global::Interop.Kitten> Kittens() => throw new NotSupportedException();
              public global::Interop.KotlinFlow<string>? Maybe() => null;
              public global::Interop.KotlinFlow<string> Feed { get; set; } = null!;
              public void Dispose() { }
              public ValueTask DisposeAsync() => default;
          }

          public static class Probe
          {
              public static string Run() => global::Interop.Fixture.Adoption(new Nanny());
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
