package io.github.xxfast.kotlin.native.nuget

import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.net.URL
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * With this plugin `apply false` in a root project and KGP applied only in a child, the root
 * classloader cannot see KGP. Gradle's class generator reflects every declared member of
 * [NugetPlugin] before `apply()` runs, so any member whose signature names a KGP type fails with
 * `Could not generate a decorated class for type NugetPlugin`. The class must name no KGP type.
 */
class NugetPluginClassSignatureTest {
  private val kgpPackage: String = "org.jetbrains.kotlin.gradle"
  private val pluginPackage: String = "io.github.xxfast.kotlin.native.nuget."

  @Test
  fun `NugetPlugin declares no member whose signature names a KGP type`() {
    val type: Class<NugetPlugin> = NugetPlugin::class.java
    val offenders: MutableList<String> = mutableListOf()

    type.declaredMethods.forEach { method: Method ->
      val types: List<Type> =
        method.genericParameterTypes.toList() + method.genericReturnType +
          method.genericExceptionTypes.toList()
      if (types.any(::namesKgp)) offenders += "method ${method.name}"
    }
    type.declaredFields.forEach { field ->
      if (namesKgp(field.genericType)) offenders += "field ${field.name}"
    }
    type.declaredConstructors.forEach { constructor ->
      if (constructor.genericParameterTypes.any(::namesKgp)) offenders += "constructor"
    }

    assertEquals(emptyList(), offenders, "NugetPlugin members naming $kgpPackage types")
  }

  /**
   * The real symptom's mechanism: `getDeclaredMethods` resolves every member's erased signature,
   * so loading [NugetPlugin] where KGP is invisible must still let it be reflected.
   */
  @Test
  fun `NugetPlugin reflects in a classloader that cannot see KGP`() {
    val pluginClasses: URL = NugetPlugin::class.java.protectionDomain.codeSource.location
    val testLoader: ClassLoader = NugetPlugin::class.java.classLoader
    val withoutKgp = object : ClassLoader(testLoader.parent) {
      override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // The plugin's own classes too, so the URLClassLoader below defines them itself.
        if (name.startsWith("$kgpPackage.") || name.startsWith(pluginPackage)) {
          throw ClassNotFoundException(name)
        }
        return testLoader.loadClass(name)
      }
    }

    URLClassLoader(arrayOf(pluginClasses), withoutKgp).use { isolated ->
      val loaded: Class<*> = Class.forName(NugetPlugin::class.java.name, false, isolated)
      assertTrue(loaded.classLoader === isolated, "NugetPlugin must come from the isolated loader")

      // Throws NoClassDefFoundError naming the KGP type when a member's signature mentions one.
      loaded.declaredMethods
      loaded.declaredFields
      loaded.declaredConstructors
    }
  }

  private fun namesKgp(type: Type): Boolean = when (type) {
    is Class<*> ->
      type.name.startsWith("$kgpPackage.") ||
        (type.isArray && namesKgp(type.componentType))
    is ParameterizedType ->
      namesKgp(type.rawType) || type.actualTypeArguments.any(::namesKgp) ||
        (type.ownerType?.let(::namesKgp) ?: false)
    is GenericArrayType -> namesKgp(type.genericComponentType)
    is WildcardType -> type.upperBounds.any(::namesKgp) || type.lowerBounds.any(::namesKgp)
    is TypeVariable<*> -> type.bounds.any(::namesKgp)
    else -> false
  }
}
