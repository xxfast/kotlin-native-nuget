using System.Reflection;
using TestLibrary.Snuggery;

namespace IntegrationTests;

/// <summary>
/// A sealed class over a supertype gets the ADR-101 treatment on the sealed route. Today the
/// sealed base renders <c>public abstract class X : IDisposable, INugetHandle</c> with its own
/// declared members only, so every inherited member is lost and no supertype is ever named.
/// <para>
/// Unexported supertype (<c>Swaddle : UnexportedBlanket()</c>, <c>Purrito : UnexportedPurring</c>,
/// both from <c>:test-models</c>' <c>dev.other.core</c>): the supertype stays out of C#, and every
/// public member it carries that the sealed base does not override is re-homed onto the sealed
/// base, callable through a base-typed value on every arm.
/// </para>
/// <para>
/// Exported supertype (<c>Ottoman : Pouffe()</c>, <c>Slumber : Dreamer</c>): the sealed base lists
/// it in its C# base list, so <c>is</c>/<c>as</c> hold and its members are reachable through the
/// sealed value.
/// </para>
/// <para>
/// Every receiver comes from a Kotlin factory typed as the sealed base, so the calls go through the
/// base carrier and Kotlin's own dispatch picks the arm's body. Oreo wriggles; Mylo lies still.
/// </para>
/// </summary>
public class SealedSupertypeTests
{
    // ---- Unexported open class: Swaddle : UnexportedBlanket(). ----

    [Fact]
    public void Swaddle_ReHomedFinalVal_ReadsThroughTheBaseOnBothArms()
    {
        using Swaddle oreo = SnuggerySample.WrigglingSwaddle(3);
        using Swaddle mylo = SnuggerySample.StillSwaddle(4);

        Assert.Equal("fleece", oreo.Fabric);
        Assert.Equal("fleece", mylo.Fabric);
    }

    [Fact]
    public void Swaddle_ReHomedFinalFun_CallsThroughTheBase()
    {
        using Swaddle swaddle = SnuggerySample.StillSwaddle(4);

        Assert.Equal("fleece draped over Mylo", swaddle.Drape("Mylo"));
    }

    [Fact]
    public void Swaddle_ReHomedOpenFun_DispatchesToTheOverridingArmAndTheInheritingOne()
    {
        using Swaddle oreo = SnuggerySample.WrigglingSwaddle(3);
        using Swaddle mylo = SnuggerySample.StillSwaddle(4);

        Assert.Equal(3, oreo.Shake());
        Assert.Equal(1, mylo.Shake());
    }

    [Fact]
    public void Swaddle_ReHomedVar_WrittenThroughTheBase_IsSeenByKotlin()
    {
        using Swaddle swaddle = SnuggerySample.WrigglingSwaddle(3);

        Assert.Equal(3, swaddle.Warmth);
        Assert.Equal("fleece@3", swaddle.Describe());

        // Mylo climbed in beside Oreo, so the swaddle warmed right up.
        swaddle.Warmth = 9;

        Assert.Equal(9, swaddle.Warmth);
        Assert.Equal("fleece@9", swaddle.Describe());
    }

    /// <summary>
    /// Pins <c>override</c> rather than <c>new</c>: the arm's <c>Shake</c> must be the base's slot,
    /// or a base-typed call would bind to the base body instead of the arm's.
    /// </summary>
    [Fact]
    public void Swaddle_ArmShake_OverridesTheReHomedBaseSlot()
    {
        MethodInfo shake = typeof(Swaddle.Wriggling).GetMethod("Shake", Type.EmptyTypes)!;

        Assert.Equal(typeof(Swaddle), shake.GetBaseDefinition().DeclaringType);
    }

    /// <summary>The guard that the unexported supertype stayed unexported.</summary>
    [Fact]
    public void Swaddle_CarriesNoBaseTypeForTheUnexportedSupertype()
    {
        Assert.Equal(typeof(object), typeof(Swaddle).BaseType);
    }

    // ---- Unexported interface: Purrito : UnexportedPurring. ----

    [Fact]
    public void Purrito_ReHomedDefaultedFun_DispatchesToTheOverridingArmAndTheInheritingOne()
    {
        using Purrito oreo = SnuggerySample.TuckedPurrito(4);
        using Purrito mylo = SnuggerySample.CrouchedPurrito(2);

        Assert.Equal("Oreo purrs in a loaf", oreo.Purr());
        Assert.Equal("prrr", mylo.Purr());
    }

    [Fact]
    public void Purrito_ReHomedDefaultedVal_ReadsThroughTheBase()
    {
        using Purrito purrito = SnuggerySample.CrouchedPurrito(2);

        Assert.Equal(5, purrito.Rumble);
    }

