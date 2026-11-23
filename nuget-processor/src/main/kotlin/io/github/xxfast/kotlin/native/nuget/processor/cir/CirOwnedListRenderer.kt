package io.github.xxfast.kotlin.native.nuget.processor.cir

/**
 * Issue #610 / ADR-243: the owned list a returned read-only `List` of handles reads back as. Emitted
 * beside `NugetListNative` under the same gate as `NugetMarshal.ReadList` (`includesList`), at the
 * namespace level `KotlinFlow<T>` sits at, so two publishers in one consumer each carry their own.
 *
 * `Keep` and the release loop compare by reference: `Contains`/`IndexOf` would call the element's
 * `Equals`, which crosses into Kotlin for a data class and throws on a disposed one.
 */
internal fun StringBuilder.renderKotlinList() {
  // A non-generic marker, so `ReleaseAbandoned(object?)` can find a nested owned list (it cannot
  // test `is KotlinList<T>` for an open `T`) and release it through its own `Dispose()`.
  appendLine("    internal interface INugetOwnedList")
  appendLine("    {")
  appendLine("    }")
  appendLine()
  appendLine("    /// <summary>")
  appendLine("    /// A snapshot of a Kotlin list that owns the elements it handed out. <see cref=\"Dispose\"/>")
  appendLine("    /// releases every element that was not passed to <see cref=\"Keep\"/>.")
  appendLine("    /// </summary>")
  appendLine("    /// <typeparam name=\"T\">The element type.</typeparam>")
  appendLine(
    "    public sealed class KotlinList<T> : global::System.Collections.ObjectModel.ReadOnlyCollection<T>, " +
      "IDisposable, INugetOwnedList",
  )
  appendLine("    {")
  appendLine("        private global::System.Collections.Generic.HashSet<object>? _kept;")
  appendLine("        private int _disposed;")
  appendLine()
  appendLine("        internal KotlinList(global::System.Collections.Generic.IList<T> items) : base(items)")
  appendLine("        {")
  appendLine("        }")
  appendLine()
  appendLine("        /// <summary>")
  appendLine("        /// Takes <paramref name=\"element\"/> out of what <see cref=\"Dispose\"/> releases; the caller owns it")
  appendLine("        /// from here. Keeping the same element twice is allowed.")
  appendLine("        /// </summary>")
  appendLine("        /// <param name=\"element\">An element of this list, by reference.</param>")
  appendLine("        /// <returns>The same reference.</returns>")
  appendLine("        public T Keep(T element)")
  appendLine("        {")
  appendLine(
    "            if (global::System.Threading.Volatile.Read(ref _disposed) != 0) " +
      "throw new ObjectDisposedException(nameof(KotlinList<T>));",
  )
  appendLine(
    "            if (element is null || !Holds(element)) " +
      "throw new ArgumentException(\"Not an element of this list.\", nameof(element));",
  )
  // A struct element (a value class) is a fresh box on every comparison, so it cannot be named by
  // reference; it holds nothing the release below reaches either.
  appendLine("            if (!typeof(T).IsValueType)")
  appendLine(
    "                (_kept ??= new global::System.Collections.Generic.HashSet<object>(" +
      "global::System.Collections.Generic.ReferenceEqualityComparer.Instance)).Add(element);",
  )
  appendLine("            return element;")
  appendLine("        }")
  appendLine()
  appendLine("        /// <summary>")
  appendLine("        /// Releases every element this list handed out that was not kept. A second call does nothing.")
  appendLine("        /// </summary>")
  appendLine("        public void Dispose()")
  appendLine("        {")
  appendLine("            if (global::System.Threading.Interlocked.Exchange(ref _disposed, 1) != 0) return;")
  appendLine("            foreach (T item in Items)")
  appendLine("            {")
  appendLine("                if (item is null || (_kept?.Contains(item) ?? false)) continue;")
  appendLine("                NugetMarshal.ReleaseAbandoned(item);")
  appendLine("            }")
  appendLine("        }")
  appendLine()
  appendLine("        private bool Holds(T element)")
  appendLine("        {")
  appendLine("            if (typeof(T).IsValueType)")
  appendLine("                return global::System.Linq.Enumerable.Contains(Items, element);")
  appendLine("            foreach (T item in Items)")
  appendLine("                if (ReferenceEquals(item, element)) return true;")
  appendLine("            return false;")
  appendLine("        }")
  appendLine("    }")
  appendLine()
}

/**
 * Issue #610 / ADR-243: `NugetMarshal.ReadList`'s read, `catch` and `finally` exactly, handing back
 * the elements as a [renderKotlinList] that owns them instead of a bare `List<T>`.
 */
internal fun StringBuilder.renderReadOwnedList() {
  // Internal, unlike `ReadList`: no new public member may expose `IntPtr` (issue #126).
  appendLine("        internal static KotlinList<T> ReadOwnedList<T>(IntPtr handle, Func<IntPtr, T> read)")
  appendLine("        {")
  appendLine("            var result = new List<T>();")
  appendLine("            try")
  appendLine("            {")
  appendLine("                int count = NugetListNative.Count(handle);")
  appendLine("                result.Capacity = count;")
  appendLine("                for (int i = 0; i < count; i++) result.Add(read(NugetListNative.Get(handle, i)));")
  appendLine("                return new KotlinList<T>(result);")
  appendLine("            }")
  appendLine("            catch { DisposeMaterialized(result); throw; }")
  appendLine("            finally { NugetListNative.Dispose(handle); }")
  appendLine("        }")
  appendLine()
}
