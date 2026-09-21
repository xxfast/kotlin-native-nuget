using System.Text.Json;
using System.Xml.Linq;
using TestLibrary.Admission;
using TestLibrary.Dev.Other.Bykind;
using TestLibrary.Dev.Other.Bytype;

namespace IntegrationTests;

/// <summary>
/// ADR-154: <c>admit(...)</c> is an additive dependency-admission verb that takes a qualified type
/// name or a package prefix. <c>include(...)</c> is untouched: <c>dev.other.bytype</c> and
/// <c>dev.other.bykind</c> appear in no include list, so every type asserted here reached C#
/// through <c>admit</c> alone.
///
/// The fixture is <c>test-models/src/nativeMain/kotlin/dev/other/bytype/Waterbowl.kt</c> (the real
/// klib dependency) plus <c>dev/other/bykind/Tuft.kt</c>, reached from
/// <c>test-library/.../test/admission/Storeroom.kt</c>. Three types are admitted by name, one
/// package by prefix, and two types (<c>Rimguard</c>, the value class <c>Eartag</c>) are left
/// un-admitted on purpose.
///
/// Oreo supervises the store room. Mylo supervises Oreo.
/// </summary>
public class DependencyAdmissionTests
{
    private const string DepNs = "TestLibrary.Dev.Other.Bytype";

    // ---- admitted enums: by value, at every position ------------------------------------------

    /// <summary>
    /// The plain enum (the <c>kermit.Severity</c> shape) is a real C# <c>enum</c>, not a handle:
    /// admission by name gives the same by-value conversion a module-local enum gets.
    /// </summary>
    [Fact]
    public void PlainAdmittedEnum_IsDeclaredAsACSharpEnum_AndRoundTripsAtReturnAndParameter()
    {
        using var storeroom = new Storeroom("Oreo");

        Assert.Equal(Bedding.Fleece, storeroom.Bedding());
        Assert.True(typeof(Bedding).IsEnum);
        Assert.Equal(DepNs, typeof(Bedding).Namespace);
        Assert.Equal("Oreo sleeps on wicker", storeroom.Describe(Bedding.Wicker));
    }

    /// <summary>
    /// The ktor <c>LogLevel</c> shape: constructor properties become extension methods and the
    /// companion member binds, so an admitted enum's edges are exercised, not just its ordinals.
    /// </summary>
    [Fact]
    public void ConstructorPropertyAdmittedEnum_ExposesItsPropertiesAndCompanion()
    {
        using var storeroom = new Storeroom("Mylo");

        Assert.Equal(PurrLevel.Rumble, storeroom.Purr);
        Assert.True(storeroom.Purr.Audible());
        Assert.True(storeroom.Purr.Rumbling());
        Assert.False(PurrLevel.Silent.Audible());
        Assert.Equal(PurrLevel.Rumble, storeroom.Contented());
    }

    /// <summary>The nullable and <c>List&lt;&gt;</c> positions of an admitted enum.</summary>
    [Fact]
    public void AdmittedEnum_BindsAtNullableAndCollectionPositions()
    {
        using var oreo = new Storeroom("Oreo");
        using var mylo = new Storeroom("Mylo");

        Assert.Null(oreo.Quietest());
        Assert.Equal(PurrLevel.Silent, mylo.Quietest());
        Assert.Equal(
            new[] { PurrLevel.Silent, PurrLevel.Chirrup, PurrLevel.Rumble },
            mylo.Purrs());
    }

    // ---- admitted class: a handle, at every position -------------------------------------------

    /// <summary>
    /// The <c>io.ktor.http.Url</c> shape: admitted by name, so it is a disposable handle class
    /// whose <c>String</c> member survives even though two of its members were dropped.
    /// </summary>
    [Fact]
    public void AdmittedDependencyClass_IsAHandle_AndItsStringMemberReadsBack()
    {
        using var storeroom = new Storeroom("Oreo");

        using Waterbowl bowl = storeroom.Bowl();
        Assert.Equal("Oreo's bowl", bowl.Label);
        Assert.Equal(250, bowl.Millilitres());
        Assert.Equal(DepNs, typeof(Waterbowl).Namespace);
        Assert.True(typeof(IDisposable).IsAssignableFrom(typeof(Waterbowl)));
    }

