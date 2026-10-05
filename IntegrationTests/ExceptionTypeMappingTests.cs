using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// Tests for ADR-029: Kotlin stdlib exceptions mapped to .NET analog types via IKotlinException.
/// All scenarios are themed around my cats Oreo (troublemaker, always causing exceptions)
/// and Mylo (good boy, always the happy path).
/// </summary>
public class ExceptionTypeMappingTests
{
    // --- kotlin.IllegalArgumentException → KotlinArgumentException : ArgumentException ---

    [Fact]
    public void Oreo_OnDiet_IsCatchableAs_ArgumentException()
    {
        // Oreo on a diet — any treat request is an illegal argument
        Assert.ThrowsAny<ArgumentException>(
            () => MappedExceptions.CheckOreoWeight(10));
    }

    [Fact]
    public void Oreo_OnDiet_IsExactType_KotlinArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => MappedExceptions.CheckOreoWeight(10));
        Assert.IsType<KotlinArgumentException>(ex);
    }

    [Fact]
    public void Oreo_OnDiet_ViaIKotlinException_KotlinType_IsIllegalArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => MappedExceptions.CheckOreoWeight(10));
        var ke = Assert.IsAssignableFrom<IKotlinException>(ex);
        Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
    }

    [Fact]
    public void Oreo_OnDiet_ViaIKotlinException_KotlinStackTrace_IsNonEmpty()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => MappedExceptions.CheckOreoWeight(10));
        var ke = (IKotlinException)ex;
        Assert.NotNull(ke.KotlinStackTrace);
        Assert.NotEmpty(ke.KotlinStackTrace);
    }

    [Fact]
    public void Mylo_AcceptsKibble_Succeeds()
    {
        // Mylo is not on a diet — negative grams means we're giving, not taking
        string result = MappedExceptions.CheckOreoWeight(-5);
        Assert.Equal("Mylo accepted 5 g of kibble gracefully", result);
    }

    // --- kotlin.IllegalStateException → KotlinInvalidOperationException : InvalidOperationException ---

    [Fact]
    public void Oreo_AsleepLaser_IsCatchableAs_InvalidOperationException()
    {
        // Oreo is asleep — activating the laser is an illegal state
        Assert.ThrowsAny<InvalidOperationException>(
            () => MappedExceptions.ActivateLaserPointer("Oreo"));
    }

    [Fact]
    public void Oreo_AsleepLaser_IsExactType_KotlinInvalidOperationException()
    {
        var ex = Assert.ThrowsAny<InvalidOperationException>(
            () => MappedExceptions.ActivateLaserPointer("Oreo"));
        Assert.IsType<KotlinInvalidOperationException>(ex);
    }

    [Fact]
    public void Oreo_AsleepLaser_ViaIKotlinException_KotlinType_IsIllegalStateException()
    {
        var ex = Assert.ThrowsAny<InvalidOperationException>(
            () => MappedExceptions.ActivateLaserPointer("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.IllegalStateException", ke.KotlinType);
    }

    [Fact]
    public void Mylo_LaserPointer_Succeeds()
    {
        string result = MappedExceptions.ActivateLaserPointer("Mylo");
        Assert.Equal("Mylo chased the red dot enthusiastically", result);
    }

    // --- kotlin.NoSuchElementException → KotlinInvalidOperationException : InvalidOperationException ---

    [Fact]
    public void Oreo_EmptyTreatBag_IsCatchableAs_InvalidOperationException()
    {
        // Oreo ate all the treats — NoSuchElementException when we try to grab the first one
        Assert.ThrowsAny<InvalidOperationException>(
            () => MappedExceptions.GrabFirstTreatFromBag("Oreo"));
    }

    [Fact]
    public void Oreo_EmptyTreatBag_IsExactType_KotlinInvalidOperationException()
    {
        var ex = Assert.ThrowsAny<InvalidOperationException>(
            () => MappedExceptions.GrabFirstTreatFromBag("Oreo"));
        Assert.IsType<KotlinInvalidOperationException>(ex);
    }

    [Fact]
    public void Oreo_EmptyTreatBag_ViaIKotlinException_KotlinType_IsNoSuchElementException()
    {
        var ex = Assert.ThrowsAny<InvalidOperationException>(
            () => MappedExceptions.GrabFirstTreatFromBag("Oreo"));
        var ke = (IKotlinException)ex;
        // Note: even though C# type is KotlinInvalidOperationException, the KotlinType still
        // carries the original Kotlin fully-qualified class name
        Assert.Equal("kotlin.NoSuchElementException", ke.KotlinType);
    }

    [Fact]
    public void Mylo_TreatBag_Succeeds()
    {
        string result = MappedExceptions.GrabFirstTreatFromBag("Mylo");
        Assert.Equal("Mylo found a treat", result);
    }

    // --- kotlin.ConcurrentModificationException → KotlinInvalidOperationException : InvalidOperationException ---

    [Fact]
    public void Oreo_BasketMeddling_IsCatchableAs_InvalidOperationException()
    {
        // Oreo keeps jumping into the basket while we count — concurrent modification
        Assert.ThrowsAny<InvalidOperationException>(
            () => MappedExceptions.CountTreatsInBasket("Oreo"));
    }

    [Fact]
    public void Oreo_BasketMeddling_IsExactType_KotlinInvalidOperationException()
    {
        var ex = Assert.ThrowsAny<InvalidOperationException>(
            () => MappedExceptions.CountTreatsInBasket("Oreo"));
        Assert.IsType<KotlinInvalidOperationException>(ex);
    }

    [Fact]
    public void Oreo_BasketMeddling_ViaIKotlinException_KotlinType_IsConcurrentModificationException()
    {
        var ex = Assert.ThrowsAny<InvalidOperationException>(
            () => MappedExceptions.CountTreatsInBasket("Oreo"));
        var ke = (IKotlinException)ex;
        // Three Kotlin types collapse to KotlinInvalidOperationException; KotlinType distinguishes them
        Assert.Equal("kotlin.ConcurrentModificationException", ke.KotlinType);
    }

    [Fact]
    public void Mylo_TreatBasket_Succeeds()
    {
        string result = MappedExceptions.CountTreatsInBasket("Mylo");
        Assert.Equal("Mylo waited patiently; basket has 5 treats", result);
    }

    // --- kotlin.UnsupportedOperationException → KotlinNotSupportedException : NotSupportedException ---

    [Fact]
    public void Oreo_RefusesBath_IsCatchableAs_NotSupportedException()
    {
        // Oreo simply does not support baths
        Assert.ThrowsAny<NotSupportedException>(
            () => MappedExceptions.GiveCatABath("Oreo"));
    }

    [Fact]
    public void Oreo_RefusesBath_IsExactType_KotlinNotSupportedException()
    {
        var ex = Assert.ThrowsAny<NotSupportedException>(
            () => MappedExceptions.GiveCatABath("Oreo"));
        Assert.IsType<KotlinNotSupportedException>(ex);
    }

    [Fact]
    public void Oreo_RefusesBath_ViaIKotlinException_KotlinType_IsUnsupportedOperationException()
    {
        var ex = Assert.ThrowsAny<NotSupportedException>(
            () => MappedExceptions.GiveCatABath("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.UnsupportedOperationException", ke.KotlinType);
    }

    [Fact]
    public void Mylo_Bath_Succeeds()
    {
        string result = MappedExceptions.GiveCatABath("Mylo");
        Assert.Equal("Mylo enjoyed a splashy bath", result);
    }

    // --- kotlin.ClassCastException → KotlinInvalidCastException : InvalidCastException ---

    [Fact]
    public void Oreo_BadCast_IsCatchableAs_InvalidCastException()
    {
        // You cannot cast Oreo to a Dog — he is very much a cat
        Assert.ThrowsAny<InvalidCastException>(
            () => MappedExceptions.TreatCatAsADog("Oreo"));
    }

    [Fact]
    public void Oreo_BadCast_IsExactType_KotlinInvalidCastException()
    {
        var ex = Assert.ThrowsAny<InvalidCastException>(
            () => MappedExceptions.TreatCatAsADog("Oreo"));
        Assert.IsType<KotlinInvalidCastException>(ex);
    }

    [Fact]
    public void Oreo_BadCast_ViaIKotlinException_KotlinType_IsClassCastException()
    {
        var ex = Assert.ThrowsAny<InvalidCastException>(
            () => MappedExceptions.TreatCatAsADog("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.ClassCastException", ke.KotlinType);
    }

    [Fact]
    public void Mylo_TreatCatAsACat_Succeeds()
    {
        string result = MappedExceptions.TreatCatAsADog("Mylo");
        Assert.Equal("Mylo trotted off happily (still a cat)", result);
    }

    // --- kotlin.ArithmeticException → KotlinArithmeticException : ArithmeticException ---

    [Fact]
    public void Oreo_ZeroTreats_DivisionByZero_IsCatchableAs_ArithmeticException()
    {
        // Oreo stole all treats, leaving zero to divide
        Assert.ThrowsAny<ArithmeticException>(
            () => MappedExceptions.ShareRemainingTreats("Oreo"));
    }

    [Fact]
    public void Oreo_ZeroTreats_IsExactType_KotlinArithmeticException()
    {
        var ex = Assert.ThrowsAny<ArithmeticException>(
            () => MappedExceptions.ShareRemainingTreats("Oreo"));
        Assert.IsType<KotlinArithmeticException>(ex);
    }

    [Fact]
    public void Oreo_ZeroTreats_ViaIKotlinException_KotlinType_IsArithmeticException()
    {
        var ex = Assert.ThrowsAny<ArithmeticException>(
            () => MappedExceptions.ShareRemainingTreats("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.ArithmeticException", ke.KotlinType);
    }

    [Fact]
    public void Mylo_ShareTreats_Succeeds()
    {
        string result = MappedExceptions.ShareRemainingTreats("Mylo");
        Assert.Equal("Mylo shared treats evenly with the household", result);
    }

    // --- kotlin.NumberFormatException → KotlinFormatException : FormatException ---

    [Fact]
    public void Oreo_ChewedLabel_NumberFormat_IsCatchableAs_FormatException()
    {
        // Oreo chewed the weight label — parsing fails with NumberFormatException
        Assert.ThrowsAny<FormatException>(
            () => MappedExceptions.ParseCatWeight("Oreo"));
    }

    [Fact]
    public void Oreo_ChewedLabel_IsExactType_KotlinFormatException()
    {
        var ex = Assert.ThrowsAny<FormatException>(
            () => MappedExceptions.ParseCatWeight("Oreo"));
        Assert.IsType<KotlinFormatException>(ex);
    }

    [Fact]
    public void Oreo_ChewedLabel_ViaIKotlinException_KotlinType_IsNumberFormatException()
    {
        var ex = Assert.ThrowsAny<FormatException>(
            () => MappedExceptions.ParseCatWeight("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.NumberFormatException", ke.KotlinType);
    }

    [Fact]
    public void Mylo_CatWeight_Succeeds()
    {
        string result = MappedExceptions.ParseCatWeight("Mylo");
        Assert.Equal("Mylo weighs a healthy 4.2 kg", result);
    }

    // --- kotlin.NullPointerException → KotlinNullReferenceException : NullReferenceException (ADR-177) ---
    // Previously unmapped (ADR-029 left it on the base KotlinException); ADR-177 gives it a row.

    [Fact]
    public void Oreo_ToyBehindSofa_NullPointer_IsCatchableAs_NullReferenceException()
    {
        // Oreo knocked the toy behind the sofa; there is nothing to dereference
        Assert.ThrowsAny<NullReferenceException>(
            () => MappedExceptions.RetrieveCatToy("Oreo"));
    }

    [Fact]
    public void Oreo_ToyBehindSofa_IsExactType_KotlinNullReferenceException()
    {
        var ex = Assert.ThrowsAny<NullReferenceException>(
            () => MappedExceptions.RetrieveCatToy("Oreo"));
        Assert.IsType<KotlinNullReferenceException>(ex);
    }

    [Fact]
    public void Oreo_ToyBehindSofa_KotlinType_IsNullPointerException_MessageVerbatim()
    {
        var ex = Assert.ThrowsAny<NullReferenceException>(
            () => MappedExceptions.RetrieveCatToy("Oreo"));
        var ke = Assert.IsAssignableFrom<IKotlinException>(ex);
        Assert.Equal("kotlin.NullPointerException", ke.KotlinType);
        // A non-null Kotlin message stays exactly as Kotlin wrote it
        Assert.Equal("Oreo's toy is gone — he knocked it behind the sofa", ex.Message);
    }

    [Fact]
    public void Mylo_ToyRetrieval_Succeeds()
    {
        string result = MappedExceptions.RetrieveCatToy("Mylo");
        Assert.Equal("Mylo retrieved his favourite toy", result);
    }

    // --- Unmapped: kotlin.IndexOutOfBoundsException → stays base KotlinException (fallback) ---

    [Fact]
    public void Oreo_ClearedShelf_IndexOutOfBounds_IsBaseKotlinException()
    {
        // IndexOutOfBoundsException is NOT mapped — .NET reserves IndexOutOfRangeException for the CLR
        var ex = Assert.Throws<KotlinException>(
            () => MappedExceptions.GetItemFromShelf("Oreo"));
        Assert.IsType<KotlinException>(ex);
    }

    [Fact]
    public void Oreo_ClearedShelf_KotlinType_IsIndexOutOfBoundsException()
    {
        var ex = Assert.Throws<KotlinException>(
            () => MappedExceptions.GetItemFromShelf("Oreo"));
        Assert.Equal("kotlin.IndexOutOfBoundsException", ex.KotlinType);
    }

    [Fact]
    public void Mylo_ShelfAccess_Succeeds()
    {
        string result = MappedExceptions.GetItemFromShelf("Mylo");
        Assert.Equal("Mylo fetched item 0 from the tidy shelf", result);
    }

    // --- Catch-all: new idiom — catch Exception with is IKotlinException guard ---

    [Fact]
    public void CatchAll_ViaIKotlinException_Guard_WorksForMappedType()
    {
        // The new idiom replacing "catch KotlinException" — works for ANY Kotlin exception,
        // mapped or unmapped, because all implement IKotlinException
        Exception? caught = null;
        try
        {
            MappedExceptions.CheckOreoWeight(10); // throws KotlinArgumentException
        }
        catch (Exception ex) when (ex is IKotlinException)
        {
            caught = ex;
        }

        Assert.NotNull(caught);
        var ke = (IKotlinException)caught;
        Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
        Assert.NotEmpty(ke.KotlinStackTrace);
    }

    [Fact]
    public void CatchAll_ViaIKotlinException_Guard_WorksForUnmappedType()
    {
        // Also works for unmapped types (KotlinException : Exception, IKotlinException)
        Exception? caught = null;
        try
        {
            // throws KotlinException (IndexOutOfBoundsException, still unmapped; ADR-177 moved the
            // NullPointerException this fact used to use onto its own row)
            MappedExceptions.GetItemFromShelf("Oreo");
        }
        catch (Exception ex) when (ex is IKotlinException)
        {
            caught = ex;
        }

        Assert.NotNull(caught);
        var ke = (IKotlinException)caught;
        Assert.Equal("kotlin.IndexOutOfBoundsException", ke.KotlinType);
    }

    [Fact]
    public void MappedType_IsCastableToIKotlinException_AndKotlinStackTrace_IsNonEmpty()
    {
        // Verify every mapped exception carries a non-empty KotlinStackTrace via the interface
        var ex = Assert.ThrowsAny<NotSupportedException>(
            () => MappedExceptions.GiveCatABath("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.NotNull(ke.KotlinStackTrace);
        Assert.NotEmpty(ke.KotlinStackTrace);
        Assert.Contains("UnsupportedOperationException", ke.KotlinStackTrace);
    }

    // ===================================================================================
    // ADR-177 (issue #349): mapping by class hierarchy. Fixture: cat/LitterBoxErrors.kt.
    // Oreo treats the litter box as a sandpit; Mylo just uses it.
    // ===================================================================================

    private const string CatPackage = "io.github.xxfast.kotlin.native.nuget.test.cat";

    // --- kotlinx.io.IOException → KotlinIOException : System.IO.IOException ---

    [Fact]
    public void Oreo_SplitBag_KotlinxIoIOException_IsCatchableAs_IOException()
    {
        Assert.ThrowsAny<System.IO.IOException>(() => LitterBoxErrors.Scoop("Oreo"));
    }

    [Fact]
    public void Oreo_SplitBag_IsExactType_KotlinIOException_KeepsTypeAndMessage()
    {
        var ex = Assert.ThrowsAny<System.IO.IOException>(() => LitterBoxErrors.Scoop("Oreo"));
        var kio = Assert.IsType<KotlinIOException>(ex);
        Assert.Equal("kotlinx.io.IOException", kio.KotlinType);
        Assert.Equal("the bag split", ex.Message);
        Assert.NotEmpty(kio.KotlinStackTrace);
    }

    [Fact]
    public void Oreo_SplitBag_ToString_NamesTheKotlinType()
    {
        var ex = Assert.ThrowsAny<System.IO.IOException>(() => LitterBoxErrors.Scoop("Oreo"));
        Assert.Contains("Kotlin type: kotlinx.io.IOException", ex.ToString());
    }

    [Fact]
    public void Mylo_Scoop_Succeeds()
    {
        Assert.Equal("Mylo's litter box is spotless", LitterBoxErrors.Scoop("Mylo"));
    }

    // --- a Kotlin subclass of kotlinx.io.IOException maps to the same row ---

    [Fact]
    public void Oreo_JammedRake_IOExceptionSubclass_IsKotlinIOException_WithConcreteKotlinType()
    {
        // Kotlin: internal class LitterBoxJammedException(m: String) : kotlinx.io.IOException(m)
        var ex = Assert.ThrowsAny<System.IO.IOException>(() => LitterBoxErrors.Rake("Oreo"));
        Assert.IsType<KotlinIOException>(ex);
        Assert.Equal($"{CatPackage}.LitterBoxJammedException", ((IKotlinException)ex).KotlinType);
        Assert.Equal("Oreo buried the rake", ex.Message);
    }

    [Fact]
    public void Mylo_Rake_Succeeds()
    {
        Assert.Equal("Mylo's litter is raked into neat rows", LitterBoxErrors.Rake("Mylo"));
    }

    // --- suspend route (the issue's own case): EOFException, an IOException subclass ---

    [Fact]
    public async Task Oreo_LitterTruck_SuspendEofException_IsCatchableAs_IOException()
    {
        await Assert.ThrowsAnyAsync<System.IO.IOException>(
            () => LitterBoxErrors.DeliverLitterAsync("Oreo"));
    }

    [Fact]
    public async Task Oreo_LitterTruck_SuspendEofException_IsExactType_KotlinIOException()
    {
        var ex = await Assert.ThrowsAnyAsync<System.IO.IOException>(
            () => LitterBoxErrors.DeliverLitterAsync("Oreo"));
        Assert.IsType<KotlinIOException>(ex);
        Assert.Equal("kotlinx.io.EOFException", ((IKotlinException)ex).KotlinType);
        Assert.Equal("the litter truck never came, Oreo chased it off", ex.Message);
    }

    [Fact]
    public async Task Mylo_LitterDelivery_Succeeds()
    {
        Assert.Equal("fresh litter delivered for Mylo", await LitterBoxErrors.DeliverLitterAsync("Mylo"));
    }

    // --- ADR-202: the runtime-owned suspend-lambda route maps module rows too ---

    [Fact]
    public async Task Oreo_SplitBag_SuspendLambda_KotlinxIoIOException_IsKotlinIOException()
    {
        // Kotlin: val onSplitBag: suspend () -> String, throwing kotlinx.io.IOException for Oreo.
        // Invoked through `nuget_suspend_func0_invoke`, which lives in the runtime, not the module.
        using var feeder = new CatFeeder("Oreo");
        using var onSplitBag = feeder.OnSplitBag;
        var ex = await Assert.ThrowsAnyAsync<System.IO.IOException>(() => onSplitBag.InvokeAsync());
        var kio = Assert.IsType<KotlinIOException>(ex);
        Assert.Equal("kotlinx.io.IOException", kio.KotlinType);
        Assert.Equal("the bag split mid-pour", ex.Message);
    }

    [Fact]
    public async Task Mylo_SplitBag_SuspendLambda_Succeeds()
    {
        using var feeder = new CatFeeder("Mylo");
        using var onSplitBag = feeder.OnSplitBag;
        Assert.Equal("Mylo's bowl is full", await onSplitBag.InvokeAsync());
    }

    // --- IOException as a cause: each node of the chain is classified on its own ---

    [Fact]
    public void Oreo_ScoopAll_IOExceptionCause_MapsInnerExceptionToKotlinIOException()
    {
        // Kotlin: throw IllegalStateException("scoop failed", kotlinx.io.IOException("the bag split"))
        var ex = Assert.ThrowsAny<InvalidOperationException>(() => LitterBoxErrors.ScoopAll("Oreo"));
        Assert.IsType<KotlinInvalidOperationException>(ex);
        var inner = Assert.IsType<KotlinIOException>(ex.InnerException);
        Assert.Equal("kotlinx.io.IOException", inner.KotlinType);
        Assert.Equal("the bag split", inner.Message);
    }

    [Fact]
    public void Mylo_ScoopAll_Succeeds()
    {
        Assert.Equal("Mylo's whole house is scooped", LitterBoxErrors.ScoopAll("Mylo"));
    }

    // --- a Kotlin subclass of a mapped stdlib type maps to that type's row ---

    [Fact]
    public void Oreo_Grumble_IllegalStateSubclass_IsCatchableAs_InvalidOperationException()
    {
        // Kotlin: internal class LitterBoxGrumble : IllegalStateException()
        Assert.ThrowsAny<InvalidOperationException>(() => LitterBoxErrors.Sift("Oreo"));
    }

    [Fact]
    public void Oreo_Grumble_IsExactType_KotlinInvalidOperationException_WithConcreteKotlinType()
    {
        var ex = Assert.ThrowsAny<InvalidOperationException>(() => LitterBoxErrors.Sift("Oreo"));
        Assert.IsType<KotlinInvalidOperationException>(ex);
        Assert.Equal($"{CatPackage}.LitterBoxGrumble", ((IKotlinException)ex).KotlinType);
    }

    [Fact]
    public void Oreo_Grumble_NullKotlinMessage_MessageIsTheKotlinTypeName()
    {
        // LitterBoxGrumble has no message; the C# Message names the concrete class, not "Kotlin error"
        var ex = Assert.ThrowsAny<InvalidOperationException>(() => LitterBoxErrors.Sift("Oreo"));
        Assert.Equal($"{CatPackage}.LitterBoxGrumble", ex.Message);
    }

    [Fact]
    public void Mylo_Sift_Succeeds()
    {
        Assert.Equal("Mylo sat patiently while the litter was sifted", LitterBoxErrors.Sift("Mylo"));
    }

    // --- kotlin.NullPointerException with no message → KotlinNullReferenceException ---

    [Fact]
    public void Oreo_NoOwner_NullPointer_IsExactType_KotlinNullReferenceException()
    {
        var ex = Assert.ThrowsAny<NullReferenceException>(() => LitterBoxErrors.OwnerName("Oreo"));
        var kne = Assert.IsType<KotlinNullReferenceException>(ex);
        Assert.Equal("kotlin.NullPointerException", kne.KotlinType);
    }

    [Fact]
    public void Oreo_NoOwner_NullKotlinMessage_MessageIsTheKotlinTypeName()
    {
        var ex = Assert.ThrowsAny<NullReferenceException>(() => LitterBoxErrors.OwnerName("Oreo"));
        Assert.Equal("kotlin.NullPointerException", ex.Message); // was "Kotlin error"
    }

    [Fact]
    public void Mylo_OwnerName_Succeeds()
    {
        Assert.Equal("Mylo belongs to the whole household", LitterBoxErrors.OwnerName("Mylo"));
    }

    // --- CancellationException on a synchronous call → KotlinOperationCanceledException ---
    // Its own row ahead of IllegalStateException (its Kotlin/Native superclass).

    [Fact]
    public void Oreo_HidesFromBath_SyncCancellation_IsCatchableAs_OperationCanceledException()
    {
        Assert.ThrowsAny<OperationCanceledException>(() => LitterBoxErrors.CancelBathTime("Oreo"));
    }

    [Fact]
    public void Oreo_HidesFromBath_SyncCancellation_IsExactType_KotlinOperationCanceledException()
    {
        var ex = Assert.ThrowsAny<OperationCanceledException>(() => LitterBoxErrors.CancelBathTime("Oreo"));
        Assert.IsType<KotlinOperationCanceledException>(ex);
        Assert.EndsWith("CancellationException", ((IKotlinException)ex).KotlinType);
        Assert.Equal("Oreo cancelled bath time by hiding under the bed", ex.Message);
    }

    [Fact]
    public void Oreo_HidesFromBath_SyncCancellation_IsNotAnInvalidOperationException()
    {
        // The IllegalStateException row must not win: most specific row first
        Exception ex = Assert.ThrowsAny<Exception>(() => LitterBoxErrors.CancelBathTime("Oreo"));
        Assert.IsNotAssignableFrom<InvalidOperationException>(ex);
    }

    [Fact]
    public void Mylo_BathTime_Succeeds()
    {
        Assert.Equal("Mylo had a lovely bath", LitterBoxErrors.CancelBathTime("Mylo"));
    }

    // --- kotlin.NoWhenBranchMatchedException (internal, matched by name) → KotlinInvalidOperationException ---

    [Fact]
    public void Oreo_NoLitterBrand_NoWhenBranchMatched_IsExactType_KotlinInvalidOperationException()
    {
        var ex = Assert.ThrowsAny<InvalidOperationException>(() => LitterBoxErrors.PickLitterBrand("Oreo"));
        Assert.IsType<KotlinInvalidOperationException>(ex);
        Assert.Equal("kotlin.NoWhenBranchMatchedException", ((IKotlinException)ex).KotlinType);
    }

    [Fact]
    public void Mylo_LitterBrand_Succeeds()
    {
        Assert.Equal("Mylo prefers the clumping one", LitterBoxErrors.PickLitterBrand("Mylo"));
    }

    // --- an unmapped custom exception still falls back to the base KotlinException ---

    [Fact]
    public void Oreo_ScatteredLitter_UnmappedCustomException_IsBaseKotlinException()
    {
        // Kotlin: internal class ScatteredLitterException(m: String) : Exception(m)
        var ex = Assert.Throws<KotlinException>(() => LitterBoxErrors.SweepLitter("Oreo"));
        Assert.Equal($"{CatPackage}.ScatteredLitterException", ex.KotlinType);
        Assert.Equal("Oreo kicked litter across the hallway", ex.Message);
    }

    [Fact]
    public void Mylo_SweepLitter_Succeeds()
    {
        Assert.Equal("Mylo's hallway is litter-free", LitterBoxErrors.SweepLitter("Mylo"));
    }
}
