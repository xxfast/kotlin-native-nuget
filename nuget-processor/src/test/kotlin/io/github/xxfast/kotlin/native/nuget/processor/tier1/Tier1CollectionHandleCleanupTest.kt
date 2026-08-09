package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ROADMAP:130 / ROADMAP:340 — a temporary native collection handle is disposed on **every** exit
 * path, not just the one where the Kotlin side returned normally. The shipped shape built the
 * handle, called native, threw on the error slot, and only then disposed: a Kotlin throw leaked the
 * handle (a rooted `StableRef`) permanently. These cells pin the structure that fixes it: handles
 * pre-declared to `IntPtr.Zero`, creation and the call inside a `try`, one `finally` disposing each
 * handle behind a zero-guard, and the marshal helpers themselves catching a mid-enumeration throw
 * so a partially built collection is not stranded either.
 */
class Tier1CollectionHandleCleanupTest {

  @Test
  fun `two collection parameters dispose in a single finally`() {
    val result = Tier1Harness.run(
      """
      package tier1.collectioncleanupmethod

      class Auditor {
        fun crossCheck(entries: List<String>, labels: Set<String>): Int = entries.size + labels.size
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected collection parameters to compile; got: ${result.compileErrors}",
    )

    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      """
      |            IntPtr entriesHandle = IntPtr.Zero;
      |            IntPtr labelsHandle = IntPtr.Zero;
      |            try
      |            {
      |                entriesHandle = NugetMarshal.CreateList(entries);
      |                labelsHandle = NugetMarshal.CreateSet(labels);
      |                int nativeResult = Native_CrossCheck(_handle, entriesHandle, labelsHandle, out IntPtr error);
      |                if (error != IntPtr.Zero)
      |                {
      |                    throw NugetErrorNative.BuildException(error);
      |                }
      |                return nativeResult;
      |            }
      |            finally
      |            {
      |                if (entriesHandle != IntPtr.Zero) { NugetListNative.Dispose(entriesHandle); }
      |                if (labelsHandle != IntPtr.Zero) { NugetSetNative.Dispose(labelsHandle); }
      |            }
      """.trimMargin(),
    )
  }

  /** ADR-075 shape: the nullable source never builds a handle, so the guard in the `finally` is
   *  the same guard the nullable case already needed. */
  @Test
  fun `constructor with a nullable collection parameter disposes in a finally`() {
    val result = Tier1Harness.run(
      """
      package tier1.collectioncleanupctor

      class Ledger(val notes: List<String>?) {
        fun size(): Int = notes?.size ?: 0
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected a nullable collection constructor parameter to compile; got: ${result.compileErrors}",
    )

    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      """
      |            IntPtr notesHandle = IntPtr.Zero;
      |            try
      |            {
      |                notesHandle = notes != null ? NugetMarshal.CreateList(notes) : IntPtr.Zero;
      |                IntPtr handle = Native_Create(notesHandle, out IntPtr error);
      |                if (error != IntPtr.Zero)
      |                {
      |                    throw NugetErrorNative.BuildException(error);
      |                }
      |                _handle = handle;
      |            }
      |            finally
      |            {
      |                if (notesHandle != IntPtr.Zero) { NugetListNative.Dispose(notesHandle); }
      |            }
      """.trimMargin(),
    )
  }

  @Test
  fun `collection property setter disposes in a finally`() {
    val result = Tier1Harness.run(
      """
      package tier1.collectioncleanupsetter

      class Chart {
        var tags: List<String> = emptyList()
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected a collection property to compile; got: ${result.compileErrors}",
    )

    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      """
      |            IntPtr valueHandle = IntPtr.Zero;
      |            try
      |            {
      |                valueHandle = NugetMarshal.CreateList(value);
      |                Native_Set_tags(_handle, valueHandle, out IntPtr error);
      |                if (error != IntPtr.Zero)
      |                {
      |                    throw NugetErrorNative.BuildException(error);
      |                }
      |            }
      |            finally
      |            {
      |                if (valueHandle != IntPtr.Zero) { NugetListNative.Dispose(valueHandle); }
      |            }
      """.trimMargin(),
    )
  }

  /**
   * The helper owns the handle between `Create()` and the caller taking it, so its own guard is a
   * `catch` + rethrow rather than a `finally`: the happy path hands the handle out alive.
   */
  @Test
  fun `marshal collection factories dispose a partially built handle on throw`() {
    val result = Tier1Harness.run(
      """
      package tier1.collectioncleanuphelpers

      class Auditor {
        fun crossCheck(entries: List<String>, labels: Set<String>): Int = entries.size + labels.size

        fun ward(rooms: Map<String, String>): Int = rooms.size
      }
      """.trimIndent(),
    )

    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      """
      |        public static IntPtr CreateList<T>(IEnumerable<T> values)
      |        {
      |            IntPtr listHandle = NugetListNative.Create();
      |            try
      |            {
      |                foreach (T value in values) NugetListNative.Add(listHandle, Wrap(value));
      |            }
      |            catch
      |            {
      |                NugetListNative.Dispose(listHandle);
      |                throw;
      |            }
      |            return listHandle;
      |        }
      """.trimMargin(),
    )
    assertContains(
      cs,
      """
      |        public static IntPtr CreateMap<TKey, TValue>(IEnumerable<KeyValuePair<TKey, TValue>> values)
      |        {
      |            IntPtr mapHandle = NugetMapNative.Create();
      |            try
      |            {
      |                foreach (var pair in values) NugetMapNative.Put(mapHandle, Wrap(pair.Key), Wrap(pair.Value));
      |            }
      |            catch
      |            {
      |                NugetMapNative.Dispose(mapHandle);
      |                throw;
      |            }
      |            return mapHandle;
      |        }
      """.trimMargin(),
    )
  }

  /** Regression: nothing to dispose means nothing to scope. A body with no collection or
   *  interface handle keeps the flat shape it shipped with. */
  @Test
  fun `a body with no temporary handles keeps its flat shape`() {
    val result = Tier1Harness.run(
      """
      package tier1.collectioncleanupflat

      class Pet(val name: String)

      class Owner {
        fun adopt(pet: Pet): Int = pet.name.length
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected the flat cell to compile; got: ${result.compileErrors}")

    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      """
      |            int nativeResult = Native_Adopt(_handle, pet._handle, out IntPtr error);
      |            if (error != IntPtr.Zero)
      |            {
      |                throw NugetErrorNative.BuildException(error);
      |            }
      |            return nativeResult;
      """.trimMargin(),
    )
    assertFalse("finally" in cs, "expected no try/finally scope without a temporary handle")
  }
}
