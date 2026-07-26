using System.Text.Json;
using TestLibrary.Menagerie;

namespace IntegrationTests;

// ADR-070: C#-declared interfaces surfacing in Kotlin as a Kotlin `interface`, plus a
// handle-backed implementation (the "Invoker" shape, mirroring Xamarin's Java binding
// Invokers). The reverse mirror of ADR-040 (Kotlin-declared interface -> C# consumer).
//
//   C# IntegrationTests
//     -> (forward bridge, Interop.cs)        MenagerieSample.*
//       -> Kotlin test-library             MenagerieSample.kt
//         -> (reverse bridge, ADR-070)        test.menagerie.{IFeedable,ITagged,Ferret,Sanctuary}
//           -> real C# TestDependency NuGet  Test.Menagerie.{IFeedable,ITagged,Ferret,Sanctuary}
//
// EXPECTED TO FAIL as of this commit: `TestLibrary.Menagerie` does not exist. Neither generator
// consumes `RirInterface` yet (verified this session: `NugetGenerateBindingsTask.kt` and
// `NugetGenerateShimsTask.kt` both `filterIsInstance<RirEnum/RirStruct/RirClass>()` only), so
// `test-library`'s Kotlin consumer code (`MenagerieSample.kt`) does not compile at all today —
// it references `test.menagerie.IFeedable`/`ITagged`, neither of which exists as a Kotlin type,
// and `Sanctuary`'s only members (Star/HiddenResident/Introduce/Featured/Flagship) are all
// interface-typed, so the bound `Sanctuary` class currently generates with none of them. That
// Kotlin compile failure fails `nugetGenerateBindings`/the native compile before this C# project
// even builds, so every [Fact] below fails the same way: the whole test assembly fails to build.
public class MenagerieRoundTripTests
{
    [Fact]
    public void FerretDescribe_ReturnsDescription()
    {
        // Ferret implements every IFeedable member PUBLICLY and identically, so per Decision 5
        // the bound Kotlin class declares the IFeedable supertype directly.
        string result = MenagerieSample.ferretDescribe();
        Assert.Equal("a ferret", result);
    }

    [Fact]
    public void FerretLegs_ReturnsFour()
    {
        // Pass-through int property: needs no marshalling, unlike Describe's string return.
        int legs = MenagerieSample.ferretLegs();
        Assert.Equal(4, legs);
    }

    [Fact]
    public void FerretFeedAndNickname_Oreo_SetsGetsAndClearsToNull()
    {
        // Oreo: black with white in the middle, like the biscuit. Feed() marshals a string
        // PARAMETER; Nickname is a nullable, SETTABLE string property — a getter AND a setter
        // slot (ADR-053 nullability composed with ADR-070's interface members).
        string? result = MenagerieSample.ferretFeedAndNickname("kibble", "Oreo");
        Assert.Equal("Oreo", result);
    }

    [Fact]
    public void StarDescribe_ReturnsFerretDescription()
    {
        // Sanctuary.Star() returns IFeedable. Per Decision 3 the Kotlin value is always an
        // IFeedableHandle, never the concrete Ferret — Describe() still dispatches correctly
        // through the interface's own slot table.
        string result = MenagerieSample.starDescribe();
        Assert.Equal("a ferret", result);
    }

    [Fact]
    public void HiddenResidentLegs_UnboundInternalRuntimeType_StillDispatches()
    {
        // Sanctuary.HiddenResident() returns an IFeedable backed by `Nocturnal`, an internal
        // class never bound or named by the generator. Proves the verified mechanism: interface
        // dispatch through a GCHandle needs no bound, public, or even named runtime type.
        int legs = MenagerieSample.hiddenResidentLegs();
        Assert.Equal(4, legs);
    }

    [Fact]
    public void Introduce_PassesBoundFerretAtInterfaceTypedParameter()
    {
        // Sanctuary.Introduce(IFeedable) takes an interface-typed parameter. Passes a bound
        // Ferret at that position (Decision 4's NugetHandleOwner-based nugetHandle() lowering).
        string result = MenagerieSample.introduce();
        Assert.Equal("introduced a ferret with 4 legs", result);
    }

    [Fact]
    public void FeaturedRoundTrip_SetsGetsAndClearsToNull()
    {
        // Sanctuary.Featured is a nullable, SETTABLE interface-typed property.
        string? result = MenagerieSample.featuredRoundTrip();
        Assert.Equal("a ferret", result);
    }

    [Fact]
    public void FlagshipTagAndLegs_DerivedInterface_DispatchesInheritedAndOwnMembers()
    {
        // Sanctuary.Flagship() returns ITagged (ITagged : IFeedable). Both `Tag` (declared on
        // ITagged) and `Legs` (inherited from IFeedable) must dispatch through the same handle
        // (Decision 5's interface-inheritance case).
        string result = MenagerieSample.flagshipTagAndLegs();
        Assert.Equal("flagship/4", result);
    }
}

