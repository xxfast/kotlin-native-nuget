package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-234: the value class underlyings the earlier rules left out bind at every position, on both
 * halves:
 * - a star- or `out`-projected generic value class at a result takes a caller-supplied method type
 *   parameter (`Sticker<TSticker> AnySticker<TSticker>()`), the rule a star parameter follows;
 * - a value class over an ineligible sealed interface (`Clue`) or an eligible sealed class
 *   (`Dossier`) crosses at its erased positions: a `Box`, a `Flow` element, a suspend result and
 *   a `MutableStateFlow` write;
 * - a value class over `Char` (`Monogram`) and `Char?` (`MaybeMonogram`), and the closed
 *   `Sticker<Char>`, cross at every ordinary and erased position;
 * - a value class over `Int?` (`TreatCount`) is writable through a `MutableStateFlow`;
 * - a property typed with a `Char` value class, either nullability, or with a non-null value class
 *   over a nullable underlying (`MaybeMonogram`, `TreatCount`), binds on its own box.
 *
 * Each cell is pinned twice: no member of the fixture skips, and a consumer touching every C#
 * member it should produce compiles against the generated `Interop.cs`.
 */
class Tier1ValueClassUnderlyingWideningTest {

  private val fixture: String = """
    package tier1.widen

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.flowOf

    open class Animal(val name: String)

    class Cat(name: String) : Animal(name)

    class Box<T>(val value: T)

    @JvmInline
    value class Sticker<T>(val value: T)

    sealed class EvidenceDevice

    sealed interface ConnectableDevice {
      val address: String
    }

    data class NearbyDevice(override val address: String) : EvidenceDevice(), ConnectableDevice

    data object SavedDevice : EvidenceDevice(), ConnectableDevice {
      override val address: String get() = "saved"
    }

    @JvmInline
    value class Monogram(val letter: Char) {
      init {
        require(letter != ' ') { "blank" }
      }

      fun shout(): String = "${'$'}letter!"

      val lower: Char get() = letter.lowercaseChar()
    }

    @JvmInline
    value class MaybeMonogram(val letter: Char?)

    @JvmInline
    value class Clue(val device: ConnectableDevice)

    @JvmInline
    value class Dossier(val evidence: EvidenceDevice)

    @JvmInline
    value class TreatCount(val count: Int?)

    fun anySticker(): Sticker<*> = Sticker(7)

    fun animalSticker(name: String): Sticker<out Animal> = Sticker(Cat(name))

    fun anyStickerOrNone(present: Boolean): Sticker<*>? = if (present) Sticker("Oreo") else null

    fun oreoMonogram(): Monogram = Monogram('O')

    fun monogramOf(monogram: Monogram): String = "signed ${'$'}{monogram.letter}"

    fun maybeSign(monogram: Monogram?): Monogram? = monogram

    fun signAll(letters: List<Monogram>): List<Monogram> = letters.reversed()

    fun signedBy(owners: Map<Monogram, String>): String = owners.values.joinToString()

    fun unsigned(): MaybeMonogram = MaybeMonogram(null)

    fun monogramOr(monogram: MaybeMonogram): Char = monogram.letter ?: '?'

    fun maybeUnsigned(present: Boolean): MaybeMonogram? = if (present) MaybeMonogram(null) else null

    fun unsignedState(monogram: MaybeMonogram?): String = monogram?.letter?.toString() ?: "none"

    fun monogramRoll(letters: List<MaybeMonogram>): List<MaybeMonogram> = letters

    fun charSticker(): Sticker<Char> = Sticker('M')

    fun charOf(sticker: Sticker<Char>): Char = sticker.value

    fun charStickers(): List<Sticker<Char>> = listOf(Sticker('O'))

    fun clueAt(address: String): Clue = Clue(NearbyDevice(address))

    fun clueAddress(clue: Clue): String = clue.device.address

    fun fileDossier(evidence: EvidenceDevice): Dossier = Dossier(evidence)

    fun boxedMonogram(): Box<Monogram> = Box(Monogram('O'))

    fun monogramInBox(box: Box<Monogram>): Char = box.value.letter

    fun boxedMaybeMonogram(present: Boolean): Box<MaybeMonogram?> =
      Box(if (present) MaybeMonogram(null) else null)

    fun maybeMonogramInBox(box: Box<MaybeMonogram?>): String = box.value?.letter?.toString() ?: "-"

    fun boxedCharSticker(): Box<Sticker<Char>> = Box(Sticker('M'))

    fun boxedClue(address: String): Box<Clue> = Box(Clue(NearbyDevice(address)))

    fun clueInBox(box: Box<Clue>): String = box.value.device.address

    fun boxedDossier(): Box<Dossier> = Box(Dossier(SavedDevice))

    fun boxedTreatsOrNone(present: Boolean): Box<TreatCount?> =
      Box(if (present) TreatCount(null) else null)

    class WideningCourier(val desk: String) {
      var initial: Monogram = Monogram('O')

      var maybeInitial: Monogram? = null

      var signature: MaybeMonogram = MaybeMonogram(null)

      var maybeSignature: MaybeMonogram? = null

      var treats: TreatCount = TreatCount(null)

      fun anyPeel(): Sticker<*> = Sticker(desk)

      fun monograms(): Flow<Monogram> = flowOf(Monogram('O'))

      fun maybeMonograms(): Flow<MaybeMonogram?> = flowOf(MaybeMonogram(null), null)

      fun charTrail(): Flow<Sticker<Char>> = flowOf(Sticker('O'))

      fun cluesFlow(): Flow<Clue> = flowOf(Clue(SavedDevice))

      fun dossiers(): Flow<Dossier> = flowOf(Dossier(SavedDevice))

      fun treatLog(): Flow<TreatCount?> = flowOf(TreatCount(3), null)

      val treatBoard: MutableStateFlow<TreatCount> = MutableStateFlow(TreatCount(null))

      val monogramBoard: MutableStateFlow<Monogram> = MutableStateFlow(Monogram('O'))

      val clueBoard: MutableStateFlow<Clue> = MutableStateFlow(Clue(SavedDevice))

      suspend fun monogramLater(): Monogram = Monogram('M')

      suspend fun maybeMonogramLater(present: Boolean): MaybeMonogram? =
        if (present) MaybeMonogram(null) else null

      suspend fun charLater(): Sticker<Char> = Sticker('O')

      suspend fun clueLater(address: String): Clue = Clue(NearbyDevice(address))

      suspend fun dossierLater(): Dossier = Dossier(SavedDevice)

      suspend fun treatsLater(present: Boolean): TreatCount? = if (present) TreatCount(null) else null
    }
  """.trimIndent()

