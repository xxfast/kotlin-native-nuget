package io.github.xxfast.kotlin.native.nuget.processor.cir

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Characterization of ordinary CIR → C# rendering (ADR-004).
 *
 * Builds CIR by hand and asserts structural C# surface. Deliberately skips specialized-protocol
 * nodes (Flow, suspend, lambda, stored callback, interface bridge, sealed helpers, generics):
 * those stay on named legacy routes and will move under a later plan migration (MIGRATION.md /
 * ADR-062).
 */
class CirOrdinaryRendererTest {

  // -- file scaffolding -------------------------------------------------------

  @Test
  fun `file preamble is nullable-enabled with default usings`() {
    val rendered: String = render()

    assertTrue(rendered.startsWith("#nullable enable"))
    assertContains(rendered, "using System;")
    assertContains(rendered, "using System.Runtime.InteropServices;")
    assertContains(rendered, "namespace Sample")
  }

  // -- CirClass ---------------------------------------------------------------

  @Test
  fun `class renders constructor property method and dispose`() {
    val cls = CirClass(
      name = "Patient",
      libraryName = "clinic",
      nativePrefix = "patient",
      constructor = CirConstructor(
        parameters = listOf(CirParameter("name", "string")),
        body = "",
        hasErrorCheck = true,
      ),
      properties = listOf(
        CirProperty(
          name = "Age",
          type = "int",
          nativeReturnType = "int",
          nativeName = "age",
          getter = "Native_Get_age(_handle, out IntPtr error)",
          setter = "Native_Set_age(_handle, value, out IntPtr error)",
          hasSyncErrorOut = true,
        ),
      ),
      methods = listOf(
        CirMethod(
          name = "Greet",
          returnType = "string",
          nativeReturnType = "IntPtr",
          nativeName = "greet",
          parameters = emptyList(),
          body = "",
          isSyncErrorCheckEnabled = true,
        ),
      ),
    )

    val rendered: String = render(cls)

    assertContains(rendered, "public class Patient : IDisposable")
    assertContains(rendered, "internal IntPtr _handle;")
    assertContains(
      rendered,
      "private static extern IntPtr Native_Create(string name, out IntPtr error);",
    )
    assertContains(rendered, "public Patient(string name)")
    assertContains(rendered, "IntPtr handle = Native_Create(name, out IntPtr error);")
    assertContains(rendered, "throw NugetErrorNative.BuildException(error);")
    assertContains(rendered, "internal Patient(IntPtr handle)")
    assertContains(
      rendered,
      "private static extern int Native_Get_age(IntPtr handle, out IntPtr error);",
    )
    assertContains(
      rendered,
      "private static extern void Native_Set_age(IntPtr handle, int value, out IntPtr error);",
    )
    assertContains(rendered, "public int Age")
    assertContains(rendered, "get => Native_Get_age(_handle, out IntPtr error);")
    assertContains(rendered, "set => Native_Set_age(_handle, value, out IntPtr error);")
    assertContains(
      rendered,
      "private static extern IntPtr Native_Greet(IntPtr handle, out IntPtr error);",
    )
    assertContains(rendered, "public string Greet()")
    assertContains(rendered, "IntPtr nativeResult = Native_Greet(_handle, out IntPtr error);")
    assertContains(rendered, "return Marshal.PtrToStringUTF8(nativeResult)!;")
    assertContains(
      rendered,
      "private static extern void Native_Dispose(IntPtr handle);",
    )
    assertContains(rendered, "public void Dispose()")
    assertContains(rendered, "Native_Dispose(handle);")
  }

  @Test
  fun `class secondary constructor uses create suffix`() {
    val cls = CirClass(
      name = "Patient",
      libraryName = "clinic",
      nativePrefix = "patient",
      constructor = CirConstructor(
        parameters = listOf(CirParameter("name", "string")),
        body = "",
        hasErrorCheck = true,
      ),
      secondaryConstructors = listOf(
        CirConstructor(
          parameters = listOf(
            CirParameter("name", "string"),
            CirParameter("age", "int"),
          ),
          body = "",
          hasErrorCheck = true,
          nativeSuffix = "_2",
        ),
      ),
      properties = emptyList(),
      methods = emptyList(),
    )

    val rendered: String = render(cls)

    assertContains(
      rendered,
      "private static extern IntPtr Native_Create_2(string name, int age, out IntPtr error);",
    )
    assertContains(rendered, "public Patient(string name, int age)")
    assertContains(rendered, "Native_Create_2(name, age, out IntPtr error)")
  }

