using System.Reflection;
using System.Text.Json;
using TestLibrary.Issue463;

namespace IntegrationTests;

/// <summary>
/// Issue #463 / ADR-204: a Kotlin <c>sealed interface</c> whose arms also extend another class.
/// ADR-112 eligibility refuses it (an arm that already has a C# base cannot also extend an abstract
/// <c>ConnectableDevice</c>), so the interface has no discriminator and every member typed with it
/// is dropped with <c>SKIPPED_SEALED_POSITION</c>. The C# types already exist (<c>IConnectableDevice</c>
/// with its <c>Address</c>, arm classes that list it); only the positions are missing, so this file
/// cannot compile until the feature ships, which is the red signal.
/// <para>
/// After ADR-204 the interface keeps its <c>I&lt;Name&gt;</c> spelling, gains an internal
/// <c>_handle</c> and a static <c>FromHandle</c> over its declared arms, and binds at every position
/// the sealed-class route binds at. A returned value is the concrete arm, so it pattern-matches over
/// the interface and is also its sealed class base. Three hierarchies, one per admitted row:
/// <c>IConnectableDevice</c> (every arm an arm of the sealed class <c>EvidenceDevice</c>, including
/// the <c>data object</c> <c>SavedDevice</c>), <c>IChargeable</c> (a plain-superclass arm, a
/// superclass-free arm, and the dual arm), and <c>RemoteDevice</c>, which implements both.
/// </para>
/// <para>
/// Oreo wears the collar tag on the desk (<c>aa:bb</c>); Mylo left his tracker in the attic, where
/// it is somehow still at 80%. Oreo chases the laser; Mylo rolls the treat ball.
/// </para>
/// </summary>
public class SealedInterfaceOverArmsTests
{
    /// <summary>
    /// The ADR's consumer sample: the return position hands back the concrete arm, which a
    /// <c>switch</c> over the interface selects and which is also an <c>EvidenceDevice</c>.
    /// </summary>
    [Fact]
    public void Preferred_ReturnsTheConcreteArm_ThatPatternMatchesOverTheInterface()
    {
        using IConnectableDevice picked = Devices.Preferred();

        string label = picked switch
        {
            NearbyDevice n => $"nearby {n.Address}",
            RemoteDevice r => $"remote {r.Address}",
            SavedDevice s => $"saved {s.Address}",
            _ => throw new InvalidOperationException($"unexpected arm {picked.GetType()}"),
        };

        Assert.Equal("nearby aa:bb", label);
        Assert.Equal("desk", Assert.IsType<NearbyDevice>(picked).Name);
        Assert.IsAssignableFrom<EvidenceDevice>(picked);
    }

    /// <summary>
    /// The parameter position: a C#-built arm is passed where the interface is expected, Kotlin's
    /// exhaustive <c>when</c> reads it, and it comes back as the sealed class base. The data-class
    /// arm compares equal (ADR-008) to the value it was built from, same type only.
    /// </summary>
    [Fact]
    public void Connect_ACSharpBuiltArmAtTheParameter_ComesBackAsItsSealedClassBase()
    {
        using var nearby = new NearbyDevice("desk", "aa:bb");

        using EvidenceDevice connected = Devices.Connect(nearby);

        var back = Assert.IsType<NearbyDevice>(connected);
        Assert.Equal(nearby, back);
        Assert.Equal("aa:bb", back.Address);
    }

    /// <summary>
    /// Every arm of <c>IConnectableDevice</c> at the return position, one per index, so the
    /// discriminator rather than the declared type decides, with the interface member read through
    /// the interface (no cast) on each: Oreo's tag, Mylo's tracker, the remembered pairing.
    /// </summary>
    [Fact]
    public void DeviceAt_DiscriminatesEveryArm_AndTheInterfaceMemberIsCallable()
    {
        using IConnectableDevice oreo = Devices.DeviceAt(0);
        using IConnectableDevice mylo = Devices.DeviceAt(1);
        using IConnectableDevice saved = Devices.DeviceAt(2);

        Assert.Equal("oreo", Assert.IsType<NearbyDevice>(oreo).Name);
        Assert.Equal("attic", Assert.IsType<RemoteDevice>(mylo).Host);
        Assert.IsType<SavedDevice>(saved);

        Assert.Equal("aa:bb", oreo.Address);
        Assert.Equal("remote://attic", mylo.Address);
        Assert.Equal("saved", saved.Address);
    }

