package io.github.xxfast.kotlin.native.nuget.test.cat

/**
 * Focused property-position fixture for the forward-marshalling migration.
 *
 * [Cat] already exercises class properties for every ordinary category. This file fills the
 * missing top-level, extension, and companion positions without adding more unrelated Cat state.
 */
class PropertyProbe {
  private var observedAge: Int? = null
  private var observedAgeReads: Int = 0

  /**
   * The legacy nullable-primitive property ABI asks once whether a value exists and, when it does,
   * asks again for the value. The counter makes that shipped two-call contract observable.
   */
  var age: Int?
    get() {
      observedAgeReads += 1
      return observedAge
    }
    set(value) {
      observedAge = value
    }

  val ageReadCount: Int get() = observedAgeReads

  fun resetAgeReadCount() {
    observedAgeReads = 0
  }

  companion object {
    var sharedAge: Int? = null
    var sharedNickname: String? = null
    var sharedBuddy: Cat? = null
    var sharedMood: Mood = Mood.SLEEPY
    val sharedTags: List<String> = listOf("clinic", "priority")
  }
}

private data class ExtensionPropertyState(
  var age: Int? = null,
  var nickname: String? = null,
  var buddy: Cat? = null,
  var mood: Mood = Mood.SLEEPY,
)

private val extensionPropertyStates: MutableMap<PropertyProbe, ExtensionPropertyState> = mutableMapOf()

private fun PropertyProbe.extensionPropertyState(): ExtensionPropertyState =
  extensionPropertyStates.getOrPut(this, ::ExtensionPropertyState)

var PropertyProbe.extensionAge: Int?
  get() = extensionPropertyState().age
  set(value) {
    extensionPropertyState().age = value
  }

var PropertyProbe.extensionNickname: String?
  get() = extensionPropertyState().nickname
  set(value) {
    extensionPropertyState().nickname = value
  }

var PropertyProbe.extensionBuddy: Cat?
  get() = extensionPropertyState().buddy
  set(value) {
    extensionPropertyState().buddy = value
  }

var PropertyProbe.extensionMood: Mood
  get() = extensionPropertyState().mood
  set(value) {
    extensionPropertyState().mood = value
  }

val PropertyProbe.extensionTags: List<String>
  get() = listOf("extension", "property")

var topLevelBuddy: Cat? = null
var topLevelMood: Mood = Mood.SLEEPY
val topLevelTags: List<String> = listOf("top-level", "property")
