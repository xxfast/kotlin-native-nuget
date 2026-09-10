package io.github.xxfast.kotlin.native.nuget.test.vessel

import io.github.xxfast.kotlin.native.nuget.hidden.Skiff

/**
 * The exported end of the chain. Its declared base
 * [io.github.xxfast.kotlin.native.nuget.hidden.Skiff] is outside the export set, but `Skiff`'s own
 * base [Vessel] is inside it, so C# must get `public class Dinghy : Vessel`: the nearest exported
 * base is kept, and only the dropped middle's members re-home here.
 *
 * Oreo's boat. He insists it is his own class; he still sails the way Mylo taught him.
 */
class Dinghy : Skiff("Dinghy") {
  /** Declared here. Must survive the dropped middle. */
  fun bail(): String = "bailing"
}
