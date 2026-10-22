package io.github.xxfast.kotlin.native.nuget.test.stickers

import io.github.xxfast.kotlin.native.nuget.test.cat.Animal
import io.github.xxfast.kotlin.native.nuget.test.cat.Box
import io.github.xxfast.kotlin.native.nuget.test.cat.Cat
import io.github.xxfast.kotlin.native.nuget.test.issue463.ConnectableDevice
import io.github.xxfast.kotlin.native.nuget.test.issue463.EvidenceDevice
import io.github.xxfast.kotlin.native.nuget.test.issue463.NearbyDevice
import io.github.xxfast.kotlin.native.nuget.test.issue463.SavedDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.yield

/**
 * ADR-234: the value class underlyings the earlier value class rules left out.
 *
 * - A star- or `out`-projected generic value class at a result (`Sticker<*>`, `Sticker<out
 *   Animal>`) takes a caller-supplied method type parameter, `Sticker<TSticker> Any<TSticker>()`,
 *   the same rule a star-projected parameter already follows.
 * - A value class over an ineligible sealed interface ([Clue] over [ConnectableDevice]) and over an
 *   eligible sealed class ([Dossier] over [EvidenceDevice]) crosses at every erased position on the
 *   value class box pair, reconstructed through the sealed type's own `FromHandle`.
 * - A value class over `Char` ([Monogram]) and `Char?` ([MaybeMonogram]), and the closed
 *   `Sticker<Char>`, cross at every ordinary and erased position.
 * - [TreatCount], a value class over a nullable primitive, crosses at every erased position.
 *
 * Oreo (black, white in the middle) signs his stickers `O`. Mylo (brown and creamy) cannot spell
 * yet, so his monogram is blank.
 */

/** A value class over `Char`: Oreo's initial. `init` refuses a space. */
value class Monogram(val letter: Char) {
  init {
    require(letter != ' ') { "Mylo left the monogram blank" }
  }

  /** A member reading the receiver. */
  fun shout(): String = "$letter!"

  /** A member property over the underlying. */
  val lower: Char get() = letter.lowercaseChar()
}

/** A value class over `Char?`: `MaybeMonogram(null)` is a monogram Mylo has not signed. */
value class MaybeMonogram(val letter: Char?)

/** A value class over an ineligible sealed interface (ADR-204): where the collar tag answered. */
value class Clue(val device: ConnectableDevice)

/** A value class over an eligible sealed class: the device Oreo filed as evidence. */
value class Dossier(val evidence: EvidenceDevice)

// --- Star and `out`-projected results ---------------------------------------------------------

/** A star-projected result: `Sticker<TSticker> AnySticker<TSticker>()`. */
fun anySticker(): Sticker<*> = Sticker(7)

/** An `out`-projected result over a class bound. */
fun animalSticker(name: String): Sticker<out Animal> = Sticker(Cat(name))

/** A star-projected nullable result. */
fun anyStickerOrNone(present: Boolean): Sticker<*>? = if (present) Sticker("Oreo") else null

// --- Char underlyings at ordinary positions ---------------------------------------------------

/** `Monogram` at a return. */
fun oreoMonogram(): Monogram = Monogram('O')

/** `Monogram` at a parameter: Kotlin re-wraps, so a space runs `init`. */
fun monogramOf(monogram: Monogram): String = "signed ${monogram.letter}"

/** `Monogram?` in and out. */
fun maybeSign(monogram: Monogram?): Monogram? = monogram

/** `List<Monogram>` in and out. */
fun signAll(letters: List<Monogram>): List<Monogram> = letters.reversed()

/** `Map<Monogram, String>`: the struct as a key. */
fun signedBy(owners: Map<Monogram, String>): String = owners.values.sorted().joinToString()

/** `MaybeMonogram` at a return, unsigned. */
fun unsigned(): MaybeMonogram = MaybeMonogram(null)

/** `MaybeMonogram` at a parameter. */
fun monogramOr(monogram: MaybeMonogram): Char = monogram.letter ?: '?'

/** `MaybeMonogram?`: `MaybeMonogram(null)` or `null`. */
fun maybeUnsigned(present: Boolean): MaybeMonogram? = if (present) MaybeMonogram(null) else null

/** `MaybeMonogram?` at a parameter. */
fun unsignedState(monogram: MaybeMonogram?): String =
  if (monogram == null) "no monogram" else monogram.letter?.toString() ?: "unsigned"

/** `List<MaybeMonogram>` in and out. */
fun monogramRoll(letters: List<MaybeMonogram>): List<MaybeMonogram> = letters

/** The closed `Sticker<Char>` at a return. */
fun charSticker(): Sticker<Char> = Sticker('M')

/** The closed `Sticker<Char>` at a parameter. */
fun charOf(sticker: Sticker<Char>): Char = sticker.value

/** `List<Sticker<Char>>`. */
fun charStickers(): List<Sticker<Char>> = listOf(Sticker('O'), Sticker('M'))

// --- Sealed underlyings at ordinary positions -------------------------------------------------

/** [Clue] at a return. */
fun clueAt(address: String): Clue = Clue(NearbyDevice("desk", address))

/** [Clue] at a parameter. */
fun clueAddress(clue: Clue): String = clue.device.address

/** [Clue] in a `List`. */
fun clues(): List<Clue> = listOf(Clue(NearbyDevice("desk", "aa:bb")), Clue(SavedDevice))

/** [Dossier] at a return and a parameter. */
fun fileDossier(evidence: EvidenceDevice): Dossier = Dossier(evidence)

