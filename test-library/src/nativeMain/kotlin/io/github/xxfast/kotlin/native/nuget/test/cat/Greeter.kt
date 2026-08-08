package io.github.xxfast.kotlin.native.nuget.test.cat

/**
 * A class implementing an interface it inherits *defaulted* members from without overriding them.
 * The generated C# class declares `: IGreeter`, so it has to carry `Greeting` and `Greet()` even
 * though [Parrot] declares neither: before the shared has-superclass predicate
 * (`ForwardClassMembership.kt`), the two planners skipped both members while `CirClassTranslator`
 * still rendered the interface list, so the generated `Parrot` did not implement the interface it
 * declared (CS0535). The Tier 1 harness never compiles the generated C#, which is why this shape
 * needs a fixture the `GeneratedBindingsCheck` build actually compiles.
 *
 * The Kotlin export reaches the default body by ordinary dynamic dispatch on the instance behind
 * the handle, so no separate delegation is generated.
 */
interface Greeter {
  val greeting: String get() = "hello"

  fun greet(): String = "$greeting from a $species"

  val species: String
}

class Parrot(override val species: String) : Greeter