  @Test
  fun `data class renders equals hashcode tostring and legacy copy`() {
    val cls = CirClass(
      name = "Point",
      libraryName = "geo",
      nativePrefix = "point",
      constructor = CirConstructor(
        parameters = listOf(
          CirParameter("x", "int"),
          CirParameter("y", "int"),
        ),
        body = "",
        hasErrorCheck = true,
      ),
      properties = emptyList(),
      methods = emptyList(),
      isDataClass = true,
    )

    val rendered: String = render(cls)

    assertContains(rendered, "public Point Copy(int x, int y)")
    assertContains(rendered, "Native_Copy(_handle, x, y, out IntPtr error)")
    assertContains(rendered, "public override bool Equals(object? obj)")
    assertContains(rendered, "Native_Equals(_handle, other._handle)")
    assertContains(rendered, "public override int GetHashCode() => Native_HashCode(_handle);")
    assertContains(
      rendered,
      "public override string ToString() => Marshal.PtrToStringUTF8(Native_ToString(_handle))!;",
    )
  }

  @Test
  fun `data class planned copy method is preferred over legacy copy`() {
    val copy = CirMethod(
      name = "Copy",
      returnType = "Point",
      nativeReturnType = "IntPtr",
      nativeName = "copy",
      parameters = listOf(
        CirParameter("x", "int"),
        CirParameter("y", "int"),
      ),
      body = """
            IntPtr handle = Native_Copy(_handle, x, y, out IntPtr error);
            if (error != IntPtr.Zero)
            {
                throw NugetErrorNative.BuildException(error);
            }
            return new Point(handle);
      """.trimIndent().prependIndent("    "),
      isSyncErrorCheckEnabled = true,
      hasCustomBody = true,
    )
    val cls = CirClass(
      name = "Point",
      libraryName = "geo",
      nativePrefix = "point",
      constructor = CirConstructor(
        parameters = listOf(
          CirParameter("x", "int"),
          CirParameter("y", "int"),
        ),
        body = "",
        hasErrorCheck = true,
      ),
      properties = emptyList(),
      methods = emptyList(),
      copyMethod = copy,
      isDataClass = true,
    )

    val rendered: String = render(cls)

    assertContains(rendered, "public Point Copy(int x, int y)")
    assertContains(rendered, "return new Point(handle);")
    // Planned copy still gets a DllImport via methodNativeImport; legacy Native_Copy block
    // from the constructor-parameter fallback must not appear as a separate hand-rolled method
    // template beyond that import.
    assertEquals(1, Regex("""public Point Copy\(""").findAll(rendered).count())
  }

  @Test
  fun `subclass inherits handle and overrides dispose`() {
    val cls = CirClass(
      name = "Inpatient",
      libraryName = "clinic",
      nativePrefix = "inpatient",
      constructor = CirConstructor(
        parameters = listOf(CirParameter("name", "string")),
        body = "",
        hasErrorCheck = true,
      ),
      properties = emptyList(),
      methods = emptyList(),
      superClass = "Patient",
    )

    val rendered: String = render(cls)

    assertContains(rendered, "public class Inpatient : Patient")
    assertFalse(rendered.contains("internal IntPtr _handle;"))
    assertContains(rendered, "public Inpatient(string name) : base(IntPtr.Zero)")
    assertContains(rendered, "internal Inpatient(IntPtr handle) : base(handle)")
    assertContains(rendered, "public override void Dispose()")
  }

