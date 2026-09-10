package io.github.xxfast.kotlin.native.nuget.test.garage

/**
 * ADR-075 / ADR-101 amendment (2026-09-11): the other half. [tally] is a generic interface
 * default, so the planner declines it structurally (GENERIC) and it reaches no plan. It still has
 * a body, so the abstract method walk must drop it from [Vault] rather than render
 * `public abstract T Tally(T)`, which is CS0246 in the generated file and CS0534 on any further
 * C# subclass. `: IRegister` stays.
 */
interface Register {
  fun <T> tally(row: T): T = row
}

abstract class Vault : Register

class StrongRoom : Vault()
