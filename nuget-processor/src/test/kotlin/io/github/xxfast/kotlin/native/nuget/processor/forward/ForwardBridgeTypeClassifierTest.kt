package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ForwardBridgeTypeClassifierTest {
  private val classifier = ForwardBridgeTypeClassifier(
    ForwardBridgeTypeContext(exportedObjectHandles = setOf("sample.Patient", "sample.Record")),
  )

  @Test
  fun `classifies alias-expanded primitives nullable strings and Char`() {
    val stringAlias = alias("sample.Nickname", type("kotlin.String"))

    assertEquals(BridgeType.Unit, classifier.classify(type("kotlin.Unit")))
    assertEquals(BridgeType.Primitive(PrimitiveKind.INT), classifier.classify(type("kotlin.Int")))
    assertEquals(BridgeType.Char, classifier.classify(type("kotlin.Char")))
    assertEquals(BridgeType.String, classifier.classify(type(stringAlias)))
    assertEquals(BridgeType.Nullable(BridgeType.String), classifier.classify(type(stringAlias, nullable = true)))
    assertEquals(BridgeType.Nullable(BridgeType.String), classifier.classify(type("kotlin.String", nullable = true)))
  }

  @Test
  fun `classifies enums and only exported classes as object handles`() {
    assertEquals(
      BridgeType.Enum("sample.State"),
      classifier.classify(type("sample.State", classKind = ClassKind.ENUM_CLASS)),
    )
    assertEquals(BridgeType.ObjectHandle("sample.Patient"), classifier.classify(type("sample.Patient")))

    val unexported = assertIs<BridgeType.Unsupported>(classifier.classify(type("sample.Secret")))
    assertEquals("declaration is not in the exported object-handle set", unexported.reason)
  }

  @Test
  fun `classifies value classes by their recursively classified underlying type`() {
    val valueClass = classDeclaration(
      qualifiedName = "sample.Record",
      modifiers = setOf(Modifier.VALUE),
      primaryConstructor = constructor(type("kotlin.String")),
    )

    assertEquals(
      BridgeType.ValueClass("sample.Record", BridgeType.String),
      classifier.classify(type(valueClass)),
    )
  }

  @Test
  fun `classifies every collection kind recursively`() {
    assertEquals(
      BridgeType.Collection(
        CollectionKind.LIST,
        element = BridgeType.Nullable(BridgeType.Primitive(PrimitiveKind.INT)),
      ),
      classifier.classify(type("kotlin.collections.List", arguments = listOf(argument(type("kotlin.Int", nullable = true))))),
    )
    assertEquals(
      BridgeType.Collection(
        CollectionKind.MUTABLE_LIST,
        element = BridgeType.String,
      ),
      classifier.classify(type("kotlin.collections.MutableList", arguments = listOf(argument(type("kotlin.String"))))),
    )
    assertEquals(
      BridgeType.Collection(
        CollectionKind.MAP,
        key = BridgeType.String,
        value = BridgeType.ObjectHandle("sample.Patient"),
      ),
      classifier.classify(
        type(
          "kotlin.collections.Map",
          arguments = listOf(argument(type("kotlin.String")), argument(type("sample.Patient"))),
        )
      ),
    )
    assertEquals(
      BridgeType.Collection(
        CollectionKind.MUTABLE_MAP,
        key = BridgeType.Primitive(PrimitiveKind.INT),
        value = BridgeType.String,
      ),
      classifier.classify(
        type(
          "kotlin.collections.MutableMap",
          arguments = listOf(argument(type("kotlin.Int")), argument(type("kotlin.String"))),
        )
      ),
    )
    assertEquals(
      BridgeType.Collection(CollectionKind.SET, element = BridgeType.Char),
      classifier.classify(type("kotlin.collections.Set", arguments = listOf(argument(type("kotlin.Char"))))),
    )
    assertEquals(
      BridgeType.Collection(CollectionKind.MUTABLE_SET, element = BridgeType.String),
      classifier.classify(type("kotlin.collections.MutableSet", arguments = listOf(argument(type("kotlin.String"))))),
    )
  }

  @Test
  fun `classifies raw collections type parameters and named legacy protocols explicitly`() {
    assertEquals(BridgeType.RawCollection(CollectionKind.LIST), classifier.classify(type("kotlin.collections.List")))

    val typeParameter = proxy<KSTypeParameter>("getSimpleName" to name("T"))
    val generic = assertIs<BridgeType.Unsupported>(classifier.classify(type(typeParameter)))
    assertEquals("type parameters require the named generic legacy route", generic.reason)

    assertEquals(
      BridgeType.SpecializedProtocol("flow kotlinx.coroutines.flow.Flow"),
      classifier.classify(type("kotlinx.coroutines.flow.Flow", arguments = listOf(argument(type("kotlin.String"))))),
    )
    assertEquals(
      BridgeType.SpecializedProtocol("lambda kotlin.Function1"),
      classifier.classify(type("kotlin.Function1")),
    )
    assertEquals(
      BridgeType.SpecializedProtocol("suspend lambda kotlin.coroutines.SuspendFunction1"),
      classifier.classify(type("kotlin.coroutines.SuspendFunction1")),
    )
  }

  private fun type(
    qualifiedName: String,
    nullable: Boolean = false,
    classKind: ClassKind = ClassKind.CLASS,
    arguments: List<KSTypeArgument> = emptyList(),
  ): KSType = type(classDeclaration(qualifiedName, classKind = classKind), nullable, arguments)

  private fun type(
    declaration: KSDeclaration,
    nullable: Boolean = false,
    arguments: List<KSTypeArgument> = emptyList(),
  ): KSType = proxy(
    "getDeclaration" to declaration,
    "isMarkedNullable" to nullable,
    "getArguments" to arguments,
  )

  private fun argument(type: KSType?): KSTypeArgument = proxy("getType" to type?.let(::typeReference))

  private fun typeReference(type: KSType): KSTypeReference = proxy("resolve" to type)

  private fun alias(qualifiedName: String, expanded: KSType): KSTypeAlias = proxy(
    "getQualifiedName" to name(qualifiedName),
    "getSimpleName" to name(qualifiedName.substringAfterLast('.')),
    "getType" to typeReference(expanded),
  )

  private fun classDeclaration(
    qualifiedName: String,
    classKind: ClassKind = ClassKind.CLASS,
    modifiers: Set<Modifier> = emptySet(),
    primaryConstructor: KSFunctionDeclaration? = null,
  ): KSClassDeclaration = proxy(
    "getQualifiedName" to name(qualifiedName),
    "getSimpleName" to name(qualifiedName.substringAfterLast('.')),
    "getClassKind" to classKind,
    "getModifiers" to modifiers,
    "getPrimaryConstructor" to primaryConstructor,
    "getTypeParameters" to emptyList<Any>(),
    // ADR-066: every fixture here represents a module-local declaration (`containingFile != null`
    // is the verified cross-module/klib signal the classifier now branches on), so a non-null
    // stand-in keeps these tests on the pre-existing "declaration is not in the exported
    // object-handle set" message rather than the new dependency-module one.
    "getContainingFile" to proxy<com.google.devtools.ksp.symbol.KSFile>(),
  )

  private fun constructor(underlying: KSType): KSFunctionDeclaration = proxy(
    "getParameters" to listOf(valueParameter(underlying)),
  )

  private fun valueParameter(type: KSType): KSValueParameter = proxy(
    "getType" to typeReference(type),
    // ADR-066: `BridgeType.ValueClass.underlyingPropertyName` needs the parameter's name to
    // unbox a value-class *result* at an ordinary position; every real fixture in this repo
    // names it `value`, matching `BridgeType.ValueClass`'s own default.
    "getName" to name("value"),
  )

  private fun name(value: String): KSName = proxy("asString" to value)

  private inline fun <reified T> proxy(vararg values: Pair<String, Any?>): T {
    val methods: Map<String, Any?> = values.toMap()
    return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
      when (method.name) {
        "toString" -> T::class.simpleName ?: "proxy"
        "hashCode" -> System.identityHashCode(methods)
        "equals" -> false
        else -> methods[method.name] ?: error("Unexpected ${T::class.simpleName}.${method.name} call")
      }
    } as T
  }
}