  @Test
  fun `abstract class renders abstract dispose without native dispose import`() {
    val cls = CirClass(
      name = "Animal",
      libraryName = "zoo",
      nativePrefix = "animal",
      constructor = null,
      properties = emptyList(),
      methods = listOf(
        CirMethod(
          name = "Speak",
          returnType = "string",
          parameters = emptyList(),
          body = "",
          isAbstract = true,
        ),
      ),
      isAbstract = true,
    )

    val rendered: String = render(cls)

    assertContains(rendered, "public abstract class Animal : IDisposable")
    assertContains(rendered, "public abstract string Speak();")
    assertContains(rendered, "public abstract void Dispose();")
    assertFalse(rendered.contains("Native_Dispose"))
  }

  @Test
  fun `class method with custom multi-line body keeps sync error out on import`() {
    val cls = CirClass(
      name = "Counter",
      libraryName = "sample",
      nativePrefix = "counter",
      constructor = null,
      properties = emptyList(),
      methods = listOf(
        CirMethod(
          name = "Labels",
          returnType = "IReadOnlyList<string>",
          nativeReturnType = "IntPtr",
          nativeName = "labels",
          parameters = emptyList(),
          body = """
            IntPtr listHandle = Native_Labels(_handle, out IntPtr error);
            if (error != IntPtr.Zero)
            {
                throw NugetErrorNative.BuildException(error);
            }
            return NugetListNative.ToList<string>(listHandle);
          """.trimIndent().prependIndent("    "),
          isSyncErrorCheckEnabled = true,
          hasCustomBody = true,
        ),
      ),
      hasInternalHandleConstructor = true,
    )

    val rendered: String = render(cls)

    assertContains(
      rendered,
      "private static extern IntPtr Native_Labels(IntPtr handle, out IntPtr error);",
    )
    assertContains(rendered, "public IReadOnlyList<string> Labels()")
    assertContains(rendered, "return NugetListNative.ToList<string>(listHandle);")
  }

  @Test
  fun `companion static method and const render inside the class`() {
    val cls = CirClass(
      name = "Factory",
      libraryName = "sample",
      nativePrefix = "factory",
      constructor = null,
      properties = emptyList(),
      methods = emptyList(),
      companionMembers = listOf(
        CirConst(name = "DefaultCapacity", type = "int", value = "16"),
        CirDllImport(
          libraryName = "sample",
          entryPoint = "factory_companion_create",
          returnType = "IntPtr",
          name = "Native_Companion_Create",
          parameters = emptyList(),
          visibility = CirVisibility.PRIVATE,
          hasSyncErrorOut = true,
        ),
        CirMethod(
          name = "Create",
          returnType = "Factory",
          nativeReturnType = "IntPtr",
          nativeName = "Native_Companion_Create",
          parameters = emptyList(),
          body = """
            IntPtr handle = Native_Companion_Create(out IntPtr error);
            if (error != IntPtr.Zero)
            {
                throw NugetErrorNative.BuildException(error);
            }
            return new Factory(handle);
          """.trimIndent().prependIndent("    "),
          isStatic = true,
          isSyncErrorCheckEnabled = true,
          hasCustomBody = true,
        ),
      ),
    )

    val rendered: String = render(cls)

    assertContains(rendered, "public const int DefaultCapacity = 16;")
    assertContains(
      rendered,
      "private static extern IntPtr Native_Companion_Create(out IntPtr error);",
    )
    assertContains(rendered, "public static Factory Create()")
  }

  @Test
  fun `method with enum parameter casts native arg under sync error check`() {
    val cls = CirClass(
      name = "Vet",
      libraryName = "clinic",
      nativePrefix = "vet",
      constructor = null,
      properties = emptyList(),
      methods = listOf(
        CirMethod(
          name = "Diagnose",
          returnType = "void",
          nativeName = "diagnose",
          parameters = listOf(CirParameter("mood", "Mood", nativeType = "int")),
          body = "",
          isSyncErrorCheckEnabled = true,
        ),
      ),
    )

    val rendered: String = render(cls)

    assertContains(
      rendered,
      "private static extern void Native_Diagnose(IntPtr handle, int mood, out IntPtr error);",
    )
    assertContains(rendered, "public void Diagnose(Mood mood)")
    assertContains(rendered, "Native_Diagnose(_handle, (int)mood, out IntPtr error);")
  }

