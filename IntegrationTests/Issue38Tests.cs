using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

// Issue #38 (ROADMAP line 63): a nullable property on a sealed subclass — a class nested inside its
// sealed parent — must export nullable in the generated CNameExports.kt with the same `errorOut`
// convention the top-level path carries, so that:
//   1. `compileKotlinMingwX64` accepts the generated file at all (today it does not:
//      `Return type mismatch: expected 'String', actual 'String?'` at
//      `export_issue38state_loaded_get_error`, and `expected 'Int', actual 'Int?'` at
//      `export_issue38state_loaded_get_retries`), and
//   2. the C# consumer sees `string?` / `int?`, not `string` / `int`.
//
// Issue38State.Loaded is the shape that blocks the idiomatic
// `sealed class UiState { data class Success(val error: String? = null) : UiState() }`.
// The report's other nesting shape — a plain class nesting a data class — is out of scope here:
// such a class is never collected at all, which is a separate missing capability.
//
// Every case asserts BOTH the null and the non-null reading of each nullable property. A null-only
// fixture would pass against an export that hard-codes null, and a value-only one would pass
// against the current non-null export; only the pair pins the round trip.
//
// The non-null `Code` int is the control: it must keep reading correctly while its nullable
// siblings are the ones the export drops the `?` from.
//
// Sealed subclasses carry only an internal C# constructor (they arrive through FromHandle), so
// instances come from the Kotlin factory, exactly as Observation.Alive does in SealedClassTests.
//
// EXPECTED RED until kotlin-dev fixes the sealed-subclass nullable export path — the pack itself
// does not compile today, so this file cannot even be built against a fresh Interop.cs. Do not
// weaken these assertions to make them go green early.
public class Issue38Tests
{
    [Fact]
    public void State_Loaded_WithValues_ErrorRoundTrips()
    {
        // Oreo: black with white in the middle, like the biscuit, and hard on water bowls.
        using Issue38State state = Issue38Sample.Issue38State(0);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        string? error = loaded.Error;
        Assert.Equal("Oreo knocked the water bowl over", error);
    }

    [Fact]
    public void State_Loaded_WithValues_RetriesRoundTrips()
    {
        using Issue38State state = Issue38Sample.Issue38State(0);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        int? retries = loaded.Retries;
        Assert.Equal(3, retries);
    }

    [Fact]
    public void State_Loaded_WithNulls_ErrorIsNull()
    {
        // Mylo — brown and creamy, like the drink Milo — settled in without incident: loaded,
        // but nothing went wrong.
        using Issue38State state = Issue38Sample.Issue38State(1);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        string? error = loaded.Error;
        Assert.Null(error);
    }

    [Fact]
    public void State_Loaded_WithNulls_RetriesIsNull()
    {
        using Issue38State state = Issue38Sample.Issue38State(1);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        int? retries = loaded.Retries;
        Assert.Null(retries);
    }

    [Fact]
    public void State_Loaded_NonNullCode_ReadsRegardlessOfNullSiblings()
    {
        // The control: the non-null scalar must read the same whether or not its nullable
        // siblings carry a value, so a shifted or miswidened export cannot hide here.
        using Issue38State withValues = Issue38Sample.Issue38State(0);
        using Issue38State withNulls = Issue38Sample.Issue38State(1);
        Assert.Equal(7, Assert.IsType<Issue38State.Loaded>(withValues).Code);
        Assert.Equal(7, Assert.IsType<Issue38State.Loaded>(withNulls).Code);
    }

    [Fact]
    public void State_Idle_IsSealed()
    {
        Assert.True(typeof(Issue38State.Idle).IsSealed);
    }

    [Fact]
    public void State_Idle_StillResolves()
    {
        // The nullable-free sibling of the hierarchy must keep working: a fix to the nullable
        // export path must not disturb the data-object branch.
        using Issue38State state = Issue38Sample.Issue38State(2);
        Assert.IsType<Issue38State.Idle>(state);
    }

    [Fact]
    public void State_Idle_ToString()
    {
        using Issue38State state = Issue38Sample.Issue38State(2);
        Assert.Equal("Idle", state.ToString());
    }