/** [Dossier] at a parameter. */
fun dossierKind(dossier: Dossier): String = dossier.evidence::class.simpleName ?: "unknown"

// --- Erased positions: Box<V> -----------------------------------------------------------------

/** `Box<Monogram>` out. */
fun boxedMonogram(): Box<Monogram> = Box(Monogram('O'))

/** `Box<Monogram>` in: the re-wrap runs `init`. */
fun monogramInBox(box: Box<Monogram>): Char = box.value.letter

/** `Box<MaybeMonogram?>`: `MaybeMonogram(null)` or no monogram at all. */
fun boxedMaybeMonogram(present: Boolean): Box<MaybeMonogram?> =
  Box(if (present) MaybeMonogram(null) else null)

/** `Box<MaybeMonogram?>` in. */
fun maybeMonogramInBox(box: Box<MaybeMonogram?>): String =
  box.value.let { if (it == null) "no monogram" else it.letter?.toString() ?: "unsigned" }

/** `Box<Sticker<Char>>` out. */
fun boxedCharSticker(): Box<Sticker<Char>> = Box(Sticker('M'))

/** `Box<Clue>` out. */
fun boxedClue(address: String): Box<Clue> = Box(Clue(NearbyDevice("attic", address)))

/** `Box<Clue>` in. */
fun clueInBox(box: Box<Clue>): String = box.value.device.address

/** `Box<Dossier>` out. */
fun boxedDossier(): Box<Dossier> = Box(Dossier(SavedDevice))

/** `Box<Dossier>` in. */
fun dossierInBox(box: Box<Dossier>): String = box.value.evidence::class.simpleName ?: "unknown"

/** `Box<TreatCount>` out, uncounted or counted. */
fun boxedTreats(count: Int?): Box<TreatCount> = Box(TreatCount(count))

/** `Box<TreatCount>` in. */
fun treatsInBox(box: Box<TreatCount>): String = box.value.count?.toString() ?: "uncounted"

/** `Box<TreatCount?>`: `TreatCount(null)` or no count at all. */
fun boxedTreatsOrNone(present: Boolean): Box<TreatCount?> =
  Box(if (present) TreatCount(null) else null)

/** `Box<TreatCount?>` in. */
fun treatsOrNoneInBox(box: Box<TreatCount?>): String =
  box.value.let { if (it == null) "no count" else it.count?.toString() ?: "uncounted" }

/**
 * The `Flow`, `MutableStateFlow` and `suspend` halves, members on a class so each route is the
 * ordinary member route and only the value class is new.
 */
class WideningCourier(val desk: String) {

  /** A member's star-projected result. */
  fun anyPeel(): Sticker<*> = Sticker(desk)

  /** `Flow<Monogram>`. */
  fun monograms(): Flow<Monogram> = flowOf(Monogram('O'), Monogram('M'))

  /** `Flow<MaybeMonogram?>`: signed, unsigned, none. */
  fun maybeMonograms(): Flow<MaybeMonogram?> =
    flowOf(MaybeMonogram('O'), MaybeMonogram(null), null)

  /** `Flow<Sticker<Char>>`. */
  fun charTrail(): Flow<Sticker<Char>> = flowOf(Sticker('O'), Sticker('M'))

  /** `Flow<Clue>`. */
  fun cluesFlow(): Flow<Clue> = flowOf(Clue(NearbyDevice("desk", "aa:bb")), Clue(SavedDevice))

  /** `Flow<Dossier>`. */
  fun dossiers(): Flow<Dossier> = flowOf(Dossier(SavedDevice))

  /** `Flow<TreatCount?>`: counted, uncounted, none. */
  fun treatLog(): Flow<TreatCount?> = flowOf(TreatCount(3), TreatCount(null), null)

  /** `MutableStateFlow<TreatCount>`, written from C#. */
  val treatBoard: MutableStateFlow<TreatCount> = MutableStateFlow(TreatCount(null))

  /** `MutableStateFlow<Monogram>`, written from C#: a space runs `init` on the re-wrap. */
  val monogramBoard: MutableStateFlow<Monogram> = MutableStateFlow(Monogram('O'))

  /** `MutableStateFlow<Clue>`, written from C#. */
  val clueBoard: MutableStateFlow<Clue> = MutableStateFlow(Clue(SavedDevice))

  /** Awaited `Monogram`. */
  suspend fun monogramLater(): Monogram {
    yield()
    return Monogram('M')
  }

  /** Awaited `MaybeMonogram?`. */
  suspend fun maybeMonogramLater(present: Boolean): MaybeMonogram? {
    yield()
    return if (present) MaybeMonogram(null) else null
  }

  /** Awaited `Sticker<Char>`. */
  suspend fun charLater(): Sticker<Char> {
    yield()
    return Sticker('O')
  }

  /** Awaited `Clue`. */
  suspend fun clueLater(address: String): Clue {
    yield()
    return Clue(NearbyDevice("desk", address))
  }

  /** Awaited `Dossier`. */
  suspend fun dossierLater(): Dossier {
    yield()
    return Dossier(SavedDevice)
  }

  /** Awaited `TreatCount?`: `TreatCount(null)` or `null`. */
  suspend fun treatsLater(present: Boolean): TreatCount? {
    yield()
    return if (present) TreatCount(null) else null
  }

  /** Awaited `TreatCount`, counted. */
  suspend fun countLater(count: Int): TreatCount {
    yield()
    return TreatCount(count)
  }
}
