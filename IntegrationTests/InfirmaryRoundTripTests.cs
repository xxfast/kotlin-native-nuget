using TestLibrary.Infirmary;

namespace IntegrationTests;

// ADR-104: the reverse thunk error channel. A Kotlin consumer calls a bound C# NuGet member, that
// member throws, and the managed exception must reach the Kotlin call site as a catchable
// NugetManagedException(managedType, message) instead of terminating the host.
//
//   C# IntegrationTests
//     -> (forward bridge, Interop.cs)        InfirmarySample.* functions
//       -> Kotlin test-library               InfirmarySample.kt
//         -> (reverse bridge, ADR-104)       test.infirmary.{Infirmary, Quarantine, Patient}
//           -> real C# TestDependency NuGet  Test.Infirmary.{Infirmary, Quarantine, Patient}
//
// The fixture is organised by RETURN SHAPE, one throwing member each: void, pass-through scalar,
// string, bound-class handle, property getter, struct out-pointers, constructor. That is not
// thoroughness for its own sake. ADR-104's mechanism ledger flags "returning `default` from a
// thunk that also has unwritten struct out-pointers is safe" as INFERRED, not verified, and says
// the implementation "should carry a test per return shape". These are those tests.
//
// One member is here for its EMITTER instead: the property SETTER. Fork A puts setters in the
// channel's scope, and it is the only seam where a missed error-slot check is SILENT: a setter
// has no result to guard, so the exception is swallowed and the write appears to succeed. With no
// fixture member reaching it, shim coverage would report nothing at all for that path rather than
// reporting it cold, which is the worst combination available.
//
// Every throwing [Fact] below asserts two things at once: the test host SURVIVES the managed
// throw, and the Kotlin call site catches a NugetManagedException carrying the managed type name
// and the message. Survival is not incidental. Without the channel the exception escapes an
// [UnmanagedCallersOnly] thunk with no catch and no error out-parameter, and .NET tears the
// process down rather than unwinding through the Kotlin/Native frame beneath it, taking the whole
// xunit run with it. That is what these tests are standing guard over.
//
// SCOPE, per ADR-104's gate decisions: Fork B carries the managed type NAME and the MESSAGE only.
// A mapped Kotlin exception type, a .NET stack trace, and an InnerException/cause chain are three
// separate deferred Phase 11 items and are deliberately NOT asserted anywhere here.
public class InfirmaryRoundTripTests
{
    // Every throwing sample surfaces "kotlinType|managedType|message" from the Kotlin catch site.
    private readonly record struct Caught(string KotlinType, string ManagedType, string Message);

    private static Caught Parse(string surfaced)
    {
        string[] parts = surfaced.Split('|');
        Assert.True(
            parts.Length == 3,
            $"expected a 'kotlinType|managedType|message' triple from the Kotlin catch site, got: {surfaced}");
        return new Caught(parts[0], parts[1], parts[2]);
    }

    // Fork D: ONE Kotlin exception type carries every managed throw. Asserting the simple name
    // here (rather than catching the type in Kotlin, which cannot be written before the generator
    // emits it) pins that decision from the C# side.
    private static void AssertManaged(Caught caught, string managedType, string message)
    {
        Assert.Equal("NugetManagedException", caught.KotlinType);
        Assert.Equal(managedType, caught.ManagedType);
        Assert.Equal(message, caught.Message);
    }

    // ---------------------------------------------------------------------------------------
    // One throwing member per return shape.
    // ---------------------------------------------------------------------------------------

    // VOID return shape: the thunk has nothing but the error slot to carry anything back.
    [Fact]
    public void OreoDischarge_Throws_SurfacesInvalidOperationException()
    {
        AssertManaged(
            Parse(InfirmarySample.oreoDischargeThrows()),
            "System.InvalidOperationException",
            "Oreo is not cleared for discharge");
    }

    // PASS-THROUGH SCALAR return shape: an int needing no marshalling conversion. Deliberately
    // paired with the string and struct shapes below: a fixture built only from this one would
    // go green while every converting path was still broken.
    [Fact]
    public void OreoTemperature_Throws_SurfacesOverflowException()
    {
        AssertManaged(
            Parse(InfirmarySample.oreoTemperatureThrows()),
            "System.OverflowException",
            "Oreo ran too hot for the thermometer");
    }

    // STRING return shape: the happy path allocates with StringToCoTaskMemUTF8 and Kotlin frees
    // it. On the error path nothing is allocated, so Kotlin must check the error slot before it
    // reads (or frees) the returned pointer.
    [Fact]
    public void OreoChart_Throws_SurfacesFormatException()
    {
        AssertManaged(
            Parse(InfirmarySample.oreoChartThrows()),
            "System.FormatException",
            "Oreo wrote his chart in claw marks");
    }

    // BOUND-CLASS HANDLE return shape. The generated Kotlin stub reads a non-null handle return
    // through `requireNotNull(ptr) { "... annotates it non-null." }`. If the error slot is checked
    // after that, this surfaces IllegalStateException/"returned null" instead of the real managed
    // exception, which is exactly the check-first codegen invariant ADR-104 leaves unverified.
    [Fact]
    public void OreoAdmit_Throws_SurfacesNotSupportedException()
    {
        AssertManaged(
            Parse(InfirmarySample.oreoAdmitThrows()),
            "System.NotSupportedException",
            "the ward is full, Oreo has to wait");
    }