    /// <summary>
    /// The <c>data object</c> arm, both ways: it arrives as a real <c>SavedDevice</c> instance
    /// through the interface, crosses back at the parameter, and compares equal to itself across two
    /// separate crossings (the <c>Loaf</c> precedent for a sealed <c>data object</c> arm).
    /// </summary>
    [Fact]
    public void SavedDevice_DataObjectArm_CrossesBothWaysAndIsEqualAcrossCrossings()
    {
        using IConnectableDevice first = Devices.DeviceAt(2);
        using IConnectableDevice second = Devices.DeviceAt(2);

        using EvidenceDevice connected = Devices.Connect(first);

        Assert.IsType<SavedDevice>(connected);
        Assert.Equal(Assert.IsType<SavedDevice>(first), Assert.IsType<SavedDevice>(second));
    }

    /// <summary>
    /// The nullable return: present is Oreo's tag as the concrete arm; absent is <c>null</c>, which
    /// is not the same as the payload-free <c>SavedDevice</c> arm.
    /// </summary>
    [Fact]
    public void NearbyOrNull_NullableReturn_CarriesTheArmOrNull()
    {
        using IConnectableDevice? present = Devices.NearbyOrNull(present: true);
        IConnectableDevice? absent = Devices.NearbyOrNull(present: false);

        Assert.Equal("aa:bb", Assert.IsType<NearbyDevice>(present).Address);
        Assert.Null(absent);
    }

    /// <summary>
    /// The nullable parameter: <c>null</c> crosses as the zero handle and Kotlin reads it as
    /// <c>null</c>; a C#-built arm crosses as its own handle and Kotlin names the arm it received.
    /// </summary>
    [Fact]
    public void DescribeOrNone_NullableParameter_CarriesTheArmOrNull()
    {
        using var nearby = new NearbyDevice("desk", "aa:bb");
        using var tracker = new RemoteDevice(host: "attic", battery: 80);

        Assert.Equal("nearby aa:bb", Devices.DescribeOrNone(nearby));
        Assert.Equal("remote remote://attic", Devices.DescribeOrNone(tracker));
        Assert.Equal("none", Devices.DescribeOrNone(null));
    }

    /// <summary>
    /// The <c>Set</c> component: every arm once, each materialised through the interface's
    /// discriminator as its concrete arm.
    /// </summary>
    [Fact]
    public void ConnectableSet_SetComponent_MaterialisesEveryArm()
    {
        IReadOnlySet<IConnectableDevice> all = Devices.ConnectableSet();
        try
        {
            Assert.Equal(3, all.Count);
            Assert.Single(all.OfType<NearbyDevice>());
            Assert.Single(all.OfType<RemoteDevice>());
            Assert.Single(all.OfType<SavedDevice>());
        }
        finally
        {
            foreach (IConnectableDevice device in all) device.Dispose();
        }
    }

    /// <summary>
    /// The <c>List</c> component: each element materialises through the interface's discriminator,
    /// so every arm comes back as itself, and each one is also an <c>EvidenceDevice</c>.
    /// </summary>
    [Fact]
    public void Connectable_ListComponent_MaterialisesEachArmInOrder()
    {
        IReadOnlyList<IConnectableDevice> all = Devices.Connectable();
        try
        {
            Assert.Collection(
                all,
                oreo => Assert.Equal("oreo", Assert.IsType<NearbyDevice>(oreo).Name),
                mylo => Assert.Equal(80, Assert.IsType<RemoteDevice>(mylo).Battery),
                saved => Assert.IsType<SavedDevice>(saved));
            Assert.All(all, device => Assert.IsAssignableFrom<EvidenceDevice>(device));
        }
        finally
        {
            foreach (IConnectableDevice device in all) device.Dispose();
        }
    }

    /// <summary>
    /// The constructor parameter and the <c>val</c>: the dock is built from a C#-built arm and reads
    /// it back as the concrete arm.
    /// </summary>
    [Fact]
    public void CollarDock_ConstructorParameterAndVal_HoldTheArmItWasBuiltWith()
    {
        using var nearby = new NearbyDevice("desk", "aa:bb");
        using var dock = new CollarDock(nearby);

        using IConnectableDevice first = dock.First;

        Assert.Equal(nearby, Assert.IsType<NearbyDevice>(first));
    }

