package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield

/**
 * ADR-136: the async twins of [Cat.closestFriend], the read that already resolves a
 * C#-implemented [Pet] back to the original instance through the ADR-084 bridge token.
 *
 * A sitter rather than another member on [Cat], because [Cat] extends `Animal` and the generated
 * bindings do not compile for a `suspend fun` or a `Flow` member on a class with a Kotlin
 * superclass (no `_scopeHandle` / `GetOrCreateScope` is emitted on that path). That is a separate
 * generator bug; this class has no superclass, so it exercises exactly the two reads this ADR is
 * about and nothing else.
 *
 * Oreo and Mylo get dropped off, and whoever picks them up should get the same cat back.
 */
class PetSitter {
  private var ward: Pet? = null

  /** Interface-typed parameter: a C#-implemented [Pet] crosses via the ADR-084 bridge factory. */
  fun take(pet: Pet) {
    ward = pet
  }

  /** The name of whoever is currently booked in, so a test can prove [take] landed. */
  fun wardName(): String = ward?.name ?: "nobody"

  /**
   * The suspend read. `yield()` makes it a real suspension, so the stored pet crosses back on the
   * completion callback rather than inline.
   */
  suspend fun handBackLater(): Pet {
    yield()
    return requireNotNull(ward) { "nobody has been dropped off yet" }
  }

  /**
   * The Flow read of the same pet: one element per collection, so the element read is the
   * `KotlinFlow<T>` `read:` delegate rather than a completion callback.
   */
  fun wards(): Flow<Pet> = flow { emit(requireNotNull(ward) { "nobody has been dropped off yet" }) }
}
