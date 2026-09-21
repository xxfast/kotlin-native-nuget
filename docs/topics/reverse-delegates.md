# Delegate parameters from C#

A bound C# method or constructor that declares a `Func<>`, `Action<>`, `Predicate<T>`, or a
package-declared `delegate` parameter binds as an ordinary Kotlin function type, and you pass a
plain Kotlin lambda:

```C#
// TestDependency
public delegate int Transform(int value);

public sealed class Workshop
{
    public int Apply(int seed, Func<int, int> step) => step(step(seed));
    public void ForEachName(Action<string> visit) { visit("Oreo"); visit("Mylo"); }
    public int ApplyNamed(int seed, Transform step) => step(seed);
}
```

```kotlin
val workshop = Workshop()
workshop.apply(21) { it * 2 }                 // 84 (invoked twice by C#)
workshop.forEachName { println(it) }          // "Oreo", then "Mylo"

val triple: test.workshop.Transform = { it * 3 }
workshop.applyNamed(21, triple)               // 63
```

C# genuinely holds a real delegate: it can invoke your lambda synchronously (as above), later,
concurrently from more than one thread, or store it and call it long after `apply`/`forEachName`
returned. Your lambda has to be safe for whatever the C# API's own documentation says it does with
the delegate it's given.

## Type mapping

| C# declared type | Kotlin |
|---|---|
| `Action`, ``Action`1..16`` | `() -> Unit`, `(T1) -> Unit`, ... |
| ``Func`1..17`` | `() -> R`, `(T1) -> R`, ... |
| `Predicate<T>` | `(T) -> Boolean` |
| `Comparison<T>` | `(T, T) -> Int` |
| `Converter<TIn, TOut>` | `(TIn) -> TOut` |
| a package-declared `delegate` | a function type, plus a `typealias` carrying the C# name |

A custom delegate's typealias is generated once per delegate, in its C#-declared namespace's
package:

```kotlin
// Generated: C# `Test.Workshop.Transform`
typealias Transform = (Int) -> Int
```

It exists so IDE completion and the generated signature still say `Transform` instead of a bare
`(Int) -> Int`; you can pass a plain lambda literal at that parameter either way, since a
`typealias` is not a distinct type.

A nullable delegate parameter (`Action? onDone`, `Func<string?>? label`) is a nullable Kotlin
function type; pass `null` or omit the argument the same as any other nullable parameter. A
delegate's own reference-type arguments and return can be nullable independently of the delegate
itself (`Action<string?>` is `(String?) -> Unit`).

## Lifetime: C# owns your lambda

Metadata carries no way to tell whether a C# method stores its delegate argument or only calls it
once and forgets it, so there's a single rule that has to be safe for both: **the C# side owns your
lambda for as long as it holds the delegate**. If C# stores the delegate in a field, your lambda
stays reachable and callable, on any thread, until C# drops that field and the .NET GC collects it:

```C#
// TestDependency
public sealed class Workshop
{
    private Func<int, int>? _kept;
    public void Keep(Func<int, int> step) => _kept = step;
    public int RunKept(int seed) => _kept!(seed);
    public void Forget() => _kept = null;
}
```

```kotlin
val workshop = Workshop()
workshop.keep { it * 3 }      // the lambda is now reachable only through C#
workshop.runKept(7)           // 21, invoked long after `keep` returned; C# may invoke it on any thread
workshop.forget()             // only now does the lambda become releasable
```

There is no `close()`, `Cleaner`, or subscription object for a delegate parameter: you don't manage
this lifetime from Kotlin. Release happens once C# genuinely lets go, and it's GC-timed rather than
prompt, the same pinning behavior a Kotlin object implementing a C# interface already has once C#
holds it (see
[Implementing a C#-declared interface in Kotlin](bridgeable-subset.md#implementing-a-c-declared-interface-in-kotlin)).

## Exceptions inside the lambda

A Kotlin exception thrown inside the lambda propagates through the C# call and back to Kotlin as an
ordinary catchable `NugetManagedException`, the same as any other reverse call
(see [Exceptions](bridgeable-subset.md#exceptions)); the C# method call that invoked your lambda
also sees a catchable managed exception on its own side. The receiver stays usable afterward:

```kotlin
try {
  workshop.apply(1) { error("boom") }
} catch (e: NugetManagedException) {
  e.managedType   // "TestLibrary.KotlinInvalidOperationException"
  e.message       // "boom"
}
workshop.apply(3) { it * 2 }   // still works after the throw
```

## Overloads that differ only by delegate shape

An overload set like `Run(Action)` / `Run(Func<int>)` binds every member, but a **bare** Kotlin
lambda never resolves against such a pair: it matches every candidate equally, so the call is an
`Overload resolution ambiguity` compile error in your own code, with nothing in the build log to
explain it. Pass a typed function value or an anonymous function instead, either of which picks one
overload:

```kotlin
val pick: () -> Int = { 7 }
Workshop.run(pick)                 // picks the Func<int> overload
Workshop.run(fun() { })            // picks the Action overload
```

## What doesn't bind yet

Each of these is named on its own build diagnostic rather than silently dropped (most as
`skipped_delegate_signature` or `skipped_delegate_position`; a custom delegate declared outside the
bound assemblies keeps the ordinary unbound-type diagnostic instead). See
[Unsupported members show up as build warnings](bridgeable-subset.md#unsupported-members-show-up-as-build-warnings).

- **Async delegates**: `Func<Task>`, `Func<Task<T>>`, `Func<CancellationToken, Task<T>>`,
  `Func<ValueTask>`.
- **A delegate at a return, property, or field position** (`Func<int,int> MakeAdder(int)`,
  `Func<int,int> Step { get; set; }`). A delegate at a **parameter** of a method or constructor is
  the only admitted position.
- **A delegate parameter of a struct method, a bound-interface member, or a generic class member.**
- **Invoke arity above 4**, a `ref`/`out`/`in` Invoke parameter, an Invoke element outside the
  ordinary bridgeable vocabulary (a struct, a collection, `object`), or a delegate nested inside
  another delegate's `Invoke`.
- **A generic or nested custom delegate**, and a custom delegate declared outside the bound
  assemblies.
- `EventHandler`, `EventHandler<T>`, and the thread/timer callbacks (`TimerCallback`,
  `WaitCallback`, `ThreadStart`): recognized as delegates, but no shape is derived for them yet.

<seealso>
    <category ref="related">
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
        <a href="bridgeable-subset.md">The bridgeable subset</a>
        <a href="objects-and-handles.md">Objects and handles</a>
        <a href="instance-members.md">Instance members</a>
        <a href="lambdas-and-callbacks.md">Lambdas and callbacks (forward)</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/158-reverse-delegate-parameters.md">ADR-158: C# delegate parameters as Kotlin function types</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/085-kotlin-implemented-csharp-interfaces.md">ADR-085: Kotlin-implemented C# interfaces</a>
    </category>
</seealso>
