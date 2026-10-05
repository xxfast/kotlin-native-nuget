@file:OptIn(NugetRuntimeApi::class)
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package io.github.xxfast.kotlin.native.nuget.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-200: `KotlinStackTrace` keeps the Kotlin frames only. The fixture lines are
 * `Throwable.getStackTrace()` elements captured on mingwX64 inside a .NET host (no `at ` prefix,
 * trailing space kept), plus macOS-shaped lines that are inferred, not captured.
 */
class StackTraceTrimTest {

  private val syncTrace: Array<String> = arrayOf(
    "0   ???                                 7ff8cd7b1bd4       " +
      "kfun:io.github.xxfast.kotlin.native.nuget.test.cat#feedCatTreat(kotlin.String){}" +
      "kotlin.String + 196 ",
    "1   ???                                 7ff8cd8b5c99       _konan_function_535 + 169 ",
    "2   ???                                 7ff8cdb2617c       " +
      "kn_testlibrary_cat__feedCatTreat + 156 ",
    "3   ???                                 7ff8cf4901be       " +
      "_ZSt25__throw_bad_function_callv + 24839742 ",
    "4   ???                                 7ff8cf29f5de       0x0 + 140706604250590 ",
    "5   ???                                 7ff994d6caec       " +
      "_ZSt25__throw_bad_function_callv + 3339243372 ",
  )

  private val workerTrace: Array<String> = arrayOf(
    "0   ???                                 7ff8d2931bd4       " +
      "kfun:io.github.xxfast.kotlin.native.nuget.test.cat.\$fetchCatTreatCOROUTINE\$0" +
      ".invokeSuspend#internal + 585 ",
    "1   ???                                 7ff8d2931c00       " +
      "kfun:kotlin.coroutines.native.internal.BaseContinuationImpl#resumeWith(kotlin.Result" +
      "<kotlin.Any?>){} + 412 ",
    "2   ???                                 7ff8d2a35c99       " +
      "_ZN6Worker19processQueueElementEb + 1706 ",
    "3   ???                                 7ff8d2a35d10       " +
      "_ZN12_GLOBAL__N_113workerRoutineEPv + 106 ",
    "4   ???                                 7ff8d2a35e20       pthread_create_wrapper + 306 ",
    "5   ???                                 7ff993fbcd87       " +
      "_ZSt25__throw_bad_function_callv + 12353192039 ",
    "6   ???                                 7ff994d6caec       " +
      "_ZSt25__throw_bad_function_callv + 12367543756 ",
  )

  @Test
  fun `a sync trace ends at the export frame`() {
    assertEquals(syncTrace.take(3), nugetTrimFrames(syncTrace))
  }

  @Test
  fun `a worker trace with no export frame is cut at the first host frame`() {
    assertEquals(workerTrace.take(5), nugetTrimFrames(workerTrace))
  }

  @Test
  fun `a host frame before the export frame still cuts`() {
    val frames: Array<String> = arrayOf(
      syncTrace[0],
      "1   ???                                 7ff8cf29f5de       0x0 + 140706604250590 ",
      syncTrace[2],
    )
    assertEquals(listOf(syncTrace[0]), nugetTrimFrames(frames))
  }

  @Test
  fun `an anchor the regex misses still loses its host frames`() {
    val frames: Array<String> = arrayOf(
      syncTrace[0],
      syncTrace[1],
      "2   ???                                 7ff8cdb2617c       some_other_export + 156 ",
      "3   ???                                 7ff8cf29f5de       0x0 + 140706604250590 ",
      syncTrace[5],
    )
    assertEquals(frames.take(3), nugetTrimFrames(frames))
  }

  @Test
  fun `a trace with nothing to cut is unchanged`() {
    val frames: Array<String> = workerTrace.take(5).toTypedArray()
    assertEquals(frames.toList(), nugetTrimFrames(frames))
  }

  @Test
  fun `an empty trace stays empty`() {
    assertEquals(emptyList(), nugetTrimFrames(emptyArray()))
  }

  @Test
  fun `a trace whose first frame looks like a host frame is never trimmed to empty`() {
    val frames: Array<String> = arrayOf(
      "0   ???                                 7ff8cf29f5de       0x0 + 140706604250590 ",
      syncTrace[0],
      syncTrace[2],
    )
    assertEquals(frames.toList(), nugetTrimFrames(frames))
  }

  @Test
  fun `a macOS shaped trace with file positions is cut at the export frame`() {
    val frames: Array<String> = arrayOf(
      "0   libkn_testlibrary.dylib  0x0000000104a31bd4 " +
        "kfun:io.github.xxfast.kotlin.native.nuget.test.cat#feedCatTreat(kotlin.String){}" +
        "kotlin.String + 196 (/src/Cat.kt:12:5)",
      "1   libkn_testlibrary.dylib  0x0000000104b35c99 _konan_function_535 + 169",
      "2   libkn_testlibrary.dylib  0x0000000104da617c " +
        "_kn_testlibrary_cat__feedCatTreat + 156",
      "3   libcoreclr.dylib                    0x0000000105a1f5de CallDescrWorkerInternal + 84",
      "4   libcoreclr.dylib                    0x0000000105a1f7aa MethodDescCallSite + 360",
    )
    assertEquals(frames.take(3), nugetTrimFrames(frames))
  }

  @Test
  fun `a macOS shaped trace with honest host symbols and no anchor is never emptied`() {
    val frames: Array<String> = arrayOf(
      "0   libkn_testlibrary.dylib  0x0000000104a31bd4 " +
        "kfun:io.github.xxfast.kotlin.native.nuget.test.cat#feedCatTreat(kotlin.String){}" +
        "kotlin.String + 196",
      "1   libcoreclr.dylib                    0x0000000105a1f5de CallDescrWorkerInternal + 84",
    )
    val kept: List<String> = nugetTrimFrames(frames)
    assertTrue(kept.isNotEmpty())
    assertEquals(frames[0], kept[0])
  }

  @Test
  fun `a linux shaped trace with a nuget runtime export is cut there`() {
    val frames: Array<String> = arrayOf(
      "0   libkn_testlibrary.so     0x00007f3a91c31bd4 " +
        "kfun:io.github.xxfast.kotlin.native.nuget.runtime#collect(){} + 44",
      "1   libkn_testlibrary.so     0x00007f3a91d35c99 nuget_flow_collect + 12",
      "2   libcoreclr.so                       0x00007f3a93a1f5de <unknown> + 0",
    )
    assertEquals(frames.take(2), nugetTrimFrames(frames))
  }

  @Test
  fun `buildError renders each node with its own frames and no cause section`() {
    val cause = NullPointerException("inner")
    val outer = IllegalStateException("outer", cause)
    val error: NugetError = buildError(outer, ::nugetStdlibMappedType)

    assertTrue(error.stackTrace.startsWith(outer.toString()), error.stackTrace)
    assertFalse(error.stackTrace.contains("Caused by"), error.stackTrace)
    assertTrue(error.stackTrace.contains("\n    at "), error.stackTrace)

    val inner: NugetError = error.cause!!
    assertTrue(inner.stackTrace.startsWith(cause.toString()), inner.stackTrace)
    assertTrue(inner.stackTrace.contains("\n    at "), inner.stackTrace)
  }
}
