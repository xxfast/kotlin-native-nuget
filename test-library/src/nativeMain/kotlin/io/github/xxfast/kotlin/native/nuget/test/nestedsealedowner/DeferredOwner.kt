package io.github.xxfast.kotlin.native.nuget.test.nestedsealedowner

class Owner {
  sealed class NestedSealed {
    value class Tag(val value: Int)
    class Leaf : NestedSealed()
    class Note(val text: String)
  }

  fun control(): Int = 7
  fun tag(): NestedSealed.Tag = NestedSealed.Tag(7)
  fun readTag(tag: NestedSealed.Tag): Int = tag.value
  val badge: NestedSealed.Tag get() = NestedSealed.Tag(9)
  fun note(): NestedSealed.Note = NestedSealed.Note("Oreo")
}