using TestLibrary;
using TestLibrary.Dev.Other.Admitted;

namespace IntegrationTests;

/// <summary>
/// ADR-066 §5 amendment, decided rule (option (i)): a Kotlin package <b>outside</b>
/// <c>rootPackage</c> maps to <c>&lt;packageId&gt;.&lt;full Kotlin package, each segment
/// PascalCased&gt;</c>. <c>dev.other.admitted</c> is admitted by an explicit
/// <c>include("io.github.xxfast.kotlin.native.nuget.test", "dev.other.admitted")</c> and must land
/// at <c>TestLibrary.Dev.Other.Admitted</c> — not at a bare <c>Dev.Other.Admitted</c> (which could
/// collide with a genuinely separate NuGet package's namespace inside the same consumer), and not
/// flattened into <c>TestLibrary</c>.
/// <para>
/// <b>PIN</b>: green on current sources. Its value is regression cover for a rule that had no
/// end-to-end fixture at all — the declaration site and every reference site share
/// <c>mapPackageToNamespace</c>, so one change moves both, and until now nothing under
/// <c>IntegrationTests/</c> contained the string <c>Dev.Other</c>.
/// </para>
/// <para>
/// That this file compiles is half the test: the <c>using</c> above names the namespace, and
/// <c>Billboards.Current()</c>'s return type is the reference site
/// (<c>global::TestLibrary.Dev.Other.Admitted.Billboard</c>).
/// </para>
/// </summary>
public class OutOfRootNamespaceTests
{
    [Fact]
    public void AdmittedOutOfRootPackage_KeepsItsFullPathUnderTheAssemblyRootNamespace()
    {
        Assert.Equal("TestLibrary.Dev.Other.Admitted", typeof(Billboard).Namespace);
    }

    [Fact]
    public void AdmittedOutOfRootType_RoundTripsThroughAnInRootRoot()
    {
        // The reference site, exercised rather than merely spelled: `Billboards` lives in the root
        // package (`TestLibrary`), `Billboard` one namespace tree away.
        using var billboards = new Billboards();

        using Billboard current = billboards.Current();

        Assert.Equal("Oreo naps here. Mylo supervises.", current.Slogan);
    }

    [Fact]
    public void AdmittedOutOfRootType_IsConstructibleFromCSharp()
    {
        // The other direction across the same seam: the generated constructor for a type declared
        // in an out-of-root namespace, called from C# with a string parameter. Without this the
        // only exercised path is the IntPtr-handle constructor the return-value route uses, and
        // the public `Billboard(string)` shim stays cold (observed in coverlet before this cell).
        using var billboard = new Billboard("Mylo supervises. Oreo naps here.");

        Assert.Equal("Mylo supervises. Oreo naps here.", billboard.Slogan);
    }

    [Fact]
    public void TheRootThatReachesIt_StaysInTheRootNamespace()
    {
        // Control: admitting an out-of-root package must not move anything that was already in
        // root. `Billboards` is the in-root reacher.
        Assert.Equal("TestLibrary", typeof(Billboards).Namespace);
    }

    [Fact]
    public void UnadmittedSiblingPackage_StaysUnadmitted()
    {
        // The load-bearing negative, one package segment away from the positive above:
        // `dev.other.core` is NOT in the include set, so `Newsroom.sponsor()` (returning
        // `dev.other.core.Advertisement`) is still absent, and no `Dev.Other.Core` namespace
        // exists in the assembly. Widening the include for `dev.other.admitted` must not have
        // widened it for its sibling.
        Assert.Null(typeof(Newsroom).GetMethod("Sponsor"));
        Assert.DoesNotContain(
            typeof(Billboard).Assembly.GetTypes(),
            t => t.Namespace == "TestLibrary.Dev.Other.Core");
    }
}
