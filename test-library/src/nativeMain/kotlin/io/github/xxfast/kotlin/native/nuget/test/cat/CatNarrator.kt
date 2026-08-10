package io.github.xxfast.kotlin.native.nuget.test.cat

/**
 * ADR-090 · ordinary-class method overloads.
 *
 * Two same-name methods on an exported ordinary class crash the whole KSP/`packNuget` run today
 * (`Forward callable catalog has duplicate plans for ...CatNarrator.describe`), because
 * `classEntries` names every export `"${prefix}_$name"` and keys every plan by `"$owner.$name"`.
 * This fixture is the consumer-side red step for the numbering scheme ADR-090 borrows from
 * ADR-082's value-class methods: first declaration keeps the bare export name, the n-th further
 * one gets `_$n`, and the C# surface stays one natural overload set with no visible numbering.
 *
 * Deliberately its own small class rather than a member of [Cat]: [Cat] already carries a dozen
 * unrelated seams, and a crash there would mask every other cell in the forward fixture.
 *
 * Two overload cells, both on this one class:
 *
 *  - [describe] · arity ladder (0, 1, 2 parameters). Proves both `_2` and `_3` numbering, and
 *    each body returns a distinct string so a mis-numbered export dispatches visibly wrong
 *    rather than silently returning the same text.
 *  - [rate] · same-name pair whose parameters differ in Kotlin but share **one wire shape**: an
 *    `Int` and a [Mood] both cross the C ABI as `int`. This is the pair that would emit CS0111 if
 *    only the DllImport EntryPoint carried the number and the private extern name did not (see
 *    ADR-090's "C# half"); on the public surface `Rate(int)` and `Rate(Mood)` are two perfectly
 *    ordinary C# overloads, so nothing but the extern name is at risk.
 *
 * A reference-nullability-only pair (`tag(s: String)` / `tag(s: String?)`) belongs to
 * `ERROR_CSHARP_SIGNATURE_COLLISION` and is covered in-process on the Kotlin side; it is kept out
 * of here on purpose, since its correct outcome is a *failed* generation.
 */
class CatNarrator(val name: String) {
  /** First declared [describe]: keeps the bare export name (`catnarrator_describe`). */
  fun describe(): String = "$name is a cat"

  /** Second declared [describe]: numbered `catnarrator_describe_2`, still `Describe` in C#. */
  fun describe(prefix: String): String = "$prefix $name"

  /** Third declared [describe]: numbered `catnarrator_describe_3`, still `Describe` in C#. */
  fun describe(prefix: String, excited: Boolean): String =
    if (excited) "$prefix $name!!!" else "$prefix $name, quietly"

  /** First declared [rate]: `Int` parameter, crosses as `int`. */
  fun rate(stars: Int): String = "$name rated $stars"

  /**
   * Second declared [rate]: [Mood] parameter, which crosses as the same `int` ordinal as
   * [rate]'s `Int` overload. Same wire shape, different Kotlin and C# signatures.
   */
  fun rate(mood: Mood): String = "$name is ${mood.name.lowercase()}"
}