    /// <summary>
    /// The interface's abstract <c>knead</c>, which the sealed base does not implement, called
    /// through the base on both arms.
    /// </summary>
    [Fact]
    public void Purrito_ReHomedAbstractFun_DispatchesToEachArm()
    {
        using Purrito oreo = SnuggerySample.TuckedPurrito(4);
        using Purrito mylo = SnuggerySample.CrouchedPurrito(2);

        Assert.Equal(12, oreo.Knead(3));
        Assert.Equal(5, mylo.Knead(3));
    }

    /// <summary>
    /// The default for <c>paws</c> lives only on <c>UnexportedPurring.knead</c>, one module away,
    /// so the base's omitting overload must come from the klib declaration (ADR-096).
    /// </summary>
    [Fact]
    public void Purrito_ReHomedAbstractFun_OmitsTheKlibDefaultedArgument()
    {
        using Purrito oreo = SnuggerySample.TuckedPurrito(4);

        Assert.Equal(8, oreo.Knead());
    }

    /// <summary>
    /// The interface's abstract <c>whiskers</c> with no default, implemented only on the arms. It
    /// must still read through the base, whichever spelling (<c>abstract</c> or <c>virtual</c>) the
    /// base carrier takes.
    /// </summary>
    [Fact]
    public void Purrito_ReHomedAbstractVal_ReadsThroughTheBaseOnEachArm()
    {
        using Purrito oreo = SnuggerySample.TuckedPurrito(4);
        using Purrito mylo = SnuggerySample.CrouchedPurrito(2);

        Assert.Equal(24, oreo.Whiskers);
        Assert.Equal(12, mylo.Whiskers);
    }

    [Fact]
    public void Purrito_ArmPurr_OverridesTheReHomedBaseSlot()
    {
        MethodInfo purr = typeof(Purrito.Tucked).GetMethod("Purr", Type.EmptyTypes)!;

        Assert.Equal(typeof(Purrito), purr.GetBaseDefinition().DeclaringType);
    }

    /// <summary>The guard that the unexported interface stayed out of C#.</summary>
    [Fact]
    public void Purrito_ImplementsNoInterfaceForTheUnexportedSupertype()
    {
        Assert.DoesNotContain(
            typeof(Purrito).GetInterfaces(),
            face => face.Name.Contains("Purring"));
    }

    // ---- Exported open class: Ottoman : Pouffe(). ----

    [Fact]
    public void Ottoman_ListsTheExportedOpenClassAsItsBase()
    {
        Assert.Equal(typeof(Pouffe), typeof(Ottoman).BaseType);
    }

    [Fact]
    public void Ottoman_ValueFromKotlin_IsAPouffe()
    {
        using Ottoman ottoman = SnuggerySample.SquatOttoman(1);

        Assert.True(ottoman is Pouffe);
    }

    [Fact]
    public void Ottoman_ExportedBaseMembers_ReachableThroughTheSealedValue()
    {
        using Ottoman ottoman = SnuggerySample.SquatOttoman(1);

        Assert.Equal("beans", ottoman.Stuffing);
        Assert.Equal(6, ottoman.Plump(3));
    }

    [Fact]
    public void Ottoman_ExportedBaseOpenFun_DispatchesToTheArmThroughAPouffeReference()
    {
        using Ottoman mylo = SnuggerySample.TallOttoman(5);
        using Ottoman oreo = SnuggerySample.SquatOttoman(1);

        Pouffe tall = mylo;
        Pouffe squat = oreo;

        Assert.Equal(5, tall.Sink());
        Assert.Equal(1, squat.Sink());
    }

    // ---- Exported interface: Slumber : Dreamer. ----

    [Fact]
    public void Slumber_ListsTheExportedInterfaceInItsBaseList()
    {
        Assert.Contains(typeof(IDreamer), typeof(Slumber).GetInterfaces());
    }

    [Fact]
    public void Slumber_ValueFromKotlin_IsADreamer()
    {
        using Slumber slumber = SnuggerySample.FitfulSlumber(2);

        Assert.True(slumber is IDreamer);
    }

    [Fact]
    public void Slumber_InterfaceMembers_ReachableThroughTheSealedValue()
    {
        using Slumber mylo = SnuggerySample.HeavySlumber(4);
        using Slumber oreo = SnuggerySample.FitfulSlumber(2);

        Assert.Equal("Mylo dreams of 4 mugs of Milo", mylo.Dream());
        Assert.Equal("chasing the red dot", oreo.Dream());
        Assert.Equal(3, oreo.Snores);
    }

    [Fact]
    public void Slumber_InterfaceMembers_DispatchThroughAnIDreamerReference()
    {
        using Slumber mylo = SnuggerySample.HeavySlumber(4);

        IDreamer dreamer = mylo;

        Assert.Equal("Mylo dreams of 4 mugs of Milo", dreamer.Dream());
        Assert.Equal(3, dreamer.Snores);
    }
}