    [Fact]
    public void State_Loaded_Error_IsNullableString()
    {
        // The C# surface itself, not just the value: `Error` must be `string?`.
        var property = typeof(Issue38State.Loaded).GetProperty(nameof(Issue38State.Loaded.Error));
        Assert.NotNull(property);
        Assert.Equal(typeof(string), property!.PropertyType);

        var context = new System.Reflection.NullabilityInfoContext();
        var info = context.Create(property);
        Assert.Equal(System.Reflection.NullabilityState.Nullable, info.ReadState);
    }

    [Fact]
    public void State_Loaded_Retries_IsNullableInt()
    {
        var property = typeof(Issue38State.Loaded).GetProperty(nameof(Issue38State.Loaded.Retries));
        Assert.NotNull(property);
        Assert.Equal(typeof(int?), property!.PropertyType);
    }

    [Fact]
    public void State_Loaded_Code_IsNonNullInt()
    {
        var property = typeof(Issue38State.Loaded).GetProperty(nameof(Issue38State.Loaded.Code));
        Assert.NotNull(property);
        Assert.Equal(typeof(int), property!.PropertyType);
    }

    [Fact]
    public void State_Loaded_PatternMatchesOnNullableError()
    {
        // The pattern the report says is blocked today: branching on a sealed subclass whose
        // payload is nullable.
        using Issue38State state = Issue38Sample.Issue38State(1);

        string message = state switch
        {
            Issue38State.Loaded { Error: null } => "loaded cleanly",
            Issue38State.Loaded loaded => $"loaded with {loaded.Error}",
            Issue38State.Idle => "idle",
            _ => throw new InvalidOperationException(),
        };

        Assert.Equal("loaded cleanly", message);
    }
}

// Sealed-subclass properties on the ADR-062 property plan.
//
// The legacy ADR-009 route spells its own property marshalling instead of going through the
// property plan the ordinary-class route uses, and four mechanisms are wrong or missing on it.
// One cell each, all on the same Issue38State hierarchy:
//
//  17. `Mood?` (nullable enum): the Kotlin export emits `.ordinal` on a nullable receiver, so the
//      generated CNameExports.kt does not compile at all. Until that is fixed nothing in this
//      file runs, exactly as the original issue #38 red.
//  18. `Cat?` (nullable exported reference): the generated C# getter calls its native export
//      twice, and every call mints a fresh StableRef, so one leaks per read. The value/null pair
//      below pins the round trip; the single-call shape is pinned by review of the generated
//      getter, since a leak is not observable from a test.
//  19. `Boolean` / `Boolean?`: the sealed-path `bool` DllImports carry no
//      `[return: MarshalAs(UnmanagedType.I1)]`, so a one-byte Kotlin Boolean is read as a
//      four-byte Win32 BOOL. That only ever shows as a false reading true, which is why every
//      false arm below is an Assert.False and not just an equality on the true arm.
//  20. Any non-List getter passes `out _`: the error slot its Kotlin export fills is discarded, so
//      a throwing Kotlin getter hands C# a default value instead of an exception.
//
// Two more cells cover the consequences ADR-111 names, the capabilities the migration hands the
// sealed route rather than the bugs it fixes:
//
//   - `Note` is a `var`. The legacy route hard-coded `setter = null`, so no sealed subclass had a
//     writable property at all. On the plan it gets a real C# setter.
//   - `Took` and `Id` are planner-only types (`Duration`, `Uuid`). The legacy type dispatch knew
//     neither, so both dropped entirely; the plan binds them as `TimeSpan` (ADR-103) and `Guid`
//     (ADR-106), the same as on any ordinary class.
//
// EXPECTED RED until kotlin-dev moves sealed-subclass properties onto the property plan.
public class Issue38PropertyPlanTests
{
    // --- 17: nullable enum ---

    [Fact]
    public void State_Loaded_WithValues_MoodRoundTrips()
    {
        // Oreo is grumpy about the water bowl he just knocked over.
        using Issue38State state = Issue38Sample.Issue38State(0);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        Mood? mood = loaded.Mood;
        Assert.Equal(Mood.Grumpy, mood);
    }

    [Fact]
    public void State_Loaded_WithNulls_MoodIsNull()
    {
        // Mylo gives nothing away: no mood on record at all.
        using Issue38State state = Issue38Sample.Issue38State(1);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        Mood? mood = loaded.Mood;
        Assert.Null(mood);
    }

