package io.github.xxfast.kotlin.native.nuget.test.cat

interface CatEventListener {
  fun onMeow(message: String)
  fun onPurr()
}
