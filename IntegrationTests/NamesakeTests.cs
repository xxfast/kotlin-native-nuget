// Both namespaces are imported AND aliased on purpose. The aliases are what every type below is
// spelled through, because `Kitten`, `LoadState` and `Mood` exist in both halves and an unqualified
// simple name would be CS0104. The plain using-namespace directives are still required: C# resolves
// extension methods only through using-NAMESPACE directives, never through a using-alias, so
// `Mood.Curious.Chirp()` and `.GetPounce()` would be CS1061 without them. CS0104 fires on
// unqualified simple names only, so the two forms coexist.
using TestLibrary.Namesake.A;
using TestLibrary.Namesake.B;
using A = TestLibrary.Namesake.A;
using B = TestLibrary.Namesake.B;

namespace IntegrationTests;

/// <summary>
/// ROADMAP lines 47, 48 and 76: export symbols are not package-qualified.
///
/// <para>
/// A Kotlin author may declare <c>a.Kitten</c> and <c>b.Kitten</c> (or two <c>rollCall()</c>
/// functions, two <c>LoadState</c> sealed classes, two <c>Mood</c> enums each carrying a property)
/// in one library, and both must be reachable from C# under their own namespaces. Today the export
/// symbol is derived from the declaration's own chain of simple names and never its package
/// (ADR-133), so each pair collides and the build fails with
/// <c>[nuget:ERROR_C_ENTRY_POINT_COLLISION]</c> before <c>CNameExports.kt</c> is written.
/// </para>
///
/// <para>
/// <b>These cells deliberately pin no symbol string.</b> The C name is a private contract between
/// the generated <c>CNameExports.kt</c> and the generated <c>Interop.cs</c>; no consumer spells it,
/// and the chosen scheme (sanitised library name + package relative to <c>rootPackage</c> + the
/// existing name, always mangled) is free to change. What a consumer can observe, and what these
/// cells assert, is that both halves bind and that each one reaches <b>its own</b> declaration:
/// every fixture pair answers the same question with a different answer, so a cell cannot pass by
/// arriving at the wrong package's code.
/// </para>
///
/// <para>
/// Two more shapes ride along. <see cref="TopLevelFunctionNamedLikeACRuntimeSymbol_Resolves"/>
/// covers line 48: <c>fun signal(dbm: Int)</c> in the ROOT Kotlin package is exported as
/// <c>@CName("signal")</c>, which <c>ld.lld</c>'s MinGW auto-exporter silently drops from the DLL's
/// export table, so the <c>DllImport</c> throws <c>EntryPointNotFoundException</c> at runtime with
/// no build-time diagnostic. The root package is the discriminating position: its package part is
/// empty, so package qualification alone would leave the symbol bare and still broken.
/// <see cref="CrossNamespaceGenericReturn_Compiles"/> covers line 76, a separate legacy-route
/// spelling defect: the outer generic return type is rendered unqualified, <c>CS0246</c>.
/// </para>
///
/// <para>
/// House A is Oreo's (black with white in the middle), house B is Mylo's (brown and creamy). Both
/// cats answer to the same paperwork and give completely different answers, which is the entire
/// point of this file.
/// </para>
/// </summary>
public class NamesakeTests
{
    // -----------------------------------------------------------------------------------------
    // Class route: constructor, property getter, method, dispose.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void TwoKittens_InDifferentPackages_BothConstructAndGreetTheirOwnWay()
    {
        using A.Kitten oreo = new A.Kitten("Oreo", 9);
        using B.Kitten mylo = new B.Kitten("Mylo", 7);

        Assert.Equal("a:Oreo has 9 lives", oreo.Greet());
        Assert.Equal("b:Mylo has 7 lives", mylo.Greet());
    }

    /// <summary>
    /// The identical constructor arguments on both halves: this is the cell that fails if the two
    /// namespaces ever collapse onto one native declaration, because then both would answer the
    /// same.
    /// </summary>
    [Fact]
    public void TwoKittens_SameArguments_StillAnswerFromTheirOwnPackage()
    {
        using A.Kitten fromA = new A.Kitten("Oreo", 9);
        using B.Kitten fromB = new B.Kitten("Oreo", 9);

        Assert.Equal("a:Oreo has 9 lives", fromA.Greet());
        Assert.Equal("b:Oreo has 9 lives", fromB.Greet());
        Assert.NotEqual(fromA.Greet(), fromB.Greet());
    }

    /// <summary>The property getter is its own symbol (<c>kitten_get_name</c> today), so it gets its own cell.</summary>
    [Fact]
    public void TwoKittens_Properties_ReadFromTheirOwnPackage()
    {
        using A.Kitten oreo = new A.Kitten("Oreo", 9);
        using B.Kitten mylo = new B.Kitten("Mylo", 7);

        Assert.Equal("Oreo", oreo.Name);
        Assert.Equal(9, oreo.Lives);
        Assert.Equal("Mylo", mylo.Name);
        Assert.Equal(7, mylo.Lives);
    }

    /// <summary>
    /// The generated <c>Dispose</c> is a third symbol on the same owner prefix
    /// (<c>kitten_dispose</c>), and it was one of the three collisions the memo's spike observed,
    /// so disposal is asserted explicitly rather than only implied by <c>using</c>.
    /// </summary>
    [Fact]
    public void TwoKittens_Dispose_IsIdempotentOnBothHalves()
    {
        A.Kitten oreo = new A.Kitten("Oreo", 9);
        B.Kitten mylo = new B.Kitten("Mylo", 7);

        oreo.Dispose();
        oreo.Dispose();
        mylo.Dispose();
        mylo.Dispose();
    }