    /// <summary>
    /// The <c>var</c>, both directions: Oreo's tag is on the dock, Mylo's tracker is written in
    /// through the setter (which only borrows it), and the getter reads the tracker back as the
    /// concrete arm.
    /// </summary>
    [Fact]
    public void CollarDock_VarProperty_RoundTripsTheWrittenArm()
    {
        using var nearby = new NearbyDevice("desk", "aa:bb");
        using var dock = new CollarDock(nearby);

        using (IConnectableDevice before = dock.Current)
        {
            Assert.IsType<NearbyDevice>(before);
        }

        using (var tracker = new RemoteDevice(host: "attic", battery: 80)) dock.Current = tracker;

        using IConnectableDevice current = dock.Current;
        Assert.Equal("attic", Assert.IsType<RemoteDevice>(current).Host);
        Assert.Equal("remote://attic", current.Address);
    }

    /// <summary>
    /// The <c>Map</c> value: every known device keyed by its address, each value the concrete arm.
    /// </summary>
    [Fact]
    public void CollarDock_ByAddress_MapValuesAreConcreteArms()
    {
        using var nearby = new NearbyDevice("desk", "aa:bb");
        using var dock = new CollarDock(nearby);

        IReadOnlyDictionary<string, IConnectableDevice> byAddress = dock.ByAddress();
        try
        {
            Assert.Equal(3, byAddress.Count);
            Assert.IsType<NearbyDevice>(byAddress["aa:bb"]);
            Assert.IsType<RemoteDevice>(byAddress["remote://attic"]);
            Assert.IsType<SavedDevice>(byAddress["saved"]);
        }
        finally
        {
            foreach (IConnectableDevice device in byAddress.Values) device.Dispose();
        }
    }

    /// <summary>
    /// The <c>StateFlow</c> item: Mylo's tracker is written onto the dock through the <c>var</c>,
    /// and the live view's <c>Value</c> materialises it through the interface's <c>FromHandle</c> as
    /// the concrete arm, not a wrapper over the interface.
    /// </summary>
    [Fact]
    public void CollarDock_LiveStateFlow_ValueIsTheConcreteArm()
    {
        using var nearby = new NearbyDevice("desk", "aa:bb");
        using var dock = new CollarDock(nearby);
        using TestLibrary.KotlinStateFlow<IConnectableDevice> live = dock.Live();

        using (var tracker = new RemoteDevice(host: "attic", battery: 80)) dock.Current = tracker;

        using IConnectableDevice now = live.Value;
        Assert.Equal("attic", Assert.IsType<RemoteDevice>(now).Host);
    }

    /// <summary>
    /// The <c>Flow</c> item: every arm once, in order, each element through the same discriminator:
    /// Oreo's tag, Mylo's tracker, the remembered pairing.
    /// </summary>
    [Fact]
    public async Task CollarDock_ScanFlow_SeesEveryArmInOrder()
    {
        using var nearby = new NearbyDevice("desk", "aa:bb");
        using var dock = new CollarDock(nearby);

        var seen = new List<IConnectableDevice>();
        try
        {
            await foreach (IConnectableDevice device in dock.Scan()) seen.Add(device);

            Assert.Collection(
                seen,
                oreo => Assert.Equal("oreo", Assert.IsType<NearbyDevice>(oreo).Name),
                mylo => Assert.Equal("attic", Assert.IsType<RemoteDevice>(mylo).Host),
                saved => Assert.IsType<SavedDevice>(saved));
        }
        finally
        {
            foreach (IConnectableDevice device in seen) device.Dispose();
        }
    }

    /// <summary>
    /// The suspend return, every arm: the completion has to go through the interface's
    /// <c>FromHandle</c>, not a constructor on the declared type (there is none on an interface).
    /// </summary>
    [Fact]
    public async Task ScanLaterAsync_SuspendReturn_DiscriminatesEachArm()
    {
        using IConnectableDevice oreo = await Devices.ScanLaterAsync(0);
        using IConnectableDevice mylo = await Devices.ScanLaterAsync(1);
        using IConnectableDevice saved = await Devices.ScanLaterAsync(2);

        Assert.IsType<NearbyDevice>(oreo);
        Assert.IsType<RemoteDevice>(mylo);
        Assert.IsType<SavedDevice>(saved);
    }

    /// <summary>
    /// The suspend parameter: the handle crosses back on the async route and Kotlin reads the
    /// address, so a pointer that never reached a real Kotlin value cannot answer.
    /// </summary>
    [Fact]
    public async Task ConnectLaterAsync_SuspendParameter_UnwrapsTheHandleBackToKotlin()
    {
        using var tracker = new RemoteDevice(host: "attic", battery: 80);

        Assert.Equal("connected remote://attic", await Devices.ConnectLaterAsync(tracker));
    }