    // PROPERTY GETTER return shape: a separate thunk emission site from a method, with a separate
    // Kotlin call site (a `get()` accessor body, not a function body).
    [Fact]
    public void OreoOccupancy_Getter_Throws_SurfacesTimeoutException()
    {
        AssertManaged(
            Parse(InfirmarySample.oreoOccupancyThrows()),
            "System.TimeoutException",
            "the ward count is still being taken");
    }

    // STRUCT OUT-POINTER return shape (ADR-056/058 Shape A: Profile's string/bool/char/enum
    // vocabulary). THE shape ADR-104 names as unverified: the thunk returns with four
    // out-pointers unwritten, one of them a string pointer the Kotlin stub currently reads through
    // `requireNotNull(outTag.value)`. Reading any of them on the error path is uninitialised
    // memScoped memory, so this test is the one that catches a missed check-first site.
    [Fact]
    public void OreoExamine_StructReturn_Throws_SurfacesArgumentException()
    {
        AssertManaged(
            Parse(InfirmarySample.oreoExamineThrows()),
            "System.ArgumentException",
            "Oreo is not a registered patient");
    }

    // PROPERTY SETTER emission site, here for its EMITTER, not its return shape. A setter has no
    // result to guard, so a Kotlin call site that forgets to read the error slot SWALLOWS the
    // managed exception and the write looks like it succeeded. Every other shape in this file at
    // least fails visibly; this is the only one that can go silently wrong, which is exactly why
    // it needs a fixture member rather than no coverage signal at all. Fork A puts setters in the
    // channel's scope, and this is a distinct thunk (C#) and a distinct call site (Kotlin) from
    // Discharge's, despite both being void.
    [Fact]
    public void MyloWardSignSetter_Throws_SurfacesUnauthorizedAccessException()
    {
        AssertManaged(
            Parse(InfirmarySample.myloWardSignSetterThrows()),
            "System.UnauthorizedAccessException",
            "only the vet renames the ward, not Mylo");
    }

    // CONSTRUCTOR return shape. The generated constructor helper ends in
    // `requireNotNull(ptr) { "... a C# constructor never returns null." }`, the second place the
    // error-slot check has to come first. Quarantine exists as its own type because ADR-043 gives
    // a bound type exactly one constructor, and Infirmary's has to keep working.
    [Fact]
    public void MyloQuarantineConstructor_Throws_SurfacesArgumentException()
    {
        AssertManaged(
            Parse(InfirmarySample.myloQuarantineCtorThrows()),
            "System.ArgumentException",
            "no free quarantine pen: Mylo already has the good pen");
    }

    // ---------------------------------------------------------------------------------------
    // Non-throwing siblings. These PASS TODAY and must keep passing: the error channel must not
    // tax the happy path (the reason KotlinNoVacancy_LegsOnly_NonThrowingSiblingSlotStillWorks
    // exists in MenagerieRoundTripTests).
    // ---------------------------------------------------------------------------------------

    [Fact]
    public void MyloChartFor_NonThrowingStringSibling_StillReturnsItsValue()
    {
        Assert.Equal("Mylo is purring, chart clean", InfirmarySample.myloChartFor());
    }

    [Fact]
    public void MyloExamineCalm_NonThrowingStructSibling_StillWritesEveryComponent()
    {
        // tag:active:grade:mood: every converted component of the Shape A struct actually
        // written through its out-pointer, which is what the error path must NOT do.
        Assert.Equal("Mylo:true:A:CALM", InfirmarySample.myloExamineCalm());
    }

    // The two survival tests: throw first, then call the non-throwing sibling on the SAME
    // receiver. Passing these means the host lived through the throw AND the receiver handle
    // (and the C# object behind it) is still usable, not just that an exception arrived.

    [Fact]
    public void OreoChartThrows_ThenMyloChartFor_OnTheSameInfirmary()
    {
        string[] parts = InfirmarySample.oreoChartThrowsThenMyloChartFor().Split('~');
        Assert.Equal(2, parts.Length);
        AssertManaged(
            Parse(parts[0]),
            "System.FormatException",
            "Oreo wrote his chart in claw marks");
        Assert.Equal("Mylo is purring, chart clean", parts[1]);
    }

    [Fact]
    public void OreoExamineThrows_ThenMyloExamineCalm_OnTheSameInfirmary()
    {
        string[] parts = InfirmarySample.oreoExamineThrowsThenMyloExamineCalm().Split('~');
        Assert.Equal(2, parts.Length);
        AssertManaged(
            Parse(parts[0]),
            "System.ArgumentException",
            "Oreo is not a registered patient");
        // The struct out-pointers the throwing call left unwritten must not have poisoned the
        // next call's: every component still round-trips.
        Assert.Equal("Mylo:true:A:CALM", parts[1]);
    }
}
