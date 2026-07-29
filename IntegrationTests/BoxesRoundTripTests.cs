using System.Text.Json;
using TestLibrary.Boxes;

namespace IntegrationTests;

// ADR-072: closed constructed generics from C# in Kotlin. C# declares `Box<T>`, `Pairing<TKey,
// TValue>` and `Crate<T>`; Kotlin binds them as real generic classes over a per-instantiation
// witness (Decision 1), never a monomorphized `BoxOfInt`/`BoxOfString` family.
//
//   C# IntegrationTests
//     -> (forward bridge, Interop.cs)   BoxesSample.*
//       -> Kotlin test-library          BoxesSample.kt
//         -> (reverse bridge, ADR-072)  test.boxes.{Box,Pairing,Crate,Boxes}
//           -> real C# TestDependency   Test.Boxes.{Box,Pairing,Crate,Boxes}
//
// These exercise the bridge at runtime, which is the only seam that proves the per-instantiation
// witness dispatch, marshalling and registration actually work rather than merely compile.
public class BoxesRoundTripTests
{
    [Fact]
    public void BoxOfInt_Value_RoundTrips()
    {
        // Construction via the fake top-level constructor (Decision 5): Box(42) in Kotlin, no
        // Box.ofInt(42) fallback needed (settled by the pre-Step-3 spike).
        int result = BoxesSample.boxOfIntValue(42);
        Assert.Equal(42, result);
    }

    [Fact]
    public void BoxOfInt_Describe_IsTFreeButStillPerInstantiation()
    {
        // Describe() mentions no T, but CS8895 still forces a per-instantiation thunk.
        string result = BoxesSample.boxOfIntDescribe(42);
        Assert.Equal("box[42]", result);
    }

    [Fact]
    public void BoxOfText_Value_IsARealKotlinString()
    {
        // Oreo: black with white in the middle, like the biscuit. Box<string> has no fake
        // constructor (skipped_ambiguous_generic_constructor vs Box<string?>), so it is only
        // reachable via Boxes.ofText. .uppercase() proves a real Kotlin String, not a raw pointer.
        string result = BoxesSample.boxOfTextUppercased("oreo");
        Assert.Equal("OREO", result);
    }

    [Fact]
    public void BoxOfMaybeText_Null_StaysGenuinelyNull()
    {
        // Decision 7: Box<string?> needs the index-aware pre-order NullableAttribute decode,
        // not the repo's index-0 decoder (which would silently bind this as non-null).
        bool isNull = BoxesSample.boxOfMaybeTextIsNull();
        Assert.True(isNull);
    }

    [Fact]
    public void BoxOfMood_EnumTypeArgument_NoCastNeeded()
    {
        // Mylo: brown and creamy, like the drink. Test.Enums.CatMood as a cross-namespace enum
        // type argument.
        bool isSleepy = BoxesSample.boxOfMoodIsSleepy();
        Assert.True(isSleepy);
    }

    [Fact]
    public void UnwrapBoxOfInt_InstantiationAtParameterPosition()
    {
        // Boxes.Unwrap(Box<int>) takes the instantiation as a PARAMETER, not just a return.
        int result = BoxesSample.unwrapBoxOfInt(7);
        Assert.Equal(7, result);
    }

    [Fact]
    public void RewrapBoxOfInt_InstantiationReachedByPhaseBSubstitution()
    {
        // Box<T>.Rewrap(): Box<T> is only discoverable by substituting T at the definition's own
        // members (Decision 2 phase B), a second fixed-point round over the same instantiation.
        int result = BoxesSample.rewrapBoxOfInt(9);
        Assert.Equal(9, result);
    }

    [Fact]
    public void TallyPairing_Arity2_ParameterNamesFromMetadata()
    {
        // Pairing<TKey, TValue>: arity 2, deliberately not named Pair (kotlin.Pair collision).
        string result = BoxesSample.tallyPairing("cats", 3);
        Assert.Equal("cats/3", result);
    }

    [Fact]
    public void CrateOfText_ConstraintReadButNotEmitted()
    {
        // Crate<T> where T : class: the constraint is read from metadata (Decision 8) but v1
        // emits no Kotlin type-parameter constraint.
        string result = BoxesSample.crateOfTextItem("boxed");
        Assert.Equal("boxed", result);
    }

