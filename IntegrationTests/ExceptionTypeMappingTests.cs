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
            () => MappedExceptions.checkOreoWeight(10));
    }

    [Fact]
    public void Oreo_OnDiet_IsExactType_KotlinArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => MappedExceptions.checkOreoWeight(10));
        Assert.IsType<KotlinArgumentException>(ex);
    }

    [Fact]
    public void Oreo_OnDiet_ViaIKotlinException_KotlinType_IsIllegalArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => MappedExceptions.checkOreoWeight(10));
        var ke = Assert.IsAssignableFrom<IKotlinException>(ex);
        Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
    }

    [Fact]
    public void Oreo_OnDiet_ViaIKotlinException_KotlinStackTrace_IsNonEmpty()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => MappedExceptions.checkOreoWeight(10));
        var ke = (IKotlinException)ex;
        Assert.NotNull(ke.KotlinStackTrace);
        Assert.NotEmpty(ke.KotlinStackTrace);
    }

    [Fact]
    public void Mylo_AcceptsKibble_Succeeds()
    {
        // Mylo is not on a diet — negative grams means we're giving, not taking
        string result = MappedExceptions.checkOreoWeight(-5);
        Assert.Equal("Mylo accepted 5 g of kibble gracefully", result);
    }

    // --- kotlin.IllegalStateException → KotlinInvalidOperationException : InvalidOperationException ---

    [Fact]
    public void Oreo_AsleepLaser_IsCatchableAs_InvalidOperationException()
    {
        // Oreo is asleep — activating the laser is an illegal state
        Assert.ThrowsAny<InvalidOperationException>(
            () => MappedExceptions.activateLaserPointer("Oreo"));
    }

    [Fact]
    public void Oreo_AsleepLaser_IsExactType_KotlinInvalidOperationException()
    {
        var ex = Assert.ThrowsAny<InvalidOperationException>(
            () => MappedExceptions.activateLaserPointer("Oreo"));
        Assert.IsType<KotlinInvalidOperationException>(ex);
    }

    [Fact]
    public void Oreo_AsleepLaser_ViaIKotlinException_KotlinType_IsIllegalStateException()
    {
        var ex = Assert.ThrowsAny<InvalidOperationException>(
            () => MappedExceptions.activateLaserPointer("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.IllegalStateException", ke.KotlinType);
    }

    [Fact]
    public void Mylo_LaserPointer_Succeeds()
    {
        string result = MappedExceptions.activateLaserPointer("Mylo");
        Assert.Equal("Mylo chased the red dot enthusiastically", result);
    }

    // --- kotlin.NoSuchElementException → KotlinInvalidOperationException : InvalidOperationException ---

    [Fact]
    public void Oreo_EmptyTreatBag_IsCatchableAs_InvalidOperationException()
    {
        // Oreo ate all the treats — NoSuchElementException when we try to grab the first one
        Assert.ThrowsAny<InvalidOperationException>(
            () => MappedExceptions.grabFirstTreatFromBag("Oreo"));
    }

    [Fact]
    public void Oreo_EmptyTreatBag_IsExactType_KotlinInvalidOperationException()
    {
        var ex = Assert.ThrowsAny<InvalidOperationException>(
            () => MappedExceptions.grabFirstTreatFromBag("Oreo"));
        Assert.IsType<KotlinInvalidOperationException>(ex);
    }

    [Fact]
    public void Oreo_EmptyTreatBag_ViaIKotlinException_KotlinType_IsNoSuchElementException()
    {
        var ex = Assert.ThrowsAny<InvalidOperationException>(
            () => MappedExceptions.grabFirstTreatFromBag("Oreo"));
        var ke = (IKotlinException)ex;
        // Note: even though C# type is KotlinInvalidOperationException, the KotlinType still
        // carries the original Kotlin fully-qualified class name
        Assert.Equal("kotlin.NoSuchElementException", ke.KotlinType);
    }

    [Fact]
    public void Mylo_TreatBag_Succeeds()
    {
        string result = MappedExceptions.grabFirstTreatFromBag("Mylo");
        Assert.Equal("Mylo found a treat", result);
    }

    // --- kotlin.ConcurrentModificationException → KotlinInvalidOperationException : InvalidOperationException ---

    [Fact]
    public void Oreo_BasketMeddling_IsCatchableAs_InvalidOperationException()
    {
        // Oreo keeps jumping into the basket while we count — concurrent modification
        Assert.ThrowsAny<InvalidOperationException>(
            () => MappedExceptions.countTreatsInBasket("Oreo"));
    }

    [Fact]
    public void Oreo_BasketMeddling_IsExactType_KotlinInvalidOperationException()
    {
        var ex = Assert.ThrowsAny<InvalidOperationException>(
            () => MappedExceptions.countTreatsInBasket("Oreo"));
        Assert.IsType<KotlinInvalidOperationException>(ex);
    }

    [Fact]
    public void Oreo_BasketMeddling_ViaIKotlinException_KotlinType_IsConcurrentModificationException()
    {
        var ex = Assert.ThrowsAny<InvalidOperationException>(
            () => MappedExceptions.countTreatsInBasket("Oreo"));
        var ke = (IKotlinException)ex;
        // Three Kotlin types collapse to KotlinInvalidOperationException; KotlinType distinguishes them
        Assert.Equal("kotlin.ConcurrentModificationException", ke.KotlinType);
    }

    [Fact]
    public void Mylo_TreatBasket_Succeeds()
    {
        string result = MappedExceptions.countTreatsInBasket("Mylo");
        Assert.Equal("Mylo waited patiently; basket has 5 treats", result);
    }

    // --- kotlin.UnsupportedOperationException → KotlinNotSupportedException : NotSupportedException ---

    [Fact]
    public void Oreo_RefusesBath_IsCatchableAs_NotSupportedException()
    {
        // Oreo simply does not support baths
        Assert.ThrowsAny<NotSupportedException>(
            () => MappedExceptions.giveCatABath("Oreo"));
    }

    [Fact]
    public void Oreo_RefusesBath_IsExactType_KotlinNotSupportedException()
    {
        var ex = Assert.ThrowsAny<NotSupportedException>(
            () => MappedExceptions.giveCatABath("Oreo"));
        Assert.IsType<KotlinNotSupportedException>(ex);
    }

    [Fact]
    public void Oreo_RefusesBath_ViaIKotlinException_KotlinType_IsUnsupportedOperationException()
    {
        var ex = Assert.ThrowsAny<NotSupportedException>(
            () => MappedExceptions.giveCatABath("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.UnsupportedOperationException", ke.KotlinType);
    }

    [Fact]
    public void Mylo_Bath_Succeeds()
    {
        string result = MappedExceptions.giveCatABath("Mylo");
        Assert.Equal("Mylo enjoyed a splashy bath", result);
    }

    // --- kotlin.ClassCastException → KotlinInvalidCastException : InvalidCastException ---

    [Fact]
    public void Oreo_BadCast_IsCatchableAs_InvalidCastException()
    {
        // You cannot cast Oreo to a Dog — he is very much a cat
        Assert.ThrowsAny<InvalidCastException>(
            () => MappedExceptions.treatCatAsADog("Oreo"));
    }

    [Fact]
    public void Oreo_BadCast_IsExactType_KotlinInvalidCastException()
    {
        var ex = Assert.ThrowsAny<InvalidCastException>(
            () => MappedExceptions.treatCatAsADog("Oreo"));
        Assert.IsType<KotlinInvalidCastException>(ex);
    }

    [Fact]
    public void Oreo_BadCast_ViaIKotlinException_KotlinType_IsClassCastException()
    {
        var ex = Assert.ThrowsAny<InvalidCastException>(
            () => MappedExceptions.treatCatAsADog("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.ClassCastException", ke.KotlinType);
    }

    [Fact]
    public void Mylo_TreatCatAsACat_Succeeds()
    {
        string result = MappedExceptions.treatCatAsADog("Mylo");
        Assert.Equal("Mylo trotted off happily (still a cat)", result);
    }

    // --- kotlin.ArithmeticException → KotlinArithmeticException : ArithmeticException ---

    [Fact]
    public void Oreo_ZeroTreats_DivisionByZero_IsCatchableAs_ArithmeticException()
    {
        // Oreo stole all treats, leaving zero to divide
        Assert.ThrowsAny<ArithmeticException>(
            () => MappedExceptions.shareRemainingTreats("Oreo"));
    }

    [Fact]
    public void Oreo_ZeroTreats_IsExactType_KotlinArithmeticException()
    {
        var ex = Assert.ThrowsAny<ArithmeticException>(
            () => MappedExceptions.shareRemainingTreats("Oreo"));
        Assert.IsType<KotlinArithmeticException>(ex);
    }

    [Fact]
    public void Oreo_ZeroTreats_ViaIKotlinException_KotlinType_IsArithmeticException()
    {
        var ex = Assert.ThrowsAny<ArithmeticException>(
            () => MappedExceptions.shareRemainingTreats("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.ArithmeticException", ke.KotlinType);
    }

    [Fact]
    public void Mylo_ShareTreats_Succeeds()
    {
        string result = MappedExceptions.shareRemainingTreats("Mylo");
        Assert.Equal("Mylo shared treats evenly with the household", result);
    }

    // --- kotlin.NumberFormatException → KotlinFormatException : FormatException ---

    [Fact]
    public void Oreo_ChewedLabel_NumberFormat_IsCatchableAs_FormatException()
    {
        // Oreo chewed the weight label — parsing fails with NumberFormatException
        Assert.ThrowsAny<FormatException>(
            () => MappedExceptions.parseCatWeight("Oreo"));
    }

    [Fact]
    public void Oreo_ChewedLabel_IsExactType_KotlinFormatException()
    {
        var ex = Assert.ThrowsAny<FormatException>(
            () => MappedExceptions.parseCatWeight("Oreo"));
        Assert.IsType<KotlinFormatException>(ex);
    }

    [Fact]
    public void Oreo_ChewedLabel_ViaIKotlinException_KotlinType_IsNumberFormatException()
    {
        var ex = Assert.ThrowsAny<FormatException>(
            () => MappedExceptions.parseCatWeight("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.NumberFormatException", ke.KotlinType);
    }

    [Fact]
    public void Mylo_CatWeight_Succeeds()
    {
        string result = MappedExceptions.parseCatWeight("Mylo");
        Assert.Equal("Mylo weighs a healthy 4.2 kg", result);
    }

    // --- Unmapped: kotlin.NullPointerException → stays base KotlinException (fallback) ---

    [Fact]
    public void Oreo_ToyBehindSofa_NullPointer_IsBaseKotlinException()
    {
        // NullPointerException is NOT mapped — .NET reserves NullReferenceException for the CLR
        var ex = Assert.Throws<KotlinException>(
            () => MappedExceptions.retrieveCatToy("Oreo"));
        Assert.IsType<KotlinException>(ex);
    }

    [Fact]
    public void Oreo_ToyBehindSofa_KotlinType_IsNullPointerException()
    {
        var ex = Assert.Throws<KotlinException>(
            () => MappedExceptions.retrieveCatToy("Oreo"));
        Assert.Equal("kotlin.NullPointerException", ex.KotlinType);
    }

    [Fact]
    public void Oreo_ToyBehindSofa_IsNotArgumentException()
    {
        // Confirm it is NOT a mapped subtype
        Assert.Throws<KotlinException>(
            () => MappedExceptions.retrieveCatToy("Oreo"));
        // No KotlinArgumentException / KotlinInvalidOperationException etc. — plain KotlinException
    }

    [Fact]
    public void Mylo_ToyRetrieval_Succeeds()
    {
        string result = MappedExceptions.retrieveCatToy("Mylo");
        Assert.Equal("Mylo retrieved his favourite toy", result);
    }

    // --- Unmapped: kotlin.IndexOutOfBoundsException → stays base KotlinException (fallback) ---

    [Fact]
    public void Oreo_ClearedShelf_IndexOutOfBounds_IsBaseKotlinException()
    {
        // IndexOutOfBoundsException is NOT mapped — .NET reserves IndexOutOfRangeException for the CLR
        var ex = Assert.Throws<KotlinException>(
            () => MappedExceptions.getItemFromShelf("Oreo"));
        Assert.IsType<KotlinException>(ex);
    }

    [Fact]
    public void Oreo_ClearedShelf_KotlinType_IsIndexOutOfBoundsException()
    {
        var ex = Assert.Throws<KotlinException>(
            () => MappedExceptions.getItemFromShelf("Oreo"));
        Assert.Equal("kotlin.IndexOutOfBoundsException", ex.KotlinType);
    }

    [Fact]
    public void Mylo_ShelfAccess_Succeeds()
    {
        string result = MappedExceptions.getItemFromShelf("Mylo");
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
            MappedExceptions.checkOreoWeight(10); // throws KotlinArgumentException
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
            MappedExceptions.retrieveCatToy("Oreo"); // throws KotlinException (NullPointerException, unmapped)
        }
        catch (Exception ex) when (ex is IKotlinException)
        {
            caught = ex;
        }

        Assert.NotNull(caught);
        var ke = (IKotlinException)caught;
        Assert.Equal("kotlin.NullPointerException", ke.KotlinType);
    }

    [Fact]
    public void MappedType_IsCastableToIKotlinException_AndKotlinStackTrace_IsNonEmpty()
    {
        // Verify every mapped exception carries a non-empty KotlinStackTrace via the interface
        var ex = Assert.ThrowsAny<NotSupportedException>(
            () => MappedExceptions.giveCatABath("Oreo"));
        var ke = (IKotlinException)ex;
        Assert.NotNull(ke.KotlinStackTrace);
        Assert.NotEmpty(ke.KotlinStackTrace);
        Assert.Contains("UnsupportedOperationException", ke.KotlinStackTrace);
    }
}
