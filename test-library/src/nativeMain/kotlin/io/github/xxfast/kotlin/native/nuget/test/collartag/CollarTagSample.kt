package io.github.xxfast.kotlin.native.nuget.test.collartag

/** The object-handle family a [CollarTag] member can return. */
class Bell(val tone: String)

/** The enum family a [CollarTag] member can return. */
enum class Temper { CALM, GRUMPY }

/**
 * A value class whose OWN methods and getters return nullable results, one per representative
 * family, null for an empty name and a value otherwise. A value-class member keeps the ADR-014
 * no-errorOut ABI, and before this fixture every one of these failed the KSP run (then skipped by
 * name). Each now binds on the ordinary member route's own wire:
 * - [nickname] / [initialCount]: a nullable reference getter and a nullable has-value getter,
 * - [motto] (`String?`), [letters] (`Int?`), [temper] (`Temper?`): the null pointer and the
 *   `bool` plus `valueOut` pair,
 * - [bell] (`Bell?`), [names] (`List<String>?`), [mishap] (`Throwable?`): handle-minting results,
 *   null-guarded on the C# side,
 * - [ownBell] / [allNames]: the non-null twins, which used to render the raw native handle.
 *
 * Mylo's collar tag has his name on it; Oreo lost hers in the garden.
 */
value class CollarTag(val name: String) {
  val nickname: String? get() = name.takeIf { it.isNotEmpty() }?.let { "$it the brave" }
  val initialCount: Int? get() = name.length.takeIf { it > 0 }

  fun motto(loud: Boolean): String? =
    name.takeIf { it.isNotEmpty() }?.let { if (loud) "${it.uppercase()}!" else it }

  fun letters(): Int? = name.length.takeIf { it > 0 }
  fun temper(): Temper? = if (name.isEmpty()) null else Temper.GRUMPY
  fun bell(): Bell? = name.takeIf { it.isNotEmpty() }?.let { Bell("$it-ding") }
  fun names(): List<String>? = name.takeIf { it.isNotEmpty() }?.let { listOf(it, it.reversed()) }
  fun mishap(): Throwable? =
    if (name.isEmpty()) null else IllegalStateException("$name slipped the collar")

  fun ownBell(): Bell = Bell("$name-dong")
  fun allNames(): List<String> = listOf(name)
}

/** Mylo, tagged. */
fun myloTag(): CollarTag = CollarTag("Mylo")

/** Oreo, whose tag has no name on it. */
fun blankTag(): CollarTag = CollarTag("")
