package io.github.xxfast.kotlin.native.nuget.test.cat

enum class Mood {
  HAPPY,
  SLEEPY,
  GRUMPY;

  val description: String
    get() = when (this) {
      HAPPY -> "The cat is happy and content."
      SLEEPY -> "The cat is sleepy and ready for a nap."
      GRUMPY -> "The cat is grumpy and doesn't want to be disturbed."
    }
}
