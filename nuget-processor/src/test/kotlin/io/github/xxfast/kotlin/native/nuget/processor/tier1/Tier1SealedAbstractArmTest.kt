package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ROADMAP Phase 4: an `abstract class` arm of a sealed class rendered `public sealed class Deep :
 * Nap`. Not harmless: an exported Kotlin subclass of the arm (`Deeper : Nap.Deep()`) is collected
 * as an ordinary class with the arm as its base, and deriving from a `sealed` C# type is CS0509.
 *
 * The invariant: an abstract arm is a C# `abstract class`, an `open` arm a plain `class`, and only
 * a final arm stays `sealed`. Nothing ever instantiates the abstract arm's own type: the
 * discriminator, the erased-generic factory and an arm-typed return all construct the arm's
 * internal concrete backing wrapper (the ADR-040 backing-class shape), so a Kotlin subclass of
 * the arm handed back as `Nap` or `Nap.Deep` materialises as that wrapper: `is Nap.Deep` holds.
 *
 * Oreo sleeps deeper than anyone has measured. Mylo naps in short, loud bursts.
 */
class Tier1SealedAbstractArmTest {

  /** The abstract arm alone, with an abstract and a concrete member, and no subclass of it. */
  private val lonely: String =
    """
    package tier1.abstractarm

    sealed class Nap {
      abstract class Deep : Nap() {
        abstract fun depth(): Int
        abstract var snores: Int
        fun label(): String = "deep"
        suspend fun dream(): Int = 1
      }

      class Light : Nap()
    }

    class Bed {
      fun deepest(): Nap = Nap.Light()
      fun deep(): Nap.Deep? = null
      val dozing: Nap.Deep? get() = null
      suspend fun later(): Nap.Deep? = null
      val naps: List<Nap.Deep> get() = emptyList()
    }
    """.trimIndent()

  /** The abstract arm and an open arm, each with an exported Kotlin subclass of its own. */
  private val families: String =
    """
    package tier1.abstractarmfamily

    sealed class Nap {
      abstract class Deep : Nap() {
        open fun label(): String = "deep"
        abstract fun depth(): Int
        abstract val mood: String
      }

      open class Doze : Nap() {
        open fun minutes(): Int = 5
      }

      class Light : Nap()

      class Backing
    }

    abstract class Drift : Nap()

    class Deeper : Nap.Deep() {
      override fun label(): String = "deeper"
      override fun depth(): Int = 9
      override val mood: String = "dreaming"
    }

    class Snooze : Nap.Doze() {
      override fun minutes(): Int = 9
    }

    class Bed {
      fun deepest(): Nap = Deeper()
      fun deep(): Nap.Deep = Deeper()
      fun deeper(): Deeper = Deeper()
      fun snooze(): Nap = Snooze()
    }
    """.trimIndent()

  private val lonelyResult: Tier1Result by lazy {
    Tier1Harness.run(lonely, processorOptions = mapOf("nuget.rootPackage" to "tier1"))
  }

  private val familiesResult: Tier1Result by lazy {
    Tier1Harness.run(families, processorOptions = mapOf("nuget.rootPackage" to "tier1"))
  }

  @Test
  fun `an abstract arm renders abstract, not sealed`() {
    val result: Tier1Result = lonelyResult
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp

    // The suspending arm owns its scope, hence `IAsyncDisposable`.
    assertContains(cs, "        public abstract class Deep : Nap, IAsyncDisposable\n")
    assertFalse(
      Regex("""public sealed class Deep\b""").containsMatchIn(cs),
      "an abstract Kotlin arm must not be a sealed C# class; generated=$cs",
    )
    // A final arm keeps its shipped spelling.
    assertContains(cs, "        public sealed class Light : Nap\n")
    // Kotlin gives an abstract class no constructor a consumer can call.
    assertFalse(cs.contains("public Deep("), "an abstract arm has no public constructor; $cs")
  }

