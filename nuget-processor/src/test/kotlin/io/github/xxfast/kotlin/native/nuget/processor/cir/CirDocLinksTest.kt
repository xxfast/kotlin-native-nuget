package io.github.xxfast.kotlin.native.nuget.processor.cir

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ADR-150 amendment: the post-pass that decides whether a `[link]` may become a `<see cref>`.
 *
 * Hand-built CIR, no KSP: the question is only ever "does THIS file declare exactly one
 * non-generic type under that name", because a cref that names anything else is CS1574 in a
 * consumer's build of a file they cannot edit.
 */
class CirDocLinksTest {

  private fun docOf(vararg targets: String): CirDoc =
    CirDoc(summary = targets.map { CirDocInline.Link(it) })

  private fun classOf(
    name: String,
    doc: CirDoc? = null,
    typeParameters: List<CirTypeParameter> = emptyList(),
    nested: List<CirDeclaration> = emptyList(),
  ): CirClass = CirClass(
    name = name,
    typeParameters = typeParameters,
    libraryName = "lib",
    nativePrefix = name.lowercase(),
    constructor = null,
    properties = emptyList(),
    methods = emptyList(),
    nestedDeclarations = nested,
    doc = doc,
  )

  private fun fileOf(
    declarations: List<CirDeclaration>,
    namespace: String = "Lib",
  ): CirFile = CirFile(namespaces = listOf(CirNamespace(namespace, declarations)))

  private fun summaryOf(file: CirFile, index: Int = 0): CirDocText =
    (file.namespaces.first().declarations[index] as CirClass).doc!!.summary!!

  @Test
  fun `a uniquely declared type resolves to a global cref and an undeclared one does not`() {
    val file: CirFile = fileOf(
      listOf(classOf("Desk", docOf("Blanket", "Missing")), classOf("Blanket")),
    ).resolveDocLinks()

    assertEquals(
      listOf(CirDocInline.TypeRef("global::Lib.Blanket"), CirDocInline.Link("Missing")),
      summaryOf(file),
    )
  }

  @Test
  fun `a generic type is never indexed, because a bare cref to one is CS1574`() {
    val file: CirFile = fileOf(
      listOf(
        classOf("Desk", docOf("Crate")),
        classOf("Crate", typeParameters = listOf(CirTypeParameter("T"))),
      ),
    ).resolveDocLinks()

    assertEquals(listOf(CirDocInline.Link("Crate")), summaryOf(file))
  }

  @Test
  fun `an ambiguous simple name falls back and its dotted path still resolves`() {
    val file: CirFile = CirFile(
      namespaces = listOf(
        CirNamespace(
          "Lib.One",
          listOf(classOf("Desk", docOf("Blanket", "One.Blanket")), classOf("Blanket")),
        ),
        CirNamespace("Lib.Two", listOf(classOf("Blanket"))),
      ),
    ).resolveDocLinks()

    // Two namespaces declare `Blanket`, so the bare name resolves to neither.
    assertEquals(
      listOf(CirDocInline.Link("Blanket"), CirDocInline.Link("One.Blanket")),
      summaryOf(file),
    )
  }

  @Test
  fun `a nested type resolves by its dotted path and by its simple name`() {
    val file: CirFile = fileOf(
      listOf(
        classOf("Desk", docOf("Inner", "Outer.Inner")),
        classOf("Outer", nested = listOf(classOf("Inner"))),
      ),
    ).resolveDocLinks()

    assertEquals(
      listOf(
        CirDocInline.TypeRef("global::Lib.Outer.Inner"),
        CirDocInline.TypeRef("global::Lib.Outer.Inner"),
      ),
      summaryOf(file),
    )
  }

  @Test
  fun `an interface resolves under the Kotlin spelling the author writes`() {
    val file: CirFile = fileOf(
      listOf(
        classOf("Desk", docOf("Perchable", "IPerchable")),
        CirInterface("IPerchable", properties = emptyList(), methods = emptyList()),
      ),
    ).resolveDocLinks()

    assertEquals(
      listOf(
        CirDocInline.TypeRef("global::Lib.IPerchable"),
        CirDocInline.TypeRef("global::Lib.IPerchable"),
      ),
      summaryOf(file),
    )
  }

  /**
   * And the price of that alias: ADR-040 emits a backing wrapper `class Perchable : IPerchable`
   * for an interface used at a bridged position, so the Kotlin spelling then names two declared
   * types and resolves to neither. Conservative by design -- `<c>` is always safe, a cref to the
   * wrapper when the author meant the interface is not.
   */
  @Test
  fun `the Kotlin spelling falls back when the ADR-040 wrapper claims the same name`() {
    val file: CirFile = fileOf(
      listOf(
        classOf("Desk", docOf("Perchable", "IPerchable")),
        CirInterface("IPerchable", properties = emptyList(), methods = emptyList()),
        classOf("Perchable"),
      ),
    ).resolveDocLinks()

    assertEquals(
      listOf(CirDocInline.Link("Perchable"), CirDocInline.TypeRef("global::Lib.IPerchable")),
      summaryOf(file),
    )
  }

  @Test
  fun `a sealed arm resolves at the scope it is declared in`() {
    val file: CirFile = fileOf(
      listOf(
        classOf("Desk", docOf("Catnap", "Label")),
        CirSealedClass(
          name = "Snooze",
          libraryName = "lib",
          nativePrefix = "snooze",
          subclasses = listOf(
            CirSealedSubclass(
              name = "Catnap",
              nativePrefix = "snooze_catnap",
              properties = emptyList(),
            ),
            CirSealedSubclass(
              name = "Label",
              nativePrefix = "snooze_label",
              properties = emptyList(),
              isNested = false,
            ),
          ),
        ),
      ),
    ).resolveDocLinks()

    assertEquals(
      listOf(
        CirDocInline.TypeRef("global::Lib.Snooze.Catnap"),
        CirDocInline.TypeRef("global::Lib.Label"),
      ),
      summaryOf(file),
    )
  }