    [Fact]
    public void State_Loaded_ThirdArm_MoodRoundTrips()
    {
        // A second non-null enum value, so a fix cannot hard-code one ordinal.
        using Issue38State state = Issue38Sample.Issue38State(3);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        Assert.Equal(Mood.Sleepy, loaded.Mood);
    }

    [Fact]
    public void State_Loaded_Mood_IsNullableMood()
    {
        // The C# surface itself: a `Mood?`, not a `Mood` that can only ever spell an ordinal.
        var property = typeof(Issue38State.Loaded).GetProperty(nameof(Issue38State.Loaded.Mood));
        Assert.NotNull(property);
        Assert.Equal(typeof(Mood?), property!.PropertyType);
    }

    // --- 18: nullable exported reference ---

    [Fact]
    public void State_Loaded_WithValues_FriendRoundTrips()
    {
        // Oreo's friend is Mylo, brown and creamy like the drink.
        using Issue38State state = Issue38Sample.Issue38State(0);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        Cat? friend = loaded.Friend;
        Assert.NotNull(friend);
        Assert.Equal("Mylo", friend!.Name);
    }

    [Fact]
    public void State_Loaded_ThirdArm_FriendRoundTrips()
    {
        // And the other way round: Mylo's friend is Oreo.
        using Issue38State state = Issue38Sample.Issue38State(3);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        Cat? friend = loaded.Friend;
        Assert.NotNull(friend);
        Assert.Equal("Oreo", friend!.Name);
    }

    [Fact]
    public void State_Loaded_WithNulls_FriendIsNull()
    {
        using Issue38State state = Issue38Sample.Issue38State(1);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        Cat? friend = loaded.Friend;
        Assert.Null(friend);
    }

    [Fact]
    public void State_Loaded_Friend_IsNullableCat()
    {
        var property = typeof(Issue38State.Loaded).GetProperty(nameof(Issue38State.Loaded.Friend));
        Assert.NotNull(property);
        Assert.Equal(typeof(Cat), property!.PropertyType);

        var context = new System.Reflection.NullabilityInfoContext();
        var info = context.Create(property);
        Assert.Equal(System.Reflection.NullabilityState.Nullable, info.ReadState);
    }

    [Fact]
    public void State_Loaded_Friend_ReadRepeatedly_StaysStable()
    {
        // Reading the same nullable reference twice must keep answering the same cat. A getter
        // that calls its export once per read is the fix; this at least pins that repeated reads
        // do not start handing back a different or dead handle.
        using Issue38State state = Issue38Sample.Issue38State(0);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        Assert.Equal("Mylo", loaded.Friend!.Name);
        Assert.Equal("Mylo", loaded.Friend!.Name);
    }

    // --- 19: bool marshalling ---

    [Fact]
    public void State_Loaded_WithValues_FlagIsTrue()
    {
        using Issue38State state = Issue38Sample.Issue38State(0);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        Assert.True(loaded.Flag);
    }

    [Fact]
    public void State_Loaded_WithNulls_FlagIsFalse()
    {
        // The one that catches a missing I1: garbage in the upper three bytes of a one-byte
        // Kotlin Boolean reads as a true here.
        using Issue38State state = Issue38Sample.Issue38State(1);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        Assert.False(loaded.Flag);
    }

    [Fact]
    public void State_Loaded_ThirdArm_FlagIsFalse()
    {
        using Issue38State state = Issue38Sample.Issue38State(3);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        Assert.False(loaded.Flag);
    }

    [Fact]
    public void State_Loaded_WithValues_MaybeFlagIsTrue()
    {
        using Issue38State state = Issue38Sample.Issue38State(0);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        bool? maybeFlag = loaded.MaybeFlag;
        Assert.True(maybeFlag);
    }

    [Fact]
    public void State_Loaded_WithNulls_MaybeFlagIsNull()
    {
        using Issue38State state = Issue38Sample.Issue38State(1);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        bool? maybeFlag = loaded.MaybeFlag;
        Assert.Null(maybeFlag);
    }

    [Fact]
    public void State_Loaded_ThirdArm_MaybeFlagIsFalse()
    {
        // Present and false: distinct from absent, and the reading that a missing I1 on the
        // `_value` slot flips. `Assert.False` on the value, not just a NotNull.
        using Issue38State state = Issue38Sample.Issue38State(3);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        bool? maybeFlag = loaded.MaybeFlag;
        Assert.NotNull(maybeFlag);
        Assert.False(maybeFlag!.Value);
    }