    /// <summary>Constructed straight from C#, then handed back into Kotlin as a parameter.</summary>
    [Fact]
    public void AdmittedDependencyClass_IsConstructible_AndTravelsBackAsAParameter()
    {
        using var storeroom = new Storeroom("Mylo");

        using var bowl = new Waterbowl("the tap, actually");
        Assert.Equal("the tap, actually", storeroom.LabelOf(bowl));
    }

    /// <summary>The property, nullable and <c>List&lt;&gt;</c> positions of the handle route.</summary>
    [Fact]
    public void AdmittedDependencyClass_BindsAtPropertyNullableAndCollectionPositions()
    {
        using var storeroom = new Storeroom("Oreo");

        using Waterbowl spare = storeroom.Spare;
        Assert.Equal("the spare bowl", spare.Label);

        Assert.Null(storeroom.MissingBowl());

        var bowls = storeroom.Bowls();
        Assert.Equal(new[] { "kitchen", "landing" }, bowls.Select(bowl => bowl.Label));
        foreach (Waterbowl bowl in bowls)
        {
            bowl.Dispose();
        }
    }

    // ---- the prefix arm ---------------------------------------------------------------------

    /// <summary>
    /// <c>admit("dev.other.bykind")</c> names no type at all: the package prefix matcher is the
    /// one <c>exclude</c> already uses (ADR-154 §1).
    /// </summary>
    [Fact]
    public void PrefixAdmittedPackage_ExportsItsTypeWithoutNamingIt()
    {
        using var storeroom = new Storeroom("Mylo");

        using Tuft tuft = storeroom.Tuft();
        Assert.Equal("brown", tuft.Colour);
        Assert.Equal("a brown tuft, brushed off the sofa", tuft.Brushed());
        Assert.Equal("TestLibrary.Dev.Other.Bykind", typeof(Tuft).Namespace);
    }

    // ---- absence: no package walk, and the value-class gate -------------------------------------

    /// <summary>
    /// ADR-154 §3: admitting <c>Waterbowl</c> admits that declaration only. Its un-admitted
    /// sibling <c>Rimguard</c> is never declared, and the members mentioning it — on the admitted
    /// class itself and on the in-root reacher — are simply absent.
    /// </summary>
    [Fact]
    public void UnadmittedSiblingType_IsNeverDeclared_AndItsMembersAreAbsent()
    {
        Assert.Null(typeof(Waterbowl).Assembly.GetType($"{DepNs}.Rimguard"));

        // On the admitted dependency class: one method position, one property position.
        Assert.Null(typeof(Waterbowl).GetMethod("FitGuard"));
        Assert.Null(typeof(Waterbowl).GetProperty("Rim"));

        // On the in-root reacher.
        Assert.Null(typeof(Storeroom).GetMethod("Rimguard"));

        // The survivors beside them, so "absent" is not "the whole type failed to bind".
        Assert.NotNull(typeof(Waterbowl).GetProperty("Label"));
        Assert.NotNull(typeof(Storeroom).GetMethod("Bowl"));
    }

    /// <summary>
    /// ROADMAP line 38, folded into ADR-154 §4: an un-admitted top-level dependency
    /// <b>value class</b> must become a named skip. Today it is spelled
    /// <c>global::TestLibrary.Dev.Other.Bytype.Eartag</c> with no declaration and no diagnostic,
    /// which does not compile (CS0246) — so this cell is also what keeps the fixture from blocking
    /// every other test.
    /// </summary>
    [Fact]
    public void UnadmittedTopLevelValueClass_IsNeitherDeclaredNorSpelled()
    {
        Assert.Null(typeof(Waterbowl).Assembly.GetType($"{DepNs}.Eartag"));
        Assert.Null(typeof(Storeroom).GetMethod("Eartag"));
    }