  private val consumer: String = """
    using System.Collections.Generic;
    using System.Threading.Tasks;
    using Tier1;

    public static class Consumer
    {
        public static async Task Touch(WideningCourier courier, NearbyDevice nearby)
        {
            Sticker<int> any = Widen.AnySticker<int>();
            Sticker<Cat> cat = Widen.AnimalSticker<Cat>("Mylo");
            Sticker<string>? maybe = Widen.AnyStickerOrNone<string>(true);
            Sticker<string> peel = courier.AnyPeel<string>();

            Monogram oreo = Widen.OreoMonogram();
            char letter = oreo.Letter;
            string signed = Widen.MonogramOf(new Monogram('M'));
            Monogram? sign = Widen.MaybeSign(null);
            IReadOnlyList<Monogram> all = Widen.SignAll(new[] { new Monogram('O') });
            string by = Widen.SignedBy(new Dictionary<Monogram, string> { [new Monogram('O')] = "Oreo" });
            string shout = oreo.Shout();
            char lower = oreo.Lower;

            MaybeMonogram blank = Widen.Unsigned();
            char? blankLetter = blank.Letter;
            char or = Widen.MonogramOr(new MaybeMonogram(null));
            MaybeMonogram? none = Widen.MaybeUnsigned(false);
            string state = Widen.UnsignedState(null);
            IReadOnlyList<MaybeMonogram> roll = Widen.MonogramRoll(new[] { new MaybeMonogram('O') });

            Sticker<char> charSticker = Widen.CharSticker();
            char charOf = Widen.CharOf(new Sticker<char>('O'));
            IReadOnlyList<Sticker<char>> charStickers = Widen.CharStickers();

            Clue clue = Widen.ClueAt("aa:bb");
            IConnectableDevice device = clue.Device;
            string address = Widen.ClueAddress(new Clue(nearby));
            Dossier dossier = Widen.FileDossier(nearby);

            using Box<Monogram> boxedMonogram = Widen.BoxedMonogram();
            char inBox = Widen.MonogramInBox(boxedMonogram);
            using Box<MaybeMonogram?> boxedMaybe = Widen.BoxedMaybeMonogram(true);
            string maybeInBox = Widen.MaybeMonogramInBox(boxedMaybe);
            using Box<Sticker<char>> boxedChar = Widen.BoxedCharSticker();
            using Box<Clue> boxedClue = Widen.BoxedClue("ee:ff");
            string clueInBox = Widen.ClueInBox(boxedClue);
            using Box<Dossier> boxedDossier = Widen.BoxedDossier();
            using Box<TreatCount?> boxedTreats = Widen.BoxedTreatsOrNone(true);

            await foreach (Monogram m in courier.Monograms()) { }
            await foreach (MaybeMonogram? m in courier.MaybeMonograms()) { }
            await foreach (Sticker<char> s in courier.CharTrail()) { }
            await foreach (Clue c in courier.CluesFlow()) { }
            await foreach (Dossier d in courier.Dossiers()) { }
            await foreach (TreatCount? t in courier.TreatLog()) { }

            courier.Initial = new Monogram('M');
            courier.MaybeInitial = null;
            Monogram? maybeInitial = courier.MaybeInitial;
            courier.Signature = new MaybeMonogram(null);
            courier.MaybeSignature = new MaybeMonogram('O');
            MaybeMonogram? maybeSignature = courier.MaybeSignature;
            courier.TreatBoard.Value = new TreatCount(5);
            courier.MonogramBoard.Value = new Monogram('M');
            courier.ClueBoard.Value = new Clue(nearby);

            Monogram later = await courier.MonogramLaterAsync();
            MaybeMonogram? maybeLater = await courier.MaybeMonogramLaterAsync(true);
            Sticker<char> charLater = await courier.CharLaterAsync();
            Clue clueLater = await courier.ClueLaterAsync("aa:bb");
            Dossier dossierLater = await courier.DossierLaterAsync();
            TreatCount? treatsLater = await courier.TreatsLaterAsync(true);
        }
    }
  """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      fixture,
      fileName = "Widen.kt",
      processorOptions = mapOf("nuget.namespace" to "Tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  @Test
  fun `every widened underlying compiles clean on the Kotlin half`() {
    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected the fixture to compile; got: ${result.compileErrors}")
  }

  @Test
  fun `no member of the fixture skips`() {
    val skipped: List<String> = result.kspWarnings.filter { warning -> "[nuget:SKIPPED_" in warning }
    assertTrue(skipped.isEmpty(), "expected every member bound; skipped=$skipped")
  }

  @Test
  fun `a consumer of every widened position compiles against the generated C#`() {
    val build: Tier1CSharpBuild = Tier1CSharpCompile.compile(result, consumer, allowUnsafe = true)
    assertTrue(build.succeeded, "expected the consumer to compile; log:\n${build.log}")
  }
}