    /// <summary>
    /// Members on a sealed arm taking and returning the interface: Oreo's tag pairs with Mylo's
    /// tracker, and its fallback is a <em>different</em> arm, so the discriminator decides.
    /// </summary>
    [Fact]
    public void NearbyDevice_ArmMembers_TakeAndReturnTheInterface()
    {
        using var oreo = new NearbyDevice("desk", "aa:bb");
        using var mylo = new RemoteDevice(host: "attic", battery: 80);

        Assert.Equal("aa:bb<->remote://attic", oreo.PairWith(mylo));

        using IConnectableDevice fallback = oreo.Fallback();
        Assert.Equal(mylo, Assert.IsType<RemoteDevice>(fallback));
    }

    /// <summary>
    /// The plain-superclass hierarchy at return and parameter: the laser extends the plain
    /// <c>Trinket</c> (and keeps its member), the treat ball has no superclass, and both cross back
    /// to a Kotlin <c>when</c> that names the arm it received.
    /// </summary>
    [Fact]
    public void Chargeable_PlainArms_RoundTripThroughReturnAndParameter()
    {
        using IChargeable laser = Devices.ChargerAt(0);
        using IChargeable ball = Devices.ChargerAt(1);

        var pointer = Assert.IsType<LaserPointer>(laser);
        Assert.Equal("shiny", Assert.IsAssignableFrom<Trinket>(pointer).Sparkle());
        Assert.Equal(15, Assert.IsType<TreatBall>(ball).Battery);

        Assert.Equal("laser 40", Devices.Charge(laser));
        Assert.Equal("ball 15", Devices.Charge(ball));

        using var built = new TreatBall(battery: 3);
        Assert.Equal("ball 3", Devices.Charge(built));
    }

    /// <summary>
    /// The mixed <c>List</c>: two plain arms and the sealed-class arm, each through the same
    /// discriminator, each read through the <c>IChargeable</c> member.
    /// </summary>
    [Fact]
    public void Chargeables_ListComponent_MaterialisesPlainAndSealedArms()
    {
        IReadOnlyList<IChargeable> all = Devices.Chargeables();
        try
        {
            Assert.Collection(
                all,
                laser => Assert.IsType<LaserPointer>(laser),
                ball => Assert.IsType<TreatBall>(ball),
                tracker => Assert.IsType<RemoteDevice>(tracker));
            Assert.Equal([40, 15, 80], all.Select(device => device.Battery));
        }
        finally
        {
            foreach (IChargeable device in all) device.Dispose();
        }
    }

    /// <summary>
    /// The dual arm: <c>RemoteDevice</c> implements both interfaces, so one C#-built value crosses
    /// both parameters, and the same arm returned as <c>IChargeable</c> is also an
    /// <c>IConnectableDevice</c> and an <c>EvidenceDevice</c>.
    /// </summary>
    [Fact]
    public void RemoteDevice_DualArm_CrossesAsBothInterfaces()
    {
        using var tracker = new RemoteDevice(host: "attic", battery: 80);

        using (EvidenceDevice connected = Devices.Connect(tracker))
        {
            Assert.Equal(tracker, Assert.IsType<RemoteDevice>(connected));
        }
        Assert.Equal("remote 80", Devices.Charge(tracker));

        using IChargeable charged = Devices.ChargerAt(2);
        var remote = Assert.IsType<RemoteDevice>(charged);
        Assert.Equal("remote://attic", Assert.IsAssignableFrom<IConnectableDevice>(remote).Address);
        Assert.IsAssignableFrom<EvidenceDevice>(remote);
    }

    /// <summary>
    /// The declaration shape ADR-204 keeps: both hierarchies stay C# interfaces (not an ADR-112
    /// abstract class), the arms keep their own class bases and list the interface, the
    /// <c>EvidenceDevice</c> arm that does not implement it is not one, and no backing wrapper or
    /// abstract class named after either interface appears.
    /// </summary>
    [Fact]
    public void Declarations_KeepTheInterfaceShape_AndAddNoBackingWrapper()
    {
        Assert.True(typeof(IConnectableDevice).IsInterface);
        Assert.True(typeof(IChargeable).IsInterface);

        Assert.Equal(typeof(EvidenceDevice), typeof(NearbyDevice).BaseType);
        Assert.Equal(typeof(EvidenceDevice), typeof(RemoteDevice).BaseType);
        Assert.Equal(typeof(EvidenceDevice), typeof(SavedDevice).BaseType);
        Assert.Equal(typeof(Trinket), typeof(LaserPointer).BaseType);

        Assert.True(typeof(IConnectableDevice).IsAssignableFrom(typeof(SavedDevice)));
        Assert.True(typeof(IChargeable).IsAssignableFrom(typeof(RemoteDevice)));
        Assert.False(typeof(IConnectableDevice).IsAssignableFrom(typeof(LostDevice)));

        Assembly assembly = typeof(IConnectableDevice).Assembly;
        Assert.Null(assembly.GetType("TestLibrary.Issue463.ConnectableDevice"));
        Assert.Null(assembly.GetType("TestLibrary.Issue463.Chargeable"));
    }

