package io.github.xxfast.kotlin.native.nuget.processor.cir

import java.lang.reflect.Method

/**
 * ROADMAP line 25: a `const val`'s value is the compiler's EVALUATED constant, never source text.
 *
 * KSP's public API exposes no constant value (`KSPropertyDeclaration` has nothing named initializer
 * or constant), so the value used to be read out of the declaring file by a regex. That emitted
 * illegal C# for a one-line `object`/companion body, a `;`-separated pair and a trailing comment,
 * and silently wrong values for underscores inside a string, templates, `\$`, shifts,
 * `Int.MIN_VALUE` and expressions over other consts; a dependency-klib const (no source file) was
 * dropped with no diagnostic at all.
 *
 * KSP2's implementation class holds the Analysis API symbol the compiler already evaluated
 * (`KSPropertyDeclarationImpl(internal val ktPropertySymbol: KaPropertySymbol)`), and KSP itself
 * reads `ktPropertySymbol.initializer is KaConstantInitializerValue`. This reader reaches the same
 * value by reflection (approved 2026-09-26, Step 2 gate):
 * `getKtPropertySymbol$...` -> `getInitializer()` -> `getConstant()` -> `getValue()`.
 *
 * Everything is matched by NAME, never by type: the internal getter is module-mangled and the
 * Analysis API classes are shaded (`ksp.org.jetbrains...`) and non-public, and in the Gradle KSP
 * worker the boxed `kotlin.UInt`/`kotlin.ULong` may come from a different classloader than this
 * processor's, so an `is ULong` check could silently miss. Integers are therefore taken through
 * `toString()` (unsigned boxes print their unsigned decimal) and parsed against the DECLARED type.
 *
 * When no value can be read the caller skips the const with a named warning
 * (`SKIPPED_UNREADABLE_CONST_VALUE`). There is deliberately no source-text fallback: it would keep
 * every silently-wrong case alive exactly when nobody is looking (a KSP bump).
 */
internal object KotlinConstValue {

  /** The prefix of KSP2's module-mangled internal getter
   *  (`getKtPropertySymbol$kotlin_analysis_api`). */
  const val SYMBOL_ACCESSOR_PREFIX: String = "getKtPropertySymbol"

  /** What reading a `const val`'s evaluated value produced. */
  sealed interface Read {
    /** The evaluated constant, as the Analysis API boxed it (`Integer`, `String`,
     *  `kotlin.UInt`...). */
    data class Evaluated(val value: Any) : Read

    /** Why no value is available, phrased to complete "its value is not readable: ...". */
    data class Unreadable(val reason: String) : Read
  }

  fun read(declaration: Any): Read = try {
    readReflectively(declaration)
  } catch (failure: ReflectiveOperationException) {
    Read.Unreadable("reflection on the KSP analysis symbol failed (${failure.describe()})")
  } catch (failure: RuntimeException) {
    // `InaccessibleObjectException` (JPMS) and `SecurityException` are RuntimeExceptions.
    Read.Unreadable("reflection on the KSP analysis symbol failed (${failure.describe()})")
  }

  private fun readReflectively(declaration: Any): Read {
    val accessor: Method = declaration.zeroArgMethod { it.startsWith(SYMBOL_ACCESSOR_PREFIX) }
      ?: return Read.Unreadable(
        "this KSP version's ${declaration.javaClass.name} has no `$SYMBOL_ACCESSOR_PREFIX` " +
            "accessor to reach the compiler's evaluated constant through",
      )
    val symbol: Any = accessor.invokeOn(declaration)
      ?: return Read.Unreadable("KSP returned no analysis symbol for it")
    val initializerGetter: Method = symbol.zeroArgMethod { it == "getInitializer" }
      ?: return Read.Unreadable(
        "the analysis symbol ${symbol.javaClass.name} has no `getInitializer` in this KSP version",
      )
    val initializer: Any = initializerGetter.invokeOn(symbol)
      ?: return Read.Unreadable("the compiler reported no initializer for it")
    val constantGetter: Method = initializer.zeroArgMethod { it == "getConstant" }
      ?: return Read.Unreadable(
        "the compiler did not evaluate its initializer to a constant " +
            "(${initializer.javaClass.simpleName})",
      )
    val constant: Any = constantGetter.invokeOn(initializer)
      ?: return Read.Unreadable("the compiler's constant initializer carried no constant")
    if (constant.javaClass.simpleName.contains("Error")) {
      return Read.Unreadable("the compiler evaluated it to an error constant ($constant)")
    }
    val valueGetter: Method = constant.zeroArgMethod { it == "getValue" }
      ?: return Read.Unreadable(
        "the constant ${constant.javaClass.name} has no `getValue` in this KSP version",
      )
    val value: Any = valueGetter.invokeOn(constant)
      ?: return Read.Unreadable("the compiler's constant carried a null value")
    return Read.Evaluated(value)
  }