    [Fact]
    public void BoxLifetime_UseBlockFreesHandle()
    {
        // ADR-051 lifetime is unchanged and generic-agnostic.
        int result = BoxesSample.boxLifetimeUse(7);
        Assert.Equal(7, result);
    }

    [Fact]
    public void DescribeAll_PolymorphismOverTheGenericItself()
    {
        // The reason we are not monomorphizing: one function generic over Box<T>, called here
        // over a list of two Box<Int> instances.
        string result = BoxesSample.describeAllBoxOfInt(1, 2);
        Assert.Equal("box[1],box[2]", result);
    }
}

// Expected absences and diagnostics: reverse-ir.json is the only seam that can see *why* a
// member was skipped (a skipped member simply never appears in the generated Kotlin surface),
// so these tests read the extraction artifact directly, mirroring MenagerieDiagnosticsTests.
public class BoxesDiagnosticsTests
{
    private static JsonElement TestDependencyAssembly()
    {
        string repoRoot = FindRepoRoot();
        string reverseIrPath = Path.Combine(
            repoRoot, "test-library", "build", "nuget-interop", "reverse-ir.json");
        Assert.True(
            File.Exists(reverseIrPath),
            $"reverse-ir.json not found at {reverseIrPath}. Run `scripts/verify.sh` (or at " +
            "least `./gradlew :test-library:nugetExtractApi`) first. This test reads the " +
            "extraction artifact, it does not produce it.");

        using JsonDocument doc = JsonDocument.Parse(File.ReadAllText(reverseIrPath));
        foreach (JsonElement assembly in doc.RootElement.GetProperty("assemblies").EnumerateArray())
        {
            if (assembly.GetProperty("packageId").GetString() == "TestDependency")
            {
                return assembly.Clone();
            }
        }

        throw new InvalidOperationException(
            "reverse-ir.json has no 'TestDependency' assembly entry. Is Test.Boxes bound in " +
            "test-library/build.gradle.kts?");
    }

    /// <summary>The named type from any namespace of <paramref name="assembly"/>, by its CLR
    /// name (arity-mangled for a generic definition, e.g. <c>Box`1</c>).</summary>
    private static JsonElement FindType(JsonElement assembly, string name)
    {
        foreach (JsonElement ns in assembly.GetProperty("namespaces").EnumerateArray())
        {
            foreach (JsonElement type in ns.GetProperty("types").EnumerateArray())
            {
                if (type.GetProperty("name").GetString() == name) return type.Clone();
            }
        }

        throw new InvalidOperationException(
            $"reverse-ir.json has no type named '{name}' in the TestDependency assembly.");
    }

    private static JsonElement Diagnostics(JsonElement assembly) =>
        assembly.GetProperty("diagnostics").Clone();

    private static bool HasDiagnostic(
        JsonElement diagnostics, string typeName, string? memberName, string kindContains)
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

    [Fact]
    public void BoxOfString_NullableAndNonNullable_AreDistinctInstantiations()
    {
        // The precondition for Decision 5's ambiguity rule: the reader must discover Box<string>
        // and Box<string?> as two SEPARATE instantiations. If they collapsed into one, the rule
        // would have nothing to detect and Decision 4's per-instantiation contract hashes would
        // collide.
        //
        // The ambiguity rule itself is deliberately NOT asserted here. It is stated in terms of
        // erased *Kotlin* parameter lists, which only the Gradle generator can compute, so
        // `skipped_ambiguous_generic_constructor` is emitted as a plugin build warning and can
        // never appear in reverse-ir.json (the reader's own output). It is covered, including
        // order-independence, by NugetGenericClassGenerationTest in nuget-plugin.
        JsonElement boxClass = FindType(TestDependencyAssembly(), "Box`1");
        List<bool> stringArgNullability = [];
        foreach (JsonElement inst in boxClass.GetProperty("instantiations").EnumerateArray())
        {
            JsonElement arg = inst.GetProperty("typeArguments")[0];
            if (arg.GetProperty("kind").GetString() == "string")
            {
                stringArgNullability.Add(
                    arg.TryGetProperty("nullable", out JsonElement n) && n.GetBoolean());
            }
        }

        Assert.Equal(2, stringArgNullability.Count);
        Assert.Contains(false, stringArgNullability);
        Assert.Contains(true, stringArgNullability);
    }

