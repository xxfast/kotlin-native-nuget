package io.github.xxfast.kotlin.native.nuget.processor.tier1

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ADR-242 (issue #611) probe: what KSP itself reports about stored state in a data class body.
 *
 * The scalar-value admission predicate will read `hasBackingField`, `isDelegated()`, `isMutable`
 * and `Modifier.LATEINIT`, which nothing else in this repo reads. This cell runs a test-only probe
 * provider, not the real processor, so every row below is KSP's raw answer. The probe emits a
 * trivial `CNameExports.kt` only so the harness compiles the fixture: a row KSP answers for
 * illegal Kotlin would pin nothing.
 */
class Tier1DataClassStoredStateTest {

  data class ClassFacts(
    val name: String,
    val data: Boolean,
    val sealed: Boolean,
    val typeParameters: Int,
    val supertypes: List<String>,
    val primaryConstructorPublic: Boolean?,
  )

  data class PropertyFacts(
    val owner: String,
    val name: String,
    val hasBackingField: Boolean,
    val delegated: Boolean,
    val mutable: Boolean,
    val lateinit: Boolean,
    val constructorProperty: Boolean,
    // `null` when KSP reports no getter; otherwise the getter's `origin`.
    val getter: String?,
    // Resolved type: qualified name, `?` when nullable, then the declaration's kind and `data`.
    val type: String,
  ) {
    val key: String get() = "$owner.$name"
    val stored: Boolean get() = hasBackingField || delegated || mutable || lateinit
  }

  private class Probe(
    private val classes: MutableList<ClassFacts>,
    private val properties: MutableList<PropertyFacts>,
    private val environment: SymbolProcessorEnvironment,
  ) : SymbolProcessor {
    private var done = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
      if (done) return emptyList()
      done = true
      resolver.getAllFiles()
        .flatMap { it.declarations }
        .filterIsInstance<KSClassDeclaration>()
        .forEach(::record)
      environment.codeGenerator
        .createNewFile(Dependencies(false), "tier1.storedstate.probe", "CNameExports", "kt")
        .use { it.write("package tier1.storedstate.probe\n\ninternal val probed: Int = 0\n".toByteArray()) }
      return emptyList()
    }

    private fun record(declaration: KSClassDeclaration) {
      val owner: String = declaration.simpleName.asString()
      val primary = declaration.primaryConstructor
      classes += ClassFacts(
        name = owner,
        data = Modifier.DATA in declaration.modifiers,
        sealed = Modifier.SEALED in declaration.modifiers,
        typeParameters = declaration.typeParameters.size,
        supertypes = declaration.superTypes
          .map { it.resolve().describe() }
          .toList(),
        primaryConstructorPublic = primary?.isPublic(),
      )
      val constructorProperties: Set<String> = primary?.parameters.orEmpty()
        .filter { it.isVal || it.isVar }
        .mapNotNull { it.name?.asString() }
        .toSet()
      declaration.getDeclaredProperties().forEach { property: KSPropertyDeclaration ->
        properties += PropertyFacts(
          owner = owner,
          name = property.simpleName.asString(),
          hasBackingField = property.hasBackingField,
          delegated = property.isDelegated(),
          mutable = property.isMutable,
          lateinit = Modifier.LATEINIT in property.modifiers,
          constructorProperty = property.simpleName.asString() in constructorProperties,
          getter = property.getter?.origin?.name,
          type = property.type.resolve().describe(),
        )
      }
      declaration.declarations.filterIsInstance<KSClassDeclaration>().forEach(::record)
    }

    private fun KSType.describe(): String {
      if (isError) return "<error>"
      val target = declaration as? KSClassDeclaration
      val name: String = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
      val nullable: String = if (isMarkedNullable) "?" else ""
      val kind: String = target?.let { klass ->
        val dataTag: String = if (Modifier.DATA in klass.modifiers) ",data" else ""
        " [${klass.classKind}$dataTag]"
      } ?: " [${declaration::class.simpleName}]"
      return "$name$nullable$kind"
    }
  }

  private class ProbeProvider(
    private val classes: MutableList<ClassFacts>,
    private val properties: MutableList<PropertyFacts>,
  ) : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
      Probe(classes, properties, environment)
  }

  private val fixture: String = """
    package tier1.storedstate

    import kotlin.properties.ReadOnlyProperty
    import kotlin.reflect.KProperty

    fun now(): Long = 42L

    // 1. plain
    data class Plain(val name: String, val count: Int)

    // 2. computed getter only
    data class Computed(val name: String) {
      val label: String get() = name + "!"
    }

    // 3. body val with an initialiser
    data class Stamped(val name: String) {
      val createdAt: Long = now()
    }

    // 5. body val whose getter reads field
    data class FieldReader(val name: String) {
      val x: Int = 0
        get() = field + 1
    }

    // 6. by lazy
    data class Lazy(val name: String) {
      val upper: String by lazy { name.uppercase() }
    }

    // 7. other delegates: a map and a custom ReadOnlyProperty
    object Doubler : ReadOnlyProperty<Any?, Int> {
      override fun getValue(thisRef: Any?, property: KProperty<*>): Int = 2
    }

    data class Mapped(val map: Map<String, Any?>) {
      val title: String by map
      val twice: Int by Doubler
    }

    // 8. var constructor component
    data class MutableComponent(var count: Int)

    // 9. body var with an initialiser
    data class Counter(val name: String) {
      var hits: Int = 0
    }

    // 10. body lateinit var
    data class Late(val name: String) {
      lateinit var cache: String
    }

    // 11. body var with custom get/set and no backing field
    data class ComputedVar(val name: String) {
      var alias: String
        get() = name
        set(value) { println(value) }
    }

    // 12. private body val with an initialiser
    data class PrivateState(val name: String) {
      private val secret: Long = now()
    }

    // 13. handle-typed component
    class Engine(val hp: Int)
    data class Car(val engine: Engine)

    // 14. sealed arms
    sealed interface Shape
    data class Dot(val x: Int) : Shape
    sealed class Base
    data class Arm(val y: Int) : Base()

    // 15. generic, non-Any superclass, interface, Exception
    data class Box<T>(val value: T)
    open class Animal(val legs: Int)
    data class Dog(val name: String) : Animal(4)
    interface Named { val name: String }
    data class Tag(override val name: String) : Named
    data class Failure(val code: Int) : Exception()

    // 16. init block only
    data class Checked(val n: Int) {
      init { require(n >= 0) }
    }

    // 17. non-public primary constructors
    data class Internal internal constructor(val n: Int)
    data class Hidden private constructor(val n: Int)

    // 18. nested scalar data class component, self reference
    data class Point(val x: Int, val y: Int)
    data class Line(val start: Point, val end: Point)
    data class Node(val value: Int, val next: Node?)

    // 19. top-level extension property on a data class
    val Plain.shout: String get() = name.uppercase()

    // 20. secondary constructor
    data class Pairish(val a: Int, val b: Int) {
      constructor(a: Int) : this(a, 0)
    }
  """.trimIndent()

  private fun probe(source: String): Triple<Tier1Result, List<ClassFacts>, List<PropertyFacts>> {
    val classes = mutableListOf<ClassFacts>()
    val properties = mutableListOf<PropertyFacts>()
    val result: Tier1Result = Tier1Harness.run(
      source,
      provider = ProbeProvider(classes, properties),
    )
    return Triple(result, classes, properties)
  }

  private fun row(
    key: String,
    hasBackingField: Boolean,
    delegated: Boolean = false,
    mutable: Boolean = false,
    lateinit: Boolean = false,
    constructorProperty: Boolean,
    getter: String?,
    type: String,
  ): PropertyFacts = PropertyFacts(
    owner = key.substringBefore('.'),
    name = key.substringAfter('.'),
    hasBackingField = hasBackingField,
    delegated = delegated,
    mutable = mutable,
    lateinit = lateinit,
    constructorProperty = constructorProperty,
    getter = getter,
    type = type,
  )

  private val int = "kotlin.Int [CLASS]"
  private val string = "kotlin.String [CLASS]"
  private val long = "kotlin.Long [CLASS]"

  // The raw pin: exactly what KSP reports, one row per declared property.
  private val expectedProperties: List<PropertyFacts> = listOf(
    // 1. constructor vals
    row("Plain.name", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = string),
    row("Plain.count", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = int),
    // 2. getter-only computed property
    row("Computed.name", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = string),
    row("Computed.label", hasBackingField = false, constructorProperty = false, getter = "KOTLIN", type = string),
    // 3. body val with initialiser
    row("Stamped.name", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = string),
    row("Stamped.createdAt", hasBackingField = true, constructorProperty = false, getter = "SYNTHETIC", type = long),
    // 5. getter reads field
    row("FieldReader.name", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = string),
    row("FieldReader.x", hasBackingField = true, constructorProperty = false, getter = "KOTLIN", type = int),
    // 6. by lazy
    row("Lazy.name", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = string),
    row("Lazy.upper", hasBackingField = false, delegated = true, constructorProperty = false, getter = "SYNTHETIC", type = string),
    // 7. by map, by a custom ReadOnlyProperty
    row("Mapped.map", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = "kotlin.collections.Map [INTERFACE]"),
    row("Mapped.title", hasBackingField = false, delegated = true, constructorProperty = false, getter = "SYNTHETIC", type = string),
    row("Mapped.twice", hasBackingField = false, delegated = true, constructorProperty = false, getter = "SYNTHETIC", type = int),
    // 8. var constructor component
    row("MutableComponent.count", hasBackingField = true, mutable = true, constructorProperty = true, getter = "SYNTHETIC", type = int),
    // 9. body var
    row("Counter.name", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = string),
    row("Counter.hits", hasBackingField = true, mutable = true, constructorProperty = false, getter = "SYNTHETIC", type = int),
    // 10. lateinit
    row("Late.name", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = string),
    row("Late.cache", hasBackingField = true, mutable = true, lateinit = true, constructorProperty = false, getter = "SYNTHETIC", type = string),
    // 11. computed var, no backing field
    row("ComputedVar.name", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = string),
    row("ComputedVar.alias", hasBackingField = false, mutable = true, constructorProperty = false, getter = "KOTLIN", type = string),
    // 12. private stored state
    row("PrivateState.name", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = string),
    row("PrivateState.secret", hasBackingField = true, constructorProperty = false, getter = "SYNTHETIC", type = long),
    // 13. handle-typed component
    row("Engine.hp", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = int),
    row("Car.engine", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = "tier1.storedstate.Engine [CLASS]"),
    // 14. sealed arms
    row("Dot.x", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = int),
    row("Arm.y", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = int),
    // 15. generic, superclass, interface, Exception
    row("Box.value", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = "tier1.storedstate.Box.T [KSTypeParameterImpl]"),
    row("Animal.legs", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = int),
    row("Dog.name", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = string),
    row("Named.name", hasBackingField = false, constructorProperty = false, getter = "SYNTHETIC", type = string),
    row("Tag.name", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = string),
    row("Failure.code", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = int),
    // 16. init block
    row("Checked.n", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = int),
    // 17. non-public primary constructors
    row("Internal.n", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = int),
    row("Hidden.n", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = int),
    // 18. nested data class component, self reference
    row("Point.x", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = int),
    row("Point.y", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = int),
    row("Line.start", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = "tier1.storedstate.Point [CLASS,data]"),
    row("Line.end", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = "tier1.storedstate.Point [CLASS,data]"),
    row("Node.value", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = int),
    row("Node.next", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = "tier1.storedstate.Node? [CLASS,data]"),
    // 20. secondary constructor
    row("Pairish.a", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = int),
    row("Pairish.b", hasBackingField = true, constructorProperty = true, getter = "SYNTHETIC", type = int),
  )

  private val any = "kotlin.Any [CLASS]"

  private val expectedClasses: List<ClassFacts> = listOf(
    ClassFacts("Plain", data = true, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = true),
    ClassFacts("Computed", data = true, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = true),
    ClassFacts("Stamped", data = true, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = true),
    ClassFacts("FieldReader", data = true, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = true),
    ClassFacts("Lazy", data = true, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = true),
    // An `object` has a primary constructor as far as KSP is concerned, and it reads public.
    ClassFacts("Doubler", data = false, sealed = false, typeParameters = 0, supertypes = listOf("kotlin.properties.ReadOnlyProperty [INTERFACE]"), primaryConstructorPublic = true),
    ClassFacts("Mapped", data = true, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = true),
    ClassFacts("MutableComponent", data = true, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = true),
    ClassFacts("Counter", data = true, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = true),
    ClassFacts("Late", data = true, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = true),
    ClassFacts("ComputedVar", data = true, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = true),
    ClassFacts("PrivateState", data = true, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = true),
    ClassFacts("Engine", data = false, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = true),
    ClassFacts("Car", data = true, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = true),
    ClassFacts("Shape", data = false, sealed = true, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = null),
    ClassFacts("Dot", data = true, sealed = false, typeParameters = 0, supertypes = listOf("tier1.storedstate.Shape [INTERFACE]"), primaryConstructorPublic = true),
    ClassFacts("Base", data = false, sealed = true, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = true),
    ClassFacts("Arm", data = true, sealed = false, typeParameters = 0, supertypes = listOf("tier1.storedstate.Base [CLASS]"), primaryConstructorPublic = true),
    ClassFacts("Box", data = true, sealed = false, typeParameters = 1, supertypes = listOf(any), primaryConstructorPublic = true),
    ClassFacts("Animal", data = false, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = true),
    ClassFacts("Dog", data = true, sealed = false, typeParameters = 0, supertypes = listOf("tier1.storedstate.Animal [CLASS]"), primaryConstructorPublic = true),
    ClassFacts("Named", data = false, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = null),
    ClassFacts("Tag", data = true, sealed = false, typeParameters = 0, supertypes = listOf("tier1.storedstate.Named [INTERFACE]"), primaryConstructorPublic = true),
    // JVM only: `kotlin.Exception` is a typealias for `java.lang.Exception` here, so the supertype
    // resolves to the alias declaration. On Kotlin/Native it is a real class (inferred, not run):
    // a supertype check must expand aliases before it compares names.
    ClassFacts("Failure", data = true, sealed = false, typeParameters = 0, supertypes = listOf("kotlin.Exception [KSTypeAliasImpl]"), primaryConstructorPublic = true),
    ClassFacts("Checked", data = true, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = true),
    ClassFacts("Internal", data = true, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = false),
    ClassFacts("Hidden", data = true, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = false),
    ClassFacts("Point", data = true, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = true),
    ClassFacts("Line", data = true, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = true),
    ClassFacts("Node", data = true, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = true),
    ClassFacts("Pairish", data = true, sealed = false, typeParameters = 0, supertypes = listOf(any), primaryConstructorPublic = true),
  )

  @Test
  fun `KSP reports stored state in a data class body through the flags the predicate reads`() {
    val (result, classes, properties) = probe(fixture)

    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "the fixture must be legal Kotlin: ${result.compileErrors}")

    val mismatches = mutableListOf<String>()
    val actualClasses: Map<String, ClassFacts> = classes.associateBy { it.name }
    assertEquals(expectedClasses.map { it.name }.toSet(), actualClasses.keys, "classes seen")
    expectedClasses.forEach { expected ->
      val actual = actualClasses.getValue(expected.name)
      if (actual != expected) mismatches += "class ${expected.name}: expected $expected, actual $actual"
    }
    val actualProperties: Map<String, PropertyFacts> = properties.associateBy { it.key }
    assertEquals(expectedProperties.map { it.key }.toSet(), actualProperties.keys, "declared properties seen")
    expectedProperties.forEach { expected ->
      val actual = actualProperties.getValue(expected.key)
      if (actual != expected) mismatches += "property ${expected.key}: expected $expected, actual $actual"
    }
    if (mismatches.isNotEmpty()) {
      fail(
        mismatches.joinToString("\n", postfix = "\n\nactual table:\n") +
            (classes.map { it.toString() } + properties.map { it.toString() }).joinToString("\n"),
      )
    }

    // 19. the top-level extension property is not a declared property of the class it extends.
    assertTrue("Plain.shout" !in actualProperties, "extension leaked into Plain: ${actualProperties.keys}")

    // The derived check the predicate makes: every body property of shapes 3 to 12 is stored,
    // and the getter-only computed property of shape 2 is not.
    val storedBody: List<String> = listOf(
      "Stamped.createdAt", "FieldReader.x", "Lazy.upper", "Mapped.title", "Mapped.twice",
      "MutableComponent.count", "Counter.hits", "Late.cache", "ComputedVar.alias",
      "PrivateState.secret",
    )
    storedBody.forEach { key ->
      assertTrue(actualProperties.getValue(key).stored, "$key must read as stored state")
    }
    val computed = actualProperties.getValue("Computed.label")
    assertTrue(!computed.stored, "Computed.label must read as getter-only: $computed")
  }

  /**
   * Shape 4: a body `val` with an initialiser AND a getter that never reads `field`. Kotlin rejects
   * it ("Initializer is not allowed here because this property has no backing field"), so it gets
   * its own run: the predicate can never meet it in a module that compiles. KSP still answers, and
   * it reads as getter-only (no backing field), so the predicate leans on the compiler for this one.
   */
  @Test
  fun `an initialiser with a getter that ignores field is not legal Kotlin`() {
    val (result, _, properties) = probe(
      """
      package tier1.storedstate.illegal

      data class Shadowed(val name: String) {
        val x: Int = 0
          get() = 1
      }
      """.trimIndent(),
    )

    assertTrue(result.compileErrors.isNotEmpty(), "expected the fixture to be rejected")
    val x = properties.single { it.key == "Shadowed.x" }
    assertEquals(
      row("Shadowed.x", hasBackingField = false, constructorProperty = false, getter = "KOTLIN", type = "kotlin.Int [CLASS]"),
      x,
    )
  }
}
