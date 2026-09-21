namespace Test.Workshop;

/// <summary>
/// A package-declared <c>delegate</c>, the "custom delegate" half of the feature. Today the reader
/// has no <c>IsDelegate</c> test, so this TypeDef lands in the bound-handle name collector and is
/// extracted as an ordinary <c>RirClass</c> with an <c>Invoke</c> method, no constructor and two
/// noise diagnostics from <c>BeginInvoke</c>/<c>EndInvoke</c> (verified by spike, 2026-09-21). It
/// must stop being a class and become a Kotlin function type plus a <c>typealias Transform</c>
/// carrying this C# name.
/// </summary>
public delegate int Transform(int value);

/// <summary>
/// The reverse delegate-parameter fixture: a C# API that takes <c>Func&lt;&gt;</c>,
/// <c>Action&lt;&gt;</c>, <c>Predicate&lt;T&gt;</c> and a package-declared delegate, consumed from
/// Kotlin with ordinary Kotlin lambdas. Oreo and Mylo supervise the bench.
///
/// One member per MECHANISM, not per type, because the whole crossing is a one-slot Kotlin bridge
/// and a fixture trimmed to <c>Func&lt;int,int&gt;</c> needs no string conversion, no nullability,
/// no stored lifetime and no off-thread invoke, so it would go green while all four were wrong:
///
/// <list type="bullet">
///   <item><see cref="Twice"/>: the STATIC route (no receiver handle in the thunk), and a
///         REENTRANT synchronous invoke: Kotlin calls C#, C# calls back into Kotlin on the same
///         thread inside the same crossing.</item>
///   <item><see cref="Apply"/>: the same shape on an INSTANCE method, the ordinary route, and the
///         payload that needs NO conversion either way (<c>int</c> in, <c>int</c> out).</item>
///   <item><see cref="ForEachName"/>: <c>Action&lt;string&gt;</c>, a <c>void</c> return (the slot
///         return arm that carries no value) and the payload that DOES need conversion, invoked
///         more than once per crossing so a slot that works exactly once shows up here.</item>
///   <item><see cref="ApplyNamed"/>: the package-declared <see cref="Transform"/>, whose Invoke
///         signature comes from its own MethodDef rather than from type arguments.</item>
///   <item><see cref="AnyLong"/>: <c>Predicate&lt;string&gt;</c>, a name-table shape that is
///         neither <c>Func</c> nor <c>Action</c>, returning <c>bool</c>, and SHORT-CIRCUITING, so
///         the second invoke happens only if the Kotlin lambda said false.</item>
///   <item><see cref="Sum4"/>: arity FOUR, the lifted ceiling. The slot is ctx + 4 + errOut, six C
///         parameters, which compiles and runs (spike c2) but is above the shipped
///         <c>KOTLIN_BRIDGE_MAX_ARITY</c> of 2, so this row is the policy lift.</item>
///   <item><see cref="Shout"/>: <c>Action&lt;string?&gt;</c>, a NULLABLE reference type ARGUMENT
///         of the delegate, whose <c>NullableAttribute</c> payload is <c>[1, 2]</c> pre-order with
///         the delegate node first. It is invoked once with a value and once with <c>null</c>, so
///         a binding that dropped byte 2 and bound this <c>(String) -&gt; Unit</c> fails on the
///         second invoke rather than silently.</item>
///   <item><see cref="DescribeOrDefault"/>: a NULLABLE DELEGATE (<c>Func&lt;string?&gt;?</c>),
///         nested inside a nullable return of the delegate itself. Null crosses as
///         <c>IntPtr.Zero</c> and both spellings are exercised from Kotlin.</item>
///   <item><see cref="Maybe"/>: the other nullability ENCODING. Every node here is nullable, so
///         Roslyn writes no per-parameter attribute at all and the information lives in a
///         method-level <c>NullableContextAttribute(2)</c> (spike b, rows f and g). A reader that
///         only looks at per-parameter attributes binds this non-null with no diagnostic.</item>
///   <item><see cref="Keep"/> / <see cref="RunKept"/> / <see cref="RunKeptOnPoolAsync"/> /
///         <see cref="Forget"/>: the STORED lifetime, which metadata cannot distinguish from a
///         per-call one, so it is the case the single lifetime rule has to be safe for. The
///         Kotlin lambda is invoked long after the Kotlin call that passed it returned, once on
///         the caller's thread and once on a POOL thread, and is only releasable after
///         <see cref="Forget"/> drops it.</item>
///   <item><see cref="Run(Action)"/> / <see cref="Run(Func{int})"/>: a deliberate OVERLOAD SET
///         differing only by delegate shape, and the one place this fixture breaks the usual
///         "no accidental overload sets" rule, on purpose. Both members bind, and a bare Kotlin
///         lambda is ALWAYS an overload-resolution ambiguity against the pair (verified by spike
///         d), so the Kotlin caller has to pass an anonymous function. <c>Action</c> is also the
///         only NON-GENERIC delegate here, a TypeReference rather than a TypeSpec, which is the
///         second of the two insertion points in the reader.</item>
///   <item><see cref="LaterAsync"/>: an ASYNC delegate (<c>Func&lt;Task&lt;int&gt;&gt;</c>),
///         deliberately OUT of v1. It must stay a NAMED skip
///         (<c>skipped_delegate_signature</c>), never bind and never vanish silently.</item>
/// </list>
///
/// The throwing-lambda path needs no member of its own: <see cref="Apply"/> is called from Kotlin
/// with a lambda that throws, and the throw has to leave the slot through the ADR-087 envelope,
/// come out of the holder as the ADR-029 mapped exception, cross the reverse thunk's ADR-104 error
/// channel and arrive back in Kotlin as a catchable <c>NugetManagedException</c>, with the host
/// still alive.
/// </summary>
public sealed class Workshop
{
    private Func<int, int>? _kept;