    /// <summary>
    /// ADR-108 must keep working beside the new value-class gate. <c>kotlin.Result</c> is not a
    /// closure terminal, so it is recorded refused <c>NOT_INCLUDED</c> exactly like an un-admitted
    /// dependency value class: a gate keyed on "was it refused" alone would silently amputate the
    /// Result route, which binds as the payload type <c>T</c> and has no C# type of its own. The
    /// carve-out is asserted by behaviour, since there is nothing to reflect over.
    /// </summary>
    [Fact]
    public void ResultReturn_StillBindsAsItsPayload_BesideTheNewValueClassGate()
    {
        using var service = TestLibrary.Cat.ResultSample.Service();
        Assert.Equal("Mylo got a treat", service.Feed("Mylo"));
    }

    // ---- #276 remarks on the owners --------------------------------------------------------

    /// <summary>
    /// ADR-064/#276: a dropped member is named on its owner in the generated XML docs, which is
    /// the premise ADR-154 rests on for keeping warn-and-skip as the default. Loaded here rather
    /// than shared with <c>XmlDocTests</c>, so a red entry in this feature lists this feature's
    /// members.
    /// </summary>
    private static readonly XDocument Doc = LoadDoc();

    private static XDocument LoadDoc()
    {
        string path = Path.ChangeExtension(typeof(DependencyAdmissionTests).Assembly.Location, ".xml");
        if (!File.Exists(path))
        {
            throw new InvalidOperationException(
                $"no documentation file at {path}; IntegrationTests.csproj must set " +
                "<GenerateDocumentationFile>true</GenerateDocumentationFile>");
        }

        return XDocument.Load(path);
    }

    private static XElement Remarks(string name)
    {
        XElement member = Doc
            .Descendants("member")
            .FirstOrDefault(entry => (string?)entry.Attribute("name") == name)
            ?? throw new Xunit.Sdk.XunitException(
                $"no <member name=\"{name}\"> in the documentation file; entries naming an " +
                "admission fixture are:\n  " +
                string.Join(
                    "\n  ",
                    Doc.Descendants("member")
                        .Select(entry => (string?)entry.Attribute("name"))
                        .Where(entry =>
                            entry is not null &&
                            (entry.Contains("Admission") || entry.Contains("Bytype")))));

        return Assert.Single(member.Elements("remarks"));
    }

    /// <summary>
    /// The reacher owns both un-admitted members, so both are named on it, in their Kotlin
    /// spelling, with the additive hint. The un-admitted type names must appear; the surviving
    /// members must not.
    /// </summary>
    [Fact]
    public void Storeroom_NamesBothUnadmittedMembers_WithTheAdditiveAdmitHint()
    {
        using var storeroom = new Storeroom("Oreo");
        Assert.Equal("Oreo's bowl", storeroom.Bowl().Label);

        // Two paragraphs, one per dropped member, in the Kotlin spelling. `CirSkipRemarks` renders
        // kind + reason + Kotlin name and deliberately carries NEITHER the author-facing hint nor
        // the source path, so the `admit(...)` line is asserted in the diagnostics artifact next
        // door, never here.
        string[] paras = Remarks("T:TestLibrary.Admission.Storeroom")
            .Elements("para")
            .Select(para => para.Value)
            .ToArray();

        Assert.Contains(paras, para =>
            para.Contains("`rimguard`") && para.Contains("dev.other.bytype.Rimguard"));
        Assert.Contains(paras, para =>
            para.Contains("`eartag`") && para.Contains("dev.other.bytype.Eartag"));

        // Members that bind are never named as dropped, and the C# spellings never appear: the
        // paragraph names what the author wrote, not a method that never existed.
        Assert.DoesNotContain(paras, para => para.Contains("`bowls`") || para.Contains("`tuft`"));
        Assert.DoesNotContain(paras, para => para.Contains("Rimguard()"));
    }

    /// <summary>
    /// The admitted dependency class carries its own remark: the two members it lost to the
    /// un-admitted sibling. This is what makes "the class is kept, the members skip named"
    /// (ADR-154 §3) observable from C#.
    /// </summary>
    [Fact]
    public void AdmittedDependencyClass_NamesTheMembersItLostToTheUnadmittedSibling()
    {
        using var bowl = new Waterbowl("Mylo's bowl");
        Assert.Equal(250, bowl.Millilitres());

        string[] paras = Remarks($"T:{DepNs}.Waterbowl")
            .Elements("para")
            .Select(para => para.Value)
            .ToArray();

        // The method position and the property position, both named, both in Kotlin spelling.
        // Backticked so `rim` cannot be satisfied by the word "primitive" inside a reason sentence.
        Assert.Contains(paras, para =>
            para.Contains("`fitGuard`") && para.Contains("dev.other.bytype.Rimguard"));
        Assert.Contains(paras, para =>
            para.Contains("`rim`") && para.Contains("dev.other.bytype.Rimguard"));
        Assert.DoesNotContain(paras, para => para.Contains("`FitGuard`"));
        Assert.DoesNotContain(paras, para => para.Contains("`label`"));
    }
}

