package io.github.xxfast.kotlin.native.nuget.hidden

import io.github.xxfast.kotlin.native.nuget.test.abstractredeclaration.RedefinedVessel

abstract class RedefinedSkiff : RedefinedVessel() {
  abstract override val sail: String
  abstract override var height: Int
  abstract val rigging: String
}