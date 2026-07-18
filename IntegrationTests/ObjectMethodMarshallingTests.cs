using TestLibrary.Clinic;

namespace IntegrationTests;

// ROADMAP Phase 3 / ADR-060 cells 1 & 25: a Kotlin `object`'s methods must marshal their
// returns and expose PascalCase names, exactly like a class method. Before the fix, the object
// path had no marshalling at all — `Clinic.greet` surfaced as `public static IntPtr greet(string)`
// (a leaked native pointer) with a camelCase name. These assertions are the consumer-side proof
// of the fix: they will not compile until `Greet` returns `string` and is PascalCased.
public class ObjectMethodMarshallingTests
{
    [Fact]
    public void Clinic_IsStaticClass()
    {
        Assert.True(typeof(Clinic).IsAbstract && typeof(Clinic).IsSealed);
    }

    // Cell 1: object method returning String must marshal to a real `string`, not leak an IntPtr.
    // The consumer never touches Marshal.PtrToStringUTF8.
    [Fact]
    public void Clinic_Greet_ReturnsMarshalledString()
    {
        string greeting = Clinic.Greet("Bob");
        Assert.Equal("Welcome to the clinic, Bob", greeting);
    }

    // Cell 3 control: primitive return keeps working (no marshalling needed).
    [Fact]
    public void Clinic_Capacity_ReturnsInt()
    {
        Assert.Equal(12, Clinic.Capacity());
    }

    // Cell 3 control: void return keeps working (still error-checked across the bridge).
    [Fact]
    public void Clinic_Reset_ReturnsVoid()
    {
        Clinic.Reset();
    }

    // Cell 25: the public method must be PascalCase, matching every class method, while the
    // native entry point stays lowercased (`clinic_greet`). Reflection over the returned type
    // proves the exposed name is `Greet`, not `greet`.
    [Fact]
    public void Clinic_Greet_IsPascalCased()
    {
        Assert.NotNull(typeof(Clinic).GetMethod("Greet"));
        Assert.Null(typeof(Clinic).GetMethod("greet"));
    }
}
