using System.Reflection;
using System.Runtime.CompilerServices;
using Kotlin.Native.Interop;

namespace ContractTests;

/// <summary>
/// ADR-241: a generated exported exception type lives in the consumer's assembly and derives from a
/// contract type. The proof is that this file compiles: every <c>base(...)</c> below crosses the
/// assembly boundary, so it only builds while <see cref="KotlinException"/>'s ctor stays protected
/// and the nine mapped types stay unsealed with public ctors. The runtime asserts pin catch-ability.
///
/// Oreo and Mylo stress-test the kibble supply chain.
/// </summary>
public class ExportedExceptionSubclassingTests
{
    [Fact]
    public void ContractIsReachedFromASeparateAssemblyWithoutInternals()
    {
        Assert.NotEqual(typeof(KotlinException).Assembly, typeof(Kibble).Assembly);
        Assert.DoesNotContain(
            typeof(KotlinException).Assembly.GetCustomAttributes<InternalsVisibleToAttribute>(),
            a => a.AssemblyName.StartsWith("ContractTests"));
    }

    [Fact]
    public void GeneratedRootShapeIsCaughtByArmByRootAndAsKotlinException()
    {
        var cause = new IOException("bag torn");
        Kibble thrown = new Kibble.Spilled("Mylo knocked the bag over", cause);
        Assert.Same(thrown, Catch<Kibble.Spilled>(thrown));
        Assert.Same(thrown, Catch<Kibble>(thrown));
        Assert.Same(thrown, Catch<KotlinException>(thrown));
        Assert.False(thrown is Kibble.Hidden);
        Assert.Same(cause, thrown.InnerException);
        Assert.Equal("Mylo knocked the bag over", thrown.Message);
        IKotlinException kotlin = thrown;
        Assert.Equal("cats.Kibble.Spilled", kotlin.KotlinType);
        Assert.Equal(Trace("Kibble.Spilled"), kotlin.KotlinStackTrace);
        Assert.Contains("Kotlin type: cats.Kibble.Spilled", thrown.ToString());
        Assert.Contains(Trace("Kibble.Spilled"), thrown.ToString());

        Kibble hidden = new Kibble.Hidden();
        Assert.Same(hidden, Catch<Kibble>(hidden));
        Assert.False(hidden is Kibble.Spilled);
        Assert.Equal("cats.Kibble.Hidden", hidden.KotlinType);
    }

    [Fact]
    public void EveryMappedRowIsSubclassableAndKeepsItsBclCatch()
    {
        var cause = new Exception("Oreo was here");
        Assert3<BadTreatCount, KotlinArgumentException, ArgumentException>(new(cause));
        Assert3<StaleKibble, KotlinInvalidOperationException, InvalidOperationException>(new(cause));
        Assert3<NoBathsSupported, KotlinNotSupportedException, NotSupportedException>(new(cause));
        Assert3<NotADog, KotlinInvalidCastException, InvalidCastException>(new(cause));
        Assert3<TreatsDividedByZero, KotlinArithmeticException, ArithmeticException>(new(cause));
        Assert3<GarbledMeow, KotlinFormatException, FormatException>(new(cause));
        Assert3<ChewedCable, KotlinIOException, IOException>(new(cause));
        Assert3<EmptyBowl, KotlinNullReferenceException, NullReferenceException>(new(cause));
        Assert3<NapInterrupted, KotlinOperationCanceledException, OperationCanceledException>(new(cause));
    }

    // Caught by the exact type, the mapped Kotlin* type and the BCL base, with real catch clauses.
    // Not a KotlinException: a row-derived exported type is an IKotlinException only.
    private static void Assert3<TExact, TMapped, TBcl>(TExact thrown)
        where TExact : TMapped
        where TMapped : TBcl, IKotlinException
        where TBcl : Exception
    {
        Assert.Same(thrown, Catch<TExact>(thrown));
        Assert.Same(thrown, Catch<TMapped>(thrown));
        Assert.Same(thrown, Catch<TBcl>(thrown));
        Assert.False(thrown is KotlinException);
        string name = typeof(TExact).Name;
        Assert.Equal("Oreo was here", thrown.InnerException!.Message);
        Assert.Equal($"{name} at 3am", thrown.Message);
        Assert.Equal($"cats.{name}", thrown.KotlinType);
        Assert.Equal(Trace(name), thrown.KotlinStackTrace);
        Assert.Contains($"Kotlin type: cats.{name}", thrown.ToString());
        Assert.Contains(Trace(name), thrown.ToString());
    }

    private static T Catch<T>(Exception thrown) where T : Exception
    {
        try
        {
            throw thrown;
        }
        catch (T e)
        {
            return e;
        }
    }

    internal static string Trace(string name) => $"at cats.{name}.pounce(Kibble.kt:3)";
}

// The ADR-241 "Consumer API" shape: an abstract root over KotlinException, nested sealed arms.
public abstract class Kibble : KotlinException
{
    private Kibble(string arm, string message, Exception? cause)
        : base($"cats.Kibble.{arm}", message, ExportedExceptionSubclassingTests.Trace($"Kibble.{arm}"), cause) { }

    public sealed class Spilled(string message, Exception cause) : Kibble(nameof(Spilled), message, cause);

    public sealed class Hidden() : Kibble(nameof(Hidden), "Oreo hid the kibble", null);
}

// One exported type per mapped row, each through the row's public ctor.
public sealed class BadTreatCount(Exception cause) : KotlinArgumentException(
    "cats.BadTreatCount", "BadTreatCount at 3am", ExportedExceptionSubclassingTests.Trace("BadTreatCount"), cause);

public sealed class StaleKibble(Exception cause) : KotlinInvalidOperationException(
    "cats.StaleKibble", "StaleKibble at 3am", ExportedExceptionSubclassingTests.Trace("StaleKibble"), cause);

public sealed class NoBathsSupported(Exception cause) : KotlinNotSupportedException(
    "cats.NoBathsSupported", "NoBathsSupported at 3am", ExportedExceptionSubclassingTests.Trace("NoBathsSupported"), cause);

public sealed class NotADog(Exception cause) : KotlinInvalidCastException(
    "cats.NotADog", "NotADog at 3am", ExportedExceptionSubclassingTests.Trace("NotADog"), cause);

public sealed class TreatsDividedByZero(Exception cause) : KotlinArithmeticException(
    "cats.TreatsDividedByZero", "TreatsDividedByZero at 3am", ExportedExceptionSubclassingTests.Trace("TreatsDividedByZero"), cause);

public sealed class GarbledMeow(Exception cause) : KotlinFormatException(
    "cats.GarbledMeow", "GarbledMeow at 3am", ExportedExceptionSubclassingTests.Trace("GarbledMeow"), cause);

public sealed class ChewedCable(Exception cause) : KotlinIOException(
    "cats.ChewedCable", "ChewedCable at 3am", ExportedExceptionSubclassingTests.Trace("ChewedCable"), cause);

public sealed class EmptyBowl(Exception cause) : KotlinNullReferenceException(
    "cats.EmptyBowl", "EmptyBowl at 3am", ExportedExceptionSubclassingTests.Trace("EmptyBowl"), cause);

public sealed class NapInterrupted(Exception cause) : KotlinOperationCanceledException(
    "cats.NapInterrupted", "NapInterrupted at 3am", ExportedExceptionSubclassingTests.Trace("NapInterrupted"), cause);
