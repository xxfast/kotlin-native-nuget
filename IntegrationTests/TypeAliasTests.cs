using System.Reflection;
using TestLibrary;

namespace IntegrationTests;

public class TypeAliasTests
{
    [Fact]
    public void TopScore_ReturnsInt()
    {
        int result = TypeAliases.TopScore();
        Assert.Equal(10, result);
    }

    [Fact]
    public void DefaultNames_ReturnsReadOnlyListOfString()
    {
        IReadOnlyList<string> names = TypeAliases.DefaultNames();
        Assert.Equal(2, names.Count);
    }

    [Fact]
    public void DefaultNames_ContainsCatNames()
    {
        IReadOnlyList<string> names = TypeAliases.DefaultNames();
        Assert.Equal("Oreo", names[0]);
        Assert.Equal("Mylo", names[1]);
    }

    [Fact]
    public void DefaultScores_ReturnsReadOnlyDictionaryOfStringInt()
    {
        IReadOnlyDictionary<string, int> scores = TypeAliases.DefaultScores();
        Assert.Equal(2, scores.Count);
    }

    [Fact]
    public void DefaultScores_ContainsCatScores()
    {
        IReadOnlyDictionary<string, int> scores = TypeAliases.DefaultScores();
        Assert.Equal(10, scores["Oreo"]);
        Assert.Equal(8, scores["Mylo"]);
    }

    [Fact]
    public void DefaultScores_OreoBeatsMylo()
    {
        IReadOnlyDictionary<string, int> scores = TypeAliases.DefaultScores();
        Assert.True(scores["Oreo"] > scores["Mylo"]);
    }
}

/// <summary>
/// ADR-018 amendment: a typealias's use-site <c>?</c> is part of the type. <c>PetName?</c> (where
/// <c>typealias PetName = String</c>) binds exactly as <c>String?</c> would, on the suspend and
/// StateFlow routes too, and a generic alias <c>Box&lt;Int&gt;</c> binds as <c>List&lt;Int&gt;</c>.
///
/// Every nullable cell is asserted in both directions, and each shape has a converted (string)
/// and an unconverted (int) flavour: a fixture that only ever returned non-null ints could not
/// tell a correct null guard from a missing one.
///
/// Expected red before the fix: the KSP round fails with ERROR_INTERNAL_GENERATOR_FAILURE on
/// <c>namesLater(): PetNames?</c>; past that, the generated Kotlin fails to compile on the
/// suspend and StateFlow exports (<c>String?</c> where <c>Any</c> was expected, and an unsafe call
/// on a nullable <c>StateFlow</c> receiver), and <c>Boxed*</c> are absent (skipped today).
/// </summary>
public class TypeAliasUseSiteNullabilityTests
{
    private static readonly NullabilityInfoContext Nullability = new();

    // ---- top-level suspend: Task<string?> / Task<int?> ----

    [Fact]
    public async Task GreetLater_Oreo_GreetsHim()
    {
        // Oreo answers to his name, a PetName? that is present.
        string? greeting = await TypeAliases.GreetLaterAsync("Oreo");
        Assert.Equal("Hello, Oreo", greeting);
    }

    [Fact]
    public async Task GreetLater_Null_ComesBackNull()
    {
        // Mylo is asleep in the laundry basket and answers to nothing.
        string? greeting = await TypeAliases.GreetLaterAsync(null);
        Assert.Null(greeting);
    }

    [Fact]
    public void GreetLater_ReturnsTaskOfNullableString()
    {
        // Task<string> and Task<string?> are the same runtime type, so only the nullable
        // annotation tells them apart. This is the cell the bug got wrong.
        MethodInfo method = typeof(TypeAliases).GetMethod("GreetLaterAsync")!;
        Assert.Equal(typeof(Task<string>), method.ReturnType);
        NullabilityInfo info = Nullability.Create(method.ReturnParameter);
        Assert.Equal(NullabilityState.Nullable, info.GenericTypeArguments[0].ReadState);
        Assert.Equal(NullabilityState.Nullable, Nullability.Create(method.GetParameters()[0]).WriteState);
    }

    [Fact]
    public async Task AgeLater_Mylo_IsOneYearOlder()
    {
        // Mylo turns three: an unconverted PetAge? that is present.
        int? age = await TypeAliases.AgeLaterAsync(2);
        Assert.Equal(3, age);
    }

    [Fact]
    public async Task AgeLater_Null_ComesBackNullNotZero()
    {
        // Oreo's birthday is a mystery. Must be null, not default(int).
        int? age = await TypeAliases.AgeLaterAsync(null);
        Assert.Null(age);
    }

    [Fact]
    public void AgeLater_ReturnsTaskOfNullableInt()
    {
        MethodInfo method = typeof(TypeAliases).GetMethod("AgeLaterAsync")!;
        Assert.Equal(typeof(Task<int?>), method.ReturnType);
        Assert.Equal(typeof(int?), method.GetParameters()[0].ParameterType);
    }

