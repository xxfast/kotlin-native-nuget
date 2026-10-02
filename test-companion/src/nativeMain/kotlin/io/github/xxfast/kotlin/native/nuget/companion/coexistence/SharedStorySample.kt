package io.github.xxfast.kotlin.native.nuget.companion.coexistence

import io.github.xxfast.kotlin.native.nuget.test.models.TopStory

/** ADR-109: both publishers admit this dependency type under their own generated namespace. */
fun sharedStory(): TopStory = TopStory("Shared story", 1, null)
