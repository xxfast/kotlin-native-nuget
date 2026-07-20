package io.github.xxfast.kotlin.native.nuget.test.models

/**
 * ADR-066's `Modifier.VALUE`/`Modifier.INLINE` regression guard, isolated from the
 * `CharSequence`-delegation concern ([StoryUri] covers that separately so the two failure modes
 * don't get conflated in one type). The forward-verification spike found that a cross-module
 * value class reports `Modifier.INLINE`, never `Modifier.VALUE` — every existing
 * `Modifier.VALUE`-only test site would misclassify this as an ordinary class and export it as an
 * opaque `IDisposable` handle instead of an unwrapped value. Deliberately plain: no supertype, no
 * extra members, so a failing assertion here means exactly one thing.
 */
value class StoryCode(val value: String)
