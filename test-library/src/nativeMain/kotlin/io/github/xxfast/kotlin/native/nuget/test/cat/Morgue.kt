package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference

/**
 * Watches one forward object weakly so C# can prove Kotlin's GC collects it once the last
 * wrapper is disposed (ADR-121). C# drives the collection through `NugetBridge.GcCollect()`.
 */
@OptIn(ExperimentalNativeApi::class)
object Morgue {
  private var watched: WeakReference<Any>? = null

  /** Watch a plain class instance: the object behind `cat_create`, nothing else touches it. */
  fun watchCat(cat: Cat) { watched = WeakReference(cat) }

  /** Watch a stored-callback receiver: the unsubscribe closure captures it (ADR-039). */
  fun watchSource(source: CatEventSource) { watched = WeakReference(source) }

  /** True while the watched object is reachable; false once Kotlin's GC has collected it. */
  fun isAlive(): Boolean = watched?.get() != null

  fun forget() { watched = null }
}