  @Test
  fun `nothing instantiates the abstract arm itself`() {
    val cs: String = lonelyResult.generatedCSharp

    assertFalse(
      Regex("""new (global::[\w.]+\.)?(Nap\.)?Deep\(""").containsMatchIn(cs),
      "the abstract arm's own type must never be constructed (CS0144); generated=$cs",
    )
    // The discriminator, the erased-generic factory and the arm-typed return all construct the
    // arm's backing wrapper instead.
    assertContains(cs, "0 => new Deep.Backing(handle, out _),")
    assertContains(
      cs,
      "[typeof(global::Interop.Abstractarm.Nap.Deep)] = static handle => " +
        "new global::Interop.Abstractarm.Nap.Deep.Backing(handle, out _),",
    )
    assertContains(cs, "new global::Interop.Abstractarm.Nap.Deep.Backing(nativeResult, out _)")
    // The legacy suspend route's completion read takes the same wrapper.
    assertContains(cs, "new global::Interop.Abstractarm.Nap.Deep.Backing(resultPtr, out _)")
    assertContains(cs, "internal sealed class Backing : Deep")
    // A suspending abstract arm keeps its async surface, which the wrapper inherits.
    assertContains(cs, "public Task<int> DreamAsync(CancellationToken")
  }

  @Test
  fun `the abstract arm declares its abstract members and the wrapper calls through`() {
    val result: Tier1Result = lonelyResult
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp

    // Declared on the arm the way an ordinary abstract class declares them.
    assertContains(cs, "            public abstract int Depth();\n")
    assertContains(cs, "            public abstract int Snores { get; set; }\n")
    // Overridden on the wrapper over call-through exports, which dispatch virtually in Kotlin.
    val backing: String = cs.substringAfter("internal sealed class Backing : Deep")
    assertContains(backing, "EntryPoint = \"library_abstractarm__nap_deep_depth\"")
    assertContains(backing, "public override int Depth()")
    assertContains(backing, "public override int Snores")
    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"library_abstractarm__nap_deep_depth\")")
    assertContains(kotlin, "asStableRef<tier1.abstractarm.Nap.Deep>().get().depth()")
    // No longer a named skip: the member has a route now.
    assertFalse(
      result.kspWarnings.any { it.contains("tier1.abstractarm.Nap.Deep.depth") },
      "an abstract arm's abstract member binds; warnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `the abstract arm's generated C# compiles`() {
    Tier1CSharpCompile.assertCompiles(
      lonelyResult,
      """
      using Interop.Abstractarm;

      namespace Consumer
      {
          public static class Probe
          {
              public static string Label(Nap nap) => nap is Nap.Deep deep ? deep.Label() : "";
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  @Test
  fun `a Kotlin subclass of an abstract or open arm derives from it in C#`() {
    val result: Tier1Result = familiesResult
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp

    assertContains(cs, "        public abstract class Deep : Nap\n")
    // An open arm was already extensible (ADR-009 amendment 2026-09-11) and stays so.
    assertContains(cs, "        public class Doze : Nap\n")
    assertContains(cs, "public class Deeper : Nap.Deep")
    assertContains(cs, "public class Snooze : Nap.Doze")
    // The abstract arm's `open fun` is virtual, so `Deeper`'s override binds (CS0506 otherwise).
    assertContains(cs, "public virtual string Label()")
    assertContains(cs, "public override string Label()")
    // `Deeper`'s overrides of the arm's abstract members bind against the arm's declarations.
    assertContains(cs, "            public abstract int Depth();\n")
    assertContains(cs, "            public abstract string Mood { get; }\n")
    val deeper: String = cs.substringAfter("public class Deeper : Nap.Deep")
    assertContains(deeper, "public override int Depth()")
    // The arm inherits `Nap.Backing`, a type Kotlin nests in the sealed base, so the wrapper takes
    // the next free name rather than hiding it (CS0108).
    assertContains(cs, "internal sealed class Backing_ : Deep")
    assertContains(cs, "=> new Deep.Backing_(handle, out _),")
    // A sibling abstract arm, declared beside its base (issue #54), takes the same shape. Its
    // wrapper avoids `Nap.Backing` the same way.
    assertContains(cs, "    public abstract class Drift : Nap\n")
    assertContains(cs, "=> new Drift.Backing_(handle, out _),")
    assertContains(
      cs,
      "[typeof(global::Interop.Abstractarmfamily.Drift)] = static handle => " +
        "new global::Interop.Abstractarmfamily.Drift.Backing_(handle, out _),",
    )

    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop.Abstractarmfamily;

      namespace Consumer
      {
          public static class Probe
          {
              public static string Label(Nap.Deep deep) => deep.Label();
              public static int Depth(Nap.Deep deep) => deep.Depth() + deep.Mood.Length;
              public static int Minutes(Nap.Doze doze) => doze.Minutes();
          }
      }
      """.trimIndent(),
    )
  }
}
