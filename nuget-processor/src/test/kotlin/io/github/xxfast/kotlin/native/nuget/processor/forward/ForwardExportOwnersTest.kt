package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.exports.cNameAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ADR-117 amendment (2026-09-13): the owner tag rides on the `@CName` annotation minted by the one
 * `cNameAnnotation(value, owner)` helper, so every export of every route carries its own owner and
 * the index needs no coarse `members`-range attribution. These two cells pin both halves of that
 * invariant: a tagged export resolves to its own owner, and an export whose `@CName` bypassed the
 * minter fails the index build by name instead of being silently attributed.
 */
class ForwardExportOwnersTest {
  private fun fileWith(function: FunSpec): FileSpec =
    FileSpec.builder("sample", "CNameExports").addFunction(function).build()

  @Test
  fun `every CName export resolves its owner from its own tag`() {
    val file: FileSpec = fileWith(
      FunSpec.builder("export_oreo_purr")
        .addAnnotation(
          cNameAnnotation(
            "oreo_purr",
            ForwardExportOwnerTag(symbol = "cattery.Oreo.purr", role = "generated Dispose"),
          ),
        )
        .build(),
    )

    val owners: ForwardExportOwners =
      ForwardExportOwners.build(file, ForwardCallablePlanCatalog(emptyList()))

    assertEquals(
      "cattery.Oreo.purr (generated Dispose)",
      owners.owners("oreo_purr").single().text,
    )
  }

  @Test
  fun `a CName export minted without the helper fails the index build`() {
    val raw: AnnotationSpec = AnnotationSpec.builder(ClassName("kotlin.native", "CName"))
      .addMember("%S", "bare_export")
      .build()
    val file: FileSpec = fileWith(FunSpec.builder("export_bare").addAnnotation(raw).build())

    val error: IllegalStateException = assertFailsWith {
      ForwardExportOwners.build(file, ForwardCallablePlanCatalog(emptyList()))
    }

    assertTrue(
      error.message.orEmpty().contains("bare_export"),
      "expected the failure to name the untagged entry point, was: ${error.message}",
    )
  }
}
