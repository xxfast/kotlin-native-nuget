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
/// The constructor is <em>expected</em> to stay unbound (a <c>Throwable</c> parameter is out of
/// ADR-107's scope), which is why every value below comes from a Kotlin factory.
/// </para>
/// </summary>
public class Issue56Tests
{
    // --- Nullable getter: the null half ---

    [Fact]
    public void QuietMishap_NullableThrowableProperty_ReadsNull()
    {
        using var failure = Issue56Sample.quietMishap();

        // Compile-time contract: the property type is Exception?, not KotlinException?.
        Exception? error = failure.Error;

        Assert.Null(error);
    }

    [Fact]
    public void QuietMishap_ThePlainStringComponent_StillBinds()
    {
        // Regression guard: adding a Throwable component must not drop the whole data class.
        using var failure = Issue56Sample.quietMishap();

        Assert.Equal("Mylo knocked the water bowl over", failure.Reason);
    }

    // --- Nullable getter: the populated half, with ADR-029 mapping and the ADR-028 chain ---

    [Fact]
    public void DietViolation_NullableThrowableProperty_IsNotNull()
    {
        using var failure = Issue56Sample.dietViolation();

        Assert.NotNull(failure.Error);
    }

    [Fact]
    public void DietViolation_Error_IsTheAdr029MappedSubtype()
    {
        using var failure = Issue56Sample.dietViolation();

        Assert.IsType<KotlinArgumentException>(failure.Error);
    }

    [Fact]
    public void DietViolation_Error_IsCatchableShapedAsArgumentException()
    {
        using var failure = Issue56Sample.dietViolation();

        Assert.IsAssignableFrom<ArgumentException>(failure.Error);
    }

    [Fact]
    public void DietViolation_Error_KotlinType_IsIllegalArgumentException()
    {
        using var failure = Issue56Sample.dietViolation();

        var ke = (IKotlinException)failure.Error!;

        Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
    }

    [Fact]
    public void DietViolation_Error_CarriesTheKotlinMessage()
    {
        using var failure = Issue56Sample.dietViolation();

        Assert.Equal("Oreo is on a diet!", failure.Error!.Message);
    }

    [Fact]
    public void DietViolation_Error_CarriesAKotlinStackTraceEvenThoughItWasNeverThrown()
    {
        using var failure = Issue56Sample.dietViolation();

        var ke = (IKotlinException)failure.Error!;

        Assert.NotNull(ke.KotlinStackTrace);
        Assert.NotEmpty(ke.KotlinStackTrace);
    }

    [Fact]
    public void DietViolation_Error_CauseChain_SurvivesAsInnerException()
    {
        using var failure = Issue56Sample.dietViolation();

        Assert.NotNull(failure.Error!.InnerException);
    }

    [Fact]
    public void DietViolation_Error_UnmappedCause_FallsBackToBaseKotlinException()
    {
        // RuntimeException has no ADR-029 mapping, so the cause must be the base type.
        using var failure = Issue56Sample.dietViolation();

        Assert.IsType<KotlinException>(failure.Error!.InnerException);
    }

    [Fact]
    public void DietViolation_Error_UnmappedCause_CarriesItsOwnMessage()
    {
        using var failure = Issue56Sample.dietViolation();

        var inner = (KotlinException)failure.Error!.InnerException!;

        Assert.Equal("the treat jar was left open", inner.Message);
    }

    [Fact]
    public void DietViolation_Error_CauseChain_EndsAfterOneLink()
    {
        using var failure = Issue56Sample.dietViolation();

        Assert.Null(failure.Error!.InnerException!.InnerException);
    }

    // --- Non-null getter: Exception, not Exception? ---

    [Fact]
    public void QuietMishap_NonNullThrowableProperty_ReadsAMappedException()
    {
        using var failure = Issue56Sample.quietMishap();

        // Compile-time contract: non-nullable Exception. No null check needed to dereference it.
        Exception fatal = failure.Fatal;

        Assert.Equal("the kitchen floor is a lake", fatal.Message);
    }

    [Fact]
    public void QuietMishap_NonNullThrowableProperty_IsIllegalStateExceptionsMapping()
    {
        // A different mapping from the one Error carries, so a shared-arm bug cannot pass here.
        using var failure = Issue56Sample.quietMishap();

        Assert.IsType<KotlinInvalidOperationException>(failure.Fatal);
    }

    [Fact]
    public void DietViolation_NonNullAndNullableProperties_AreIndependent()
    {
        using var failure = Issue56Sample.dietViolation();

        Assert.Equal("Oreo is on a diet!", failure.Error!.Message);
        Assert.Equal("the treat jar is empty", failure.Fatal.Message);
    }

    // --- Snapshot, not identity (ADR-107 Consequences) ---

    [Fact]
    public void DietViolation_TwoReads_AreEqualInContentButNotTheSameObject()
    {
        using var failure = Issue56Sample.dietViolation();

        Exception? first = failure.Error;
        Exception? second = failure.Error;

        Assert.Equal(first!.Message, second!.Message);
        Assert.False(ReferenceEquals(first, second));
    }

    // --- A `var Throwable?` binds get-only ---

    [Fact]
    public void LastError_VarThrowableProperty_IsReadable()
    {
        using var failure = Issue56Sample.dietViolation();

        Exception? lastError = failure.LastError;

        Assert.Equal("Oreo is on a diet!", lastError!.Message);
    }

    [Fact]
    public void LastError_VarThrowableProperty_HasNoSetter()
    {
        // C# cannot mint a typed Kotlin Throwable, so ADR-107 binds the setter away. Asserting the
        // property is present first means this goes green on the feature, not on a total drop.
        var property = typeof(Issue56Failure).GetProperty("LastError");

        Assert.NotNull(property);
        Assert.True(property!.CanRead);
        Assert.False(property.CanWrite);
    }

    // --- The sealed-subclass arm (the legacy ADR-009 renderer, a separate code path) ---

    [Fact]
    public void FailedLoad_SealedSubclass_DiscriminatesToTheFailureArm()
    {
        using Issue56LoadState state = Issue56Sample.failedLoad();

        Assert.IsType<Issue56LoadState.Failure>(state);
    }

    [Fact]
    public void FailedLoad_SealedSubclassThrowableProperty_IsTheMappedException()
    {
        using Issue56LoadState state = Issue56Sample.failedLoad();

        var failure = (Issue56LoadState.Failure)state;
        Exception? error = failure.Error;

        Assert.IsType<KotlinArgumentException>(error);
        Assert.Equal("Oreo is on a diet!", error!.Message);
    }

    [Fact]
    public void FailedLoad_SealedSubclassThrowableProperty_KotlinType_IsIllegalArgumentException()
    {
        using Issue56LoadState state = Issue56Sample.failedLoad();

        var ke = (IKotlinException)((Issue56LoadState.Failure)state).Error!;

        Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
    }

    [Fact]
    public void PendingLoad_PayloadFreeArm_StillDiscriminates()
    {
        // The other arm of the same sealed base, so the discriminator has a real choice to make.
        using Issue56LoadState state = Issue56Sample.pendingLoad();

        Assert.IsType<Issue56LoadState.Loading>(state);
    }
}