    /// <summary>
    /// STATIC, and reentrant: invokes the Kotlin lambda TWICE, nested, inside one crossing.
    /// </summary>
    public static int Twice(int seed, Func<int, int> step) => step(step(seed));

    /// <summary>The same shape on an INSTANCE method. No conversion on either payload.</summary>
    public int Apply(int seed, Func<int, int> step) => step(step(seed));

    /// <summary>
    /// <c>Action&lt;string&gt;</c>: a <c>void</c> slot return, a converting payload, and more than
    /// one invoke per crossing.
    /// </summary>
    public void ForEachName(Action<string> visit)
    {
        visit("Oreo");
        visit("Mylo");
    }

    /// <summary>The package-declared delegate, decoded from its own <c>Invoke</c> MethodDef.</summary>
    public int ApplyNamed(int seed, Transform step) => step(seed);

    /// <summary>
    /// <c>Predicate&lt;string&gt;</c>: a bool return, and short-circuiting, so the number of
    /// invokes depends on what Kotlin answered.
    /// </summary>
    public bool AnyLong(Predicate<string> test) => test("Oreo") || test("Marshmallow");

    /// <summary>Arity FOUR: the lifted slot ceiling, six C parameters on the Kotlin side.</summary>
    public static int Sum4(Func<int, int, int, int, int> add) => add(1, 2, 3, 4);

    /// <summary>
    /// <c>Action&lt;string?&gt;</c>: a nullable reference ARGUMENT of the delegate, invoked once
    /// with a value and once with <c>null</c>.
    /// </summary>
    public string Shout(Action<string?> sink)
    {
        sink("Oreo");
        sink(null);
        return "shouted twice";
    }

    /// <summary>A NULLABLE delegate whose own return is a nullable reference.</summary>
    public string DescribeOrDefault(Func<string?>? label) => label?.Invoke() ?? "none";

    /// <summary>
    /// The method-level <c>NullableContextAttribute(2)</c> encoding: no per-parameter attribute is
    /// written at all, because every annotatable node agrees.
    /// </summary>
    public string? Maybe(Func<int, int>? step) => step is null ? null : $"maybe {step(20)}";

    /// <summary>STORES the delegate. The lifetime the one rule has to be safe for.</summary>
    public void Keep(Func<int, int> step) => _kept = step;

    /// <summary>Invokes the stored lambda later, on the calling thread.</summary>
    public int RunKept(int seed) => _kept!(seed);

    /// <summary>Invokes the stored lambda on a POOL thread, not the one that passed it.</summary>
    public Task<int> RunKeptOnPoolAsync(int seed) => Task.Run(() => _kept!(seed));

    /// <summary>Drops the stored delegate, which is what makes it releasable.</summary>
    public void Forget() => _kept = null;

    /// <summary>
    /// Overload set differing ONLY by delegate shape, the <c>Task.Run</c> shape. Also the only
    /// NON-GENERIC delegate in this fixture.
    /// </summary>
    public static string Run(Action act)
    {
        act();
        return "ran an action";
    }

    /// <inheritdoc cref="Run(Action)"/>
    public static string Run(Func<int> pick) => $"ran a func -> {pick()}";

    /// <summary>
    /// ASYNC delegate: out of v1 scope, must stay a NAMED skip rather than binding.
    /// </summary>
    public Task<int> LaterAsync(Func<Task<int>> work) => work();
}