/// <summary>
/// ADR-154 §5: the un-admitted default stays a warning with a named skip in the ADR-100
/// diagnostics artifact, and the hint becomes the additive <c>add admit("&lt;qualified type&gt;")</c>
/// rather than the whole replacement <c>include(...)</c> line (#60's shape, which cannot apply to
/// an additive verb). Read directly from <c>NugetDiagnostics.json</c>, the way
/// <c>SealedSubclassMethodDiagnosticsTests</c> does.
///
/// Keyed on the skip KIND deliberately per position: ADR-154's spike found a property-position
/// dependency refusal reports <c>SKIPPED_UNSUPPORTED_PROPERTY</c> carrying the dependency hint,
/// while a method/return position reports <c>SKIPPED_UNEXPORTED_DEPENDENCY_TYPE</c>. Anything that
/// counts "dependency type out of scope" keys on
/// <c>ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE</c>, never on the kind.
/// </summary>
public class DependencyAdmissionDiagnosticsTests
{
    private sealed record Diagnostic(string Severity, string Kind, string Declaration, string Message);

    private static string FindRepoRoot()
    {
        DirectoryInfo? dir = new(AppContext.BaseDirectory);
        while (dir is not null)
        {
            if (Directory.Exists(Path.Combine(dir.FullName, "test-library")) &&
                Directory.Exists(Path.Combine(dir.FullName, "IntegrationTests")))
            {
                return dir.FullName;
            }
            dir = dir.Parent;
        }

        throw new InvalidOperationException(
            "could not find the repo root walking up from " + AppContext.BaseDirectory);
    }

    private static (string Path, IReadOnlyList<Diagnostic> Entries)[] DiagnosticFiles()
    {
        string kspRoot = Path.Combine(FindRepoRoot(), "test-library", "build", "generated", "ksp");
        Assert.True(
            Directory.Exists(kspRoot),
            $"{kspRoot} does not exist. Run `scripts/verify.sh` first: this test reads the KSP " +
            "artifact, it does not produce it.");

        string[] files = Directory
            .GetFiles(kspRoot, "NugetDiagnostics.json", SearchOption.AllDirectories)
            .OrderBy(path => path, StringComparer.Ordinal)
            .ToArray();

        Assert.True(files.Length > 0, $"no NugetDiagnostics.json found under {kspRoot}");

        return files.Select(path =>
        {
            using JsonDocument doc = JsonDocument.Parse(File.ReadAllText(path));
            List<Diagnostic> entries = doc.RootElement.EnumerateArray().Select(entry =>
                new Diagnostic(
                    entry.GetProperty("severity").GetString() ?? "",
                    entry.GetProperty("kind").GetString() ?? "",
                    entry.GetProperty("declaration").GetString() ?? "",
                    entry.GetProperty("message").GetString() ?? "")).ToArray().ToList();
            return (path, (IReadOnlyList<Diagnostic>)entries);
        }).ToArray();
    }