    [Fact]
    public async Task GreetMaybeLater_AliasToNullable_RoundTripsBothWays()
    {
        // MaybePetName = String?, the alias itself is nullable: already correct, kept honest.
        Assert.Equal("Maybe, Mylo", await TypeAliases.GreetMaybeLaterAsync("Mylo"));
        Assert.Null(await TypeAliases.GreetMaybeLaterAsync(null));

        MethodInfo method = typeof(TypeAliases).GetMethod("GreetMaybeLaterAsync")!;
        NullabilityInfo info = Nullability.Create(method.ReturnParameter);
        Assert.Equal(NullabilityState.Nullable, info.GenericTypeArguments[0].ReadState);
    }

    // ---- PetNames? on the suspend route: binds like List<String>? (ADR-119 amendment) ----

    [Fact]
    public async Task NamesLater_NullableCollectionAlias_BindsLikeItsWrittenOutType()
    {
        // `suspend fun namesLater(): PetNames?` is `List<String>?`, which the suspend route binds as
        // `Task<IReadOnlyList<string>?>` (see Issue122Tests' MaybeAsync). The alias must agree.
        Assert.Null(await TypeAliases.NamesLaterAsync());

        MethodInfo method = typeof(TypeAliases).GetMethod("NamesLaterAsync")!;
        NullabilityInfo info = Nullability.Create(method.ReturnParameter);
        Assert.Equal(NullabilityState.Nullable, info.GenericTypeArguments[0].ReadState);
    }

    // ---- generic alias Box<T> = List<T> ----

    [Fact]
    public void BoxedTallies_BindsAsReadOnlyListOfInt()
    {
        // Oreo caught 2 moths, Mylo caught 3; the box doubles the brag.
        IReadOnlyList<int> doubled = TypeAliases.BoxedTallies([2, 3]);
        Assert.Equal([4, 6], doubled);
    }

    [Fact]
    public void BoxedNames_BindsAsReadOnlyListOfString()
    {
        IReadOnlyList<string> shouted = TypeAliases.BoxedNames(["Oreo", "Mylo"]);
        Assert.Equal(["OREO", "MYLO"], shouted);
    }

    [Fact]
    public async Task BoxedLater_SuspendReturn_BindsAsTaskOfReadOnlyListOfInt()
    {
        // Oreo has 4 white whiskers, Mylo has 2 brown ones.
        IReadOnlyList<int> whiskers = await TypeAliases.BoxedLaterAsync();
        Assert.Equal([4, 2], whiskers);
    }

    // ---- class members ----

    [Fact]
    public void AliasCat_Nick_PlanRoute_StaysNullable()
    {
        using var oreo = new AliasCat("Oreo");
        using var stray = new AliasCat(null);
        Assert.Equal("Oreo-bean", oreo.Nick);
        Assert.Null(stray.Nick);
        Assert.Null(stray.Name);
    }

    [Fact]
    public async Task AliasCat_NicknameLater_RoundTripsBothWays()
    {
        using var mylo = new AliasCat("Mylo");
        using var stray = new AliasCat(null);
        string? nick = await mylo.NicknameLaterAsync();
        string? none = await stray.NicknameLaterAsync();
        Assert.Equal("Mylo-bean", nick);
        Assert.Null(none);

        MethodInfo method = typeof(AliasCat).GetMethod("NicknameLaterAsync")!;
        NullabilityInfo info = Nullability.Create(method.ReturnParameter);
        Assert.Equal(NullabilityState.Nullable, info.GenericTypeArguments[0].ReadState);
    }

    [Fact]
    public async Task AliasCat_Lives_RoundTripsBothWays()
    {
        // Oreo has nine lives; a stray with no name has an unknown number, not zero.
        using var oreo = new AliasCat("Oreo");
        using var stray = new AliasCat(null);
        int? lives = await oreo.LivesAsync();
        int? unknown = await stray.LivesAsync();
        Assert.Equal(9, lives);
        Assert.Null(unknown);
        Assert.Equal(typeof(Task<int?>), typeof(AliasCat).GetMethod("LivesAsync")!.ReturnType);
    }

    [Fact]
    public void AliasCat_MaybeTally_IsNullBeforeTallyStarts()
    {
        // Mylo hasn't started counting his treats yet.
        using var mylo = new AliasCat("Mylo");
        using KotlinStateFlow<int>? tally = mylo.MaybeTally;
        Assert.Null(tally);
    }

    [Fact]
    public void AliasCat_MaybeTally_AfterStart_CarriesTheValue()
    {
        // Oreo starts at 3 treats, then sneaks a fourth off the bench.
        using var oreo = new AliasCat("Oreo");
        oreo.StartTally(3);
        using KotlinStateFlow<int>? tally = oreo.MaybeTally;
        Assert.NotNull(tally);
        Assert.Equal(3, tally.Value);

        oreo.BumpTally(4);
        using KotlinStateFlow<int>? bumped = oreo.MaybeTally;
        Assert.NotNull(bumped);
        Assert.Equal(4, bumped.Value);
    }

    [Fact]
    public void AliasCat_MaybeTally_PropertyIsAnnotatedNullable()
    {
        PropertyInfo property = typeof(AliasCat).GetProperty("MaybeTally")!;
        Assert.Equal(typeof(KotlinStateFlow<int>), property.PropertyType);
        Assert.Equal(NullabilityState.Nullable, Nullability.Create(property).ReadState);
    }
}