  /**
   * The C# literal for [value] as a const of the Kotlin type named [kotlinType] (a simple name out
   * of `KOTLIN_TO_CSHARP_PARAM`), or null when the value is not a value of that type.
   *
   * The literal's shape comes from the DECLARED type, never from the runtime box: an unsuffixed
   * `const val X: Long = 5` and `4_000_000_000u` as `UInt` both render by their declared type.
   * `Byte`/`Short`/`UByte`/`UShort` need no suffix (C# converts an in-range `int` constant), and
   * `-2147483648` / `-9223372036854775808L` are legal C# through the literal-negation special case.
   */
  fun csharpLiteral(value: Any, kotlinType: String): String? {
    val digits: String = value.toString()
    return when (kotlinType) {
      "String" -> (value as? String)?.let { text -> "\"${text.csharpEscaped('"')}\"" }
      "Char" -> (value as? Char)?.let { char -> "'${char.toString().csharpEscaped('\'')}'" }
      "Boolean" -> (value as? Boolean)?.toString()
      "Byte" -> digits.toByteOrNull()?.toString()
      "Short" -> digits.toShortOrNull()?.toString()
      "Int" -> digits.toIntOrNull()?.toString()
      "Long" -> digits.toLongOrNull()?.let { "${it}L" }
      "UByte" -> digits.toUByteOrNull()?.toString()
      "UShort" -> digits.toUShortOrNull()?.toString()
      "UInt" -> digits.toUIntOrNull()?.let { "${it}U" }
      "ULong" -> digits.toULongOrNull()?.let { "${it}UL" }
      "Float" -> (value as? Float)?.let(::csharpFloat)
      "Double" -> (value as? Double)?.let(::csharpDouble)
      else -> null
    }
  }

  // The JVM's `Float.toString`/`Double.toString` always round-trip, and their `1.0E10` / `1.0E-5`
  // exponent forms are legal C# real literals.
  private fun csharpFloat(value: Float): String = when {
    value.isNaN() -> "float.NaN"
    value == Float.POSITIVE_INFINITY -> "float.PositiveInfinity"
    value == Float.NEGATIVE_INFINITY -> "float.NegativeInfinity"
    else -> "${value}f"
  }

  private fun csharpDouble(value: Double): String = when {
    value.isNaN() -> "double.NaN"
    value == Double.POSITIVE_INFINITY -> "double.PositiveInfinity"
    value == Double.NEGATIVE_INFINITY -> "double.NegativeInfinity"
    else -> "${value}"
  }

  /**
   * The body of a regular (never raw, never verbatim) C# literal delimited by [quote]. C# rejects a
   * raw new-line character inside a regular literal, and counts U+0085/U+2028/U+2029 as new-lines
   * too; surrogates are escaped so a lone one cannot corrupt the UTF-8 `Interop.cs`.
   */
  private fun String.csharpEscaped(quote: Char): String = buildString {
    this@csharpEscaped.forEach { char ->
      when {
        char == '\\' -> append("\\\\")
        char == quote -> append('\\').append(quote)
        char == '\n' -> append("\\n")
        char == '\r' -> append("\\r")
        char == '\t' -> append("\\t")
        char == '\u0000' -> append("\\0")
        char < ' ' || char == '\u007F' || char == '\u0085' || char == ' ' ||
            char == ' ' || char.isSurrogate() ->
          append("\\u").append(char.code.toString(16).uppercase().padStart(4, '0'))
        else -> append(char)
      }
    }
  }

  /** The first zero-argument method up the class chain whose name satisfies [matches]. */
  private fun Any.zeroArgMethod(matches: (String) -> Boolean): Method? {
    var type: Class<*>? = javaClass
    while (type != null) {
      type.declaredMethods
        .firstOrNull { method -> method.parameterCount == 0 && matches(method.name) }
        ?.let { method -> return method }
      type = type.superclass
    }
    return null
  }

  // The implementing classes are not public (`KaFirKotlinPropertyKtPropertyBasedSymbol`), so the
  // call needs `setAccessible` even for a public method name.
  private fun Method.invokeOn(target: Any): Any? {
    isAccessible = true
    return invoke(target)
  }

  private fun Throwable.describe(): String {
    val cause: Throwable = (this as? java.lang.reflect.InvocationTargetException)?.targetException
      ?: this
    return "${cause.javaClass.simpleName}: ${cause.message}"
  }
}