    /// <summary>
    /// Asserts one expected skip in every target's file: right kind, warning severity, and a hint
    /// naming the additive <c>admit</c> line the author would paste.
    /// </summary>
    private static void AssertSkipped(string declaration, string kind, string admitEntry)
    {
        foreach ((string path, IReadOnlyList<Diagnostic> entries) in DiagnosticFiles())
        {
            Diagnostic? match = entries.FirstOrDefault(entry =>
                entry.Declaration == declaration && entry.Kind == kind);

            string present = string.Join(
                "\n  ",
                entries
                    .Where(entry =>
                        entry.Declaration.Contains("bytype", StringComparison.Ordinal) ||
                        entry.Declaration.Contains("admission", StringComparison.Ordinal))
                    .Select(entry => $"{entry.Kind} {entry.Declaration}")
                    .DefaultIfEmpty("(no admission-fixture entries at all)"));

            Assert.True(
                match is not null,
                $"expected `{kind} {declaration}` in {path}, but the admission entries there are:" +
                $"\n  {present}");

            Assert.Equal("WARNING", match!.Severity);
            Assert.Contains($"admit(\"{admitEntry}\")", match.Message);

            // The additive verb cannot walk anyone into the #55 replacement trap, so the hint must
            // not spell an include line, and must never fall back to the literal placeholder the
            // collection-element route prints today.
            Assert.DoesNotContain("include(", match.Message);
            Assert.DoesNotContain("the dependency's package", match.Message);
        }
    }

    /// <summary>The method/return position on the in-root reacher: the dependency kind.</summary>
    [Fact]
    public void Storeroom_Rimguard_IsNamedAsAnUnexportedDependencyType()
    {
        AssertSkipped(
            "io.github.xxfast.kotlin.native.nuget.test.admission.Storeroom.rimguard",
            "SKIPPED_UNEXPORTED_DEPENDENCY_TYPE",
            "dev.other.bytype.Rimguard");
    }

    /// <summary>
    /// ROADMAP line 38: the un-admitted top-level value class must be a named skip on the same
    /// route, not a silently spelled undeclared type.
    /// </summary>
    [Fact]
    public void Storeroom_Eartag_TheTopLevelValueClass_IsNamedRatherThanSilentlySpelled()
    {
        AssertSkipped(
            "io.github.xxfast.kotlin.native.nuget.test.admission.Storeroom.eartag",
            "SKIPPED_UNEXPORTED_DEPENDENCY_TYPE",
            "dev.other.bytype.Eartag");
    }

    /// <summary>
    /// The member of the admitted dependency class itself, at a method position: the class is kept
    /// and only the member is lost.
    /// </summary>
    [Fact]
    public void Waterbowl_FitGuard_IsNamedOnTheAdmittedDependencyClass()
    {
        AssertSkipped(
            "dev.other.bytype.Waterbowl.fitGuard",
            "SKIPPED_UNEXPORTED_DEPENDENCY_TYPE",
            "dev.other.bytype.Rimguard");
    }

    /// <summary>
    /// The property position of the same refusal. The kind differs by design (ADR-154, spike 1b),
    /// which is why nothing may count dependency-scope skips by kind.
    /// </summary>
    [Fact]
    public void Waterbowl_Rim_IsNamedUnderThePropertyKind_WithTheSameAdmitHint()
    {
        AssertSkipped(
            "dev.other.bytype.Waterbowl.rim",
            "SKIPPED_UNSUPPORTED_PROPERTY",
            "dev.other.bytype.Rimguard");
    }

    /// <summary>
    /// The admitted types must be reported as admitted, and the un-admitted ones must never appear
    /// in the export manifest: the "no package walk" rule, read from the producer side.
    /// </summary>
    [Fact]
    public void ExportManifest_ListsTheAdmittedTypes_AndNeitherUnadmittedOne()
    {
        foreach ((string path, IReadOnlyList<Diagnostic> entries) in DiagnosticFiles())
        {
            string manifest = string.Join(
                "\n",
                entries
                    .Where(entry => entry.Kind == "INFO_EXPORTED_FROM_DEPENDENCY")
                    .Select(entry => entry.Message));

            Assert.Contains("dev.other.bytype.Bedding", manifest);
            Assert.Contains("dev.other.bytype.PurrLevel", manifest);
            Assert.Contains("dev.other.bytype.Waterbowl", manifest);
            Assert.Contains("dev.other.bykind.Tuft", manifest);
            Assert.DoesNotContain("dev.other.bytype.Rimguard", manifest);
            Assert.DoesNotContain("dev.other.bytype.Eartag", manifest);

            // `include(...)` is untouched by this feature: the package it already admits stays in.
            Assert.Contains("dev.other.admitted.Billboard", manifest);
            Assert.DoesNotContain("dev.other.core.", manifest);
            Assert.True(manifest.Length > 0, $"no export manifest entry in {path}");
        }
    }
}
