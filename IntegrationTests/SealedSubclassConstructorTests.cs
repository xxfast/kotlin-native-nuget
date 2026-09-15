using System.Reflection;
using TestLibrary;
using TestLibrary.Issue54;
using TestLibrary.Models;

namespace IntegrationTests;

/// <summary>
/// Issue #222: a sealed subclass never exports its public constructor, so C# gets only the
/// <c>internal Deep(IntPtr handle)</c> one and a consumer has no way to build an instance to pass
/// back. This is the other half of #126, which made the sealed-arm <em>parameter</em> position
/// render as the mapped type. That gave the signature; this has to give the instance.
///
/// <para>
/// The route already exists elsewhere in the same generated file: a non-sealed subclass
/// (<c>HighPerch : Roost.Perch</c>) chains <c>: base(IntPtr.Zero)</c> and then sets the inherited
/// handle, and a non-subclass <c>data class</c> with the same parameter list exports its
/// constructor unconditionally. Only the sealed subclass loses it, so the invariant under test is
/// that sitting in a hierarchy is a detail of the declaration and not of the API.
/// </para>
///
/// <para>
/// Both declaration positions, once each: <c>Nap.Deep</c> is nested and one klib boundary away in
/// <c>:test-models</c>, <c>FlatShape.Circle</c> is nested and module-local, and each is driven
/// through a different parameter shape (the arm's own type, and the sealed base). <c>Zoomies</c> is
/// the <c>data object</c> control that was already reachable and must not change.
/// <c>FlatShape.Groomed</c> is the negative cell: its constructor takes an opt-in-marked type, so
/// it genuinely cannot be exported and must stay constructor-free.
/// </para>
///
/// <para>
/// Every construction here is spelled with a <strong>named</strong> argument
/// (<c>new Nap.Deep(minutes: 12)</c>), and that is load-bearing rather than stylistic. The handle
/// constructor is <c>internal</c>, but the generated <c>Interop.cs</c> compiles <em>into</em> this
/// test assembly, so it is visible here, and <c>int</c> converts to <c>IntPtr</c> implicitly. A
/// positional <c>new Nap.Deep(12)</c> therefore compiles today, binds to
/// <c>internal Deep(IntPtr handle)</c>, and dereferences pointer 12, which takes the whole test
/// host down instead of failing a test. Naming the parameter is what turns that into an honest
/// compile error. Once the constructor exports, positional resolves to it anyway: an exact
/// <c>int</c> match beats a user-defined implicit conversion.
/// </para>
///
/// <para>
/// Oreo sleeps twelve hours at a stretch, so twelve minutes is a nap he would not admit to. Mylo
/// does not nap so much as stop moving at speed, then start again.
/// </para>
/// </summary>
public class SealedSubclassConstructorTests
{
    /// <summary>
    /// The issue's shape verbatim: construct the nested <c>CLASS</c> arm in C#, hand it to a Kotlin
    /// method that takes that arm, and Kotlin reads the value the consumer chose. A constructor
    /// that compiled but handed over a dead handle cannot answer this.
    /// </summary>
    [Fact]
    public void Deep_ConstructedInCSharp_RoundTripsThroughAnArmTypedParameter()
    {
        using var newsroom = new Newsroom();

        using var deep = new Nap.Deep(minutes: 12);

        Assert.Equal(12, newsroom.NapMinutes(deep));
    }

    /// <summary>
    /// The same instance at a <em>base</em>-typed parameter, so a constructed arm also passes where
    /// the sealed base is expected and Kotlin's own <c>when</c> discriminates it back to the arm it
    /// was built as.
    /// </summary>
    [Fact]
    public void Deep_ConstructedInCSharp_PassesWhereTheSealedBaseIsExpected()
    {
        using var newsroom = new Newsroom();

        using var deep = new Nap.Deep(minutes: 12);

        Assert.Equal("deep:12", newsroom.Describe(deep));
    }

