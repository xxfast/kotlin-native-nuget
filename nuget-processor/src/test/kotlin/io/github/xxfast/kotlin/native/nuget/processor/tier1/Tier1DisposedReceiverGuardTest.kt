package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #610: every generated wrapper instance member over the owner's `_handle` opens with
 * `if (_handle.IsInvalid) throw new ObjectDisposedException(nameof(Owner));`, so a disposed receiver
 * is an `ObjectDisposedException` instead of the zero `NugetKotlinHandle.Null` sentinel reaching
 * Kotlin (a `KotlinNullReferenceException`, or a process abort through an export with no error
 * slot, which is what a data class's `Equals` / `GetHashCode` / `ToString` are).
 */
class Tier1DisposedReceiverGuardTest {

  private val source: String =
    """
    package tier1.disposedguard

    class Kitten(var name: String) {
      fun purr(): String = "purr"
      fun nap(times: Int) { }
      fun count(pounce: (Int) -> Unit): Int { pounce(1); return 1 }
      // The legacy per-call route: a nullable lambda.
      fun onMaybe(pounce: ((Int) -> Unit)?) { pounce?.invoke(1) }
    }

    interface Adoptable {
      val name: String
      var day: Int
      fun greet(): String
    }

    data class Record(val kitten: Kitten, var day: Int)

    class Crate<T>(val item: T) {
      var count: Int = 0
      fun peek(): T = item
    }

    sealed class Job {
      data class Running(val percent: Int) : Job() {
        var label: String = ""
        fun bump(): Int = percent + 1
      }
      data object Idle : Job()
    }

    abstract class Animal {
      abstract val legs: Int
      abstract fun sound(): String
    }

    object Shelter {
      var capacity: Int = 3
      fun open(): Boolean = true
    }

    fun tally(): Int = 1

    class Centre {
      fun kitten(): Kitten = Kitten("Oreo")
      fun pet(): Adoptable = object : Adoptable {
        override val name: String = "Mylo"
        override var day: Int = 1
        override fun greet(): String = "hi"
      }
      fun record(): Record = Record(Kitten("Oreo"), 1)
      fun crate(): Crate<Int> = Crate(1)
      fun job(): Job = Job.Running(1)
      fun animal(): Animal = object : Animal() {
        override val legs: Int = 4
        override fun sound(): String = "meow"
      }
    }
    """.trimIndent()

  private val result: Tier1Result by lazy { Tier1Harness.run(source) }

  private fun guard(owner: String): String =
    "if (_handle.IsInvalid) throw new ObjectDisposedException(nameof($owner));"

  /** The generated code lines of the C# type declared by [header], `///` doc lines dropped. */
  private fun block(header: String): String {
    val cs: String = result.generatedCSharp
    val start: Int = cs.indexOf(header)
    assertTrue(start >= 0, "expected `$header` in the generated C#; cs=$cs")
    val indent: String = cs.substring(cs.lastIndexOf('\n', start) + 1, start)
    val end: Int = cs.indexOf("\n$indent}", start)
    return cs.substring(start, end).lines()
      .filterNot { line -> line.trimStart().startsWith("///") }
      .joinToString("\n")
  }

  /** The first statement after the `{` that follows [signature] in [code]. */
  private fun firstStatementAfter(code: String, signature: String): String {
    val at: Int = code.indexOf(signature)
    assertTrue(at >= 0, "expected `$signature`; code=$code")
    return code.substring(code.indexOf('{', at) + 1).trimStart().lineSequence().first().trim()
  }

  private fun assertOpensWithGuard(code: String, signature: String, owner: String) {
    val first: String = firstStatementAfter(code, signature)
    assertTrue(first == guard(owner), "`$signature` must open with `${guard(owner)}`; got `$first`; code=$code")
  }

  /** As [assertOpensWithGuard], for the [accessor] (`get` or `set`) of the property declared by [property]. */
  private fun assertAccessorOpensWithGuard(code: String, property: String, accessor: String, owner: String) {
    assertTrue(property in code, "expected `$property`; code=$code")
    val section: String = code.substringAfter(property)
    val at: MatchResult? = Regex("""\n\s*$accessor\n""").find(section)
    assertTrue(at != null, "expected a block-bodied `$accessor` on `$property`; code=$code")
    val first: String =
      section.substring(section.indexOf('{', at.range.last) + 1).trimStart().lineSequence().first().trim()
    assertTrue(first == guard(owner), "`$property` $accessor must open with `${guard(owner)}`; got `$first`")
  }

  @Test
  fun `a class's method, getter and setter open with the disposed-receiver guard`() {
    assertTrue(result.compiledClean, "expected clean Kotlin; got: ${result.compileErrors}")
    val kitten: String = block("public class Kitten")
    assertOpensWithGuard(kitten, "public string Purr()", "Kitten")
    assertOpensWithGuard(kitten, "void Nap(int times)", "Kitten")
    assertAccessorOpensWithGuard(kitten, "public string Name", "get", "Kitten")
    assertAccessorOpensWithGuard(kitten, "public string Name", "set", "Kitten")
  }

  @Test
  fun `a callback null check stays ahead of the guard, and both precede the registration`() {
    val count: String = block("public class Kitten").substringAfter("public int Count(")
    val nullCheck: Int = count.indexOf("ArgumentNullException.ThrowIfNull(pounce);")
    val guardAt: Int = count.indexOf(guard("Kitten"))
    val register: Int = count.indexOf("NugetThunks.RegisterCtx(")
    assertTrue(nullCheck in 0 until guardAt, "argument validation precedes receiver state; $count")
    assertTrue(guardAt < register, "the guard precedes the registration; $count")
  }

  @Test
  fun `the legacy per-call callback route checks the receiver after its argument`() {
    val maybe: String = block("public class Kitten").substringAfter("public void OnMaybe(")
    val nullCheck: Int = maybe.indexOf("ArgumentNullException.ThrowIfNull(pounce);")
    val guardAt: Int = maybe.indexOf(guard("Kitten"))
    val register: Int = maybe.indexOf("NugetThunks.RegisterCtx(nativeCallback)")
    assertTrue(nullCheck in 0 until guardAt, "argument validation precedes receiver state; $maybe")
    assertTrue(guardAt < register, "the guard precedes the registration; $maybe")
  }

  @Test
  fun `an interface backing wrapper's members open with the guard`() {
    val pet: String = block("public sealed class Adoptable")
    assertOpensWithGuard(pet, "public string Greet()", "Adoptable")
    assertAccessorOpensWithGuard(pet, "public string Name", "get", "Adoptable")
    assertAccessorOpensWithGuard(pet, "public int Day", "get", "Adoptable")
    assertAccessorOpensWithGuard(pet, "public int Day", "set", "Adoptable")
  }

  @Test
  fun `a data class's Equals, GetHashCode and ToString open with the guard`() {
    val record: String = block("public class Record")
    assertOpensWithGuard(record, "public override bool Equals(object? obj)", "Record")
    assertOpensWithGuard(record, "public override int GetHashCode()", "Record")
    assertOpensWithGuard(record, "public override string ToString()", "Record")
    // A data arm's three overrides name the arm.
    val running: String = block("public sealed class Running")
    assertOpensWithGuard(running, "public override bool Equals(object? obj)", "Running")
    assertOpensWithGuard(running, "public override int GetHashCode()", "Running")
    assertOpensWithGuard(running, "public override string ToString()", "Running")
    assertOpensWithGuard(running, "public int Bump()", "Running")
    // A disposed `other` is refused before the slotless `Native_Equals`, after the receiver guard.
    assertArgumentGuarded(record, "Record")
    assertArgumentGuarded(running, "Running")
  }

  private fun assertArgumentGuarded(code: String, owner: String) {
    val equals: String = code.substringAfter("public override bool Equals(object? obj)").substringBefore("return false;")
    val receiver: Int = equals.indexOf(guard(owner))
    val argument: Int =
      equals.indexOf("if (other._handle.IsInvalid) throw new ObjectDisposedException(nameof($owner));")
    val call: Int = equals.indexOf("Native_Equals(_handle, other._handle)")
    assertTrue(receiver in 0 until argument, "the receiver guard comes first; $equals")
    assertTrue(argument < call, "the argument guard precedes Native_Equals; $equals")
  }

  @Test
  fun `a generic class names itself applied, so nameof binds`() {
    val crate: String = block("public class Crate<T>")
    assertOpensWithGuard(crate, "public T Peek()", "Crate<T>")
    assertAccessorOpensWithGuard(crate, "public int Count", "get", "Crate<T>")
    assertAccessorOpensWithGuard(crate, "public int Count", "set", "Crate<T>")
  }

  @Test
  fun `a static owner has no guard`() {
    val code: String = result.generatedCSharp.lines()
      .filterNot { line -> line.trimStart().startsWith("///") }
      .joinToString("\n")
    val shelter: String = block("public static class Shelter")
    assertFalse("_handle" in shelter, "an object has no receiver handle; $shelter")
    val tally: String = code.substringAfter("public static int Tally()").substringBefore("\n        }")
    assertFalse("IsInvalid" in tally, "a top-level function has no guard; $tally")
  }

  @Test
  fun `the guarded C# compiles`() {
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop;

      namespace Consumer
      {
          public static class Probe
          {
              public static int Touch(Centre centre)
              {
                  using Kitten kitten = centre.Kitten();
                  kitten.Name = kitten.Purr();
                  using Record record = centre.Record();
                  int total = record.GetHashCode() + record.ToString().Length + (record.Equals(record) ? 1 : 0);
                  using Crate<int> crate = centre.Crate();
                  crate.Count = crate.Peek();
                  using Job job = centre.Job();
                  if (job is Job.Running running) total += running.Bump() + running.GetHashCode();
                  using Animal animal = centre.Animal();
                  IAdoptable pet = centre.Pet();
                  pet.Day = pet.Day + 1;
                  return total + animal.Legs + animal.Sound().Length + pet.Greet().Length;
              }
          }
      }
      """.trimIndent(),
    )
  }
}
