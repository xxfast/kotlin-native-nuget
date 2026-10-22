package io.github.xxfast.kotlin.native.nuget.test.listenerprops

/**
 * An ADR-039 listener with a `var`. Like a bridge-factory `var` (ADR-084 amendment), its setter is
 * a `<name>Set` slot ordered directly after its getter, so Kotlin writes through to the C# object
 * it was handed on the `add`/`remove` subscription route. A `String`, so every write mints a handle
 * for C# to read and release.
 */
interface NameTagListener {
  var tag: String
  fun onRetag()
}

/** Oreo and Mylo wear name tags; the board rewrites them all and reads them back. */
class NameTagBoard {
  private val listeners: MutableList<NameTagListener> = mutableListOf()

  fun addNameTagListener(listener: NameTagListener) { listeners.add(listener) }

  fun removeNameTagListener(listener: NameTagListener) { listeners.remove(listener) }

  /** Writes [tag] to every listener, then reads each back through its getter, one per line. */
  fun retagAll(tag: String): String = listeners.joinToString("; ") { listener ->
    listener.tag = "$tag:${listener.tag}"
    listener.onRetag()
    listener.tag
  }
}
