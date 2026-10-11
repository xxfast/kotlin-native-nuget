package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-071 amendment (value-class element write): a `MutableStateFlow<V>` whose element is an
 * exported value class binds a settable `KotlinMutableStateFlow<V>`. The write crosses as the
 * underlying, exactly as the synchronous value-class property setter does (ADR-077): C# unwraps
 * the record struct's underlying property (`v.Id`, `(int)v.Mood`, `v.Cat._handle`) and Kotlin
 * re-wraps it (`Tag(value)`), so the value class's `init` re-runs. One classifier arm covers the
 * property, the held method return and the awaited `suspend` holder, and the compare-and-set
 * slots relabel the same shapes.
 *
 * A nullable `V?` element writes too, on the ordinary route's nullable value-class seam: an
 * in-band null for a String or handle underlying, the has-value pair for a primitive or enum one.
 * A reference underlying refuses `default(V)` in C# (its underlying is null, which no non-null
 * Kotlin slot can take). A value class whose underlying the ordinary setter refuses (here a
 * nullable `String?` underlying) is not a flow element at all, and the member is skipped by name.
 */
class Tier1MutableStateFlowValueClassElementTest {

  private val source: String = """
    package tier1.vcwrite

    import kotlinx.coroutines.flow.MutableStateFlow

    enum class Mood { HAPPY, SLEEPY }

    class Cat(val name: String)

    @JvmInline
    value class Tag(val id: String)

    @JvmInline
    value class Naps(val count: Int)

    @JvmInline
    value class MoodRing(val mood: Mood)

    @JvmInline
    value class Collar(val cat: Cat)

    @JvmInline
    value class Flag(val on: Boolean)

    @JvmInline
    value class Nick(val name: String?)

    class Tracker {
      val tag: MutableStateFlow<Tag> = MutableStateFlow(Tag("oreo-1"))
      val naps: MutableStateFlow<Naps> = MutableStateFlow(Naps(3))
      val ring: MutableStateFlow<MoodRing> = MutableStateFlow(MoodRing(Mood.HAPPY))
      val collar: MutableStateFlow<Collar> = MutableStateFlow(Collar(Cat("Oreo")))
      val spareTag: MutableStateFlow<Tag?> = MutableStateFlow(null)
      val spareNaps: MutableStateFlow<Naps?> = MutableStateFlow(null)
      val spareRing: MutableStateFlow<MoodRing?> = MutableStateFlow(null)
      val spareCollar: MutableStateFlow<Collar?> = MutableStateFlow(null)
      val flag: MutableStateFlow<Flag?> = MutableStateFlow(null)
      val nick: MutableStateFlow<Nick> = MutableStateFlow(Nick(null))
      fun tagDial(): MutableStateFlow<Tag> = tag
      fun nickDial(): MutableStateFlow<Nick> = nick
      suspend fun awaitTag(): MutableStateFlow<Tag> = tag
    }
    """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))
  }

  private fun assertClean() {
    assertEquals(emptyList(), result.kspErrors, "KSP errors (an ADR-055 mismatch lands here)")
    assertTrue(result.compiledClean, "generated Kotlin must compile; got: ${result.compileErrors}")
  }

  /**
   * The unwrap every value-class crossing shares (`valueClassUnderlyingOrThrow`): a reference
   * underlying is read through a `?? throw`, so `default(V)` never reaches Kotlin.
   */
  private fun guarded(struct: String, property: String, type: String, parameter: String): String =
    "($struct.$property ?? throw new ArgumentException(" +
      "\"default($type) carries no $property; construct a $type instead\", nameof($parameter)))"

  private fun tag(struct: String, parameter: String = struct): String =
    guarded(struct, "Id", "Tag", parameter)

  private fun collar(struct: String, parameter: String = struct): String =
    guarded(struct, "Cat", "Collar", parameter)

  @Test
  fun `a String underlying crosses as the string and re-wraps in Kotlin`() {
    assertClean()
    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp
    assertContains(csharp, Regex("""public KotlinMutableStateFlow<[\w.:]*Tag> Tag\b"""))
    assertContains(kotlin, ".tag.value = tier1.vcwrite.Tag(value)")
    assertContains(
      csharp,
      Regex("""private static extern void Native_SetTagValue\(NugetKotlinHandle handle, """ +
        """\[MarshalAs\(UnmanagedType\.LPUTF8Str\)\] string value, out IntPtr error\);"""),
    )
    assertContains(csharp, "Native_SetTagValue(_handle, ${tag("v")}, out IntPtr error);")
  }

  @Test
  fun `a primitive, enum and handle underlying cross as their own wire`() {
    assertClean()
    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp
    assertContains(kotlin, ".naps.value = tier1.vcwrite.Naps(value)")
    assertContains(csharp, "Native_SetNapsValue(_handle, v.Count, out IntPtr error);")
    assertContains(
      kotlin,
      ".ring.value = tier1.vcwrite.MoodRing(tier1.vcwrite.Mood.entries[value])",
    )
    assertContains(csharp, "Native_SetRingValue(_handle, (int)v.Mood, out IntPtr error);")
    assertContains(
      kotlin,
      ".collar.value = tier1.vcwrite.Collar(value.asStableRef<tier1.vcwrite.Cat>().get())",
    )
    assertContains(
      csharp,
      "Native_SetCollarValue(_handle, ${collar("v")}._handle, out IntPtr error);",
    )
  }

  @Test
  fun `a nullable element writes through the ordinary nullable value-class seam`() {
    assertClean()
    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp
    assertContains(csharp, Regex("""public KotlinMutableStateFlow<[\w.:]*Tag\?> SpareTag\b"""))
    assertContains(kotlin, ".spareTag.value = value?.let { tier1.vcwrite.Tag(it) }")
    assertContains(
      csharp,
      "Native_SetSpareTagValue(_handle, v.HasValue ? ${tag("v.Value", "v")} : null, " +
        "out IntPtr error);",
    )
    assertContains(
      kotlin,
      ".spareNaps.value = if (valueHasValue) tier1.vcwrite.Naps(value) else null",
    )
    assertContains(
      csharp,
      "Native_SetSpareNapsValue(_handle, v.HasValue, v.GetValueOrDefault().Count, " +
        "out IntPtr error);",
    )
    assertContains(
      csharp,
      "Native_SetSpareRingValue(_handle, v.HasValue, (int)v.GetValueOrDefault().Mood, " +
        "out IntPtr error);",
    )
    assertContains(
      kotlin,
      ".spareCollar.value = value?.asStableRef<tier1.vcwrite.Cat>()?.get()" +
        "?.let { tier1.vcwrite.Collar(it) }",
    )
    assertContains(
      csharp,
      "Native_SetSpareCollarValue(_handle, v.HasValue ? ${collar("v.Value", "v")}._handle " +
        ": NugetKotlinHandle.Null, out IntPtr error);",
    )
    assertContains(csharp, Regex("""public KotlinMutableStateFlow<[\w.:]*Flag\?> Flag\b"""))
  }

  @Test
  fun `default of a reference-underlying record struct is refused in C# before it crosses`() {
    assertClean()
    val csharp: String = result.generatedCSharp
    // The guard is the unwrap itself, in the native call's argument list.
    assertContains(csharp, "Native_SetTagValue(_handle, ${tag("v")}, out IntPtr error);")
    assertContains(csharp, "${collar("v")}._handle")
    // The nullable spelling: `v?.Id` on a non-null default would ship a null and silently clear
    // the flow, so only a PRESENT value is unwrapped, through the same guard.
    assertContains(csharp, "v.HasValue ? ${tag("v.Value", "v")} : null")
    // Both compare-and-set slots carry the same guard, relabelled.
    assertContains(csharp, tag("expect"))
    assertContains(csharp, tag("update"))
    // A primitive or enum underlying's default is a legitimate value, so it is not guarded.
    assertFalse("default(Naps)" in csharp, "a primitive underlying must not be guarded")
    assertFalse("default(MoodRing)" in csharp, "an enum underlying must not be guarded")
  }

  @Test
  fun `compare-and-set crosses both slots as the underlying`() {
    assertClean()
    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp
    assertContains(
      csharp,
      "Native_CompareAndSetTagValue(_handle, ${tag("expect")}, ${tag("update")}, " +
        "out IntPtr error), error);",
    )
    assertContains(kotlin, "compareAndSet(tier1.vcwrite.Tag(expect), tier1.vcwrite.Tag(update))")
  }

  @Test
  fun `the held method return and the awaited holder are settable`() {
    assertClean()
    val csharp: String = result.generatedCSharp
    assertContains(csharp, Regex("""public KotlinMutableStateFlow<[\w.:]*Tag> TagDial\(\)"""))
    assertContains(
      csharp,
      Regex("""public Task<KotlinMutableStateFlow<[\w.:]*Tag>> AwaitTagAsync\("""),
    )
    assertContains(csharp, "Native_TagDialSetValue(owned, ${tag("v")}, out IntPtr error);")
    assertContains(csharp, "SetValue(flowHandle, ${tag("v")}, out IntPtr error);")
    assertContains(
      result.generated,
      "@CName(\"library_tier1_vcwrite__tracker_awaitTag_set_value\")",
    )
  }

  /**
   * `Nick` wraps a `String?`. It has a record struct but no ADR-171 box/unbox pair, so nothing
   * could read a bare `Nick` element back (`FromHandle<Nick>` has no `Factories` entry). It used
   * to bind as a read-only holder whose `.Value` would throw; the flow route now drops the member
   * and names it, and the write-arm refusal, which describes a member that survives read-only,
   * stays silent.
   */
  @Test
  fun `a value class over an underlying the setter refuses is skipped by name`() {
    assertClean()
    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp
    assertFalse(
      Regex("""Flow<[\w.:]*Nick\??>""").containsMatchIn(csharp),
      "expected no holder of a bare Nick element; generatedCSharp=$csharp",
    )
    assertFalse("tracker_set_nick_value" in kotlin, "expected no Kotlin setter for Nick")
    listOf("Tracker.nick:", "Tracker.nickDial:").forEach { member ->
      val named: List<String> = result.kspWarnings.filter { it.contains(member) }
      assertEquals(1, named.size, "expected $member named once; got: ${result.kspWarnings}")
      assertContains(named.single(), "Nick")
      assertContains(named.single(), "those are the underlyings it has a boxed form for")
    }
    assertFalse(
      result.kspWarnings.any { it.contains("has no write arm") },
      "a dropped member has no read-only surface to describe; got: ${result.kspWarnings}",
    )
    // Every writable element is silent: nothing names Tag, Naps, MoodRing, Collar or Flag.
    listOf("Tracker.tag:", "Tracker.spareTag:", "Tracker.flag:", "Tracker.tagDial").forEach {
      assertFalse(
        result.kspWarnings.any { warning -> warning.contains(it) },
        "expected $it to bind without a diagnostic; kspWarnings=${result.kspWarnings}",
      )
    }
  }

  /**
   * The other underlyings the synchronous value-class setter refuses. None of these value classes
   * has a C# record struct at all, so the classifier refuses the type at every position and the
   * member is named with the value class and its underlying (`SKIPPED_UNSUPPORTED_TYPE`), and the
   * write-arm refusal, which describes a member that survives read-only, stays silent. The C# is
   * compiled for real:
   * this cell used to assert the Kotlin half only, while the C# half spelled the undeclared type.
   */
  @Test
  fun `a Char, stdlib class, nested value class or generic underlying is refused by name`() {
    val refusing: Tier1Result = Tier1Harness.run(
      """
      package tier1.vcrefused

      import kotlin.time.Instant
      import kotlinx.coroutines.flow.MutableStateFlow

      @JvmInline
      value class Initial(val letter: Char)

      @JvmInline
      value class Stamp(val at: Instant)

      @JvmInline
      value class Inner(val id: String)

      @JvmInline
      value class Outer(val inner: Inner)

      @JvmInline
      value class Crate<T>(val item: T)

      @JvmInline
      value class Oops(val cause: Throwable)

      class Tracker {
        val initial: MutableStateFlow<Initial> = MutableStateFlow(Initial('a'))
        val stamp: MutableStateFlow<Stamp> = MutableStateFlow(Stamp(Instant.fromEpochSeconds(0)))
        val outer: MutableStateFlow<Outer> = MutableStateFlow(Outer(Inner("a")))
        val crate: MutableStateFlow<Crate<Int>> = MutableStateFlow(Crate(1))
        val oops: MutableStateFlow<Oops> = MutableStateFlow(Oops(IllegalStateException("x")))
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    assertEquals(emptyList(), refusing.kspErrors)
    assertTrue(refusing.compiledClean, "got: ${refusing.compileErrors}")
    assertFalse(
      Regex("""tracker_set_\w+_value""").containsMatchIn(refusing.generated),
      "expected no Kotlin setter for a refused value class element",
    )
    mapOf(
      "Tracker.initial:" to listOf("`tier1.vcrefused.Initial`", "`kotlin.Char`"),
      "Tracker.stamp:" to listOf("`tier1.vcrefused.Stamp`", "`kotlin.time.Instant`"),
      "Tracker.outer:" to listOf("`tier1.vcrefused.Outer`", "`tier1.vcrefused.Inner`"),
      "Tracker.oops:" to listOf("`tier1.vcrefused.Oops`", "`kotlin.Throwable`"),
      "Tracker.crate:" to listOf("`tier1.vcrefused.Crate`"),
    ).forEach { (member, named) ->
      val skips: List<String> = refusing.kspWarnings.filter {
        it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE.name}]") &&
          it.contains(member) && it.contains("has no C# record struct")
      }
      assertEquals(1, skips.size, "expected $member skipped once; got: ${refusing.kspWarnings}")
      named.forEach { text ->
        assertContains(skips.single(), text, message = "expected $member to name $text")
      }
    }
    assertFalse(
      refusing.kspWarnings.any { it.contains("has no write arm") },
      "a dropped member has no read-only surface to describe; got: ${refusing.kspWarnings}",
    )
    Tier1CSharpCompile.assertCompiles(
      refusing,
      "namespace Consumer { public static class Probe { } }",
      allowUnsafe = true,
    )
  }

  @Test
  fun `every value-class write shape compiles in C#`() {
    assertClean()
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      namespace Consumer
      {
          public static class Probe
          {
              public static void Run(global::Interop.Tracker tracker)
              {
                  tracker.Tag.Value = new global::Interop.Tag("oreo-9");
                  tracker.Naps.Update(n => new global::Interop.Naps(n.Count + 1));
                  tracker.SpareTag.Value = null;
                  tracker.SpareNaps.Value = new global::Interop.Naps(2);
                  tracker.Flag.Value = new global::Interop.Flag(true);
                  tracker.TagDial().CompareAndSet(
                      new global::Interop.Tag("a"), new global::Interop.Tag("b"));
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