// Decision 6's five negative cases, one per diagnostic. These interfaces are never referenced
// from any bound member (IBox<T>/IWithStatics/IWithDim/IIndexedThing/IEmpty appear nowhere in
// Sanctuary or Ferret), so a round trip through TestLibrary cannot observe them at all — a
// skipped interface simply never appears in the generated Kotlin surface, which looks
// indistinguishable from "this interface was never extracted in the first place". The only seam
// that can see *why* it was skipped is the extraction artifact itself: `reverse-ir.json`
// (ADR-046), produced by `nugetExtractApi` before either generator runs. These tests read that
// file directly rather than fake an assertion through a seam that cannot see the answer.
public class MenagerieDiagnosticsTests
{
    private static JsonElement TestDependencyDiagnostics()
    {
        string repoRoot = FindRepoRoot();
        string reverseIrPath = Path.Combine(
            repoRoot, "test-library", "build", "nuget-interop", "reverse-ir.json");
        Assert.True(
            File.Exists(reverseIrPath),
            $"reverse-ir.json not found at {reverseIrPath}. Run `scripts/verify.sh` (or at " +
            "least `./gradlew :test-library:nugetExtractApi`) first — this test reads the " +
            "extraction artifact, it does not produce it.");

        using JsonDocument doc = JsonDocument.Parse(File.ReadAllText(reverseIrPath));
        foreach (JsonElement assembly in doc.RootElement.GetProperty("assemblies").EnumerateArray())
        {
            if (assembly.GetProperty("packageId").GetString() == "TestDependency")
            {
                // Clone so the JsonElement stays valid after `doc` (a `using`) is disposed.
                return assembly.GetProperty("diagnostics").Clone();
            }
        }

        throw new InvalidOperationException(
            "reverse-ir.json has no 'TestDependency' assembly entry. Is Test.Menagerie bound in " +
            "test-library/build.gradle.kts?");
    }

    private static string FindRepoRoot()
    {
        DirectoryInfo? dir = new(AppContext.BaseDirectory);
        while (dir is not null)
        {
            if (Directory.Exists(Path.Combine(dir.FullName, "test-library")) &&
                Directory.Exists(Path.Combine(dir.FullName, "TestDependency")))
            {
                return dir.FullName;
            }
            dir = dir.Parent;
        }

        throw new InvalidOperationException(
            $"could not find the repo root (a directory containing both test-library/ and " +
            $"TestDependency/) walking up from {AppContext.BaseDirectory}");
    }

    private static bool HasDiagnostic(JsonElement diagnostics, string typeName, string? memberName, string kindContains)
    {
        foreach (JsonElement d in diagnostics.EnumerateArray())
        {
            if (d.GetProperty("typeName").GetString() != typeName) continue;
            if (memberName is not null &&
                (!d.TryGetProperty("memberName", out JsonElement m) || m.GetString() != memberName))
            {
                continue;
            }
            string kind = d.GetProperty("kind").GetString() ?? "";
            if (kind.Contains(kindContains, StringComparison.OrdinalIgnoreCase)) return true;
        }
        return false;
    }

    [Fact]
    public void GenericInterface_IBox_IsSkippedWithDiagnostic()
    {
        // IBox<T>: open generic, arity-mangled CLR name (IBox`1) is not valid Kotlin.
        // Kind casing not fixed by the ADR (its Decision 6 table uses SCREAMING_SNAKE_CASE,
        // every diagnostic kind shipped and verified today is lowercase_snake_case) — matched
        // loosely on "generic_interface" so this test survives whichever casing lands.
        JsonElement diagnostics = TestDependencyDiagnostics();
        Assert.True(
            HasDiagnostic(diagnostics, "IBox`1", null, "generic_interface"),
            "expected a *_generic_interface diagnostic naming IBox`1");
    }

    [Fact]
    public void StaticAbstractMember_IWithStatics_IsSkippedWithDiagnostic()
    {
        // IWithStatics.Rank(): static abstract interface member, CS8926 if called through the
        // interface type itself.
        JsonElement diagnostics = TestDependencyDiagnostics();
        Assert.True(
            HasDiagnostic(diagnostics, "IWithStatics", "Rank", "interface_static_member"),
            "expected a *_interface_static_member diagnostic naming IWithStatics.Rank");
    }

    [Fact]
    public void DefaultInterfaceMethod_IWithDim_IsSkippedWithDiagnostic()
    {
        // IWithDim.Greeting(): default interface method. Pre-existing diagnostic, unchanged by
        // ADR-070 (verified in the ADR's spike that it already fires) — this is the one negative
        // case in this fixture that is NOT expected to newly fail: the diagnostic already exists.
        JsonElement diagnostics = TestDependencyDiagnostics();
        Assert.True(
            HasDiagnostic(diagnostics, "IWithDim", "Greeting", "skipped_default_interface_method"),
            "expected the pre-existing skipped_default_interface_method diagnostic naming " +
            "IWithDim.Greeting");
    }

    [Fact]
    public void Indexer_IIndexedThing_IsSkippedWithDiagnostic()
    {
        // this[int]: reaches the reader today as a parameterless property literally named
        // "Item", with no diagnostic. A generated `receiver.Item` thunk would be CS1546.
        JsonElement diagnostics = TestDependencyDiagnostics();
        Assert.True(
            HasDiagnostic(diagnostics, "IIndexedThing", null, "indexer"),
            "expected a *_indexer diagnostic naming IIndexedThing");
    }

    [Fact]
    public void EmptyInterface_IEmpty_IsSkippedWithDiagnostic()
    {
        // IEmpty: zero admissible members. Must not emit an empty registration export.
        JsonElement diagnostics = TestDependencyDiagnostics();
        Assert.True(
            HasDiagnostic(diagnostics, "IEmpty", null, "empty_interface"),
            "expected a *_empty_interface diagnostic naming IEmpty");
    }
}