  @Test
  fun `read-only property uses expression-bodied getter`() {
    val cls = CirClass(
      name = "Patient",
      libraryName = "clinic",
      nativePrefix = "patient",
      constructor = null,
      properties = listOf(
        CirProperty(
          name = "Name",
          type = "string",
          nativeReturnType = "IntPtr",
          nativeName = "name",
          getter = "Marshal.PtrToStringUTF8(Native_Get_name(_handle, out IntPtr error))!",
          hasSyncErrorOut = true,
        ),
      ),
      methods = emptyList(),
    )

    val rendered: String = render(cls)

    assertContains(
      rendered,
      "public string Name => Marshal.PtrToStringUTF8(Native_Get_name(_handle, out IntPtr error))!;",
    )
    assertFalse(rendered.contains("Native_Set_name"))
  }

  @Test
  fun `multi-line property getter and setter use block accessors`() {
    val cls = CirClass(
      name = "Patient",
      libraryName = "clinic",
      nativePrefix = "patient",
      constructor = null,
      properties = listOf(
        CirProperty(
          name = "Nickname",
          type = "string?",
          nativeReturnType = "IntPtr",
          nativeName = "nickname",
          getter = """
                IntPtr ptr = Native_Get_nickname(_handle, out IntPtr error);
                if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
                return ptr == IntPtr.Zero ? null : Marshal.PtrToStringUTF8(ptr);
          """.trimIndent().prependIndent("    "),
          setter = """
                Native_Set_nickname(_handle, value, out IntPtr error);
                if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
          """.trimIndent().prependIndent("    "),
          hasSyncErrorOut = true,
        ),
      ),
      methods = emptyList(),
    )

    val rendered: String = render(cls)

    assertContains(rendered, "public string? Nickname")
    assertContains(rendered, "get")
    assertContains(rendered, "set")
    assertContains(rendered, "return ptr == IntPtr.Zero ? null : Marshal.PtrToStringUTF8(ptr);")
    assertContains(rendered, "Native_Set_nickname(_handle, value, out IntPtr error);")
  }

  @Test
  fun `constructor without error check uses body verbatim`() {
    val cls = CirClass(
      name = "HandleBox",
      libraryName = "sample",
      nativePrefix = "handlebox",
      constructor = CirConstructor(
        parameters = listOf(CirParameter("raw", "IntPtr")),
        body = "_handle = raw;",
        hasErrorCheck = false,
      ),
      properties = emptyList(),
      methods = emptyList(),
      disposable = false,
      hasInternalHandleConstructor = false,
    )

    val rendered: String = render(cls)

    assertContains(rendered, "public class HandleBox")
    assertFalse(rendered.contains(": IDisposable"))
    assertContains(rendered, "public HandleBox(IntPtr raw)")
    assertContains(rendered, "_handle = raw;")
    assertFalse(rendered.contains("Native_Dispose"))
    assertFalse(rendered.contains("internal HandleBox(IntPtr handle)"))
  }

  @Test
  fun `class implementing interfaces lists them without a superclass`() {
    val cls = CirClass(
      name = "Sensor",
      libraryName = "iot",
      nativePrefix = "sensor",
      constructor = null,
      properties = emptyList(),
      methods = emptyList(),
      interfaces = listOf("IDisposable", "IAsyncDisposable"),
      disposable = false,
    )

    val rendered: String = render(cls)

    assertContains(rendered, "public class Sensor : IDisposable, IAsyncDisposable")
  }

  @Test
  fun `property with multi-line setter only keeps expression getter`() {
    val cls = CirClass(
      name = "Patient",
      libraryName = "clinic",
      nativePrefix = "patient",
      constructor = null,
      properties = listOf(
        CirProperty(
          name = "Age",
          type = "int",
          nativeReturnType = "int",
          nativeName = "age",
          getter = "Native_Get_age(_handle, out IntPtr error)",
          setter = """
                Native_Set_age(_handle, value, out IntPtr error);
                if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
          """.trimIndent().prependIndent("    "),
          hasSyncErrorOut = true,
        ),
      ),
      methods = emptyList(),
    )

    val rendered: String = render(cls)

    assertContains(rendered, "get => Native_Get_age(_handle, out IntPtr error);")
    assertContains(rendered, "set")
    assertContains(rendered, "Native_Set_age(_handle, value, out IntPtr error);")
  }

