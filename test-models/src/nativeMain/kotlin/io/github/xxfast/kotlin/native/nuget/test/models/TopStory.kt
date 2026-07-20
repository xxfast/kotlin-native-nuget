package io.github.xxfast.kotlin.native.nuget.test.models

/**
 * ADR-066: the reachability-closure fixture's headline type. Lives one Gradle module away from
 * `:test-library` (this package sits under `:test-models`, where the nuget plugin is not
 * applied), so it is only visible to the forward KSP processor if the export set is a
 * reachability closure rather than `getAllFiles()`.
 *
 * [byline] is what makes this "reachable only transitively" for [Byline]: nothing in
 * `:test-library` returns a `Byline` directly, so `Byline` only enters the closure as a member of
 * an already-admitted `TopStory`. It is nullable so the closure also has to survive cross-module
 * nullability on a second hop.
 */
data class TopStory(val title: String, val rank: Int, val byline: Byline?)