  @Test
  fun `links resolve in every slot of a doc, not only the summary`() {
    val doc = CirDoc(
      remarks = listOf(CirDocBlock.Para(listOf(CirDocInline.Link("Blanket")))),
      params = listOf(CirDocParam("a", listOf(CirDocInline.Link("Blanket")))),
      returns = listOf(CirDocInline.Link("Blanket")),
      throws = listOf(CirDocThrows("KotlinException", listOf(CirDocInline.Link("Blanket")))),
      seeAlso = listOf(CirDocInline.Link("Blanket")),
    )
    val file: CirFile =
      fileOf(listOf(classOf("Desk", doc), classOf("Blanket"))).resolveDocLinks()
    val resolved: CirDoc = (file.namespaces.first().declarations[0] as CirClass).doc!!
    val cref = CirDocInline.TypeRef("global::Lib.Blanket")

    assertEquals(listOf(CirDocBlock.Para(listOf(cref))), resolved.remarks)
    assertEquals(listOf(cref), resolved.params.single().text)
    assertEquals(listOf(cref), resolved.returns)
    assertEquals(listOf(cref), resolved.throws.single().text)
    assertEquals(listOf(cref), resolved.seeAlso)
  }

  @Test
  fun `a member doc resolves through a static class, an object and an enum entry`() {
    val method = CirMethod(
      name = "Take",
      returnType = "string",
      parameters = emptyList(),
      nativeName = "Native_Take",
      body = "return null!;",
      doc = docOf("Blanket"),
    )
    val file: CirFile = fileOf(
      listOf(
        CirStaticClass("DeskKt", listOf(method)),
        CirObject("Jar", "lib", "jar", listOf(method)),
        CirEnum(
          "Mood", "lib", nativePrefix = "mood",
          entries = listOf(CirEnumEntry("Happy", 0, docOf("Blanket"))),
        ),
        classOf("Blanket"),
      ),
    ).resolveDocLinks()
    val declarations: List<CirDeclaration> = file.namespaces.first().declarations
    val cref = listOf(CirDocInline.TypeRef("global::Lib.Blanket"))

    assertEquals(
      cref,
      ((declarations[0] as CirStaticClass).members.single() as CirMethod).doc!!.summary,
    )
    assertEquals(cref, ((declarations[1] as CirObject).methods.single() as CirMethod).doc!!.summary)
    assertEquals(cref, (declarations[2] as CirEnum).entries.single().doc!!.summary)
  }

  private fun scoped(path: String): CirDoc =
    CirDoc(summary = listOf(CirDocInline.Link(path.substringAfterLast('.'), scopedPath = path)))

  private fun summaryIn(file: CirFile, namespace: String): CirDocText = (
    file.namespaces.single { it.name == namespace }.declarations
      .first { it is CirClass && it.name == "Desk" } as CirClass
    ).doc!!.summary!!

  @Test
  fun `a scoped link crefs its own namespace's type even when another namespace has one too`() {
    val file: CirFile = CirFile(
      namespaces = listOf(
        CirNamespace("Lib.One", listOf(classOf("Desk", scoped("Odd")), classOf("Odd"))),
        CirNamespace("Lib.Two", listOf(classOf("Desk", scoped("Odd")), classOf("Odd"))),
        CirNamespace("Lib.Three", listOf(classOf("Desk", docOf("Odd")))),
      ),
    ).resolveDocLinks()

    assertEquals(listOf(CirDocInline.TypeRef("global::Lib.One.Odd")), summaryIn(file, "Lib.One"))
    assertEquals(listOf(CirDocInline.TypeRef("global::Lib.Two.Odd")), summaryIn(file, "Lib.Two"))
    // Unscoped and claimed by two namespaces: ambiguous, so no cref at all.
    assertEquals(listOf(CirDocInline.Link("Odd")), summaryIn(file, "Lib.Three"))
  }

  @Test
  fun `a scoped link to an own type the file does not declare never borrows another's`() {
    val file: CirFile = CirFile(
      namespaces = listOf(
        CirNamespace("Lib.One", listOf(classOf("Odd"))),
        CirNamespace("Lib.Two", listOf(classOf("Desk", scoped("Odd")))),
      ),
    ).resolveDocLinks()

    assertEquals(
      listOf(CirDocInline.Link("Odd", scopedPath = "Odd")),
      summaryIn(file, "Lib.Two"),
    )
  }

  @Test
  fun `a scoped nested path resolves exactly, past a same-named sibling in the namespace`() {
    val file: CirFile = fileOf(
      listOf(
        classOf("Desk", scoped("Outer.Inner")),
        classOf("Outer", nested = listOf(classOf("Inner"))),
        classOf("Other", nested = listOf(classOf("Inner"))),
      ),
    ).resolveDocLinks()

    // The bare `Inner` is ambiguous here, but the scoped path names exactly one type.
    assertEquals(listOf(CirDocInline.TypeRef("global::Lib.Outer.Inner")), summaryOf(file))
  }

  @Test
  fun `a scoped path through an interface carries the Kotlin spelling`() {
    val file: CirFile = fileOf(
      listOf(
        classOf("Desk", scoped("Perch.Inner")),
        CirInterface(
          "IPerch", properties = emptyList(), methods = emptyList(),
          nestedDeclarations = listOf(classOf("Inner")),
        ),
      ),
    ).resolveDocLinks()

    assertEquals(listOf(CirDocInline.TypeRef("global::Lib.IPerch.Inner")), summaryOf(file))
  }
}