    /// <summary>
    /// The fixture's source directory as the diagnostics' <c>file</c> field spells it. Keyed on the
    /// file rather than the declaration because the legacy suspend routes name their skips by the
    /// bare member (<c>scanLater</c>, <c>connectLater</c>), not the package-qualified name.
    /// </summary>
    private const string FixtureDirectory = "/nuget/test/issue463/";

    /// <summary>
    /// The skip side of the same feature: once the hierarchies are admitted, nothing declared in the
    /// <c>issue463</c> fixture may be skipped or warned about at all. Today it carries
    /// <c>SKIPPED_INELIGIBLE_SEALED_INTERFACE</c> on both interfaces, <c>SKIPPED_SEALED_POSITION</c>,
    /// <c>SKIPPED_UNSUPPORTED_PROPERTY</c>, <c>SKIPPED_UNSUPPORTED_RETURN</c> (the nullable and
    /// suspend returns), <c>SKIPPED_UNSUPPORTED_INPUT</c> (the suspend parameter) and the
    /// <c>WARNING_NO_PUBLIC_CONSTRUCTOR</c> cascade on <c>CollarDock</c>. A fix that binds the
    /// positions but leaves a type-level skip behind fails here and nowhere else.
    /// </summary>
    [Fact]
    public void Diagnostics_NameNothingInTheIssue463FixtureWithASkipOrWarning()
    {
        foreach ((string path, IReadOnlyList<Diagnostic> entries) in DiagnosticFiles())
        {
            string[] skipped = entries
                .Where(entry => entry.File.Replace('\\', '/').Contains(FixtureDirectory, StringComparison.Ordinal))
                .Where(entry =>
                    entry.Kind.StartsWith("SKIPPED_", StringComparison.Ordinal) ||
                    entry.Kind.StartsWith("WARNING_", StringComparison.Ordinal))
                .Select(entry => $"{entry.Kind} {entry.Declaration}")
                .ToArray();

            Assert.True(
                skipped.Length == 0,
                $"{path} still skips issue463 declarations:\n  {string.Join("\n  ", skipped)}");
        }
    }

    private sealed record Diagnostic(string Severity, string Kind, string Declaration, string File, string Message);

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
            "could not find the repo root (a directory containing both test-library/ and " +
            $"IntegrationTests/) walking up from {AppContext.BaseDirectory}");
    }

    /// <summary>
    /// Every <c>NugetDiagnostics.json</c> the KSP round wrote, one per Kotlin target, read the way
    /// <c>SealedSubclassMethodDiagnosticsTests</c> reads them.
    /// </summary>
    private static (string Path, IReadOnlyList<Diagnostic> Entries)[] DiagnosticFiles()
    {
        string kspRoot = Path.Combine(FindRepoRoot(), "test-library", "build", "generated", "ksp");
        Assert.True(
            Directory.Exists(kspRoot),
            $"{kspRoot} does not exist. Run `scripts/verify.sh` (or at least " +
            "`./gradlew :test-library:packNuget`) first: this test reads the KSP artifact, it " +
            "does not produce it.");

        string[] files = Directory
            .GetFiles(kspRoot, "NugetDiagnostics.json", SearchOption.AllDirectories)
            .OrderBy(path => path, StringComparer.Ordinal)
            .ToArray();

        Assert.True(files.Length > 0, $"no NugetDiagnostics.json found under {kspRoot}");

        return files.Select(path =>
        {
            using JsonDocument doc = JsonDocument.Parse(File.ReadAllText(path));
            List<Diagnostic> entries = doc.RootElement.GetProperty("diagnostics").EnumerateArray().Select(entry =>
                new Diagnostic(
                    entry.GetProperty("severity").GetString() ?? "",
                    entry.GetProperty("kind").GetString() ?? "",
                    entry.GetProperty("declaration").GetString() ?? "",
                    entry.TryGetProperty("file", out JsonElement file) ? file.GetString() ?? "" : "",
                    entry.GetProperty("message").GetString() ?? "")).ToList();
            return (path, (IReadOnlyList<Diagnostic>)entries);
        }).ToArray();
    }
}
