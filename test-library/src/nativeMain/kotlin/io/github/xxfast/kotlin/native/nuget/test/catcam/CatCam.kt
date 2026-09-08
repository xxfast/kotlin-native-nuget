package io.github.xxfast.kotlin.native.nuget.test.catcam

import dev.other.core.Advertisement
import io.github.xxfast.kotlin.native.nuget.test.catcam.lens.CamId
import io.github.xxfast.kotlin.native.nuget.test.catcam.lens.Snapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Issue #111: the legacy lambda routes spell every one of a lambda's type arguments as
 * `arg.type?.resolve()?.declaration?.simpleName`, which drops both the argument's namespace and
 * its own type arguments. `packNuget` stays green; the consumer's `Interop.cs` then fails
 * `CS0246: The type or namespace name 'Flow' could not be found`.
 *
 * The class arms of the fixture. Two properties per arm, so the two failure modes cannot be
 * confused for each other:
 *
 *  - [onPick] is **expressible**: both type arguments are exported classes, so the fix must
 *    *qualify* them (`KotlinFunc<global::TestLibrary.Catcam.Lens.CamId,
 *    global::TestLibrary.Catcam.Lens.Snapshot>`). Today it renders both bare, which only compiles
 *    when the argument's namespace happens to match the declaring class's. It does not here.
 *  - [onStream] is **not expressible**: `Flow<Snapshot>` has no C# spelling on this route at all,
 *    so the member must be absent from C# with a `SKIPPED_UNSUPPORTED_PROPERTY` naming it. This
 *    is the reported repro, verbatim except for the cat names.
 *  - [onStreamAsync] is the same cell through the *suspend* arm
 *    (`CirClassTranslator.kt:352` rather than `:340`). It exists so the two arms cannot silently
 *    diverge: a fix applied to one copy and not the other leaves this one broken.
 *  - [onSponsor] is the unexported-dependency row: `dev.other.core` sits outside `rootPackage`
 *    and is never admitted, so `Advertisement` is never declared in C# either. Same outcome as
 *    [onStream] (absent, and named in a diagnostic), reached by a different predicate.
 *
 * [watching] is the control: an ordinary property on the same class that must keep binding, so
 * "the gate over-fired and took the class with it" is distinguishable from a correct skip.
 *
 * The cam watches Oreo (black, white bib) and Mylo (brown and creamy) doing nothing at all.
 */
class CatCam(val watching: String) {

  /** Expressible, cross-namespace: must qualify, must keep binding, must invoke. */
  val onPick: (CamId) -> Snapshot = { id -> Snapshot("${id.value}: $watching, unmoved") }

  /** The reported repro: `Flow<Snapshot>` is unspellable here, so the member must be absent. */
  val onStream: (CamId) -> Flow<Snapshot> = { id ->
    flowOf(Snapshot("${id.value}: $watching, still unmoved"))
  }

  /** The `suspend` twin of [onStream], through the second copy of the arm. */
  val onStreamAsync: suspend (CamId) -> Flow<Snapshot> = { id ->
    flowOf(Snapshot("${id.value}: $watching, asleep"))
  }

  /** Unexported type argument: `dev.other.core` is never admitted, so `Advertisement` is never
   *  declared and cannot be named from C# however it is spelled. */
  val onSponsor: (CamId) -> Advertisement = { id -> Advertisement("${id.value} sponsored by tuna") }
}
