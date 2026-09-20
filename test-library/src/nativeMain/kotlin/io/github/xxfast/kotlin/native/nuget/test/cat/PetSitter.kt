package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

  private val onWatch: MutableStateFlow<Pet?> = MutableStateFlow(null)

  /** Interface-typed parameter: a C#-implemented [Pet] crosses via the ADR-084 bridge factory. */
  fun take(pet: Pet) {
    ward = pet
    onWatch.value = pet
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

  // --- The NULLABLE twins of the two reads above (ROADMAP: the nullable arms of
  // `legacyInterfaceRead` and `legacyInterfaceElementReadArgument`). Same sitter, same `Dog`, but
  // the sitter may be empty: an empty cat basket has to arrive in C# as `null`, and a full one has
  // to arrive as the SAME instance the consumer dropped off (ADR-136's identity rule, now through
  // the nullable arm). ---

  /**
   * Nullable suspend read: `Task<IPet?>`. `yield()` makes the completion a real callback, so the
   * null crosses on the completion path (`IntPtr.Zero`), not inline.
   */
  suspend fun handBackLaterOrNull(): Pet? {
    yield()
    return ward
  }

  /**
   * `StateFlow<Pet?>` at a PROPERTY: the nullable element arm at the first of the generator's two
   * call sites. Starts null (nobody booked in), flips to whoever [take] dropped off.
   */
  val watching: StateFlow<Pet?> = onWatch.asStateFlow()

  /**
   * The same StateFlow at a non-suspend METHOD return: the generator computes `isNullableElement`
   * separately here, so this is a distinct call site rather than a spelling of the property.
   */
  fun watchingNow(): StateFlow<Pet?> = onWatch.asStateFlow()
}