  @Test
  fun `boolean property getter gets marshal-as on native import`() {
    val cls = CirClass(
      name = "Flag",
      libraryName = "sample",
      nativePrefix = "flag",
      constructor = null,
      properties = listOf(
        CirProperty(
          name = "Enabled",
          type = "bool",
          nativeReturnType = "bool",
          nativeName = "enabled",
          getter = "Native_Get_enabled(_handle, out IntPtr error)",
          hasSyncErrorOut = true,
        ),
      ),
      methods = emptyList(),
    )

    val rendered: String = render(cls)

    assertContains(rendered, "[return: MarshalAs(UnmanagedType.I1)]")
    assertContains(
      rendered,
      "private static extern bool Native_Get_enabled(IntPtr handle, out IntPtr error);",
    )
  }

  @Test
  fun `override method with void return uses expression body`() {
    val cls = CirClass(
      name = "Dog",
      libraryName = "zoo",
      nativePrefix = "dog",
      constructor = null,
      properties = emptyList(),
      methods = listOf(
        CirMethod(
          name = "Speak",
          returnType = "void",
          nativeName = "speak",
          parameters = emptyList(),
          body = "Native_Speak(_handle, out IntPtr error)",
          isOverride = true,
          isSyncErrorCheckEnabled = true,
        ),
      ),
      superClass = "Animal",
    )

    val rendered: String = render(cls)

    assertContains(rendered, "public override void Speak()")
    assertContains(rendered, "Native_Speak(_handle, out IntPtr error);")
  }

  // -- CirObject --------------------------------------------------------------

  @Test
  fun `object renders as static class with sync error methods`() {
    val obj = CirObject(
      name = "Clinic",
      libraryName = "clinic",
      nativePrefix = "clinic",
      methods = listOf(
        CirDllImport(
          libraryName = "clinic",
          entryPoint = "clinic_greet",
          returnType = "IntPtr",
          name = "Native_Greet",
          parameters = listOf(CirParameter("name", "string")),
          visibility = CirVisibility.PRIVATE,
          hasSyncErrorOut = true,
        ),
        CirMethod(
          name = "Greet",
          returnType = "string",
          nativeReturnType = "IntPtr",
          nativeName = "Native_Greet",
          parameters = listOf(CirParameter("name", "string")),
          body = "",
          isStatic = true,
          isSyncErrorCheckEnabled = true,
        ),
      ),
    )

    val rendered: String = render(obj)

    assertContains(rendered, "public static class Clinic")
    assertContains(
      rendered,
      "private static extern IntPtr Native_Greet(string name, out IntPtr error);",
    )
    assertContains(rendered, "public static string Greet(string name)")
    assertContains(rendered, "IntPtr nativeResult = Native_Greet(name, out IntPtr error);")
  }

  // -- CirEnum ----------------------------------------------------------------

  @Test
  fun `enum renders entries and extension property`() {
    val enum = CirEnum(
      name = "Mood",
      libraryName = "clinic",
      entries = listOf(
        CirEnumEntry("Calm", 0),
        CirEnumEntry("Anxious", 1),
      ),
      properties = listOf(
        CirEnumProperty(
          name = "Label",
          type = "string",
          nativeReturnType = "IntPtr",
          nativeName = "label",
        ),
        CirEnumProperty(
          name = "Severity",
          type = "int",
          nativeReturnType = "int",
          nativeName = "severity",
        ),
      ),
    )

    val rendered: String = render(enum)

    assertContains(rendered, "public enum Mood")
    assertContains(rendered, "Calm = 0,")
    assertContains(rendered, "Anxious = 1,")
    assertContains(rendered, "public static class MoodExtensions")
    assertContains(
      rendered,
      "private static extern IntPtr Native_GetLabel(int ordinal);",
    )
    assertContains(rendered, "public static string Label(this Mood mood)")
    assertContains(
      rendered,
      "Marshal.PtrToStringUTF8(Native_GetLabel((int)mood))!",
    )
    assertContains(rendered, "public static int Severity(this Mood mood)")
    assertContains(rendered, "Native_GetSeverity((int)mood)")
  }

