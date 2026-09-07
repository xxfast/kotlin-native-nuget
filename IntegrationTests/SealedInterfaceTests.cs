using System;
using System.Collections.Generic;
using System.Reflection;
using TestLibrary.Issue54;

// `TestLibrary.Issue54.Monitor` collides with `System.Threading.Monitor`, which implicit usings
// pull in, so the fixture's class is named explicitly here.
using Monitor = TestLibrary.Issue54.Monitor;

namespace IntegrationTests;

/// <summary>
/// A Kotlin <c>sealed interface</c> whose subclasses are nested inside it is, today, declared as a
/// bare <c>public interface IPulse</c> with no members and no subclasses, because the nested
/// subclasses are never collected and no <c>FromHandle</c> discriminator is generated. Every member
/// typed with it is then dropped with <c>SKIPPED_SEALED_POSITION</c>, so <c>Pulse</c> is a C# type
/// that can never be obtained, implemented or passed. This file therefore cannot compile until the
/// feature ships, which is the red signal.
/// <para>
/// After the fix an <em>eligible</em> sealed interface (no type parameters, every subclass a nested
/// class or object with no other superclass, no sub-interfaces) maps exactly like a sealed class:
/// <c>public abstract class Pulse</c> with nested <c>sealed</c> subclasses and
/// <c>Pulse.FromHandle(IntPtr)</c>, and it binds at property, collection-component, return and
/// parameter positions. No <c>IPulse</c> may survive anywhere in the assembly.
/// </para>
/// <para>
/// <c>Mixed</c> is the ineligible control: its subclass <c>Odd</c> carries a second superclass
/// (<c>Rhythm</c>), which no nested <c>sealed class Odd : Mixed</c> declaration can express, so
/// <c>Monitor.Mixed()</c> must keep skipping. A fix that admits every sealed interface fails here.
/// </para>
/// <para>
/// Oreo purrs a beat you can count at 60; Mylo goes so limp in the sun he reads flat.
/// </para>
/// </summary>
public class SealedInterfaceTests
{
    /// <summary>
    /// The sealed base at a top-level function return, the position sealed <em>classes</em> already
    /// bind. Discriminates onto the payload arm and reads it: Oreo, purring at 72.
    /// </summary>
    [Fact]
    public void AnyPulse_SealedInterfaceAtATopLevelReturn_DiscriminatesToThePayloadArm()
    {
        using Pulse any = SealedInterfaceSample.AnyPulse();

        var beat = Assert.IsType<Pulse.Beat>(any);
        Assert.Equal(72, beat.Bpm);
    }

    /// <summary>
    /// The payload-free arm at a <c>var</c> property getter: Mylo, flat, is still a value that has
    /// to arrive as a real subclass rather than a raw pointer.
    /// </summary>
    [Fact]
    public void Current_SealedInterfacePropertyGetter_DiscriminatesToThePayloadFreeArm()
    {
        using var monitor = new Monitor();

        using Pulse current = monitor.Current;

        Assert.IsType<Pulse.Flat>(current);
    }

    /// <summary>
    /// The collection-component position, read-only, with both arms in a fixed order: index 0 is
    /// Oreo at 60, index 1 is Mylo, flat.
    /// </summary>
    [Fact]
    public void History_SealedInterfaceCollectionComponent_MaterialisesBothArmsInOrder()
    {
        using var monitor = new Monitor();

        IReadOnlyList<Pulse> history = monitor.History;

        Assert.Collection(
            history,
            oreo => Assert.Equal(60, Assert.IsType<Pulse.Beat>(oreo).Bpm),
            mylo => Assert.IsType<Pulse.Flat>(mylo));
    }

    /// <summary>
    /// The scalar setter half of the <c>var</c> property. The subclasses expose only an internal
    /// handle constructor (ADR-009), so the value written back comes from another getter rather
    /// than from <c>new Beat(60)</c>. Mylo is on the monitor; Oreo takes over.
    /// </summary>
    [Fact]
    public void Current_SealedInterfaceSetter_RoundTripsThroughTheHandleWire()
    {
        using var monitor = new Monitor();

        monitor.Current = monitor.History[0];

        using Pulse after = monitor.Current;
        Assert.Equal(60, Assert.IsType<Pulse.Beat>(after).Bpm);
    }