    [Fact]
    public void State_Loaded_Flag_IsNonNullBool()
    {
        var property = typeof(Issue38State.Loaded).GetProperty(nameof(Issue38State.Loaded.Flag));
        Assert.NotNull(property);
        Assert.Equal(typeof(bool), property!.PropertyType);
    }

    [Fact]
    public void State_Loaded_MaybeFlag_IsNullableBool()
    {
        var property = typeof(Issue38State.Loaded).GetProperty(nameof(Issue38State.Loaded.MaybeFlag));
        Assert.NotNull(property);
        Assert.Equal(typeof(bool?), property!.PropertyType);
    }

    // --- 20: a throwing getter must throw, not return a default ---

    [Fact]
    public void State_Boom_Resolves()
    {
        using Issue38State state = Issue38Sample.Issue38State(4);
        Assert.IsType<Issue38State.Issue38Boom>(state);
    }

    [Fact]
    public void State_Boom_ReadingBoom_Throws()
    {
        // Oreo's 3am zoomies: reading this is always an illegal state. Under ADR-029
        // IllegalStateException maps to KotlinInvalidOperationException : InvalidOperationException.
        using Issue38State state = Issue38Sample.Issue38State(4);
        var boom = Assert.IsType<Issue38State.Issue38Boom>(state);
        Assert.ThrowsAny<InvalidOperationException>(() => boom.Boom);
    }

    [Fact]
    public void State_Boom_ReadingBoom_ThrowsWithKotlinMessage()
    {
        // Pins the exception to the Kotlin getter's own throw, so the sealed route's
        // "Unknown sealed class type" InvalidOperationException cannot pass this test.
        using Issue38State state = Issue38Sample.Issue38State(4);
        var boom = Assert.IsType<Issue38State.Issue38Boom>(state);
        var ex = Assert.ThrowsAny<InvalidOperationException>(() => boom.Boom);
        Assert.Equal("boom", ex.Message);
    }

    [Fact]
    public void State_Boom_ReadingBoom_KotlinTypeIsIllegalStateException()
    {
        using Issue38State state = Issue38Sample.Issue38State(4);
        var boom = Assert.IsType<Issue38State.Issue38Boom>(state);
        var ex = Assert.ThrowsAny<InvalidOperationException>(() => boom.Boom);
        var ke = (IKotlinException)ex;
        Assert.Equal("kotlin.IllegalStateException", ke.KotlinType);
    }

    // --- ADR-111 consequence: a `var` on a sealed subclass gets a setter ---

    [Fact]
    public void State_Loaded_Note_ReadsTheStartingNote()
    {
        // The read half on its own, so a setter that quietly writes nothing cannot pass the
        // round trip below by having never changed anything.
        using Issue38State state = Issue38Sample.Issue38State(0);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        Assert.Equal("n", loaded.Note);
    }

    [Fact]
    public void State_Loaded_Note_SetterRoundTrips()
    {
        // Oreo's note on the fridge gets amended after the water bowl incident. Assigning at all
        // is the point: the legacy sealed route emitted no setter, so this would not compile.
        using Issue38State state = Issue38Sample.Issue38State(0);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        loaded.Note = "changed";
        Assert.Equal("changed", loaded.Note);
    }

    // --- ADR-111 consequence: planner-only types bind instead of skipping ---

    [Fact]
    public void State_Loaded_Took_RoundTripsAsTimeSpan()
    {
        // How long Mylo took to get to the bowl. `Duration` was absent from the sealed C# surface
        // entirely; the local is typed `TimeSpan` so the ADR-103 mapping is pinned at compile time
        // as well as by the value.
        using Issue38State state = Issue38Sample.Issue38State(0);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        TimeSpan took = loaded.Took;
        Assert.Equal(TimeSpan.FromMilliseconds(1500), took);
    }

    [Fact]
    public void State_Loaded_Id_RoundTripsAsGuid()
    {
        // Mylo's microchip, fixed rather than random so the exact value is assertable. `Uuid` was
        // the other type the legacy dispatch skipped; ADR-106 maps it to `Guid`.
        using Issue38State state = Issue38Sample.Issue38State(0);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        Guid id = loaded.Id;
        Assert.Equal(Guid.Parse("feedface-0a1e-4c0a-b0b0-0ff1ceb0bade"), id);
    }
}
