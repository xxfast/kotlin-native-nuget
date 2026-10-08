package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/** Acquisition and collection have independent gates so lifetime races need no timing guesses. */
class SuspendFlowCafe {
  private val acquisition = CompletableDeferred<Unit>()
  private val acquired = CompletableDeferred<Unit>()
  private val collection = CompletableDeferred<Unit>()
  private val collecting = CompletableDeferred<Unit>()
  private var portionAcquisitions: Int = 0
  private var portionSubscriptions: Int = 0
  private var pet: Pet = strayPet()

  val acquisitionStarted: Boolean get() = acquired.isCompleted
  val collectionStarted: Boolean get() = collecting.isCompleted
  val acquisitionCount: Int get() = portionAcquisitions
  val subscriptionCount: Int get() = portionSubscriptions

  fun releaseAcquisition() { acquisition.complete(Unit) }
  fun releaseCollection() { collection.complete(Unit) }
  fun installPet(pet: Pet) { this.pet = pet }

  suspend fun portions(): Flow<Int> {
    portionAcquisitions++
    return flow {
      portionSubscriptions++
      emit(17)
      emit(29)
      emit(43)
    }
  }
  suspend fun optionalPortions(): Flow<Int?> = flowOf(17, null, 29)
  suspend fun observations(): Flow<Observation?> =
    flowOf(Observation.Dead("Mylo stole the tuna"), null, Observation.Superposition)
  suspend fun nicknames(): Flow<String?> = flowOf("Oreo", null, "Mylo")
  suspend fun companions(): Flow<Cat?> = flowOf(Cat("Oreo"), null, Cat("Mylo"))
  suspend fun moods(): Flow<List<Mood>> =
    flowOf(listOf(Mood.GRUMPY, Mood.SLEEPY), listOf(Mood.SLEEPY))
  suspend fun tags(): Flow<List<CatId>> =
    flowOf(listOf(CatId("oreo-17"), CatId("mylo-29")), emptyList())
  suspend fun moodSet(): Flow<Set<Mood>> = flowOf(setOf(Mood.GRUMPY, Mood.SLEEPY))
  suspend fun taggedMoods(): Flow<Map<CatId, Mood>> =
    flowOf(mapOf(CatId("oreo-17") to Mood.GRUMPY, CatId("mylo-29") to Mood.SLEEPY))
  suspend fun pets(): Flow<Pet?> = flowOf(pet, null, strayPet())
  suspend fun markings(): Flow<ByteArray?> =
    flowOf(byteArrayOf(0, 127, -128, -1), null, byteArrayOf(), byteArrayOf(29))

  suspend fun acquisitionFault(): Flow<Int> = error("Oreo's acquisition failed")
  suspend fun emissionFault(): Flow<Int> = flow {
    emit(17)
    error("Mylo's emission failed")
  }

  suspend fun gated(): Flow<Int> {
    acquired.complete(Unit)
    acquisition.await()
    return flowOf(17, 29)
  }

  /** Cancelling the job does not discard a successful non-cooperative return. */
  suspend fun stubborn(): Flow<Int> {
    withContext(NonCancellable) {
      acquired.complete(Unit)
      acquisition.await()
    }
    return flowOf(17, 29)
  }

  suspend fun held(): Flow<Int> = flow {
    emit(17)
    collecting.complete(Unit)
    collection.await()
    emit(29)
  }

  suspend fun nullableCollections(): Flow<List<Int>?> = flowOf(null)

  // ADR-026 amendment (2026-10-09): a nullable acquired `Flow<T>?` awaits to `KotlinFlow<T>?`.
  suspend fun maybeNicknames(count: Int): Flow<String>? =
    if (count == 0) null else flowOf("Oreo", "Mylo")
  suspend fun maybeCompanions(count: Int): Flow<Cat>? =
    if (count == 0) null else flowOf(Cat("Oreo"), Cat("Mylo"))
}

suspend fun cafePortions(): Flow<Int> = flowOf(71, 83)
suspend fun cafeNicknames(): Flow<String?> = flowOf("Oreo", null, "Mylo")
suspend fun cafeMaybePortions(count: Int): Flow<Int>? = if (count == 0) null else flowOf(71, 83)
fun cafeAcquisitionRelease(cafe: SuspendFlowCafe): () -> Unit = { cafe.releaseAcquisition() }

sealed class SuspendFlowNap {
  abstract suspend fun dreams(): Flow<String>

  class Loaf(val name: String) : SuspendFlowNap() {
    override suspend fun dreams(): Flow<String> = flowOf("$name naps", "$name wakes")
    suspend fun purrs(): Flow<Int> = flowOf(3, 7)
  }
}

interface SuspendFlowMenu {
  suspend fun specials(): Flow<String>
}

fun houseFlowMenu(): SuspendFlowMenu = object : SuspendFlowMenu {
  override suspend fun specials(): Flow<String> = flowOf("Oreo's tuna", "Mylo's salmon")
}
