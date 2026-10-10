package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-209 over the ADR-071 value-class write arm: a `MutableSharedFlow<V>` whose element is an
 * exported value class binds `KotlinMutableSharedFlow<V>`, and `EmitAsync` / `TryEmit` cross the
 * slot the `MutableStateFlow<V>` setter crosses. C# unwraps the record struct's underlying
 * (`v.Id`, `v.Count`, `(int)v.Mood`, `v.Cat._handle`) and Kotlin re-wraps it (`Tag(value)`), so
 * the value class's `init` re-runs on every emit. One write slot serves the property, the held
 * method return, the awaited `suspend` holder, the interface carrier and a top-level `suspend`.
 *
 * A reference underlying refuses `default(V)` in C# on BOTH writes, before the extern is entered:
 * its underlying is null, which the non-null Kotlin slot cannot take (and which, nullable, would
 * emit a null nobody passed). The refusal is the unwrap itself, the one expression every
 * value-class crossing shares (`(v.Id ?? throw new ArgumentException(...))`), not a statement of
 * its own. A value class whose underlying the setter refuses is not a flow element at all (no
 * record struct, or no boxed form to read back), so the member is skipped by name.
 */
class Tier1MutableSharedFlowValueClassElementTest {

  private val source: String = """
    package tier1.sharedvc

    import kotlinx.coroutines.flow.MutableSharedFlow

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

    class Bulletin {
      val tags: MutableSharedFlow<Tag> = MutableSharedFlow(replay = 1)
      val naps: MutableSharedFlow<Naps> = MutableSharedFlow(replay = 1)
      val rings: MutableSharedFlow<MoodRing> = MutableSharedFlow(replay = 1)
      val collars: MutableSharedFlow<Collar> = MutableSharedFlow(replay = 1)
      val spareTags: MutableSharedFlow<Tag?> = MutableSharedFlow(replay = 1)
      val spareNaps: MutableSharedFlow<Naps?> = MutableSharedFlow(replay = 1)
      val spareRings: MutableSharedFlow<MoodRing?> = MutableSharedFlow(replay = 1)
      val spareCollars: MutableSharedFlow<Collar?> = MutableSharedFlow(replay = 1)
      val flags: MutableSharedFlow<Flag?> = MutableSharedFlow(replay = 1)
      val nicks: MutableSharedFlow<Nick> = MutableSharedFlow(replay = 1)
      fun tagDesk(): MutableSharedFlow<Tag> = tags
      fun nickDesk(): MutableSharedFlow<Nick> = nicks
      suspend fun awaitTags(): MutableSharedFlow<Tag> = tags
    }

    interface Wire {
      val tags: MutableSharedFlow<Tag>
      fun tagDesk(): MutableSharedFlow<Tag>
    }

    class LoudWire : Wire {
      override val tags: MutableSharedFlow<Tag> = MutableSharedFlow(replay = 1)
      override fun tagDesk(): MutableSharedFlow<Tag> = tags
    }

    fun wire(): Wire = LoudWire()

    suspend fun tagWire(): MutableSharedFlow<Tag> = MutableSharedFlow(replay = 1)
    """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))
  }

  /** The guarded unwrap of [struct]'s [property], naming the write lambda's own `v`. */
  private fun guarded(struct: String, property: String, type: String): String =
    "($struct.$property ?? throw new ArgumentException(" +
      "\"default($type) carries no $property; construct a $type instead\", nameof(v)))"

  private val tagId: String = guarded("v", "Id", "Tag")
  private val collarCat: String = guarded("v", "Cat", "Collar")

  /**
   * The disposed-owner guard an owner-keyed delegate opens with, AHEAD of the call whose
   * arguments refuse a `default(V)`: a wrapper that outlives its `Bulletin` answers
   * `ObjectDisposedException` whatever was passed.
   */
  private val ownerGuard: String =
    "if (_handle.IsInvalid) throw new ObjectDisposedException(nameof(Bulletin));"

  private fun assertClean() {
    assertEquals(emptyList(), result.kspErrors, "KSP errors (an ADR-055 mismatch lands here)")
    assertTrue(result.compiledClean, "generated Kotlin must compile; got: ${result.compileErrors}")
  }

  @Test
  fun `a String underlying emits as the string and re-wraps in Kotlin`() {
    assertClean()
    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp
    assertContains(csharp, Regex("""public KotlinMutableSharedFlow<[\w.:]*Tag> Tags\b"""))
    assertContains(
      csharp,
      Regex(
        """private static extern bool Native_TryEmitTags\(NugetKotlinHandle handle, """ +
          """\[MarshalAs\(UnmanagedType\.LPUTF8Str\)\] string value, out IntPtr error\);""",
      ),
    )
    assertContains(
      csharp,
      Regex(
        """private static extern IntPtr Native_EmitTags\(NugetKotlinHandle handle, """ +
          """NugetKotlinHandle scopeHandle, \[MarshalAs\(UnmanagedType\.LPUTF8Str\)\] """ +
          """string value, IntPtr callback, IntPtr userData\);""",
      ),
    )
    assertContains(csharp, "Native_TryEmitTags(_handle, $tagId, out IntPtr error), error);")
    assertContains(
      csharp, "Native_EmitTags(_handle, GetOrCreateScope(), $tagId, callback, userData);",
    )
    assertContains(kotlin, "obj.tags.tryEmit(tier1.sharedvc.Tag(value))")
    // The re-wrap runs before the launch, so an `init` that throws faults the Task instead of
    // escaping the `@CName`.
    val emit: String = kotlin
      .substringAfter("fun export_library_tier1_sharedvc__bulletin_emit_tags(")
      .substringBefore("\n}\n")
    assertContains(emit, "`value`: String,")
    assertContains(emit, Regex("""val element = try \{\s+tier1\.sharedvc\.Tag\(value\)"""))
    assertContains(emit, "obj.tags.emit(element)")
  }

  @Test
  fun `a primitive, enum and handle underlying emit as their own wire`() {
    assertClean()
    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp
    assertContains(csharp, Regex("""public KotlinMutableSharedFlow<[\w.:]*Naps> Naps\b"""))
    assertContains(
      csharp,
      "private static extern bool Native_TryEmitNaps(NugetKotlinHandle handle, int value, " +
        "out IntPtr error);",
    )
    assertContains(csharp, "Native_TryEmitNaps(_handle, v.Count, out IntPtr error), error);")
    assertContains(
      csharp, "Native_EmitNaps(_handle, GetOrCreateScope(), v.Count, callback, userData);",
    )
    assertContains(kotlin, "obj.naps.tryEmit(tier1.sharedvc.Naps(value))")
    assertContains(csharp, "Native_TryEmitRings(_handle, (int)v.Mood, out IntPtr error), error);")
    assertContains(
      kotlin,
      "obj.rings.tryEmit(tier1.sharedvc.MoodRing(tier1.sharedvc.Mood.entries[value]))",
    )
    assertContains(
      csharp, "Native_TryEmitCollars(_handle, $collarCat._handle, out IntPtr error), error);",
    )
    assertContains(
      kotlin,
      "obj.collars.tryEmit(" +
        "tier1.sharedvc.Collar(value.asStableRef<tier1.sharedvc.Cat>().get()))",
    )
  }

  @Test
  fun `a nullable element emits through the ordinary nullable value-class seam`() {
    assertClean()
    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp
    assertContains(csharp, Regex("""public KotlinMutableSharedFlow<[\w.:]*Tag\?> SpareTags\b"""))
    // Null stays null; only a PRESENT default is refused, which `v?.Id` would have read as null.
    assertContains(
      csharp,
      "Native_TryEmitSpareTags(_handle, v.HasValue ? ${guarded("v.Value", "Id", "Tag")} : null, " +
        "out IntPtr error), error);",
    )
    assertContains(kotlin, "obj.spareTags.tryEmit(value?.let { tier1.sharedvc.Tag(it) })")
    assertContains(
      csharp,
      "Native_TryEmitSpareNaps(_handle, v.HasValue, v.GetValueOrDefault().Count, " +
        "out IntPtr error), error);",
    )
    assertContains(
      csharp,
      "Native_EmitSpareNaps(_handle, GetOrCreateScope(), v.HasValue, " +
        "v.GetValueOrDefault().Count, callback, userData);",
    )
    assertContains(
      kotlin,
      "obj.spareNaps.tryEmit(if (valueHasValue) tier1.sharedvc.Naps(value) else null)",
    )
    assertContains(
      csharp,
      "Native_TryEmitSpareRings(_handle, v.HasValue, (int)v.GetValueOrDefault().Mood, " +
        "out IntPtr error), error);",
    )
    assertContains(
      csharp,
      "Native_TryEmitSpareCollars(_handle, v.HasValue ? " +
        "${guarded("v.Value", "Cat", "Collar")}._handle : NugetKotlinHandle.Null, " +
        "out IntPtr error), error);",
    )
    assertContains(
      kotlin,
      "obj.spareCollars.tryEmit(value?.asStableRef<tier1.sharedvc.Cat>()?.get()" +
        "?.let { tier1.sharedvc.Collar(it) })",
    )
    // A `Boolean` underlying is writable nullable too, as the MutableStateFlow arm admits it.
    assertContains(csharp, Regex("""public KotlinMutableSharedFlow<[\w.:]*Flag\?> Flags\b"""))
  }

  @Test
  fun `default of a reference-underlying record struct is refused in C# before either emit`() {
    assertClean()
    val csharp: String = result.generatedCSharp
    // Both delegates refuse in the call's own argument list: after the owner guard's statement,
    // before the extern is entered. The whole lambda is pinned, so nothing else sits in between.
    assertContains(
      csharp,
      "v => { $ownerGuard return NugetErrorNative.Check(" +
        "Native_TryEmitTags(_handle, $tagId, out IntPtr error), error); }",
    )
    assertContains(
      csharp,
      "(v, callback, userData) => { $ownerGuard return Native_EmitTags(" +
        "_handle, GetOrCreateScope(), $tagId, callback, userData); }",
    )
    assertContains(
      csharp,
      "v => { $ownerGuard return NugetErrorNative.Check(Native_TryEmitCollars(" +
        "_handle, $collarCat._handle, out IntPtr error), error); }",
    )
    assertContains(
      csharp,
      "(v, callback, userData) => { $ownerGuard return Native_EmitCollars(" +
        "_handle, GetOrCreateScope(), $collarCat._handle, callback, userData); }",
    )
    // The nullable spelling: `v?.Id` on a non-null default would ship a null and emit a null
    // nobody passed, so the unwrap refuses the present value.
    val spareId: String = "v.HasValue ? ${guarded("v.Value", "Id", "Tag")} : null"
    assertContains(
      csharp,
      "v => { $ownerGuard return NugetErrorNative.Check(Native_TryEmitSpareTags(" +
        "_handle, $spareId, out IntPtr error), error); }",
    )
    assertContains(
      csharp,
      "(v, callback, userData) => { $ownerGuard return Native_EmitSpareTags(" +
        "_handle, GetOrCreateScope(), $spareId, callback, userData); }",
    )
    // One spelling: the statement form this guard used to take is gone from every write.
    assertFalse(
      Regex("""is null\) throw new ArgumentException\(""").containsMatchIn(csharp),
      "expected the default(V) refusal only as the unwrap expression",
    )
    // A primitive or enum underlying's default is a legitimate value, so it is not guarded.
    listOf("Naps", "MoodRing", "Flag").forEach { type ->
      assertFalse("default($type)" in csharp, "$type has a real default and must not be guarded")
    }
    assertContains(
      csharp,
      "v => { $ownerGuard return NugetErrorNative.Check(Native_TryEmitNaps(_handle, v.Count, " +
        "out IntPtr error), error); }",
    )
  }

  @Test
  fun `the held, awaited, interface and top-level holders emit through the same guarded slot`() {
    assertClean()
    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp
    assertContains(csharp, Regex("""public KotlinMutableSharedFlow<[\w.:]*Tag> TagDesk\(\)"""))
    assertContains(
      csharp,
      Regex("""public Task<KotlinMutableSharedFlow<[\w.:]*Tag>> AwaitTagsAsync\("""),
    )
    assertContains(
      csharp,
      Regex("""public static Task<KotlinMutableSharedFlow<[\w.:]*Tag>> TagWireAsync\("""),
    )
    // Held (class and interface backing wrapper), awaited and top-level: every write lambda in
    // the file that crosses a non-null `Tag` is guarded, so no route reaches Kotlin with a null
    // string. A bare `v.Id` would be an unguarded write, and there is none.
    val writes: List<String> = csharp.lines().filter { line ->
      Regex("""\bv\.Id\b""").containsMatchIn(line) && line.contains("=> {")
    }
    val routes: List<String> = listOf(
      "Native_TagDeskTryEmit(owned, $tagId,", "Native_TagDeskEmit(owned,",
      "Native_AwaitTagsAsyncTryEmit(flowHandle, $tagId,", "Native_AwaitTagsAsyncEmit(flowHandle,",
      "TagWireAsync_native_TryEmit(flowHandle, $tagId,", "TagWireAsync_native_Emit(flowHandle,",
      "Native_TryEmitTags(_handle, $tagId,",
      "Native_EmitTags(_handle, GetOrCreateScope(), $tagId,",
    )
    routes.forEach { route ->
      assertTrue(writes.any { it.contains(route) }, "expected a write through $route; got $writes")
    }
    // The `default(Tag)` refusal is the unwrap in the call's argument list on every route, so the
    // call is the only statement of a flow keyed on its own handle. An owner-keyed delegate opens
    // with the disposed-owner guard, a statement ahead of that call, which therefore wins on a
    // disposed owner.
    val disposedOwner = Regex(
      """^if \(_handle\.IsInvalid\) throw new ObjectDisposedException\(nameof\([\w.:]+\)\); """,
    )
    writes.forEach { line ->
      val body: String = line.substringAfter("=> { ")
      assertEquals("(_handle," in line, disposedOwner.containsMatchIn(body), line)
      val call: String = disposedOwner.replace(body, "")
      assertTrue(call.startsWith("return "), line)
      assertContains(call.substringAfter("("), "$tagId, ")
      // Every `v.Id` on the line is the guarded one, and it is the line's only refusal.
      assertEquals(1, Regex("""\bv\.Id\b""").findAll(line).count(), line)
      assertEquals(1, Regex("""throw new Argument""").findAll(line).count(), line)
    }
    // Two writes each on three owners (Bulletin, LoudWire and the Wire backing wrapper) of the
    // property and the held desk, plus the awaited and the top-level holder.
    assertEquals(16, writes.size, "expected sixteen Tag write lambdas; got $writes")
    listOf(
      "library_tier1_sharedvc__bulletin_tagDesk_try_emit",
      "library_tier1_sharedvc__bulletin_tagDesk_emit",
      "library_tier1_sharedvc__bulletin_awaitTags_try_emit",
      "library_tier1_sharedvc__bulletin_awaitTags_emit",
      "library_tier1_sharedvc__wire_try_emit_tags",
      "library_tier1_sharedvc__wire_emit_tags",
    ).forEach { name -> assertContains(kotlin, "@CName(\"$name\")") }
    assertContains(
      kotlin,
      "flowHandle.asStableRef<kotlinx.coroutines.flow.MutableSharedFlow<Tag>>().get()" +
        ".tryEmit(tier1.sharedvc.Tag(value))",
    )
  }

  /**
   * `Nick` wraps a `String?`: a record struct but no ADR-171 box/unbox pair, so nothing could
   * read a bare `Nick` element back (`FromHandle<Nick>` has no `Factories` entry). It used to
   * bind as a read-only `KotlinSharedFlow<Nick>` whose collect and `ReplayCache` would throw; the
   * flow route now drops the member and names it, and the write refusal, which describes a
   * member that survives read-only, stays silent.
   */
  @Test
  fun `a value class over an underlying the setter refuses is skipped by name`() {
    assertClean()
    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp
    assertFalse(
      Regex("""SharedFlow<[\w.:]*Nick\??>""").containsMatchIn(csharp),
      "expected no holder of a bare Nick element; generatedCSharp=$csharp",
    )
    assertFalse("try_emit_nicks" in kotlin, "expected no Kotlin tryEmit export for Nick")
    assertFalse("nickDesk_try_emit" in kotlin, "expected no held tryEmit export for Nick")
    assertFalse("get_nicks_replay_cache" in kotlin, "expected no replay-cache export for Nick")
    listOf("Bulletin.nicks:", "Bulletin.nickDesk:").forEach { member ->
      val named: List<String> = result.kspWarnings.filter { it.contains(member) }
      assertEquals(1, named.size, "expected $member named once; got: ${result.kspWarnings}")
      assertContains(named.single(), "Nick")
      assertContains(named.single(), "those are the underlyings it has a boxed form for")
    }
    assertFalse(
      result.kspWarnings.any { it.contains("Nick") && it.contains("has no write arm") },
      "a dropped member has no read-only surface to describe; got: ${result.kspWarnings}",
    )
    // Every writable element is silent: nothing names Tag, Naps, MoodRing, Collar or Flag.
    listOf(
      "Bulletin.tags:", "Bulletin.naps:", "Bulletin.spareTags:", "Bulletin.flags:",
      "Bulletin.tagDesk", "Bulletin.awaitTags", "tagWire",
    ).forEach { member ->
      assertFalse(
        result.kspWarnings.any { warning ->
          warning.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) &&
            warning.contains(member)
        },
        "expected $member to bind without a diagnostic; kspWarnings=${result.kspWarnings}",
      )
    }
  }

  /**
   * The other underlyings the synchronous value-class setter refuses. None of these value classes
   * has a C# record struct at all, so the classifier refuses the type at every position and the
   * member is named once with the value class and its underlying (`SKIPPED_UNSUPPORTED_TYPE`),
   * exactly as on the `MutableStateFlow` twin (`Tier1MutableStateFlowValueClassElementTest`). The
   * C# is compiled for real: this cell used to assert the Kotlin half only, while the C# half
   * spelled the undeclared type.
   */
  @Test
  fun `a Char, stdlib class, nested value class or generic underlying is refused by name`() {
    val refusing: Tier1Result = Tier1Harness.run(
      """
      package tier1.sharedvcrefused

      import kotlin.time.Instant
      import kotlinx.coroutines.flow.MutableSharedFlow

      @JvmInline
      value class Initial(val letter: Char)

      @JvmInline
      value class Stamp(val at: Instant)

      @JvmInline
      value class Inner(val id: String)

      @JvmInline
      value class Outer(val inner: Inner)

      @JvmInline
      value class Oops(val cause: Throwable)

      class Bulletin {
        val initials: MutableSharedFlow<Initial> = MutableSharedFlow(replay = 1)
        val stamps: MutableSharedFlow<Stamp> = MutableSharedFlow(replay = 1)
        val outers: MutableSharedFlow<Outer> = MutableSharedFlow(replay = 1)
        val oopses: MutableSharedFlow<Oops> = MutableSharedFlow(replay = 1)
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    assertEquals(emptyList(), refusing.kspErrors)
    assertTrue(refusing.compiledClean, "got: ${refusing.compileErrors}")
    assertFalse(
      Regex("""bulletin_(try_)?emit_\w+""").containsMatchIn(refusing.generated),
      "expected no Kotlin emit export for a refused value class element",
    )
    val members: String = refusing.generatedCSharp.lines()
      .filter { line -> line.trimStart().startsWith("public Kotlin") }
      .joinToString("\n")
    assertFalse(
      Regex("""Kotlin(Mutable)?SharedFlow<[^>\n]*> \w+\b""").containsMatchIn(members),
      "expected no holder at all for a refused value class element; got:\n$members",
    )
    val pkg = "tier1.sharedvcrefused"
    mapOf(
      "Bulletin.initials:" to listOf("`$pkg.Initial`", "`kotlin.Char`"),
      "Bulletin.stamps:" to listOf("`$pkg.Stamp`", "`kotlin.time.Instant`"),
      "Bulletin.outers:" to listOf("`$pkg.Outer`", "`$pkg.Inner`"),
      "Bulletin.oopses:" to listOf("`$pkg.Oops`", "`kotlin.Throwable`"),
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
  fun `every value-class emit shape compiles in C#`() {
    assertClean()
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System.Collections.Generic;
      using System.Threading;
      using System.Threading.Tasks;
      using global::Interop;

      namespace Consumer
      {
          public static class Probe
          {
              public static async Task<bool> Run(
                  Bulletin bulletin, IWire wire, Cat cat, CancellationToken token)
              {
                  bool accepted = bulletin.Tags.TryEmit(new Tag("oreo-9"));
                  await bulletin.Tags.EmitAsync(new Tag("oreo-10"), token);
                  IReadOnlyList<Tag> tags = bulletin.Tags.ReplayCache;
                  bulletin.Naps.TryEmit(new Naps(3));
                  await bulletin.Naps.EmitAsync(new Naps(4));
                  bulletin.Rings.TryEmit(new MoodRing(Mood.Sleepy));
                  await bulletin.Collars.EmitAsync(new Collar(cat));
                  bulletin.SpareTags.TryEmit(null);
                  bulletin.SpareTags.TryEmit(new Tag("spare"));
                  await bulletin.SpareTags.EmitAsync(null);
                  bulletin.SpareNaps.TryEmit(null);
                  bulletin.SpareNaps.TryEmit(new Naps(2));
                  bulletin.SpareRings.TryEmit(new MoodRing(Mood.Happy));
                  bulletin.SpareCollars.TryEmit(null);
                  bulletin.Flags.TryEmit(new Flag(true));
                  IReadOnlyList<Tag?> spares = bulletin.SpareTags.ReplayCache;
                  using KotlinMutableSharedFlow<Tag> desk = bulletin.TagDesk();
                  desk.TryEmit(new Tag("held"));
                  using KotlinMutableSharedFlow<Tag> awaited = await bulletin.AwaitTagsAsync();
                  await awaited.EmitAsync(new Tag("awaited"), token);
                  wire.Tags.TryEmit(new Tag("wired"));
                  using KotlinMutableSharedFlow<Tag> wireDesk = wire.TagDesk();
                  await wireDesk.EmitAsync(new Tag("wire-held"));
                  using KotlinMutableSharedFlow<Tag> top = await Fixture.TagWireAsync();
                  top.TryEmit(new Tag("top"));
                  return accepted && tags.Count + spares.Count > 0;
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
