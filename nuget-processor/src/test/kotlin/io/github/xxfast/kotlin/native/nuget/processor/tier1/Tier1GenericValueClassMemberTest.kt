package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-225: a generic value class's own members and secondary constructors bind on its C# record
 * struct, one export per member for every instantiation. The receiver crosses as the underlying
 * at the class's own `T` (the bare-`T` box for `Sticker<T>`, a plain `long` for the phantom
 * `Decal<T>`), re-wrapped in Kotlin so `init` runs inside the export's error slot. A member with
 * its own type parameters binds on any value class, generic or not (`Collar`).
 *
 * `@JvmInline` is only for the JVM harness (ADR-060's Tier 1 constraint).
 *
 * Oreo (black, white in the middle) collects the stickers. Mylo (brown and creamy) peels them off.
 */
class Tier1GenericValueClassMemberTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.stickers

      import kotlin.jvm.JvmInline

      enum class Mood { HAPPY, SLEEPY }

      class Cat(val name: String)

      @JvmInline
      value class Sticker<T>(val value: T) {
        init { require(value != -1) { "peeled" } }
        @Suppress("UNCHECKED_CAST")
        constructor(label: String, times: Int) : this(label.repeat(times) as T)
        constructor(oreo: T, mylo: T, pickMylo: Boolean) : this(if (pickMylo) mylo else oreo)
        val shiny: Boolean get() = value != null
        val peeled: T get() = value
        fun describe(): String = "sticker of ${'$'}value"
        fun swap(other: T): Sticker<T> = Sticker(other)
        fun <R> pairWith(other: R): String = "${'$'}value+${'$'}other"
        fun <R> hand(item: R): R = item
        suspend fun peelLater(): T = value
        suspend fun describeLater(): String = describe()
      }

      @JvmInline
      value class Decal<T>(val serial: Long) {
        constructor(label: String) : this(label.toLong())
        val doubled: Long get() = serial * 2
        fun <R> stickOn(item: R): String = "decal #${'$'}serial on ${'$'}item"
        suspend fun serialLater(): Long = serial
      }

      @JvmInline
      value class Badge<T : Any>(val value: T) {
        fun describe(): String = "badge of ${'$'}value"
      }

      @JvmInline
      value class Doze<T : Enum<T>>(val value: T) {
        @Suppress("UNCHECKED_CAST")
        constructor(label: String) : this(label as T)
        constructor(first: T, second: T) : this(second)
        fun label(): String = "dozing ${'$'}{value.name}"
        suspend fun labelLater(): String = label()
      }

      @JvmInline
      value class Collar(val id: String) {
        fun <R> wear(item: R): R = item
      }

      @JvmInline
      value class Nap<T>(val value: T) {
        suspend fun later(): T = value
      }

      fun badge(): Badge<String> = Badge("Oreo")
      fun doze(): Doze<Mood> = Doze(Mood.SLEEPY)
      fun collar(id: String): Collar = Collar(id)
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  private val code: String by lazy { result.generatedCSharp.withoutDocComments() }

  @Test
  fun `every member and secondary constructor binds, none is named`() {
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(
      result.kspWarnings.none { it.contains("[nuget:SKIPPED") },
      "expected nothing skipped; kspWarnings=${result.kspWarnings}",
    )
    listOf(
      "public Sticker(string label, int times) : this(CreateChecked_2(label, times))",
      "public Sticker(T oreo, T mylo, bool pickMylo) : this(CreateChecked_3(oreo, mylo, pickMylo))",
      "private static T CreateChecked_3(T oreo, T mylo, bool pickMylo)",
      "public bool Shiny",
      "public T Peeled",
      "public string Describe()",
      "public global::Interop.Stickers.Sticker<T> Swap(T other)",
      "public string PairWith<R>(R other)",
      "public R Hand<R>(R item)",
      "public Decal(string label) : this(CreateChecked_2(label))",
      "public long Doubled",
      "public string StickOn<R>(R item)",
      "public string Label()",
      "public R Wear<R>(R item)",
      "public Doze(string label) : this(CreateChecked_2(label))",
      "public Doze(T first, T second) : this(CreateChecked_3(first, second))",
    ).forEach { signature -> assertContains(code, signature) }
  }

  @Test
  fun `a generic struct hoists its externs and boxes its receiver per call`() {
    assertContains(code, "internal static class StickerNative")
    assertContains(code, "internal static class DecalNative")
    assertFalse(
      Regex("""struct Sticker<T>\(T Value\)\s*\{[^}]*static extern""").containsMatchIn(code),
      "a generic struct cannot declare an extern (CS7042)",
    )
    assertContains(
      code,
      "nugetSelfBox = NugetMarshal.Wrap<T>(nugetSelf.Value!, out nugetSelfOwned);",
    )
    assertContains(code, "Native_GetDoubled(nugetSelf.Serial, out IntPtr error)")
  }

  /**
   * A suspend member rides the ADR-229 static suspend route: the receiver box is minted and
   * released around the synchronous launch call, read in Kotlin before the launch, and rewrapped
   * inside the coroutine, so `init` faults the task.
   */
  @Test
  fun `a suspend member is a Task-returning instance method on the struct`() {
    assertContains(
      code,
      "public Task<T> PeelLaterAsync(CancellationToken cancellationToken = default)",
    )
    assertContains(
      code,
      "public Task<string> DescribeLaterAsync(CancellationToken cancellationToken = default)",
    )
    assertContains(
      code,
      "public Task<long> SerialLaterAsync(CancellationToken cancellationToken = default)",
    )
    assertContains(
      code,
      "IntPtr receiverBox = NugetMarshal.Wrap<T>(this.Value!, out bool receiverOwned);",
    )
    assertContains(code, "SerialLaterAsync_native(this.Serial, ")
    // A struct whose only members are suspend ones still hoists its externs (CS7042).
    assertContains(code, "internal static class NapNative")
    // No erased spelling is within `Enum<T>`: the whole export, launch included, runs in the
    // trampoline.
    assertContains(
      code,
      "public Task<string> LabelLaterAsync(CancellationToken cancellationToken = default)",
    )
    assertContains(
      result.generated,
      "val obj = receiver.asStableRef<Any>().get()" +
        ".let { bounded -> bounded as kotlin.Enum<*>; bounded as T }",
    )
    assertContains(result.generated, "tier1.stickers.Doze(obj).labelLater()")
    assertContains(result.generated, "val obj = receiver?.asStableRef<Any>()?.get()")
    assertContains(result.generated, "tier1.stickers.Sticker(obj).peelLater()")
    assertContains(result.generated, "tier1.stickers.Decal<Nothing>(receiver).serialLater()")
  }

  /** A bound `T` is a non-null slot: `default(Badge<string>).Describe()` throws before crossing. */
  @Test
  fun `a default receiver of a non-null slot is refused in C#`() {
    assertContains(
      code,
      "(nugetSelf.Value ?? throw new ArgumentException(\"default(Badge) carries no Value; " +
          "construct a Badge instead\"))",
    )
    val doze: String = code.substringAfter("struct Doze<T>").substringBefore("struct ")
    assertFalse(doze.contains("carries no Value"), "a struct T has a real default")
  }

  @Test
  fun `the Kotlin half re-wraps the receiver and spells what it cannot infer`() {
    listOf(
      "tier1.stickers.Sticker<Any?>(label, times).value",
      "tier1.stickers.Sticker(oreo?.asStableRef<Any>()?.get(), " +
        "mylo?.asStableRef<Any>()?.get(), pickMylo).value",
      "tier1.stickers.Sticker(value?.asStableRef<Any>()?.get()).describe()",
      "tier1.stickers.Sticker(value?.asStableRef<Any>()?.get()).pairWith<Any?>(",
      "tier1.stickers.Decal<Nothing>(label).serial",
      "tier1.stickers.Decal<Nothing>(value).doubled",
      "tier1.stickers.Badge(value.asStableRef<Any>().get()).describe()",
      "tier1.stickers.Doze(value.asStableRef<Any>().get()" +
        ".let { bounded -> bounded as kotlin.Enum<*>; bounded as T }).label()",
      "tier1.stickers.Collar(value).wear<Any?>(",
    ).forEach { call -> assertContains(result.generated, call) }
    assertContains(result.generated, "fun <T> nugetTrampoline()")
    // No erased argument is within `Enum<T>`, so an unwitnessed constructor names the variable.
    assertContains(result.generated, "tier1.stickers.Doze<T>(label).value")
    assertContains(
      result.generated,
      "tier1.stickers.Doze(first.asStableRef<Any>().get()" +
        ".let { bounded -> bounded as kotlin.Enum<*>; bounded as T }, ",
    )
  }

  @Test
  fun `the consumer calls every member on closed instantiations`() {
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop.Stickers;
      public static class Consumer
      {
          public static void Run()
          {
              var oreo = new Cat("Oreo");
              string described = new Sticker<int>(3).Describe();
              bool shiny = new Sticker<string?>(null).Shiny;
              Cat peeled = new Sticker<Cat>(oreo).Peeled;
              Sticker<int> swapped = new Sticker<int>(7).Swap(8);
              string paired = new Sticker<string>("Oreo").PairWith(7);
              Mood handed = new Sticker<int>(1).Hand(Mood.Happy);
              Sticker<string> built = new Sticker<string>("ab", 2);
              Sticker<Cat> picked = new Sticker<Cat>(oreo, oreo, true);
              Decal<Cat> decal = new Decal<Cat>("42");
              long doubled = decal.Doubled;
              string stuck = decal.StickOn("Oreo");
              string badge = Fixture.Badge().Describe();
              string doze = new Doze<Mood>(Mood.Sleepy).Label();
              Doze<Mood> second = new Doze<Mood>(Mood.Happy, Mood.Sleepy);
              string? worn = Fixture.Collar("t1").Wear<string?>(null);
              System.Threading.Tasks.Task<Cat> later = new Sticker<Cat>(oreo).PeelLaterAsync();
              System.Threading.Tasks.Task<long> serialLater = decal.SerialLaterAsync();
              System.Threading.Tasks.Task<string> labelLater = new Doze<Mood>(Mood.Happy).LabelLaterAsync();
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  /** Open question 2 of the ADR: a member spelled like the positional property is ADR-117's. */
  @Test
  fun `a member named like the positional property is a named collision`() {
    val clash: Tier1Result = Tier1Harness.run(
      """
      package tier1.clash

      import kotlin.jvm.JvmInline

      @JvmInline
      value class Label<T>(val value: T) {
        fun value(): String = "Mylo"
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    )
    assertTrue(
      clash.kspErrors.any { error ->
        error.contains(ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION.name) &&
            error.contains("Label")
      },
      "expected the ADR-117 collision; kspErrors=${clash.kspErrors}",
    )
  }
}
