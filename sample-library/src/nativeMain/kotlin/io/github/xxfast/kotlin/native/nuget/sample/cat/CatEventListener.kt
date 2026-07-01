package io.github.xxfast.kotlin.native.nuget.sample.cat

interface CatEventListener {
  fun onMeow(message: String)
  fun onPurr()
}
