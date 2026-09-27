package io.github.xxfast.kotlin.native.nuget.test.ctorcollision

/**
 * A single-parameter public constructor must never lose to the generated internal handle
 * constructor. Every class wrapper carries `internal X(IntPtr handle)`, and because the generated
 * `Interop.cs` compiles into the consumer's own assembly, `internal` does not hide it from overload
 * resolution. Two failure modes, one class per C# overload rule that produces them:
 *
 *  - **loud (CS0121)**: the argument converts to both `T?` (lifted) and `nint` (numeric) and
 *    neither conversion is better. [LitterTray] (`int?` given a bare `5`) and [Pillow] (`short?`,
 *    which is ambiguous even given a correctly typed `(short)5`).
 *  - **silent (the handle wins, no diagnostic)**: `nint` converts implicitly to `long`, `float` and
 *    `double`, so it is the *better* target and `new FoodBowl(5)` wraps Kotlin handle `0x5`.
 *    [FoodBowl] (`long`), [WaterFountain] (`double?`) and [TallScratcher] (`double`, the derived
 *    route whose public constructor chains `: base(IntPtr.Zero)`).
 *
 * The sealed-arm route is `Issue54Shape.Circle(Double)` and the generic route is `cat.Box`; both
 * already exist, so they are not repeated here. Every class is single-parameter on purpose: only a
 * unary public constructor competes with today's unary handle constructor.
 *
 * Oreo gets the litter tray, the pillow and the tall scratcher; Mylo gets the food bowl and the
 * water fountain, and would like it noted that he got the better deal.
 */
class LitterTray(val scoops: Int?)

/**
 * `short?`: CS0121 even for a typed `(short)5`, one of two rows (`sbyte?` is the other) where a
 * correctly typed argument does not save you.
 */
class Pillow(val loft: Short?)

/** `long`: silent today, `nint` is a better conversion target than `long` for an `int` literal. */
class FoodBowl(val grams: Long)

/** `double?`: silent today, same better-target rule through the lifted conversion. */
class WaterFountain(val litres: Double?)

/** The base of the derived route. Constructed directly it is the ordinary `double` root cell. */
open class Scratcher(val height: Double)

/** The derived route: its public constructor chains the base's handle constructor with zero. */
class TallScratcher(height: Double) : Scratcher(height)
