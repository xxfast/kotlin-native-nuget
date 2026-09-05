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
    // `sample.State` is here because the enum branch is gated on membership exactly as the class
    // branch is: only an enum the renderer actually declares may be spelled as a C# enum.
    ForwardBridgeTypeContext(
      exportedObjectHandles = setOf("sample.Patient", "sample.Record", "sample.State"),
    ),
  )

  private val interfaceClassifier = ForwardBridgeTypeClassifier(
    ForwardBridgeTypeContext(exportedObjectHandles = setOf("sample.Pet")),
  )

  /** ADR-088: a bound stub is deliberately NOT in [ForwardBridgeTypeContext.exportedObjectHandles]
   *  (it is excluded from the forward root buckets so it is never re-projected as `IIFeedable`),
   *  so the manifest branch has to win before the membership check. */
  private val boundClassifier = ForwardBridgeTypeClassifier(
    ForwardBridgeTypeContext(
      exportedObjectHandles = setOf("sample.Pet"),
      boundInterfaces = mapOf(
        "test.menagerie.IFeedable" to
            ForwardBoundInterface("test.menagerie.IFeedable", "Test.Menagerie.IFeedable", true),
        "test.menagerie.ICrowded" to
            ForwardBoundInterface("test.menagerie.ICrowded", "Test.Menagerie.ICrowded", false),
      ),
    ),
  )

  @Test
  fun `classifies a manifest-listed interface as BoundInterface with the ORIGINAL C# name`() {
    assertEquals(
      BridgeType.BoundInterface(
        "test.menagerie.IFeedable",
        csharpType = "global::Test.Menagerie.IFeedable",
        implementable = true,
      ),
      boundClassifier.classify(type("test.menagerie.IFeedable", classKind = ClassKind.INTERFACE)),
    )
  }

  @Test
  fun `carries the manifest implementable flag through classification`() {
    assertEquals(
      BridgeType.BoundInterface(
        "test.menagerie.ICrowded",
        csharpType = "global::Test.Menagerie.ICrowded",
        implementable = false,
      ),
      boundClassifier.classify(type("test.menagerie.ICrowded", classKind = ClassKind.INTERFACE)),
    )
  }

  @Test
  fun `classifies an exported interface as BridgeType Interface with I-prefixed csharpType`() {
    assertEquals(
      BridgeType.Interface("sample.Pet", csharpType = "IPet", backingType = "Pet"),
      interfaceClassifier.classify(type("sample.Pet", classKind = ClassKind.INTERFACE)),
    )
  }

  @Test
  fun `classifies a nullable exported interface as Nullable of BridgeType Interface`() {
    assertEquals(
      BridgeType.Nullable(BridgeType.Interface("sample.Pet", csharpType = "IPet", backingType = "Pet")),
      interfaceClassifier.classify(type("sample.Pet", classKind = ClassKind.INTERFACE, nullable = true)),
    )
  }

  @Test
  fun `classifies an unexported interface as Unsupported not silently dropped as a specialized protocol`() {
    val unexported = assertIs<BridgeType.Unsupported>(
      interfaceClassifier.classify(type("sample.Stranger", classKind = ClassKind.INTERFACE)),
    )
    assertEquals("declaration is not in the exported object-handle set", unexported.reason)
  }

  @Test
  fun `classifies a generic interface as the pre-existing generic declaration legacy route`() {
    val genericInterface = classDeclaration(
      qualifiedName = "sample.Pet",
      classKind = ClassKind.INTERFACE,
    )
    val withTypeParameter = Proxy.newProxyInstance(
      KSClassDeclaration::class.java.classLoader,
      arrayOf(KSClassDeclaration::class.java),
    ) { _, method, _ ->
      when (method.name) {
        "getTypeParameters" -> listOf(proxy<KSTypeParameter>("getSimpleName" to name("T")))
        else -> method.invoke(genericInterface)
      }
    } as KSClassDeclaration
    assertEquals(
      BridgeType.SpecializedProtocol("generic declaration sample.Pet"),
      interfaceClassifier.classify(type(withTypeParameter)),
    )
  }

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

  /**
   * The three ways an enum can fail the membership gate, and the two different flags they carry:
   * a nested enum is undeclarable in any module (no export scope can fix it), while a *top-level*
   * cross-module one is merely out of scope and keeps ADR-066's `include(...)` route. Before the
   * gate all three were spelled as C# enum references to types nothing declares.
   */
  @Test
  fun `an enum outside the exported set is unsupported rather than spelled`() {
    val nested = assertIs<BridgeType.Unsupported>(
      classifier.classify(
        type(
          classDeclaration(
            "sample.Owner.Mode",
            classKind = ClassKind.ENUM_CLASS,
            parentDeclaration = classDeclaration("sample.Owner"),
          ),
        ),
      ),
    )
    assertEquals(true, nested.isUndeclaredEnum)
    assertEquals(false, nested.isUnexportedDependency)
    assertEquals("sample.Owner.Mode", nested.rendered)

    val outOfScope = assertIs<BridgeType.Unsupported>(
      classifier.classify(type("sample.Hidden", classKind = ClassKind.ENUM_CLASS)),
    )
    assertEquals(true, outOfScope.isUndeclaredEnum)

    val dependency = assertIs<BridgeType.Unsupported>(
      classifier.classify(
        type(
          classDeclaration(
            "dep.outside.Airwave",
            classKind = ClassKind.ENUM_CLASS,
            containingFile = null,
          ),
        ),
      ),
    )
    assertEquals(true, dependency.isUnexportedDependency)
    assertEquals(false, dependency.isUndeclaredEnum)
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
      classifier.classify(
        type(
          "kotlin.collections.List",
          arguments = listOf(argument(type("kotlin.Int", nullable = true)))
        )
      ),
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
    // Both default to the module-local, top-level shape every other fixture here wants; the
    // undeclared-enum gate is the one test that varies them.
    parentDeclaration: KSDeclaration? = null,
    containingFile: com.google.devtools.ksp.symbol.KSFile? =
      proxy<com.google.devtools.ksp.symbol.KSFile>(),
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
    "getContainingFile" to containingFile,
    // ADR-074: every fixture here is an ordinary (non-`expect`) declaration; the classifier now
    // reads this before anything else in `classifyNonNullable`.
    "isExpect" to false,
    // The C# spelling walks enclosing declarations (`nestedCsName`, so a sealed subclass reads
    // `Shape.Circle`); every fixture here is top-level, so the walk stops immediately.
    "getParentDeclaration" to parentDeclaration,
    // ADR-107's `isStdlibThrowable` walks the supertypes of any declaration with no containing
    // file; no fixture here stands in for a stdlib throwable, so the walk finds nothing.
    "getSuperTypes" to emptySequence<KSTypeReference>(),
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
        // A stub whose value is deliberately `null` (an absent parent declaration, an absent
        // primary constructor) is a stub, not a gap: key presence decides, not the value.
        in methods -> methods[method.name]
        else -> error("Unexpected ${T::class.simpleName}.${method.name} call")
      }
    } as T
  }
}
