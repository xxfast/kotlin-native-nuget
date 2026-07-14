package io.github.xxfast.kotlin.native.nuget.sample.structs

import sample.enums.CatMood
import sample.nested.Nurseries
import sample.nested.Nursery
import sample.structs.Collar
import sample.structs.Extent
import sample.structs.Litter
import sample.structs.Litters
import sample.structs.Nest
import sample.structs.Point
import sample.structs.Profile
import sample.structs.Shelter

// ADR-059: nested struct components — a struct field inside a struct, flattened recursively on
// the wire (depth-first, pre-order) but reassembled as a NESTED Kotlin `data class` on the
// surface. Litter mixes a component that needs marshalling conversion (Profile: string/bool/
// char/enum) with one that does not (Extent: direct ints), so a generator that open-codes
// conversion at the wrong nesting level, or forgets to convert a leaf reached through a path,
// fails here rather than in an all-int fixture that would go green by coincidence.
//
//   C# SampleApp.Tests
//     -> (forward bridge, Interop.cs)        NestedSample.* functions in this file
//       -> Kotlin sample-library             NestedSample.kt (this file)
//         -> (reverse bridge, ADR-059)       sample.structs.{Litter,Nest,Litters,Shelter},
//                                            sample.nested.{Nursery,Nurseries}
//           -> real C# SampleDependency NuGet Sample.Structs.{Litter,Nest,Litters,Shelter},
//                                            Sample.Nested.{Nursery,Nurseries}
//
// Nursery deliberately lives in a DIFFERENT C# namespace (Sample.Nested, not Sample.Structs), and
// therefore a different Kotlin package (sample.nested, not sample.structs): its `litter`
// component is a `sample.structs.Litter`. This is the only fixture shape that exercises the two
// cross-package import sites nesting creates (a Kotlin `import sample.structs.Litter` in the
// generated Nursery.kt, and a C# `using Sample.Structs;` in the generated shim) — both broken on
// `main` today (structFileContent renders a nested component's type as a bare, unimported name).
//
// `Litters.Merge(Litter, Litter)` is a deliberate ADVERSARIAL negative (8 + 8 in, 8 out = 24 ABI
// arguments, over the 22-argument CFunction ceiling) and must be SKIPPED by the generator with a
// `skipped_abi_arity_limit` diagnostic. It is intentionally NOT called here: it must not exist in
// the generated Kotlin bindings at all, while the rest of `Litters` still binds.
//
// The forward-exported functions below stay in the existing String/Int/Boolean vocabulary
// (ADR-048/006) rather than exporting Litter/Nest/Nursery themselves: mapping a multi-component
// nested data class across the FORWARD boundary is ADR-014's concern and out of scope here.
//
// These functions will not compile against `sample-library` until kotlin-dev lands the
// fixed-point struct classification (reader) and the recursive abiArgs/structConstruction
// (generators) ADR-059 specifies. That is the intended TDD failure mode — do not weaken these
// assertions, or drop the nested calls, to make the file compile early.

// Nested struct in (Litter: 8 leaves), string out. Exercises the full converted vocabulary
// (Mother: string/bool/char/enum) alongside direct ints (Basket) and a second, DIFFERENT enum
// value at the outer level (Mood), so Mother.mood and Mood must render differently.
fun litterSummary(tag: String, count: Int): String =
  Litters.describe(
    Litter(
      mother = Profile(tag, true, 'A', CatMood.PLAYFUL),
      basket = Extent(3, 4),
      count = count,
      mood = CatMood.CALM,
    ),
  )

// Nested struct in AND out: reassembled from a flat out-pointer list back into a nested data
// class. Distinct values per leaf, so a DFS-order bug cannot pass by coincidence.
fun growLitter(tag: String, count: Int, by: Int): String {
  val grown: Litter =
    Litters.grow(
      Litter(Profile(tag, true, 'A', CatMood.PLAYFUL), Extent(3, 4), count, CatMood.CALM), by,
    )
  return "${grown.mother.tag}|${grown.mother.grade}|${grown.mother.mood}|" +
      "${grown.basket.width}x${grown.basket.height}|${grown.count}|${grown.mood}"
}

// DEPTH 2, both directions, and ACROSS a Kotlin package boundary: Nursery is `sample.nested`,
// its `litter` component is `sample.structs`. Compiles only if the generated Nursery.kt imports
// it.
fun rehome(tag: String, room: Int): String {
  val moved: Nursery =
    Nurseries.rehome(
      Nursery(
        Litter(Profile(tag, false, 'Z', CatMood.SLEEPY), Extent(1, 2), 3, CatMood.CALM), room,
      ),
    )
  return "${moved.litter.mother.tag}/${moved.litter.basket.height}/${moved.litter.count}/${moved.room}"
}

// Two struct parameters of different nesting depth in one signature (Litter: 8 leaves, Extent:
// 2 leaves) — the shape where an abiArgs expansion bug shows up as a misaligned argument.
fun compareLitter(tag: String, w: Int, h: Int): String =
  Litters.compare(
    Litter(Profile(tag, true, 'A', CatMood.CALM), Extent(2, 3), 1, CatMood.CALM), Extent(w, h),
  )

// Members on a nesting struct: a computed property (Summary, reconstructed receiver), an
// instance method returning the struct itself (Grow), and a companion static factory (Single).
fun litterMembers(tag: String): String {
  val l = Litter(Profile(tag, true, 'A', CatMood.PLAYFUL), Extent(2, 3), 4, CatMood.CALM)
  return "${l.summary}|${l.grow(1).count}|${Litter.single(tag).count}"
}

// Shape B outer, all three nested component sources (Collar via field, Centre via settable
// auto-prop, Bounds via init-only auto-prop), through a HANDLE class's settable struct property:
// setter = flattened in-args, getter = flattened out-pointers.
fun shelterNest(colour: String, x: Int, y: Int): String = Shelter().use { shelter ->
  shelter.current = Nest(
    collar = Collar(2, colour, true, 'B', CatMood.PLAYFUL),
    centre = Point(x, y),
    bounds = Extent(5, 6),
    lined = true,
  )
  "${shelter.current.tag}|${shelter.current.collar.colour}|${shelter.current.centre.x}"
}

// Value semantics survive nesting: equality is DEEP (delegates through every nested data class),
// and there is still nothing to close(). A change two levels down (mother.tag) is observable.
fun nestedValueEquality(): Boolean {
  val a = Litter(Profile("o", true, 'A', CatMood.CALM), Extent(1, 2), 3, CatMood.CALM)
  return a == a.copy() &&
      a.mother == a.copy().mother &&
      a != a.copy(count = 4) &&
      a != a.copy(mother = a.mother.copy(tag = "p"))
}
