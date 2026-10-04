package io.github.xxfast.kotlin.native.nuget.test.klibabstract

import dev.other.core.UnexportedAbstractRoost

abstract class KlibNester : UnexportedAbstractRoost() {
  fun describe(): String = "$material@$height"
}

class KlibWren : KlibNester() {
  override val material: String = "twig"
  override var height: Int = 3
  override fun chirp(): String = "Mylo's $material nest at $height"
}