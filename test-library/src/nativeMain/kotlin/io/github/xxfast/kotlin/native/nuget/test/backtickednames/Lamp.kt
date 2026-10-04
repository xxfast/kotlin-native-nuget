package io.github.xxfast.kotlin.native.nuget.test.backtickednames

/**
 * Kotlin names that need backticks. A hard keyword binds under its PascalCase C# name with the
 * generated Kotlin call backticked, on the plan route and on the legacy `suspend` route. Every
 * member returns which body ran, so a call reaching the wrong one reads back the wrong text.
 *
 * No member here is named with a space: Kotlin/Native's own shared-library C API (`api.cpp`)
 * spells a public member `const char* (*burn down)(...)` and the link fails, whatever the bridge
 * does with it, so that shape is pinned in Tier 1 only (`Tier1BacktickedNameRoutesTest`).
 *
 * Oreo switches the lamp on; Mylo waits for it to warm up.
 */
class Lamp {
  /** A keyword member with a keyword parameter, on the ordinary plan route. */
  fun `in`(`object`: Int): String = "in:${`object`}"

  /** A keyword member on the legacy `suspend` route. */
  suspend fun `is`(`fun`: String): String = "is:${`fun`}"
}
