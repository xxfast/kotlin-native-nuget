package io.github.xxfast.kotlin.native.nuget.test.unrouted

import io.github.xxfast.kotlin.native.nuget.test.cat.Box
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// EXPERIMENT fixture (ROADMAP Phase 3, research H-dropped-positions.md). One declaration per
// matrix cell: FLOW_PROTOCOL / CALLBACK_PROTOCOL / GENERIC at every owner position, to measure
// which positions a legacy route actually re-emits and which vanish with no diagnostic.
// Every declaration is named after its cell so one grep per name fills a matrix row.

/** Ordinary class owner. */
class Depot {
  /** Anchor: keeps the class planned and constructible. */
  fun okOnClass(): Int = 1

  /** row 1: FLOW_PROTOCOL, type-based return on a class method. Predicted RE. */
  fun flowReturnOnClass(): Flow<Int> = flowOf(1)

  /** row 2: FLOW_PROTOCOL, type-based parameter on a class method. Predicted NONE. */
  fun flowParamOnClass(events: Flow<Int>): Int = 0

  /** row 8: CALLBACK_PROTOCOL, lambda parameter on a class method. Predicted RE. */
  fun callbackParamOnClass(cb: (Int) -> Unit) {
    cb(1)
  }

  /** row 10: CALLBACK_PROTOCOL, lambda return on a class method. Predicted NONE. */
  fun callbackReturnOnClass(): (Int) -> Unit = {}

  /** row 21: GENERIC, generic-declaration return on a class method. Predicted NONE. */
  fun genericReturnOnClass(): Box<Int> = Box(1)

  /** row 21: GENERIC, generic-declaration parameter on a class method. Predicted NONE. */
  fun genericParamOnClass(box: Box<Int>): Int = box.value

  /** row 17 / extra observation: structural GENERIC (own `<T>`) on an ordinary class. */
  fun <T> structuralOnClass(value: T): T = value

  /** extra observation: SUSPEND_CALLBACK_PROTOCOL, suspend lambda parameter on a class method. */
  fun suspendCallbackParamOnClass(cb: suspend (Int) -> Unit): Int = if (cb === cb) 0 else 1

  /** row 7: FLOW_PROTOCOL as a collection element. Predicted NONE. */
  fun flowElementOnClass(): List<Flow<Int>> = emptyList()

  /** row 14: CALLBACK_PROTOCOL as a collection element. Predicted NONE. */
  fun callbackElementOnClass(): List<(Int) -> Unit> = emptyList()

  /** row 23: GENERIC as a collection element. Predicted NONE. */
  fun genericElementOnClass(): List<Box<Int>> = emptyList()
}

/** row 24: constructor parameters carrying each of the three reasons, alongside a good primary. */
class Dock(val id: Int) {
  constructor(id: Int, onDockCallbackParamOnConstructor: (Int) -> Unit) : this(id)

  constructor(id: Int, flowParamOnConstructor: Flow<Int>) : this(id)

  constructor(id: Int, genericParamOnConstructor: Box<Int>) : this(id)

  fun okOnDock(): Int = id
}

/** Object owner (row 3 / 13 / 18 / 22). */
object DepotRegistry {
  fun okOnObject(): Int = 1

  fun flowReturnOnObject(): Flow<Int> = flowOf(1)

  fun flowParamOnObject(events: Flow<Int>): Int = 0

  fun callbackParamOnObject(cb: (Int) -> Unit): Int {
    cb(1)
    return 1
  }

  fun callbackReturnOnObject(): (Int) -> Unit = {}

  fun genericReturnOnObject(): Box<Int> = Box(1)

  fun genericParamOnObject(box: Box<Int>): Int = box.value

  fun <T> structuralOnObject(value: T): T = value
}

/** Interface-default owner (rows 3 / 13 / 18 / 22 at an interface). Every member has a body. */
interface Manifest {
  fun okOnInterface(): Int = 1

  fun flowReturnOnInterface(): Flow<Int> = flowOf(1)

  fun flowParamOnInterface(events: Flow<Int>): Int = 0

  fun callbackParamOnInterface(cb: (Int) -> Unit) {
    cb(1)
  }

  fun genericReturnOnInterface(): Box<Int> = Box(1)

  fun <T> structuralOnInterface(value: T): T = value
}

/** Makes [Manifest] reachable from an exported class (ADR-075 shape, mirrors `garage/Vault.kt`). */
class ManifestDesk : Manifest {
  fun okOnManifestDesk(): Int = 2
}

/** row 5: FLOW_PROTOCOL, type-based parameter on a top-level function. Predicted NONE. */
fun flowParamOnTopLevel(events: Flow<Int>): Int = 0

/** row 11: CALLBACK_PROTOCOL, lambda return on a top-level function. Predicted RE. */
fun callbackReturnOnTopLevel(): (String) -> String = { it }

/** row 12: CALLBACK_PROTOCOL, lambda parameter on a top-level function. Predicted NONE. */
fun callbackParamOnTopLevel(cb: (Int) -> Unit): Int {
  cb(1)
  return 1
}

/** row 20: GENERIC, generic-declaration parameter on a top-level function. Predicted NONE. */
fun genericParamOnTopLevel(box: Box<Int>): Int = box.value

/** row 15: structural GENERIC with a `T`-typed parameter, top level. Predicted RE. */
fun <T> structuralOnTopLevel(value: T): T = value

/** row 16: structural GENERIC with NO `T`-typed parameter, top level. Predicted PART. */
fun <T> structuralRefusedOnTopLevel(): List<T> = emptyList()

/** row 6: FLOW_PROTOCOL return on an extension. Predicted NONE. */
fun Depot.flowReturnOnExtension(): Flow<Int> = flowOf(1)

/** row 6: FLOW_PROTOCOL parameter on an extension. Predicted NONE. */
fun Depot.flowParamOnExtension(events: Flow<Int>): Int = 0

/** row 13: CALLBACK_PROTOCOL parameter on an extension. Predicted NONE. */
fun Depot.callbackParamOnExtension(cb: (Int) -> Unit): Int {
  cb(1)
  return 1
}

/** row 22: GENERIC return on an extension. Predicted NONE. */
fun Depot.genericReturnOnExtension(): Box<Int> = Box(1)

/** row 18: structural GENERIC on an extension. Predicted NONE. */
fun <T> Depot.structuralOnExtension(value: T): T = value