  // -- CirValueClass ----------------------------------------------------------

  @Test
  fun `primitive value class uses create-checked primary constructor`() {
    val cls = CirValueClass(
      name = "CatId",
      libraryName = "clinic",
      nativePrefix = "catid",
      underlyingType = "int",
      underlyingName = "Value",
      underlyingNativeType = "int",
      constructors = listOf(
        CirValueClassConstructor(
          parameters = listOf(CirParameter("value", "int")),
          nativeName = "catid_create",
          body = "CreateChecked(value)",
        ),
      ),
      properties = listOf(
        CirProperty(
          name = "IsValid",
          type = "bool",
          nativeReturnType = "bool",
          nativeName = "isValid",
          getter = "Native_GetIsValid(Value)",
        ),
      ),
      methods = listOf(
        CirMethod(
          name = "Next",
          returnType = "int",
          nativeReturnType = "int",
          nativeName = "next",
          parameters = emptyList(),
          body = "Native_Next(Value, out IntPtr error)",
          isSyncErrorCheckEnabled = true,
        ),
      ),
    )

    val rendered: String = render(cls)

    assertContains(rendered, "public readonly record struct CatId")
    assertContains(rendered, "public int Value { get; }")
    assertContains(
      rendered,
      "private static extern int Native_Create(int value, out IntPtr error);",
    )
    assertContains(rendered, "private static int CreateChecked(int value)")
    assertContains(rendered, "public CatId(int value)")
    assertContains(rendered, "Value = CreateChecked(value);")
    assertContains(rendered, "public bool IsValid => Native_GetIsValid(Value);")
    assertContains(rendered, "public int Next() => Native_Next(Value, out IntPtr error);")
  }

  @Test
  fun `reference value class uses positional record and this-delegation`() {
    val cls = CirValueClass(
      name = "ArticleUri",
      libraryName = "news",
      nativePrefix = "articleuri",
      underlyingType = "string",
      underlyingName = "Value",
      underlyingNativeType = "IntPtr",
      underlyingIsReference = true,
      constructors = listOf(
        CirValueClassConstructor(
          parameters = listOf(CirParameter("value", "string")),
          nativeName = "articleuri_create",
          body = "value",
          hasErrorCheck = false,
        ),
        CirValueClassConstructor(
          parameters = listOf(
            CirParameter("host", "string"),
            CirParameter("path", "string"),
          ),
          nativeName = "articleuri_create_2",
          body = "CreateChecked_1(host, path)",
          hasErrorCheck = true,
        ),
      ),
      properties = emptyList(),
      methods = emptyList(),
    )

    val rendered: String = render(cls)

    assertContains(rendered, "public readonly record struct ArticleUri(string Value)")
    assertContains(rendered, "public ArticleUri(string value) : this(value) { }")
    assertContains(rendered, "private static IntPtr CreateChecked_1(string host, string path)")
    assertContains(
      rendered,
      "public ArticleUri(string host, string path) : this(CreateChecked_1(host, path)) { }",
    )
  }

  // -- CirStaticClass / extensions --------------------------------------------

  @Test
  fun `static class renders extension method with this receiver`() {
    val members: List<CirMember> = listOf(
      CirDllImport(
        libraryName = "sample",
        entryPoint = "int_scale",
        returnType = "void",
        name = "Native_Scale",
        parameters = listOf(
          CirParameter("receiver", "int"),
          CirParameter("factor", "double"),
        ),
        visibility = CirVisibility.PRIVATE,
        hasSyncErrorOut = true,
      ),
      CirMethod(
        name = "Scale",
        returnType = "void",
        nativeName = "Native_Scale",
        parameters = listOf(
          CirParameter("receiver", "int"),
          CirParameter("factor", "double"),
        ),
        body = "",
        isStatic = true,
        isExtension = true,
        isSyncErrorCheckEnabled = true,
      ),
    )

    val rendered: String = render(CirStaticClass("IntExtensions", members))

    assertContains(rendered, "public static partial class IntExtensions")
    assertContains(
      rendered,
      "private static extern void Native_Scale(int receiver, double factor, out IntPtr error);",
    )
    assertContains(rendered, "public static void Scale(this int receiver, double factor)")
    assertContains(rendered, "Native_Scale(receiver, factor, out IntPtr error);")
  }

