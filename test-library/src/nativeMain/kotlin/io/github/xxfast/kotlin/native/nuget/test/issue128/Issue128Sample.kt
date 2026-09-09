package io.github.xxfast.kotlin.native.nuget.test.issue128

import io.github.xxfast.kotlin.native.nuget.test.models.CatteryInternalApi
import io.github.xxfast.kotlin.native.nuget.test.models.Grooming

/**
 * Fixture for [#128](https://github.com/xxfast/kotlin-native-nuget/issues/128): a parameter whose
 * **type** is opt-in-marked makes *every* arity of the callable illegal, so ADR-096's
 * trailing-omitting overload cannot repair the constructor ADR-115 dropped.
 *
 * ADR-115 already skips the declared arity of [GroomingPlan]'s constructor, because `grooming` is
 * typed with a marked class. ADR-096 then synthesized `GroomingPlan(name)` and `GroomingPlan()`,
 * and those do not compile either: Kotlin propagates the opt-in requirement from the callee's
 * declared value-parameter types, at every arity, regardless of what the default expression reads
 * (verified against Kotlin 2.4.10; `DefaultReadsMarked(val n: Int = Mode.Fast.ordinal)` is fine,
 * `Mixed(a = 5)` where `a`'s sibling is marked-typed is not). So this file is **red before the
 * fix**, and it is red in the strongest available way: `nugetGen` emits `GroomingPlan(name)`,
 * `compileKotlin` rejects it, `packNuget` fails, and `IntegrationTests` cannot build.
 *
 * No `@file:OptIn` and no `optIn` compiler flag: this module builds exactly as a consumer's does
 * (issue #128 requirement 3). Only the two declarations that genuinely name [Grooming] opt in, and
 * a per-declaration `@OptIn` is enough for that.
 *
 * ## Cells
 *
 * | # | Shape | What it pins |
 * | - | ----- | ------------ |
 * | 1 | [GroomingPlan] | every constructor arity goes; the type is factory-only in C# |
 * | 2 | [GroomingPlan.name] | the unmarked sibling property survives the skip |
 * | 3 | [plan] | the factory keeps the type reachable, which is what makes the bad call reachable |
 * | 4 | [schedule] | the function half of requirement 4, not just constructors |
 * | 5 | [GroomingLog] | over-skip guard: a marked PROPERTY on an unmarked type keeps its shorter arity (ADR-115 gate (b)) |
 *
 * Cell 5 is the control that matters. `@property:`-marked parameters are legal to omit (verified:
 * `PropMarked(5)` and `PropMarked()` both compile from a non-opting file), so a fix that keys on
 * the marker rather than on the parameter's *type* would delete a constructor that works today,
 * and no absence assertion could notice.
 */
@OptIn(CatteryInternalApi::class)
data class GroomingPlan(
  /** Control: unmarked type, defaulted, and must stay a C# property. */
  val name: String = "Oreo",
  /** The bug: a marked TYPE, trailing and defaulted, so ADR-096 used to synthesize around it. */
  val grooming: Grooming = Grooming.DAILY,
)

/**
 * Cell 3: keeps [GroomingPlan] in the export closure, and gives C# the only way to hold one. The
 * issue's last paragraph notes the failure is only reachable once the type is exported at all.
 */
@OptIn(CatteryInternalApi::class)
fun plan(): GroomingPlan = GroomingPlan()

/** Cell 4: the same rule on a function. No arity of this is exported either. */
@OptIn(CatteryInternalApi::class)
fun schedule(name: String = "Oreo", grooming: Grooming = Grooming.DAILY): String =
  "$name/${grooming.name.lowercase()}"

/**
 * Cell 5: the marker sits on the *property*, and the parameter's type is a plain `String`, so
 * omitting it is legal Kotlin and the shorter arities must survive. Two C# constructors:
 * `GroomingLog(string note)` and `GroomingLog()`. The declared two-argument one is dropped by
 * ADR-115, exactly as it was before this fix.
 */
data class GroomingLog(
  val note: String = "clean",
  // `@property:`, not the default target: [CatteryInternalApi] declares no `@Target`, and a
  // marker may not sit on a value parameter (Kotlin 2.4.10 rejects it outright).
  @property:CatteryInternalApi val ledger: String = "ledger-0001",
)
