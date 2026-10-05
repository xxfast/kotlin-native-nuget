using TestLibrary;
using TestLibrary.Issue56;

namespace IntegrationTests;

/// <summary>
/// Issue <a href="https://github.com/xxfast/kotlin-native-nuget/issues/56">#56</a> part 2 /
/// ADR-107: a <c>Throwable</c>-typed property reads as a constructed, unthrown
/// <c>System.Exception</c>, riding the error envelope that the thrown position already uses, so the
/// ADR-029 type mapping and the ADR-028 cause chain come along unchanged.
/// <para>
/// Today the classifier has no <c>Throwable</c> branch, so <c>Error</c>, <c>Fatal</c> and
/// <c>LastError</c> are dropped with <c>SKIPPED_UNSUPPORTED_PROPERTY</c> and do not exist on the
/// generated <see cref="Issue56Failure"/>. These tests cannot compile until the feature ships.
/// </para>
/// <para>
/// ADR-201 supersedes ADR-107 decision 4: the constructor and the <c>LastError</c> setter now
/// bind, taking any <c>System.Exception</c>, which Kotlin receives as a
/// <c>NugetManagedException</c> (type name and message only). The values the getter cells read
/// still come from the Kotlin factories, so the mapping and cause-chain cells keep observing real
/// Kotlin exceptions.
/// </para>
/// </summary>
public class Issue56Tests
{
    // --- Nullable getter: the null half ---

    [Fact]
    public void QuietMishap_NullableThrowableProperty_ReadsNull()
    {
        using var failure = Issue56Sample.QuietMishap();

        // Compile-time contract: the property type is Exception?, not KotlinException?.
        Exception? error = failure.Error;

        Assert.Null(error);
    }

    [Fact]
    public void QuietMishap_ThePlainStringComponent_StillBinds()
    {
        // Regression guard: adding a Throwable component must not drop the whole data class.
        using var failure = Issue56Sample.QuietMishap();

        Assert.Equal("Mylo knocked the water bowl over", failure.Reason);
    }

    // --- Nullable getter: the populated half, with ADR-029 mapping and the ADR-028 chain ---

    [Fact]
    public void DietViolation_NullableThrowableProperty_IsNotNull()
    {
        using var failure = Issue56Sample.DietViolation();

        Assert.NotNull(failure.Error);
    }

    [Fact]
    public void DietViolation_Error_IsTheAdr029MappedSubtype()
    {
        using var failure = Issue56Sample.DietViolation();

        Assert.IsType<KotlinArgumentException>(failure.Error);
    }

    [Fact]
    public void DietViolation_Error_IsCatchableShapedAsArgumentException()
    {
        using var failure = Issue56Sample.DietViolation();

        Assert.IsAssignableFrom<ArgumentException>(failure.Error);
    }

    [Fact]
    public void DietViolation_Error_KotlinType_IsIllegalArgumentException()
    {
        using var failure = Issue56Sample.DietViolation();

        var ke = (IKotlinException)failure.Error!;

        Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
    }

    [Fact]
    public void DietViolation_Error_CarriesTheKotlinMessage()
    {
        using var failure = Issue56Sample.DietViolation();

        Assert.Equal("Oreo is on a diet!", failure.Error!.Message);
    }

    [Fact]
    public void DietViolation_Error_CarriesAKotlinStackTraceEvenThoughItWasNeverThrown()
    {
        using var failure = Issue56Sample.DietViolation();

        var ke = (IKotlinException)failure.Error!;

        Assert.NotNull(ke.KotlinStackTrace);
        Assert.NotEmpty(ke.KotlinStackTrace);
    }

    [Fact]
    public void DietViolation_Error_CauseChain_SurvivesAsInnerException()
    {
        using var failure = Issue56Sample.DietViolation();

        Assert.NotNull(failure.Error!.InnerException);
    }

    [Fact]
    public void DietViolation_Error_UnmappedCause_FallsBackToBaseKotlinException()
    {
        // RuntimeException has no ADR-029 mapping, so the cause must be the base type.
        using var failure = Issue56Sample.DietViolation();

        Assert.IsType<KotlinException>(failure.Error!.InnerException);
    }

    [Fact]
    public void DietViolation_Error_UnmappedCause_CarriesItsOwnMessage()
    {
        using var failure = Issue56Sample.DietViolation();

        var inner = (KotlinException)failure.Error!.InnerException!;

        Assert.Equal("the treat jar was left open", inner.Message);
    }

