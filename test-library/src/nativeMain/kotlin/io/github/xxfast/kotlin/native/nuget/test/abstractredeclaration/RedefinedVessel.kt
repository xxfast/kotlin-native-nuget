package io.github.xxfast.kotlin.native.nuget.test.abstractredeclaration

import io.github.xxfast.kotlin.native.nuget.hidden.RedefinedSkiff

abstract class RedefinedVessel {
  abstract val sail: String
  abstract var height: Int
}

abstract class RedefinedDinghy : RedefinedSkiff()

class RedefinedRowboat : RedefinedDinghy() {
  override val sail: String = "Oreo's canvas"
  override var height: Int = 3
  override val rigging: String = "Mylo's rope"
}