    /// <summary>
    /// The issue's spelling verbatim, positional. Now that <c>Deep(int)</c> exists, an exact
    /// <c>int</c> match wins over the <c>int</c> to <c>IntPtr</c> conversion into the internal handle
    /// constructor, so this binds to the exported one and hands over a live Kotlin object rather than
    /// dereferencing pointer 12. This is the claim ADR-148 could only infer from the resolution rules.
    /// </summary>
    [Fact]
    public void Deep_PositionalArgument_BindsToTheExportedConstructorNotTheHandleOne()
    {
        using var newsroom = new Newsroom();

        using var deep = new Nap.Deep(12);

        Assert.Equal(12, deep.Minutes);
        Assert.Equal(12, newsroom.NapMinutes(deep));
    }

    /// <summary>
    /// The <c>data object</c> control arm, reached the only way a consumer ever could: off a return
    /// position. It already worked, and this feature must leave it alone.
    /// </summary>
    [Fact]
    public void Zoomies_ObjectArm_StillReachesTheSameBaseTypedParameter()
    {
        using var newsroom = new Newsroom();

        using Nap zoomies = newsroom.Nap();

        Assert.Equal("zoomies", newsroom.Describe(zoomies));
    }

    /// <summary>
    /// The constructed instance owns a real Kotlin object, not a placeholder: it reads its own
    /// payload back through its generated property getter without ever crossing a factory.
    /// </summary>
    [Fact]
    public void Deep_ConstructedInCSharp_ReadsItsOwnPayloadBack()
    {
        using var deep = new Nap.Deep(minutes: 12);

        Assert.Equal(12, deep.Minutes);
    }

    /// <summary>
    /// The constructed wrapper is the arm type and the base type at once, so the handle came in
    /// through the inherited <c>: base(IntPtr.Zero)</c> route rather than a parallel one.
    /// </summary>
    [Fact]
    public void Deep_ConstructedInCSharp_PatternMatchesAsBothTheArmAndTheBase()
    {
        using var deep = new Nap.Deep(minutes: 12);

        Assert.IsType<Nap.Deep>(deep);
        Assert.IsAssignableFrom<Nap>(deep);
    }

    /// <summary>
    /// The module-local nested arm, on the other side of the boundary from <c>Nap.Deep</c>: same
    /// mechanism, no klib hop, and the argument arrives at a sealed-base parameter. Oreo curls
    /// three deep on the sill, which is nine.
    /// </summary>
    [Fact]
    public void Circle_ConstructedInCSharp_RoundTripsThroughASealedBaseParameter()
    {
        using var factory = new FlatShapeFactory();

        using var circle = new FlatShape.Circle(radius: 3);

        Assert.Equal(9, factory.Area(circle));
    }

    /// <summary>
    /// And it reads its own payload back too, so the constructor is not merely accepted by the
    /// compiler.
    /// </summary>
    [Fact]
    public void Circle_ConstructedInCSharp_ReadsItsOwnPayloadBack()
    {
        using var circle = new FlatShape.Circle(radius: 3);

        Assert.Equal(3, circle.Radius);
    }

    /// <summary>
    /// The negative cell, stated by reflection rather than by a call that fails to compile:
    /// <c>FlatShape.Groomed</c>'s only constructor parameter is typed with an opt-in-marked enum,
    /// so no public constructor may appear. "Export every arm's constructor" is not the fix.
    /// </summary>
    [Fact]
    public void Groomed_UnbridgeableConstructorParameter_ExposesNoPublicConstructor()
    {
        ConstructorInfo[] constructors = typeof(FlatShape.Groomed)
            .GetConstructors(BindingFlags.Public | BindingFlags.Instance);

        Assert.Empty(constructors);
    }

    /// <summary>
    /// The same arm is still a usable type: a factory hands one over and its bridgeable payload
    /// reads. Skipping the constructor must not cost the arm its declaration.
    /// </summary>
    [Fact]
    public void Groomed_UnbridgeableConstructorParameter_IsStillReachableFromAFactory()
    {
        using var factory = new FlatShapeFactory();

        using FlatShape.Groomed groomed = factory.Groomed();

        Assert.Equal(1, groomed.Level);
        Assert.Equal(-1, factory.Area(groomed));
    }
}
