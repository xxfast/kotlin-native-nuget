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
 * A reference underlying guards `default(V)` in C# on BOTH writes, before anything crosses: its
 * underlying is null, which the non-null Kotlin slot cannot take (and which, nullable, would emit
 * a null nobody passed). A value class whose underlying the setter refuses stays the read-only
 * `KotlinSharedFlow<V>` and is named with that underlying.
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

  private val tagGuard: String = "if (v.Id is null) throw new ArgumentException(" +
    "\"default(Tag) carries no Id; construct a Tag instead\", nameof(v));"

  /**
   * The disposed-owner guard an owner-keyed delegate opens with, AHEAD of the argument guards: a
   * wrapper that outlives its `Bulletin` answers `ObjectDisposedException` whatever was passed.
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
    assertContains(csharp, "Native_TryEmitTags(_handle, v.Id, out IntPtr error), error);")
    assertContains(
      csharp, "Native_EmitTags(_handle, GetOrCreateScope(), v.Id, callback, userData);",
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
      csharp, "Native_TryEmitCollars(_handle, v.Cat._handle, out IntPtr error), error);",
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
    assertContains(csharp, "Native_TryEmitSpareTags(_handle, v?.Id, out IntPtr error), error);")
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
      "Native_TryEmitSpareCollars(_handle, v?.Cat._handle ?? NugetKotlinHandle.Null, " +
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
    // Both delegates carry the guard ahead of the native call, in the same statement block.
    assertContains(
      csharp,
      "v => { $ownerGuard $tagGuard return NugetErrorNative.Check(" +
        "Native_TryEmitTags(_handle, v.Id, out IntPtr error), error); }",
    )
    assertContains(
      csharp,
      "(v, callback, userData) => { $ownerGuard $tagGuard return Native_EmitTags(" +
        "_handle, GetOrCreateScope(), v.Id, callback, userData); }",
    )
    val collarGuard: String = "if (v.Cat is null) throw new ArgumentException(" +
      "\"default(Collar) carries no Cat; construct a Collar instead\", nameof(v));"
    assertContains(
      csharp,
      "v => { $ownerGuard $collarGuard return NugetErrorNative.Check(Native_TryEmitCollars(",
    )
    assertContains(
      csharp,
      "(v, callback, userData) => { $ownerGuard $collarGuard return Native_EmitCollars(",
    )
    // The nullable spelling: `v?.Id` on a non-null default would ship a null and emit a null
    // nobody passed, so the guard tests the present value.
    val spareGuard: String = "if (v.HasValue && v.Value.Id is null) throw new ArgumentException(" +
      "\"default(Tag) carries no Id; construct a Tag instead\", nameof(v));"
    assertContains(
      csharp,
      "v => { $ownerGuard $spareGuard return NugetErrorNative.Check(Native_TryEmitSpareTags(",
    )
    assertContains(
      csharp,
      "(v, callback, userData) => { $ownerGuard $spareGuard return Native_EmitSpareTags(",
    )
    // A primitive or enum underlying's default is a legitimate value, so it is not guarded.
    assertFalse("v.Count is null" in csharp, "a primitive underlying must not be guarded")
    assertFalse("v.Mood is null" in csharp, "an enum underlying must not be guarded")
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
    // the file that crosses `v.Id` is guarded, so no route reaches Kotlin with a null string.
    val writes: List<String> = csharp.lines().filter { line ->
      line.contains("v.Id,") && line.contains("=> {")
    }
    val routes: List<String> = listOf(
      "Native_TagDeskTryEmit(owned, v.Id,", "Native_TagDeskEmit(owned,",
      "Native_AwaitTagsAsyncTryEmit(flowHandle, v.Id,", "Native_AwaitTagsAsyncEmit(flowHandle,",
      "TagWireAsync_native_TryEmit(flowHandle, v.Id,", "TagWireAsync_native_Emit(flowHandle,",
      "Native_TryEmitTags(_handle, v.Id,", "Native_EmitTags(_handle, GetOrCreateScope(), v.Id,",
    )
    routes.forEach { route ->
      assertTrue(writes.any { it.contains(route) }, "expected a write through $route; got $writes")
    }
    // The `default(Tag)` guard is the last statement before the call on every route. A flow
    // keyed on its own handle opens with it; an owner-keyed delegate opens with the
    // disposed-owner guard instead, which therefore wins on a disposed owner.
    val disposedOwner = Regex(
      """^if \(_handle\.IsInvalid\) throw new ObjectDisposedException\(nameof\([\w.:]+\)\); """,
    )
    writes.forEach { line ->
      val body: String = line.substringAfter("=> { ")
      assertEquals("(_handle," in line, disposedOwner.containsMatchIn(body), line)
      assertTrue(disposedOwner.replace(body, "").startsWith("$tagGuard return "), line)
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

  @Test
  fun `a value class over an underlying the setter refuses stays read-only and is named`() {
    assertClean()
    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp
    assertContains(csharp, Regex("""public KotlinSharedFlow<[\w.:]*Nick> Nicks\b"""))
    assertContains(csharp, Regex("""public KotlinSharedFlow<[\w.:]*Nick> NickDesk\(\)"""))
    assertFalse(
      Regex("""KotlinMutableSharedFlow<[\w.:]*Nick>""").containsMatchIn(csharp),
      "expected no writable holder of a Nick element; generatedCSharp=$csharp",
    )
    assertFalse("try_emit_nicks" in kotlin, "expected no Kotlin tryEmit export for Nick")
    assertFalse("nickDesk_try_emit" in kotlin, "expected no held tryEmit export for Nick")
    assertContains(kotlin, "@CName(\"library_tier1_sharedvc__bulletin_get_nicks_replay_cache\")")
    listOf("Bulletin.nicks:", "Bulletin.nickDesk").forEach { member ->
      assertTrue(
        result.kspWarnings.any {
          it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) &&
            it.contains(member) && it.contains("EmitAsync") &&
            it.contains("value class tier1.sharedvc.Nick over kotlin.String?") &&
            it.contains("has no write arm")
        },
        "expected a SKIPPED_UNSUPPORTED_INPUT naming $member and its underlying; " +
          "kspWarnings=${result.kspWarnings}",
      )
    }
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
   * The other underlyings the synchronous value-class setter refuses. Each is named with its
   * underlying and gets no write export. Asserted on the Kotlin half and the diagnostics only:
   * none of these value classes has a C# record struct at all, exactly as on the
   * `MutableStateFlow` twin (`Tier1MutableStateFlowValueClassElementTest`).
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
    assertFalse(
      Regex("""KotlinMutableSharedFlow<[^>\n]*> \w+\b""").containsMatchIn(refusing.generatedCSharp),
      "expected no writable holder for a refused value class element",
    )
    mapOf(
      "Bulletin.initials:" to "kotlin.Char",
      "Bulletin.stamps:" to "kotlin.time.Instant",
      "Bulletin.outers:" to "tier1.sharedvcrefused.Inner",
      "Bulletin.oopses:" to "kotlin.Throwable",
    ).forEach { (member, named) ->
      assertTrue(
        refusing.kspWarnings.any {
          it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) &&
            it.contains(member) && it.contains("over $named has no write arm") &&
            it.contains("EmitAsync")
        },
        "expected $member refused naming $named; kspWarnings=${refusing.kspWarnings}",
      )
    }
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
                  KotlinSharedFlow<Nick> nicks = bulletin.Nicks;
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