    [Fact]
    public void DietViolation_Error_CauseChain_EndsAfterOneLink()
    {
        using var failure = Issue56Sample.DietViolation();

        Assert.Null(failure.Error!.InnerException!.InnerException);
    }

    // --- Non-null getter: Exception, not Exception? ---

    [Fact]
    public void QuietMishap_NonNullThrowableProperty_ReadsAMappedException()
    {
        using var failure = Issue56Sample.QuietMishap();

        // Compile-time contract: non-nullable Exception. No null check needed to dereference it.
        Exception fatal = failure.Fatal;

        Assert.Equal("the kitchen floor is a lake", fatal.Message);
    }

    [Fact]
    public void QuietMishap_NonNullThrowableProperty_IsIllegalStateExceptionsMapping()
    {
        // A different mapping from the one Error carries, so a shared-arm bug cannot pass here.
        using var failure = Issue56Sample.QuietMishap();

        Assert.IsType<KotlinInvalidOperationException>(failure.Fatal);
    }

    [Fact]
    public void DietViolation_NonNullAndNullableProperties_AreIndependent()
    {
        using var failure = Issue56Sample.DietViolation();

        Assert.Equal("Oreo is on a diet!", failure.Error!.Message);
        Assert.Equal("the treat jar is empty", failure.Fatal.Message);
    }

    // --- Snapshot, not identity (ADR-107 Consequences) ---

    [Fact]
    public void DietViolation_TwoReads_AreEqualInContentButNotTheSameObject()
    {
        using var failure = Issue56Sample.DietViolation();

        Exception? first = failure.Error;
        Exception? second = failure.Error;

        Assert.Equal(first!.Message, second!.Message);
        Assert.False(ReferenceEquals(first, second));
    }

    // --- A `var Throwable?`: readable, and (ADR-201) writable ---

    [Fact]
    public void LastError_VarThrowableProperty_IsReadable()
    {
        using var failure = Issue56Sample.DietViolation();

        Exception? lastError = failure.LastError;

        Assert.Equal("Oreo is on a diet!", lastError!.Message);
    }

    [Fact]
    public void LastError_VarThrowableProperty_WritesAManagedException()
    {
        // ADR-201: Kotlin receives a NugetManagedException, so the read-back carries the managed
        // type name in its message rather than an ADR-029 mapping.
        using var failure = Issue56Sample.DietViolation();

        failure.LastError = new InvalidOperationException("the water bowl is empty");

        Assert.Equal(
            "System.InvalidOperationException: the water bowl is empty",
            failure.LastError!.Message);
    }

    // --- ADR-201: the constructor binds ---

    [Fact]
    public void Constructor_ThrowableParameters_AreConstructibleFromCSharp()
    {
        using var failure = new Issue56Failure(
            "Mylo knocked the water bowl over",
            null,
            new ArgumentException("the floor is a lake"));

        Assert.Null(failure.Error);
        Assert.Equal("System.ArgumentException: the floor is a lake", failure.Fatal.Message);
    }

    // --- The sealed-subclass arm (the legacy ADR-009 renderer, a separate code path) ---

    [Fact]
    public void FailedLoad_SealedSubclass_DiscriminatesToTheFailureArm()
    {
        using Issue56LoadState state = Issue56Sample.FailedLoad();

        Assert.IsType<Issue56LoadState.Failure>(state);
    }

    [Fact]
    public void FailedLoad_SealedSubclassThrowableProperty_IsTheMappedException()
    {
        using Issue56LoadState state = Issue56Sample.FailedLoad();

        var failure = (Issue56LoadState.Failure)state;
        Exception? error = failure.Error;

        Assert.IsType<KotlinArgumentException>(error);
        Assert.Equal("Oreo is on a diet!", error!.Message);
    }

    [Fact]
    public void FailedLoad_SealedSubclassThrowableProperty_KotlinType_IsIllegalArgumentException()
    {
        using Issue56LoadState state = Issue56Sample.FailedLoad();

        var ke = (IKotlinException)((Issue56LoadState.Failure)state).Error!;

        Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
    }

    [Fact]
    public void PendingLoad_PayloadFreeArm_StillDiscriminates()
    {
        // The other arm of the same sealed base, so the discriminator has a real choice to make.
        using Issue56LoadState state = Issue56Sample.PendingLoad();

        Assert.IsType<Issue56LoadState.Loading>(state);
    }
}
