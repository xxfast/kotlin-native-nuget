package io.github.xxfast.kotlin.native.nuget.test.nested

/**
 * ADR-196 fixture: the generic nested shapes.
 *
 * - [Tote.Purse] is a generic class under a non-generic owner: `Tote.Purse<T>`, its ADR-147
 *   carrier `Tote.PurseNative` nested beside it. [Tote.Purse.swap] takes and returns a `T`, so a
 *   `T` needing conversion (`string`) and one that does not (`int`) both cross the boxed wire.
 * - [Tote.Charm] is its `inner` twin: `new Tote.Charm<int>(tote, 7)`, the outer first (ADR-141).
 * - Everything nested in the generic [Teapot] lands on a non-generic `public static class Teapot`
 *   holder beside `Teapot<T>`, which is Kotlin's own scope spelled letter for letter: [Teapot.Lid],
 *   [Teapot.Defaults], [Teapot.Blend] and the generic [Teapot.Cozy] as themselves, and the `inner`
 *   [Teapot.Strainer] and [Teapot.Infuser], which capture `T`, flattened onto the holder with the
 *   captured parameter first (`Teapot.Strainer<T>`, `Teapot.Infuser<T, U>`) and constructed from
 *   a `Teapot<T>` outer.
 * - [Teapot.lidAt] and [TeaShop.spare] return the same `Teapot.Lid` from inside and outside the
 *   generic owner, and [TeaShop.numberOf] takes it back.
 *
 * Member names are `lidAt`/`blendOf`, never `lid`/`blend`, only for readability: unlike a
 * non-generic owner, `Teapot<T>`'s members share no scope with the holder's types (no CS0102).
 *
 * Oreo (black with the white middle) carries the tote; Mylo (brown and creamy) minds the teapot.
 */
class Tote(val owner: String) {

  /** `Tote.Purse<T>`, `tote_purse_create(item, error)` with a boxed `T`. */
  class Purse<T>(val item: T) {
    fun swap(other: T): T = other

    fun describe(): String = "purse:$item"
  }

  /** `new Tote.Charm<T>(tote, tag)`: a generic inner class of a non-generic owner. */
  inner class Charm<T>(val tag: T) {
    fun label(): String = "$owner:$tag"
  }
}

class Teapot<T>(val item: T) {

  /** `Teapot.Lid` on the holder; `teapot_lid_create(number, error)`. */
  class Lid(val number: Int) {
    fun describe(): String = "lid#$number"
  }

  object Defaults {
    const val CUPS: Int = 3
  }

  enum class Blend { GREEN, BLACK }

  /** `Teapot.Cozy<U>`: a generic class nested in a generic owner, on the holder. */
  class Cozy<U>(val pattern: U)

  /** `new Teapot.Strainer<T>(teapot, mesh)`: an inner class that captures `T`. */
  inner class Strainer(val mesh: Int) {
    fun peek(): T = item

    fun twice(): Int = mesh * 2
  }

  /** `new Teapot.Infuser<T, U>(teapot, leaf)`: a generic inner class that captures `T` too. */
  inner class Infuser<U>(val leaf: U) {
    fun both(): String = "$item/$leaf"
  }

  fun lidAt(number: Int): Lid = Lid(number)

  fun blendOf(): Blend = Blend.BLACK
}

object TeaShop {
  fun spare(): Teapot.Lid = Teapot.Lid(9)

  fun numberOf(lid: Teapot.Lid): Int = lid.number
}