    [Fact]
    public void Numbers_UnboundListInstantiation_IsSkippedWithDiagnostic()
    {
        // List<int>: the definition lives outside the bound assemblies.
        JsonElement diagnostics = Diagnostics(TestDependencyAssembly());
        Assert.True(
            HasDiagnostic(diagnostics, "Boxes", "Numbers", "skipped_unbound_generic_instantiation"),
            "expected skipped_unbound_generic_instantiation naming Boxes.Numbers (List<int>)");
    }

    [Fact]
    public void Counts_UnboundDictionaryInstantiation_IsSkippedWithDiagnostic()
    {
        // Dictionary<string,int>: same diagnostic, arity 2.
        JsonElement diagnostics = Diagnostics(TestDependencyAssembly());
        Assert.True(
            HasDiagnostic(diagnostics, "Boxes", "Counts", "skipped_unbound_generic_instantiation"),
            "expected skipped_unbound_generic_instantiation naming Boxes.Counts (Dictionary<string,int>)");
    }

    [Fact]
    public void Nested_BoxOfBoxOfInt_IsSkippedWithDiagnostic()
    {
        // Box<Box<int>>: a generic instantiation as a type argument is outside the Decision 6
        // vocabulary.
        JsonElement diagnostics = Diagnostics(TestDependencyAssembly());
        Assert.True(
            HasDiagnostic(diagnostics, "Boxes", "Nested", "skipped_generic_type_argument"),
            "expected skipped_generic_type_argument naming Boxes.Nested (Box<Box<int>>)");
    }

    [Fact]
    public void Identity_GenericMethod_IsSkippedWithDiagnostic()
    {
        // T Identity<T>(T): a generic METHOD, not resolvable through any instantiation.
        // ADR-043's exclusion survives unchanged.
        JsonElement diagnostics = Diagnostics(TestDependencyAssembly());
        Assert.True(
            HasDiagnostic(diagnostics, "Boxes", "Identity", "skipped_open_generic"),
            "expected skipped_open_generic naming Boxes.Identity");
    }

    [Fact]
    public void Peek_BareTypeParameterNullableReturn_IsSkippedWithDiagnostic()
    {
        // T? Peek(): a bare type parameter annotated nullable is not representable per
        // instantiation.
        JsonElement diagnostics = Diagnostics(TestDependencyAssembly());
        Assert.True(
            HasDiagnostic(diagnostics, "Box`1", "Peek", "skipped_nullable_type_parameter"),
            "expected skipped_nullable_type_parameter naming Box`1.Peek");
    }

    [Fact]
    public void Unused_NeverInstantiated_EmitsExactlyOneInfoDiagnostic()
    {
        // Unused<T>: no Kotlin type, no export, no C# class, exactly one
        // info_uninstantiated_generic_type. Regression test for the Box`1 interpolation leak
        // (ADR-072 Context item 2 / Decision 10): a package-declared generic class must never
        // reach the RIR (or Kotlin/C# source) under its arity-mangled CLR name.
        JsonElement assembly = TestDependencyAssembly();
        JsonElement diagnostics = Diagnostics(assembly);

        int matches = 0;
        foreach (JsonElement d in diagnostics.EnumerateArray())
        {
            if (d.GetProperty("typeName").GetString() != "Unused`1") continue;
            string kind = d.GetProperty("kind").GetString() ?? "";
            if (kind.Contains("info_uninstantiated_generic_type", StringComparison.OrdinalIgnoreCase))
            {
                matches++;
            }
        }
        Assert.Equal(1, matches);

        foreach (JsonElement ns in assembly.GetProperty("namespaces").EnumerateArray())
        {
            foreach (JsonElement type in ns.GetProperty("types").EnumerateArray())
            {
                string? name = type.TryGetProperty("name", out JsonElement n) ? n.GetString() : null;
                Assert.False(
                    name is not null && name.StartsWith("Unused", StringComparison.Ordinal),
                    $"Unused<T> must not reach the RIR under any name; found type '{name}'");
            }
        }
    }
}
