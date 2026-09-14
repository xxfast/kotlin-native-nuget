package io.github.xxfast.kotlin.native.nuget.test.nested

import io.github.xxfast.kotlin.native.nuget.test.cat.CatEventListener

/**
 * Site (a) of the interface-spelling sweep: the ADR-039 add/remove pair renders its listener
 * parameter as a BARE `I$simpleName` (`CirClassTranslator.translateInterfaceBridgeMethod`), which
 * only happens to compile because every shipped pair fixture (`cat.CatEventSource`) sits in the
 * same namespace as its listener interface.
 *
 * Two cells, because the bare name fails for two different reasons:
 *
 * - [PerchWatch] pairs on a **nested** interface ([Aviary.Watcher]). ADR-133 declares it as
 *   `Aviary.IWatcher`, so a bare `IWatcher` names nothing at all, even inside its own namespace,
 *   and the generated file does not compile.
 * - [WindowSill] pairs on a **cross-package** interface ([CatEventListener], declared in
 *   `TestLibrary.Cat`). The generated `TestLibrary.Nested` file emits only `System` usings
 *   (`CirRenderer`), so a bare `ICatEventListener` is undeclared here too.
 *
 * Neither listener interface is arbitrary. The pair route generates a Kotlin bridge object whose
 * overrides all have Unit block bodies and no properties (`InterfaceBridgeExports`), so a listener
 * interface with a value-returning method or an abstract property cannot travel this route at all
 * today. `Aviary.Keeper` (`fun greet(): String`) and `cat.Pet` are both out for that reason: they
 * would fail in the Kotlin compile long before the C# spelling was observable.
 *
 * Oreo lands on a perch and Mylo taps the window; both want someone to notice.
 */
class PerchWatch(val name: String) {
  private val watchers: MutableList<Aviary.Watcher> = mutableListOf()

  fun addWatcher(watcher: Aviary.Watcher) { watchers.add(watcher) }

  fun removeWatcher(watcher: Aviary.Watcher) { watchers.remove(watcher) }

  /** Fires both arities, so an arity-0 and an arity-1 slot are each proven to dispatch. */
  fun rustle() {
    watchers.forEach { it.onLand("$name the perch creaks") }
    watchers.forEach { it.onFlyOff() }
  }
}

/**
 * The cross-package half: same route, listener declared in `TestLibrary.Cat`.
 *
 * Mylo sits on the sill and announces every passing bird.
 */
class WindowSill(val name: String) {
  private val listeners: MutableList<CatEventListener> = mutableListOf()

  fun addListener(listener: CatEventListener) { listeners.add(listener) }

  fun removeListener(listener: CatEventListener) { listeners.remove(listener) }

  fun tap() {
    listeners.forEach { it.onMeow("$name spots a bird") }
    listeners.forEach { it.onPurr() }
  }
}
