using TestLibrary.Nullability;

namespace IntegrationTests;

// ADR-053 (ROADMAP line 156/157) reverse round trip: C# nullable reference type annotations
// (NullableAttribute / NullableContextAttribute) mapped to Kotlin T?.
//
//   C# IntegrationTests
//     -> (forward bridge, Interop.cs)        NicknameSample.findNickname("Oreo")
//       -> Kotlin test-library             fun findNickname(name: String): String?
//         -> (reverse bridge, ADR-053)       test.nullability.NicknameBook.find
//           -> real C# TestDependency NuGet  NicknameBook.Find("Oreo")
//           <- "Biscuit" | null
//
// Expected red state: the metadata reader does not decode NullableAttribute yet (Step 4).
// Concretely:
//   - `defaultNickname(): Nickname` and `primary: Nickname` still bind as nullable
//     (ADR-051's unconditional `Foo?` for every handle return), so `.value` on a nullable
//     receiver fails to compile in test-library.
//   - `describe(nickname: Nickname?)` and `favourite`/`primary`'s setters still bind
//     non-null-parameter / read-only-property (ADR-051 "rule 4"), so passing a nullable local
//     or assigning to `book.favourite` / `book.primary` fails to compile.
//   - `greet(name: String?)` still binds its parameter as non-null `String`, so passing a
//     `String?` local fails to compile.
//   - `note: String?` still binds as non-null `var note: String`, so assigning a `String?`
//     local fails to compile.
//   - `find(name: String): String?` and `lookup(name: String): Nickname?` already compile
//     today (both were already nullable under the old unconditional rules), but `find`'s
//     current non-null-with-`?: error(...)` codegen throws at runtime instead of returning
//     null for Mylo, so `FindNickname_Mylo_ReturnsNull` fails at the assertion, not at compile.
// This file stays red until the kotlin-dev implements the ADR-053 metadata reader and
// generator changes.
public class NullabilityRoundTripTests
{
    [Fact]
    public void FindNickname_Oreo_ReturnsBiscuit()
    {
        // Oreo: black with white in the middle, like the biscuit — his nickname is on record.
        string? result = NicknameSample.findNickname("Oreo");
        Assert.Equal("Biscuit", result);
    }

    [Fact]
    public void FindNickname_Mylo_ReturnsNull()
    {
        // Mylo has no nickname on record: a nullable return that is actually null.
        string? result = NicknameSample.findNickname("Mylo");
        Assert.Null(result);
    }

    [Fact]
    public void GreetNickname_NullName_FallsBackToStranger()
    {
        // Null passed into a nullable parameter.
        string result = NicknameSample.greetNickname(null);
        Assert.Equal("Hello, stranger", result);
    }

    [Fact]
    public void GreetNickname_Mylo_ReturnsHelloMylo()
    {
        // Mylo: brown and creamy, like the drink Milo.
        string result = NicknameSample.greetNickname("Mylo");
        Assert.Equal("Hello, Mylo", result);
    }

    [Fact]
    public void LookupNickname_Mylo_ReturnsCream()
    {
        // Mylo's looked-up nickname: a nullable handle return that isn't null.
        string? result = NicknameSample.lookupNickname("Mylo");
        Assert.Equal("Cream", result);
    }

    [Fact]
    public void LookupNickname_Oreo_ReturnsNull()
    {
        // Oreo has no looked-up nickname: a nullable handle return that is actually null.
        string? result = NicknameSample.lookupNickname("Oreo");
        Assert.Null(result);
    }

    [Fact]
    public void DefaultNickname_ReturnsBiscuit()
    {
        // Non-null handle return: no requireNotNull needed on the Kotlin side anymore.
        string result = NicknameSample.defaultNickname();
        Assert.Equal("Biscuit", result);
    }

    [Fact]
    public void DescribeNickname_Null_ReturnsNone()
    {
        // Null passed into a nullable handle parameter.
        string result = NicknameSample.describeNickname(null);
        Assert.Equal("none", result);
    }

    [Fact]
    public void DescribeNickname_Cream_ReturnsCream()
    {
        string result = NicknameSample.describeNickname("Cream");
        Assert.Equal("Cream", result);
    }

    [Fact]
    public void FavouriteNicknameRoundTrip_Mylo_SetsGetsAndClearsToNull()
    {
        // ROADMAP line 157: the settable handle property `var favourite: Nickname?`.
        string? result = NicknameSample.favouriteNicknameRoundTrip("Mylo");
        Assert.Equal("Mylo", result);
    }

    [Fact]
    public void PrimaryNicknameRoundTrip_Oreo_SetsAndGets()
    {
        // The settable, non-null handle property `var primary: Nickname`.
        string result = NicknameSample.primaryNicknameRoundTrip("Oreo");
        Assert.Equal("Oreo", result);
    }

    [Fact]
    public void NoteRoundTrip_Null_ReturnsNull()
    {
        // The settable, nullable string property `var note: String?`.
        string? result = NicknameSample.noteRoundTrip(null);
        Assert.Null(result);
    }

    [Fact]
    public void NoteRoundTrip_Value_ReturnsValue()
    {
        string? result = NicknameSample.noteRoundTrip("Oreo naps a lot");
        Assert.Equal("Oreo naps a lot", result);
    }

    [Fact]
    public void LegacyFindNickname_Oreo_ReturnsOreo()
    {
        // Oblivious island (`#nullable disable`): binds non-null either way, but should raise
        // one info_oblivious_nullability diagnostic per member (checked at the build level,
        // not assertable here).
        string result = NicknameSample.legacyFindNickname("Oreo");
        Assert.Equal("Oreo", result);
    }
}