    /// <summary>
    /// The class-method return, the ordinary member plan rather than the property plan. Reads back
    /// whatever the setter last wrote, discriminated on the way out.
    /// </summary>
    [Fact]
    public void Latest_SealedInterfaceAtAClassMethodReturn_DiscriminatesToTheWrittenArm()
    {
        using var monitor = new Monitor();

        using (Pulse initial = monitor.Latest())
        {
            Assert.IsType<Pulse.Flat>(initial);
        }

        monitor.Current = monitor.History[0];

        using Pulse latest = monitor.Latest();
        Assert.Equal(60, Assert.IsType<Pulse.Beat>(latest).Bpm);
    }

    /// <summary>
    /// The parameter position: the handle crosses back and is unwrapped to a real Kotlin
    /// <c>Pulse</c>. The return is a plain <c>Int</c>, so the assertion reads the Kotlin side of the
    /// wire; a handle that arrived as a raw pointer cannot answer it.
    /// </summary>
    [Fact]
    public void Record_SealedInterfaceAtAParameterPosition_UnwrapsTheHandleBackToKotlin()
    {
        using var monitor = new Monitor();

        Assert.Equal(60, monitor.Record(monitor.History[0]));
    }

    /// <summary>
    /// The payload-free arm across the same parameter: Mylo is present and flat, which is not the
    /// same as absent.
    /// </summary>
    [Fact]
    public void Record_SealedInterfaceAtAParameterPosition_CarriesThePayloadFreeArm()
    {
        using var monitor = new Monitor();

        Assert.Equal(0, monitor.Record(monitor.History[1]));
    }

    /// <summary>
    /// The declaration shape itself: an eligible sealed interface becomes an abstract
    /// <em>class</em>, not a C# interface, and its subclasses are nested and sealed.
    /// </summary>
    [Fact]
    public void Pulse_IsDeclaredAsAnAbstractClass_WithNestedSealedSubclasses()
    {
        Assert.True(typeof(Pulse).IsClass);
        Assert.True(typeof(Pulse).IsAbstract);
        Assert.False(typeof(Pulse).IsInterface);

        Assert.Equal(typeof(Pulse), typeof(Pulse.Beat).DeclaringType);
        Assert.Equal(typeof(Pulse), typeof(Pulse.Flat).DeclaringType);
        Assert.Equal(typeof(Pulse), typeof(Pulse.Beat).BaseType);
        Assert.Equal(typeof(Pulse), typeof(Pulse.Flat).BaseType);
        Assert.True(typeof(Pulse.Beat).IsSealed);
        Assert.True(typeof(Pulse.Flat).IsSealed);
    }

    /// <summary>
    /// The interface route must not leave a second, unreachable declaration behind. Name-agnostic
    /// about where it would sit: nothing in the assembly may be called <c>IPulse</c>.
    /// </summary>
    [Fact]
    public void IPulse_TheUselessInterfaceDeclaration_IsGoneFromTheAssembly()
    {
        Assembly assembly = typeof(Pulse).Assembly;

        Assert.DoesNotContain(assembly.GetTypes(), type => type.Name == "IPulse");
    }

    /// <summary>
    /// Control: <c>Mixed</c> is ineligible because <c>Odd</c> has another superclass, so the member
    /// typed with it must still be skipped. If a fix admits every sealed interface, this method
    /// appears and the test fails.
    /// </summary>
    [Fact]
    public void Mixed_IneligibleSealedInterface_IsStillSkippedAtAMemberPosition()
    {
        Assert.Null(typeof(Monitor).GetMethod("Mixed"));
    }

    /// <summary>
    /// The positive half of the same control: an ineligible sealed interface keeps today's
    /// behaviour and is still declared as <c>IMixed</c>, so the fix is a narrowing of the interface
    /// route rather than its removal.
    /// </summary>
    [Fact]
    public void IMixed_IneligibleSealedInterface_IsStillDeclaredAsAnInterface()
    {
        Assembly assembly = typeof(Monitor).Assembly;

        Type? mixed = assembly.GetType("TestLibrary.Issue54.IMixed");

        Assert.NotNull(mixed);
        Assert.True(mixed.IsInterface);
    }

    /// <summary>
    /// The other half of the control: the ineligible hierarchy takes nothing else down with it.
    /// <c>Rhythm</c>, the second superclass, is an ordinary exported class and keeps binding.
    /// </summary>
    [Fact]
    public void Rhythm_TheOtherSuperclass_StillBindsAsAnOrdinaryClass()
    {
        using var rhythm = new Rhythm();

        Assert.Equal("steady", rhythm.Tempo());
    }
}
