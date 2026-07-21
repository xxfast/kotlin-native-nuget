package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Ordinary synchronous surface shapes that were under-represented in the ADR-060 cell suite.
 * Structural Interop.cs assertions + compile cleanliness. No specialized protocols.
 */
class Tier1OrdinarySurfaceTest {

  @Test
  fun `data class renders equals hashcode tostring and copy`() {
    val result = Tier1Harness.run(
      """
      package tier1.dataclass

      data class Point(val x: Int, val y: Int)
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected data class exports to compile; got: ${result.compileErrors}")
    val cs = result.generatedCSharp
    assertContains(cs, "public class Point : IDisposable")
    assertContains(cs, "public Point Copy(int x, int y)")
    assertContains(cs, "public override bool Equals(object? obj)")
    assertContains(cs, "public override int GetHashCode()")
    assertContains(cs, "public override string ToString()")
  }

  @Test
  fun `top-level property get and set surface in Interop cs`() {
    val result = Tier1Harness.run(
      """
      package tier1.toplevelprop

      var counter: Int = 0
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "got: ${result.compileErrors}")
    val cs = result.generatedCSharp
    assertContains(cs, "public static int Counter")
    assertContains(cs, "EntryPoint = \"get_counter\"")
    assertContains(cs, "EntryPoint = \"set_counter\"")
  }

  @Test
  fun `extension property on Int surfaces as static Get Set methods`() {
    val result = Tier1Harness.run(
      """
      package tier1.extprop

      var Int.doubled: Int
        get() = this * 2
        set(_) { /* no-op storage for export surface */ }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "got: ${result.compileErrors}")
    val cs = result.generatedCSharp
    assertContains(cs, "GetDoubled(this int")
    assertContains(cs, "SetDoubled(this int")
  }

  @Test
  fun `companion property surfaces as static class members`() {
    val result = Tier1Harness.run(
      """
      package tier1.companionprop

      class Patient(val name: String) {
        companion object {
          var defaultAge: Int = 0
        }
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "got: ${result.compileErrors}")
    val cs = result.generatedCSharp
    assertContains(cs, "public static int DefaultAge")
    assertContains(cs, "patient_companion_get_defaultAge")
  }

  @Test
  fun `enum entries and enum property extension render`() {
    val result = Tier1Harness.run(
      """
      package tier1.enumprop

      enum class Mood(val label: String) {
        CALM("calm"),
        ANXIOUS("anxious"),
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "got: ${result.compileErrors}")
    val cs = result.generatedCSharp
    assertContains(cs, "public enum Mood")
    assertContains(cs, "Calm = 0")
    assertContains(cs, "Anxious = 1")
    assertContains(cs, "public static class MoodExtensions")
    assertContains(cs, "public static string Label(this Mood mood)")
  }

  @Test
  fun `secondary constructor uses create suffix`() {
    val result = Tier1Harness.run(
      """
      package tier1.secondaryctor

      class Patient(val name: String) {
        constructor(name: String, age: Int) : this(name)
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "got: ${result.compileErrors}")
    val cs = result.generatedCSharp
    assertContains(cs, "public Patient(string name)")
    assertContains(cs, "public Patient(string name, int age)")
    assertContains(cs, "Native_Create_2")
  }

  @Test
  fun `multi-package rootPackage folds nested namespace`() {
    val result = Tier1Harness.run(
      mapOf(
        "Root.kt" to """
          package com.clinic

          class Desk(val id: Int)
        """.trimIndent(),
        "Nested.kt" to """
          package com.clinic.patients

          class Patient(val name: String)
        """.trimIndent(),
      ),
      processorOptions = mapOf(
        "nuget.rootPackage" to "com.clinic",
        "nuget.namespace" to "Clinic",
      ),
    )

    assertTrue(result.compiledClean, "got: ${result.compileErrors}")
    val cs = result.generatedCSharp
    assertContains(cs, "namespace Clinic")
    assertContains(cs, "namespace Clinic.Patients")
    assertContains(cs, "public class Desk")
    assertContains(cs, "public class Patient")
  }

  @Test
  fun `class string property compiles and surfaces on Interop cs`() {
    val result = Tier1Harness.run(
      """
      package tier1.stringprop

      class Patient(var nickname: String)
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "got: ${result.compileErrors}")
    val cs = result.generatedCSharp
    assertContains(cs, "public string Nickname")
    assertContains(cs, "Marshal.PtrToStringUTF8")
  }

  @Test
  fun `object with Unit method and Int return compiles`() {
    val result = Tier1Harness.run(
      """
      package tier1.objectsurface

      object Registry {
        fun reset() {}
        fun size(): Int = 0
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "got: ${result.compileErrors}")
    val cs = result.generatedCSharp
    assertContains(cs, "public static class Registry")
    assertContains(cs, "public static void Reset()")
    assertContains(cs, "public static int Size()")
  }

  @Test
  fun `primitive value class renders record struct with CreateChecked`() {
    val result = Tier1Harness.run(
      """
      package tier1.valueclass

      @JvmInline
      value class CatId(val value: Int) {
        fun next(): Int = value + 1
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "got: ${result.compileErrors}")
    val cs = result.generatedCSharp
    assertContains(cs, "public readonly record struct CatId")
    assertContains(cs, "public int Value { get; }")
    assertContains(cs, "CreateChecked")
    assertContains(cs, "public int Next()")
  }

  @Test
  fun `abstract class renders abstract members and dispose`() {
    val result = Tier1Harness.run(
      """
      package tier1.abstractclass

      abstract class Animal {
        abstract fun speak(): String
      }

      class Dog : Animal() {
        override fun speak(): String = "woof"
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "got: ${result.compileErrors}")
    val cs = result.generatedCSharp
    assertContains(cs, "public abstract class Animal : IDisposable")
    assertContains(cs, "public abstract void Dispose()")
    assertContains(cs, "public class Dog : Animal")
    // Abstract members without a plan stay off the base; the override lands on Dog.
    assertContains(cs, "public override string Speak()")
  }

  @Test
  fun `nullable string property uses two-call free direct nullable path`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullablestringprop

      class Patient(var nickname: String?)
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "got: ${result.compileErrors}")
    val cs = result.generatedCSharp
    assertContains(cs, "public string? Nickname")
    // Nullable String is direct presence-via-null, not LegacyTwoCall.
    assertContains(cs, "Marshal.PtrToStringUTF8(nativeResult)")
  }

  @Test
  fun `nullable int property keeps legacy two-call getter`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullableintprop

      class Patient(var age: Int?)
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "got: ${result.compileErrors}")
    val cs = result.generatedCSharp
    assertContains(cs, "public int? Age")
    assertContains(cs, "bool hasValue")
    assertContains(cs, "Native_Get_age_value")
    assertContains(cs, "Native_Set_age_null")
  }

  @Test
  fun `top-level function String and list factory compile`() {
    val result = Tier1Harness.run(
      """
      package tier1.toplevel

      fun greet(name: String): String = "hi ${'$'}name"

      fun tags(): List<String> = listOf("a")
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "got: ${result.compileErrors}")
    val cs = result.generatedCSharp
    // Top-level functions keep Kotlin camelCase names on the file-named static class (ADR-007).
    assertContains(cs, "public static partial class Fixture")
    assertContains(cs, "public static string greet(string name)")
    assertContains(cs, "public static IReadOnlyList<string> tags()")
  }
}
