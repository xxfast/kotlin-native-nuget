# `NugetErrorNative._lastManagedFault` keeps one exception rooted per thread until the next fault

> Discovered while landing [ADR-161](../adr/161-csharp-callback-exception-into-kotlin.md) part B.

The `[ThreadStatic] Exception? _lastManagedFault` field on the generated `NugetErrorNative` class
(one per consuming assembly) stores the most recent managed exception a user-code callback thunk
caught, so the shared `BuildException` can rethrow the original instead of a `KotlinException`
wrapper. It is cleared when a matching read consumes it, but a thread that throws once and then
never crosses an export's error arm again on that same thread keeps that one exception rooted for
as long as the thread lives (typically the process, for a thread pool or dedicated worker thread).
Not measured as a practical leak; recorded because it is a deliberate, unbounded-lifetime root that
a memory profiler would otherwise have to explain.
