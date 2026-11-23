package io.github.xxfast.kotlin.native.nuget.processor.tier1

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ADR-242 (issue #611): the scalar data class admission predicate, through the real processor.
 * Each shape is either a C# value (`public sealed class X : IEquatable<X>`, no `IDisposable`) or
 * keeps ADR-008's handle class. Read off the class declaration line of the generated C#, never a
 * `///` line. The probe that pinned the flags this reads is [Tier1DataClassStoredStateTest].
 */
class Tier1DataValueAdmissionTest {

  private val fixture: String = """
    package tier1.datavalues

    import kotlin.properties.ReadOnlyProperty
    import kotlin.reflect.KProperty
    import kotlin.time.Duration
    import kotlin.time.Instant
    import kotlin.uuid.Uuid

    fun now(): Long = 42L

    enum class Mood { Happy, Sleepy }
    @JvmInline value class Stitches(val count: Int)
    class Engine(val hp: Int)

    data class Plain(val name: String, val count: Int)
    data class Computed(val name: String) {
      val label: String get() = name + "!"
      fun shout(loud: Boolean): String = if (loud) name.uppercase() else name
    }
    data class Checked(val n: Int) {
      init { require(n >= 0) }
    }
    data class Pairish(val a: Int, val b: Int) {
      constructor(a: Int) : this(a, 0)
    }
    data class Wide(
      val mood: Mood,
      val stitches: Stitches,
      val at: Instant,
      val every: Duration,
      val serial: Uuid,
      val nickname: String?,
      val weight: Double,
      val letter: Char,
      val tags: List<String>,
      val fabrics: Set<String>,
      val patches: Map<String, Int>,
    )
    data class Point(val x: Int, val y: Int)
    data class Line(val start: Point, val end: Point?, val stops: List<Point>)

    data class Stamped(val name: String) {
      val createdAt: Long = now()
    }
    data class FieldReader(val name: String) {
      val x: Int = 0
        get() = field + 1
    }
    data class Lazy(val name: String) {
      val upper: String by lazy { name.uppercase() }
    }
    object Doubler : ReadOnlyProperty<Any?, Int> {
      override fun getValue(thisRef: Any?, property: KProperty<*>): Int = 2
    }
    data class Delegated(val name: String) {
      val twice: Int by Doubler
    }
    data class MutableComponent(var count: Int)
    data class Counter(val name: String) {
      var hits: Int = 0
    }
    data class Late(val name: String) {
      lateinit var cache: String
    }
    data class ComputedVar(val name: String) {
      var alias: String
        get() = name
        set(value) { println(value) }
    }
    data class PrivateState(val name: String) {
      private val secret: Long = now()
    }
    data class Car(val engine: Engine)
    data class Garage(val engines: List<Engine>)
    data class Growing(val tags: MutableList<String>)
    data class Chipped(val chip: ByteArray)
    sealed interface Shape
    data class Dot(val x: Int) : Shape
    sealed class Base
    data class Arm(val y: Int) : Base()
    data class Box<T>(val value: T)
    open class Animal(val legs: Int)
    data class Dog(val name: String) : Animal(4)
    interface Named { val name: String }
    data class Tag(override val name: String) : Named
    data class Failure(val code: Int) : Exception()
    data class Internal internal constructor(val n: Int)
    data class PrivateComponent(val shown: Int, private val hidden: Int)
    data class InternalComponent(val shown: Int, internal val hidden: Int)
    data class Node(val value: Int, val next: Node?)
    data class Ping(val pong: Pong?)
    data class Pong(val ping: Ping?)

    class Factory {
      fun box(): Box<Int> = Box(1)
      fun failure(): Failure = Failure(1)
    }
  """.trimIndent()

  private val values: List<String> = listOf(
    "Plain", "Computed", "Checked", "Pairish", "Wide", "Point", "Line",
  )

  private val handles: Map<String, String> = linkedMapOf(
    "Stamped" to "body val with an initialiser",
    "FieldReader" to "body val whose getter reads field",
    "Lazy" to "by lazy body property",
    "Delegated" to "custom ReadOnlyProperty delegate",
    "MutableComponent" to "var component",
    "Counter" to "body var",
    "Late" to "lateinit var",
    "ComputedVar" to "mutable computed property",
    "PrivateState" to "private body val with an initialiser",
    "Car" to "handle-typed component",
    "Garage" to "list of handle-typed elements",
    "Growing" to "MutableList component",
    "Chipped" to "ByteArray component",
    "Dot" to "sealed interface arm",
    "Arm" to "sealed class arm",
    "Dog" to "non-Any superclass",
    "Tag" to "exported interface",
    "Internal" to "internal primary constructor",
    "PrivateComponent" to "private component",
    "InternalComponent" to "internal component",
    "Node" to "self-referencing type graph",
    "Ping" to "mutually recursive type graph",
    "Pong" to "mutually recursive type graph",
  )

  private fun classLine(interop: String, name: String): String? = interop.lines()
    .map { it.trim() }
    .firstOrNull { line ->
      !line.startsWith("///") &&
          Regex("""^public (sealed |abstract |partial )*class $name\b""").containsMatchIn(line)
    }

  private fun interopOf(result: Tier1Result): String = result.generatedFiles.entries
    .firstOrNull { it.key.endsWith("Interop.cs") }?.value
    ?: fail("no Interop.cs: ${result.generatedFiles.keys} kspErrors=${result.kspErrors}")

  @Test
  fun `the predicate admits exactly the immutable all-scalar in-module data classes`() {
    val result = Tier1Harness.run(fixture)
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    val interop: String = interopOf(result)

    val wrong = mutableListOf<String>()
    values.forEach { name ->
      val line: String? = classLine(interop, name)
      val isValue: Boolean = line != null &&
          line.startsWith("public sealed class $name : IEquatable<$name>") &&
          "IDisposable" !in line
      if (!isValue) wrong += "$name should be a value, declared as: $line"
    }
    handles.forEach { (name, why) ->
      val line: String? = classLine(interop, name)
      // A derived handle class (an arm, a subclass) inherits `IDisposable` from its base.
      val isHandle: Boolean = line != null && "IEquatable<$name>" !in line &&
          ("IDisposable" in line || Regex("""class $name : \w""").containsMatchIn(line))
      if (!isHandle) wrong += "$name ($why) should stay a handle, declared as: $line"
    }
    if (wrong.isNotEmpty()) fail(wrong.joinToString("\n"))
  }

  /** Generic and `Throwable` data classes keep their own routes; neither becomes a value. */
  @Test
  fun `a generic or Throwable data class is never a value`() {
    val interop: String = interopOf(Tier1Harness.run(fixture))
    listOf("Box", "Failure").forEach { name ->
      val line: String? = classLine(interop, name)
      assertTrue(line == null || "IEquatable<$name>" !in line, "$name became a value: $line")
    }
  }

  /** In-module only: a dependency-module data class stays a handle even when it would qualify. */
  @Test
  fun `a dependency-module data class stays a handle`() {
    val jar: File = Tier1DependencyLibrary.compile(
      """
      package dep.datavalues

      data class Ribbon(val colour: String, val length: Int)
      """.trimIndent(),
    )
    val result = Tier1Harness.run(
      """
      package tier1.depvalues

      import dep.datavalues.Ribbon

      class Wrapper {
        fun ribbon(): Ribbon = Ribbon("red", 3)
      }
      """.trimIndent(),
      processorOptions = mapOf(
        "nuget.namespace" to "Lib",
        "nuget.includePackages" to "tier1.depvalues",
        "nuget.admit" to "dep.datavalues.Ribbon",
      ),
      libraries = listOf(jar),
    )
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    val interop: String = interopOf(result)
    val line: String? = classLine(interop, "Ribbon")
    assertTrue(line != null && "IDisposable" in line, "Ribbon should stay a handle: $line")
  }
  /**
   * The value shape builds in C#: construction, components, a computed member, equality, `Copy`,
   * and every ordinary position a value crosses (return, parameter, nullable, list both ways, a
   * handle-class property and method) bind against it. A value has no `Dispose` to call.
   */
  @Test
  fun `a consumer of every ordinary value position compiles against the generated C#`() {
    val result = Tier1Harness.run(
      """
      package tier1.valueshape

      enum class Mood { Happy, Sleepy }

      data class Toy(val name: String, val colors: List<String>, val mood: Mood, val squeaks: Int) {
        init { require(name.isNotBlank()) }
        val label: String get() = name + colors
        fun describe(loud: Boolean): String = if (loud) name.uppercase() else name
      }

      data class Crate(val star: Toy, val spare: Toy?, val stack: List<Toy>)

      data class Tagged(val name: String, val notes: List<String>? = null, val count: Int = 1)

      fun favourite(): Toy = Toy("Bun", listOf("pink"), Mood.Happy, 3)
      fun echo(toy: Toy): Toy = toy
      fun find(name: String): Toy? = null
      fun nameOf(toy: Toy?): String = toy?.name ?: "nobody"
      fun toys(): List<Toy> = listOf(favourite())
      fun total(toys: List<Toy>): Int = toys.sumOf { it.squeaks }
      fun crate(): Crate = Crate(favourite(), null, toys())

      class Shelf {
        var favourite: Toy = Toy("Bun", listOf("pink"), Mood.Happy, 3)
        var spare: Toy? = null
        fun put(toy: Toy): Int = 1
      }
      """.trimIndent(),
      fileName = "Values.kt",
      processorOptions = mapOf("nuget.namespace" to "Tier1"),
    )
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    val consumer = """
      using System;
      using System.Collections.Generic;
      using Tier1;
      public static class Consumer
      {
          public static void Touch()
          {
              Toy toy = new Toy("Bun", new[] { "pink" }, Mood.Happy, 3);
              string name = toy.Name;
              IReadOnlyList<string> colors = toy.Colors;
              string label = toy.Label;
              string loud = toy.Describe(true);
              bool same = toy.Equals(Values.Favourite()) && toy == Values.Echo(toy) && toy != Values.Echo(toy).Copy(squeaks: 9);
              int hash = toy.GetHashCode();
              string text = toy.ToString();
              Toy louder = toy.Copy(squeaks: 4);
              Toy? found = Values.Find("Milo");
              string nobody = Values.NameOf(null) + Values.NameOf(toy);
              IReadOnlyList<Toy> toys = Values.Toys();
              int total = Values.Total(new[] { toy, louder });
              Crate crate = Values.Crate();
              Toy star = crate.Star;
              Toy? spare = crate.Spare;
              IReadOnlyList<Toy> stack = crate.Stack;
              Crate copied = crate.Copy(spare: toy);
              using var shelf = new Shelf();
              shelf.Favourite = toy;
              shelf.Spare = null;
              Toy? onShelf = shelf.Spare;
              int put = shelf.Put(toy);
              IEquatable<Toy> equatable = toy;
              Tagged defaulted = new Tagged("Oreo");
              Tagged counted = new Tagged("Mylo", count: 2, notes: new[] { "sleepy" });
              bool tagsEqual = defaulted == counted.Copy(name: "Oreo");
          }
      }
    """.trimIndent()
    val build: Tier1CSharpBuild = Tier1CSharpCompile.compile(result, consumer)
    assertTrue(build.succeeded, "expected the consumer to compile; log:\n${build.log}")
  }
  /**
   * An opt-in-marked component (on the parameter, or through its type) is never rebuilt by a
   * value: the class must either stay a handle or bind every component, never crash the processor
   * or render a value whose C# constructor dropped a component.
   */
  @Test
  fun `an opt-in-marked component keeps the class a handle`() {
    val result = Tier1Harness.run(
      """
      package tier1.markedvalues

      @RequiresOptIn(level = RequiresOptIn.Level.ERROR)
      annotation class Fragile

      @Fragile
      enum class Glaze { Matte, Gloss }

      data class MarkedParameter(val name: String, @property:Fragile val chips: Int = 0)

      @OptIn(Fragile::class)
      data class MarkedType(val name: String, val glaze: Glaze = Glaze.Matte)

      fun parameter(): MarkedParameter = MarkedParameter("Oreo")

      @OptIn(Fragile::class)
      fun type(): MarkedType = MarkedType("Mylo")
      """.trimIndent(),
      fileName = "Marked.kt",
      processorOptions = mapOf("nuget.namespace" to "Tier1"),
    )
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    val interop: String = interopOf(result)
    listOf("MarkedParameter", "MarkedType").forEach { name ->
      val line: String? = classLine(interop, name)
      assertTrue(line != null && "IDisposable" in line, "$name should stay a handle: $line")
    }
    val build: Tier1CSharpBuild = Tier1CSharpCompile.compile(result, "")
    assertTrue(build.succeeded, "expected the generated C# to compile; log:\n${build.log}")
  }
}
