package io.github.xxfast.kotlin.native.nuget.test.garage

/**
 * ADR-075 / ADR-101 amendment (2026-09-11): the other half. [tally] is a generic interface
 * default. ADR-237 erases its `T`, so `IRegister` declares `object? Tally(object?)` and [Vault]
 * implements it explicitly; the abstract method walk still never renders
 * `public abstract T Tally(T)`. `: IRegister` stays.
 */
interface Register {
  fun <T> tally(row: T): T = row
}

abstract class Vault : Register

class StrongRoom : Vault()
