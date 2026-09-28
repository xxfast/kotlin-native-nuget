package io.github.xxfast.kotlin.native.nuget.test.nested

/**
 * ADR-133 amendment (2026-09-28): a nested type's base list is `global::`-qualified.
 *
 * [Collar] declares a nested interface spelled the same as itself, so the C# is
 * `public interface ICollar { public interface ICollar { } public class Impl : ... }`. Inside the
 * outer body a bare `ICollar` binds to the nested `ICollar.ICollar`, so [Collar.Impl]'s base list
 * must read `global::TestLibrary.Nested.ICollar`. Spelled bare it still *built*, but `ICollar.Impl`
 * implemented the wrong interface and the consumer's `Tagging.LevelOf(Tagging.MakeImpl(5))` was
 * CS1503: the silent shape.
 *
 * The supertype is fully qualified on the Kotlin side for the same reason: inside [Collar]'s body a
 * bare `Collar` resolves to the nested one in Kotlin too.
 *
 * Not named `Marker`: `NestedClassGateTests` forbids a namespace-root type of that name, because
 * `ProbeOuter.Marker` must exist only nested.
 *
 * Oreo wears the outer collar; Mylo's has the nested one, and nobody can tell them apart.
 */
interface Collar {
  interface Collar

  fun level(): Int

  class Impl(val n: Int) : io.github.xxfast.kotlin.native.nuget.test.nested.Collar {
    override fun level(): Int = n
  }
}

fun makeImpl(level: Int): Collar.Impl = Collar.Impl(level)

fun levelOf(collar: io.github.xxfast.kotlin.native.nuget.test.nested.Collar): Int = collar.level()
