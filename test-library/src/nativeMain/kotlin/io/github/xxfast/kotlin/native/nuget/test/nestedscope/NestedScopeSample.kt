package io.github.xxfast.kotlin.native.nuget.test.nestedscope

import dev.other.core.UnexportedLabelOwner

fun refusedTag(): UnexportedLabelOwner.Tag = UnexportedLabelOwner.Tag("Oreo")
val refusedTagProperty: UnexportedLabelOwner.Tag get() = UnexportedLabelOwner.Tag("Mylo")
fun control(): Int = 7