    // -----------------------------------------------------------------------------------------
    // Top-level function route (ADR-007 file class), the shape the memo's spike 2a shows also
    // needs the Kotlin call site qualified, not just the symbol.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void TwoTopLevelFunctions_InDifferentPackages_CallTheirOwnDeclaration()
    {
        Assert.Equal("a: Oreo present", A.Registry.RollCall());
        Assert.Equal("b: Mylo present", B.Registry.RollCall());
    }

    // -----------------------------------------------------------------------------------------
    // Sealed route: discriminator plus arms.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void TwoSealedClasses_InDifferentPackages_ReturnTheirOwnReadyArm()
    {
        using A.LoadState fromA = A.LoadStates.LoadFor("Oreo");
        using B.LoadState fromB = B.LoadStates.LoadFor("Mylo");

        A.LoadState.Ready readyA = Assert.IsType<A.LoadState.Ready>(fromA);
        B.LoadState.Ready readyB = Assert.IsType<B.LoadState.Ready>(fromB);
        Assert.Equal("a:Oreo", readyA.Name);
        Assert.Equal("b:Mylo", readyB.Name);
    }

    /// <summary>
    /// The same input takes the OTHER arm in each package, so the discriminator itself is proven to
    /// be the right package's discriminator and not merely a working one.
    /// </summary>
    [Fact]
    public void TwoSealedClasses_SameInput_DiscriminateIntoOppositeArms()
    {
        using A.LoadState fromA = A.LoadStates.LoadFor("Mylo");
        using B.LoadState fromB = B.LoadStates.LoadFor("Mylo");

        A.LoadState.Failed failed = Assert.IsType<A.LoadState.Failed>(fromA);
        B.LoadState.Ready ready = Assert.IsType<B.LoadState.Ready>(fromB);
        Assert.Equal("a: Mylo is not in house A", failed.Cause);
        Assert.Equal("b:Mylo", ready.Name);
    }

    // -----------------------------------------------------------------------------------------
    // Enum route, both property families: a MEMBER property on the enum (the EnumExports prefix
    // route) and an EXTENSION property over an enum receiver (the receiver-prefix fallback).
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void TwoEnums_InDifferentPackages_MapTheirOwnArms()
    {
        Assert.Equal(A.Mood.Smug, A.Moods.MoodOf("Oreo"));
        Assert.Equal(B.Mood.Curious, B.Moods.MoodOf("Oreo"));
    }

    [Fact]
    public void TwoEnums_MemberProperty_ReadsFromItsOwnPackage()
    {
        Assert.Equal("a: Oreo chirps at the window", A.Mood.Curious.Chirp());
        Assert.Equal("a: Oreo owns the warmest chair", A.Mood.Smug.Chirp());
        Assert.Equal("b: Mylo chirps at the fridge", B.Mood.Curious.Chirp());
        Assert.Equal("b: Mylo has already been fed twice", B.Mood.Smug.Chirp());
    }

    [Fact]
    public void TwoEnums_ExtensionProperty_ReadsFromItsOwnPackage()
    {
        Assert.Equal("a: Oreo pounces on the blind cord", A.Mood.Curious.GetPounce());
        Assert.Equal("a: Oreo cannot be bothered", A.Mood.Smug.GetPounce());
        Assert.Equal("b: Mylo pounces on the milk jug", B.Mood.Curious.GetPounce());
        Assert.Equal("b: Mylo naps through it", B.Mood.Smug.GetPounce());
    }

    // -----------------------------------------------------------------------------------------
    // ROADMAP line 48: a top-level function named like a C runtime symbol.
    // -----------------------------------------------------------------------------------------

    /// <summary>
    /// Today on mingwX64: <c>EntryPointNotFoundException</c>, because <c>signal</c> is defined in
    /// the DLL but absent from its export table. Everything else about this cell succeeds today
    /// (KSP, the Kotlin export, the C# compile), which is exactly why the failure is worth a test.
    /// </summary>
    [Fact]
    public void TopLevelFunctionNamedLikeACRuntimeSymbol_Resolves()
    {
        Assert.Equal(-42, TestLibrary.Tower.Signal(-42));
        Assert.Equal(-42, TestLibrary.Tower.LastSignal());
    }

    // -----------------------------------------------------------------------------------------
    // ROADMAP line 76: cross-namespace generic return renders unqualified (CS0246).
    // -----------------------------------------------------------------------------------------

    /// <summary>
    /// <c>genericReturnOnTopLevel()</c> lives in <c>TestLibrary.Unrouted</c> and returns
    /// <c>TestLibrary.Cat.Box&lt;int&gt;</c>. The defect is a compile error in the generated
    /// bindings, so the mere existence of this cell is most of the assertion: if the outer type is
    /// still spelled unqualified, <c>Interop.cs</c> does not compile and the whole test project
    /// goes red. The body proves the call also works once it does compile.
    /// </summary>
    [Fact]
    public void CrossNamespaceGenericReturn_Compiles()
    {
        using TestLibrary.Cat.Box<int> box = TestLibrary.Unrouted.UnroutedTopLevelGeneric.GenericReturnOnTopLevel();
        Assert.Equal(1, box.Value);
    }
}
