using System.Runtime.InteropServices;
using TestLibrary.Platform;

namespace IntegrationTests;

// ADR-074 ("Forward expect/actual declarations: the actual is the export root"). Oreo travels
// with a smart collar; the `Device`/`Sensor` fixture stands in for it. Kotlin only ever declared
// ONE collar (`expect class Device`), but the CI box only ever runs ONE of its two actual bodies
// at a time (macosArm64 on macos-latest, mingwX64 elsewhere) — so these tests select the expected
// value by RID, the same way Oreo's collar reports different values depending which cat door he
// walked through.
//
// The static class is `PlatformApi`, not `Platform`: the package `...test.platform` already maps
// to C# namespace `TestLibrary.Platform`, and a class named `Platform` inside a namespace segment
// also named `Platform` is legal but needlessly fragile — incidental to this feature, so the
// fixture file is named `PlatformApi.kt` instead.
//
// NOTE on casing: top-level FUNCTIONS keep their exact Kotlin (camelCase) name
// (`platformName()`, `defaultClock()`, `labelOf(...)`) and top-level PROPERTIES are PascalCased
// (`PlatformTag`) — the same split as every other top-level member in this fixture library
// (`Arithmetic.add`, `ClinicSample.patientNameLength` vs. `Properties.CatBreed`). Settled and
// cited in ADR-074's "Casing, corrected while landing this ADR" paragraph; don't re-litigate it
// here.
public class PlatformTests
{
    private static readonly bool IsMacOs = RuntimeInformation.IsOSPlatform(OSPlatform.OSX);

    private static readonly string ExpectedPlatformName = IsMacOs ? "macos" : "mingw";
    private static readonly string ExpectedPlatformTag = IsMacOs ? "osx-arm64" : "win-x64";
    private static readonly string ExpectedDeviceSuffix = IsMacOs ? "on macos" : "on mingw";
    private static readonly string ExpectedDeviceId = IsMacOs ? "macos-device" : "mingw-device";
    private static readonly int ExpectedSensorReading = IsMacOs ? 42 : 24;

    // --- Top-level `expect fun` / `expect val` (PlatformApi.kt), Decision 3: static class `PlatformApi` ---

    [Fact]
    public void PlatformName_ReturnsRunningActualsValue()
    {
        // Proves the *actual* body ran, not merely that the symbol resolved: "macos"/"mingw"
        // only exist on the actual side, never on the expect header.
        Assert.Equal(ExpectedPlatformName, PlatformApi.platformName());
    }

    [Fact]
    public void PlatformTag_ReturnsRunningActualsValue()
    {
        Assert.Equal(ExpectedPlatformTag, PlatformApi.PlatformTag);
    }

    // --- `expect class Device(name: String)`: explicit ctor + method + property ---

    [Fact]
    public void Device_Describe_ReturnsRunningActualsValue()
    {
        using Device collar = new Device("Oreo's collar");
        Assert.Equal($"Oreo's collar {ExpectedDeviceSuffix}", collar.Describe());
    }

    [Fact]
    public void Device_Id_ReturnsRunningActualsValue()
    {
        using Device collar = new Device("Oreo's collar");
        Assert.Equal(ExpectedDeviceId, collar.Id);
    }

    [Fact]
    public void Device_ExplicitConstructor_ProducesUsableInstance()
    {
        // "new Device(...)" is the explicit-ctor row: the class carries a real public
        // constructor, not just the handle-only `internal Device(IntPtr handle)`.
        using var device = new Device("x");
        Assert.Equal($"x {ExpectedDeviceSuffix}", device.Describe());
    }

    // --- `expect class Sensor` with NO explicit constructor (the `ctors=0` row) ---

    [Fact]
    public void Sensor_ImplicitConstructor_ProducesUsableInstance()
    {
        // This is the row that matters most: an `expect class` with no declared constructor has
        // ZERO constructors on the KSP side (verified in the ADR spike). `new Sensor()` only
        // compiles, and only returns a real reading, if the fix gives the implicit-ctor row a
        // genuine public no-arg constructor rather than the handle-only degenerate class the
        // `actual typealias` row produces today.
        using var sensor = new Sensor();
        Assert.Equal(ExpectedSensorReading, sensor.Reading());
    }

    [Fact]
    public void Sensor_Reading_ReturnsRunningActualsValue()
    {
        using var doorSensor = new Sensor();
        Assert.Equal(ExpectedSensorReading, doorSensor.Reading());
    }

    // --- `expect object PlatformRegistry` ---

    [Fact]
    public void PlatformRegistry_Count_ReturnsActualsValue()
    {
        Assert.Equal(1, PlatformRegistry.Count());
    }

    // --- `actual typealias Clock = SystemClock` (Decision 2): no `Clock` type on the C# side ---

    [Fact]
    public void DefaultClock_ReturnsSystemClockAndReportsSystemClockLabel()
    {
        // Deliberately NOT `var`: under Decision 2 there is no `Clock` type in C# at all, the
        // `expect` name is erased and every reference redirects to the alias target. If the
        // redirect were wrong (e.g. still binding to a non-existent `Clock`, or losing the type
        // through `object`), this line fails to COMPILE, not just fails to assert.
        //
        // `defaultClock()` is itself an `expect fun` (actualized per target), so per Decision 3 it
        // must bind in `PlatformApi` exactly like `platformName()`/`PlatformTag`, not in whichever
        // target file supplied its body.
        using SystemClock clock = PlatformApi.defaultClock();
        Assert.Equal("system-clock", clock.Label());
    }

    [Fact]
    public void LabelOf_ParameterPositionRedirect_ReturnsSystemClockLabel()
    {
        // `labelOf(clock: Clock)` is declared in shared (non-expect/actual) code in
        // PlatformApi.kt, exercising the Decision 2 redirect at a PARAMETER position rather than
        // `defaultClock()`'s return position: the generated signature must take a `SystemClock`,
        // never a `Clock`.
        using SystemClock clock = PlatformApi.defaultClock();
        Assert.Equal("system-clock", PlatformApi.labelOf(clock));
    }

    // --- Decision 3: the static class is `PlatformApi`, never `PlatformApiMacos`/`PlatformApiMingw` ---

    [Fact]
    public void PlatformApi_IsStaticClass()
    {
        // Every call above already only compiles because a type literally named `PlatformApi`
        // exists in `TestLibrary.Platform` (Decision 3: the expect's file name, `PlatformApi.kt`,
        // not whichever target file happened to supply the actual body). This is the structural
        // half of that proof, mirroring the existing `CatRegistry_IsStaticClass` pattern.
        Assert.True(typeof(PlatformApi).IsAbstract && typeof(PlatformApi).IsSealed);
    }

    [Fact]
    public void PlatformApi_TargetSpecificClassNamesDoNotExist()
    {
        // Negative half of the Decision 3 proof: neither target's file name leaked into the C#
        // surface as its own class, on either target's package (only one target's Interop.cs
        // ships, so at most one of these could ever exist, but neither should).
        Assert.Null(Type.GetType("TestLibrary.Platform.PlatformApiMacos, TestLibrary"));
        Assert.Null(Type.GetType("TestLibrary.Platform.PlatformApiMingw, TestLibrary"));
    }
}
