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
        using Issue38State state = Issue38Sample.issue38State(0);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        string? error = loaded.Error;
        Assert.Equal("Oreo knocked the water bowl over", error);
    }

    [Fact]
    public void State_Loaded_WithValues_RetriesRoundTrips()
    {
        using Issue38State state = Issue38Sample.issue38State(0);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        int? retries = loaded.Retries;
        Assert.Equal(3, retries);
    }

    [Fact]
    public void State_Loaded_WithNulls_ErrorIsNull()
    {
        // Mylo — brown and creamy, like the drink Milo — settled in without incident: loaded,
        // but nothing went wrong.
        using Issue38State state = Issue38Sample.issue38State(1);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        string? error = loaded.Error;
        Assert.Null(error);
    }

    [Fact]
    public void State_Loaded_WithNulls_RetriesIsNull()
    {
        using Issue38State state = Issue38Sample.issue38State(1);
        var loaded = Assert.IsType<Issue38State.Loaded>(state);
        int? retries = loaded.Retries;
        Assert.Null(retries);
    }

    [Fact]
    public void State_Loaded_NonNullCode_ReadsRegardlessOfNullSiblings()
    {
        // The control: the non-null scalar must read the same whether or not its nullable
        // siblings carry a value, so a shifted or miswidened export cannot hide here.
        using Issue38State withValues = Issue38Sample.issue38State(0);
        using Issue38State withNulls = Issue38Sample.issue38State(1);
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
        using Issue38State state = Issue38Sample.issue38State(2);
        Assert.IsType<Issue38State.Idle>(state);
    }

    [Fact]
    public void State_Idle_ToString()
    {
        using Issue38State state = Issue38Sample.issue38State(2);
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
        using Issue38State state = Issue38Sample.issue38State(1);

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