  @Test
  fun `dllimport marshals boolean return when requested`() {
    val members: List<CirMember> = listOf(
      CirDllImport(
        libraryName = "sample",
        entryPoint = "flag_is_set",
        returnType = "bool",
        name = "Native_IsSet",
        parameters = listOf(CirParameter("handle", "IntPtr")),
        visibility = CirVisibility.PRIVATE,
        hasSyncErrorOut = true,
        marshalBooleanReturn = true,
      ),
    )

    val rendered: String = render(CirStaticClass("Flags", members))

    assertContains(rendered, "[return: MarshalAs(UnmanagedType.I1)]")
    assertContains(
      rendered,
      "private static extern bool Native_IsSet(IntPtr handle, out IntPtr error);",
    )
  }

  // -- CirInterface (ordinary declaration shape, not bridge protocol) ---------

  @Test
  fun `interface renders variance properties and methods`() {
    val iface = CirInterface(
      name = "IBox",
      typeParameters = listOf(CirTypeParameter("T", variance = CirVariance.COVARIANT)),
      properties = listOf(
        CirInterfaceProperty("Value", "T"),
        CirInterfaceProperty("Tag", "string", hasSetter = true),
      ),
      methods = listOf(
        CirInterfaceMethod(
          name = "Map",
          returnType = "void",
          parameters = listOf(CirParameter("label", "string")),
        ),
      ),
    )

    val rendered: String = render(iface)

    assertContains(rendered, "public interface IBox<out T> : IDisposable")
    assertContains(rendered, "T Value { get; }")
    assertContains(rendered, "string Tag { get; set; }")
    assertContains(rendered, "void Map(string label);")
  }

  // -- package → namespace (ordinary, shared by enum/object/class paths) -------

  @Test
  fun `mapPackageToNamespace folds under root package`() {
    assertEquals(
      "Clinic.Patients",
      mapPackageToNamespace("com.example.patients", "com.example", "Clinic"),
    )
  }

  @Test
  fun `mapPackageToNamespace capitalizes each relative segment`() {
    assertEquals(
      "Clinic.Deep.Nested",
      mapPackageToNamespace("com.example.deep.nested", "com.example", "Clinic"),
    )
  }

  @Test
  fun `mapPackageToNamespace uses root namespace alone for exact root package`() {
    assertEquals(
      "Clinic",
      mapPackageToNamespace("com.example", "com.example", "Clinic"),
    )
  }

  @Test
  fun `mapPackageToNamespace keeps packages outside root as a suffix`() {
    assertEquals(
      "Clinic.Other.Module",
      mapPackageToNamespace("other.module", "com.example", "Clinic"),
    )
  }

  @Test
  fun `mapPackageToNamespace ignores empty root package`() {
    assertEquals(
      "Clinic",
      mapPackageToNamespace("com.example.patients", "", "Clinic"),
    )
  }

  @Test
  fun `mapReturnType and mapParamType cover primitives and fall back to IntPtr`() {
    assertEquals("int", mapReturnType("Int"))
    assertEquals("void", mapReturnType("Unit"))
    assertEquals("IntPtr", mapReturnType("String"))
    assertEquals("IntPtr", mapReturnType("Patient"))

    assertEquals("string", mapParamType("String"))
    assertEquals("char", mapParamType("Char"))
    assertEquals("IntPtr", mapParamType("Patient"))
  }

  // -- helpers ----------------------------------------------------------------

  private fun render(vararg declarations: CirDeclaration, namespace: String = "Sample"): String =
    CirRenderer().render(
      CirFile(
        namespaces = listOf(CirNamespace(namespace, declarations.toList())),
      ),
    )
}
