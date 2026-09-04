using System.Text.Json;
using TestLibrary;
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

    // ADR-085: Kotlin-implemented C# interfaces passed back to C#. Every fixture in this block
    // is EXPECTED TO FAIL as of this commit — `nugetHandle()` still hits the `error(...)` branch
    // for a plain Kotlin `Goat : IFeedable` (no `NugetHandleOwner`), so `MenagerieSample.kt`'s
    // ADR-085 functions abort the Kotlin/Native process at the point of the interface-typed
    // crossing, which surfaces here as the native library aborting or the whole test host
    // crashing rather than a normal Assert failure. See the class doc comment on
    // `MenagerieRoundTripTests` above for how a Kotlin-side compile break (if any) would instead
    // fail earlier, at build time.

    [Fact]
    public void KotlinGoatIntroduce_DispatchesDescribeAndLegsIntoKotlin()
    {
        // Sanctuary.Introduce(IFeedable) called with a Kotlin-implemented Goat (not a bound
        // Ferret): the String-returning Describe() member PLUS the Int Legs getter must both
        // dispatch back into the Kotlin object through a minted bridge.
        string result = MenagerieSample.kotlinGoatIntroduce();
        Assert.Equal("introduced Nibbles the goat with 4 legs", result);
    }

    [Fact]
    public void KotlinGoatFeedCount_DispatchesVoidStringParameterMember()
    {
        // Sanctuary.FeedAnimal(IFeedable, string) calls IFeedable.Feed(string) — a void-returning,
        // string-PARAMETER member — on a Kotlin-implemented Goat. The Kotlin side counts calls in
        // `goat.meals`, read back after the C#->Kotlin round trip to prove the call actually
        // reached the Kotlin object rather than merely not throwing.
        int meals = MenagerieSample.kotlinGoatFeedCount("kibble");
        Assert.Equal(1, meals);
    }

    [Fact]
    public void KotlinGoatRename_WritesThenReadsNicknameThroughBothSlots()
    {
        // Sanctuary.Rename(IFeedable, string?) writes then reads IFeedable.Nickname on a
        // Kotlin-implemented Goat: exercises BOTH the setter and getter slots (Introduce only
        // ever exercises a getter), mirroring Mylo's brown-and-creamy nickname fixtures elsewhere.
        string? result = MenagerieSample.kotlinGoatRename("Mylo");
        Assert.Equal("Mylo", result);
    }

    [Fact]
    public void KotlinGoatRename_NullNickname_IsDistinctFromEmptyString()
    {
        // Same setter/getter round trip, but with null: the nullable-string slot must ride
        // IntPtr.Zero through, not collapse null into "".
        string? result = MenagerieSample.kotlinGoatRename(null);
        Assert.Null(result);
    }

    [Fact]
    public void KotlinGoatRename_EmptyStringNickname_IsNotNull()
    {
        string? result = MenagerieSample.kotlinGoatRename("");
        Assert.NotNull(result);
        Assert.Equal("", result);
    }

    [Fact]
    public void KotlinGoatFeaturedIsSameInstance_KotlinSideIdentityIsPreserved()
    {
        // Sanctuary.Featured stores a Kotlin-implemented Goat and hands it back. ADR-085 promises
        // Kotlin-side identity (not C#-side): the value read back on the Kotlin side of the
        // crossing must be the SAME Goat instance, via the token probe, not merely an equal one.
        bool same = MenagerieSample.kotlinGoatFeaturedIsSameInstance();
        Assert.True(same, "expected sanctuary.featured to resolve back to the original Goat instance");
    }

    // ADR-085 follow-up fixtures ("Addendum: target-keyed dispatch, and a cross-package enum
    // import errata"). Both bugs below are FIXED and VERIFIED (`scripts/verify.sh`, 939 passed /
    // 0 failed) — these tests pass today; kept as regression coverage for the two fixes.
    //
    // Item A (multi-interface dispatch bug, fixed): `nugetMintBridge` used to dispatch by `is`
    // first-match in RIR iteration order and ignore which interface the crossing position needed
    // (`Any.nugetHandle(interfaceName)` received the needed interface name but never passed it to
    // `nugetMintBridge`). `RingLeader` implements BOTH `IFeedable` and `IPerformer` (independent
    // interfaces, neither derives from the other) and exercised this: `IFeedable` is declared
    // first in Menagerie.cs, so a value passed at the IPerformer-typed position (`Applaud`) used
    // to still match `is IFeedable` first and mint the WRONG bridge, one that does not implement
    // `IPerformer` at all. `nugetMintBridge(value, interfaceName)` now keys on `interfaceName`
    // first, `is` second, so each crossing position mints against the interface it actually
    // needs.
    //
    // Item B (cross-package enum slot import, fixed): `IPerformer.Energy` is
    // `Test.Wellness.EnergyLevel`, a namespace independent of `IPerformer`'s own
    // `Test.Menagerie`. The generated `IPerformerBindings.kt` slot body for the setter needed to
    // reference `EnergyLevel` by its bound Kotlin name; a shared `registrableEnumTypes` collector
    // applied at every interface-file emission position now imports it (see
    // `RingLeaderRecharge_...` below).

    [Fact]
    public void RingLeaderIntroduceViaFeedable_DispatchesIntoFeedableNotPerformer()
    {
        // RingLeader implements BOTH IFeedable and IPerformer. Crossing at the IFeedable-typed
        // parameter (Sanctuary.Introduce) must mint/resolve an IFeedable bridge and dispatch
        // Describe()/Legs, not IPerformer's slot table.
        string result = MenagerieSample.ringLeaderIntroduceViaFeedable();
        Assert.Equal("introduced Mylo the ringleader with 2 legs", result);
    }

    [Fact]
    public void RingLeaderApplaudViaPerformer_DispatchesIntoPerformerNotFeedable()
    {
        // Same dual-interface Kotlin object, crossing at the IPerformer-typed parameter this
        // time. `IFeedable` is declared first in Menagerie.cs, so today's first-match dispatch
        // mints an IFeedable bridge here too, which does not implement IPerformer at all.
        string result = MenagerieSample.ringLeaderApplaudViaPerformer();
        Assert.Equal("Mylo takes a bow", result);
    }

    [Fact]
    public void RingLeaderRecharge_CrossPackageEnumSetterAndGetter_RoundTrips()
    {
        // IPerformer.Energy is Test.Wellness.EnergyLevel, a namespace independent of IPerformer's
        // own Test.Menagerie. The setter is the inbound crossing that needs the generated
        // IPerformerBindings.kt slot body to import EnergyLevel; today it does not, so
        // test-library fails to compile before this assertion can even run.
        string result = MenagerieSample.ringLeaderRecharge();
        // The reverse enum generator emits SCREAMING_CASE entries (`HIGH`), so Kotlin's
        // EnergyLevel.name is "HIGH", not the C# spelling.
        Assert.Equal("HIGH", result);
    }

    // Phase 13 Wave 2, item 1 (ADR-086 addendum / ADR-085's "Deferred" list): derived-interface
    // flattening. `ITagged : IFeedable` plans no Kotlin bridge factory today, so a Kotlin class
    // implementing `ITagged` is a named `skipped_kotlin_bridge` and minting hits `nugetHandle()`'s
    // `error(...)` fallback. EXPECTED TO FAIL as of this commit: both tests below throw instead of
    // returning their expected string (see the task report for the exact exception surfaced).

    [Fact]
    public void KotlinTabbyShowcase_DerivedInterfaceFlattening_DispatchesOwnAndInheritedMembers()
    {
        // Sanctuary.Showcase(ITagged) calls BOTH Tag (ITagged's own member) AND Legs (inherited
        // from IFeedable) on a Kotlin-implemented ITagged. The flattened bridge this needs does
        // not exist yet.
        string result = MenagerieSample.kotlinTabbyShowcase();
        Assert.Equal("tabby: 4 legs", result);
    }

    [Fact]
    public void KotlinTabbyIntroduceViaFeedable_SameObjectCrossesAtBasePosition()
    {
        // The SAME Kotlin Tabby instance, crossing instead at the base IFeedable-typed position
        // (Sanctuary.Introduce). A flattened ITagged bridge must satisfy both this crossing and
        // KotlinTabbyShowcase's.
        string result = MenagerieSample.kotlinTabbyIntroduceViaFeedable();
        Assert.Equal("introduced Tabby the tagged tabby with 4 legs", result);
    }

    // Phase 13 Wave 2, item 2 (ADR-086): object- and interface-typed slots on a
    // Kotlin-implemented bound interface. `IKeeper` plans no Kotlin bridge factory today either
    // (its Ferret/IFeedable parameters, returns, and nullable Ferret? property are all outside the
    // v1 slot vocabulary), so minting hits the same `error(...)` fallback item 1 hits. EXPECTED TO
    // FAIL as of this commit.

    [Fact]
    public void KotlinZookeeper_GroomsAndStoresTheSamePet_OwnershipTransfers()
    {
        // Sanctuary.KeeperRoundTrip drives every IKeeper crossing: a bound-object PARAMETER +
        // RETURN (Groom — ownership transfers to Kotlin, and the SAME Ferret comes back to C#), a
        // nullable bound-object PROPERTY (Favorite, null then non-null), and a bound-INTERFACE
        // PARAMETER + RETURN (Pair) with a real C# Ferret as the partner — C#-side identity IS
        // promised for a C#-originated object per ADR-086's identity table.
        string result = MenagerieSample.kotlinZookeeperRoundTripWithFerretPartner();
        string[] parts = result.Split('|');
        Assert.Equal(6, parts.Length);
        Assert.Equal("True", parts[0]);    // keeper.Favorite started null
        Assert.Equal("True", parts[1]);    // Groom(pet) returned the SAME pet (C#-side identity)
        Assert.Equal("True", parts[2]);    // keeper.Favorite reads back the SAME pet after the set
        Assert.Equal("True", parts[3]);    // Pair(partner) returned the SAME partner (C#-origin)
        Assert.Equal("a ferret", parts[4]); // paired.Describe() dispatches on the real Ferret
        Assert.Equal("True", parts[5]);    // Zookeeper.stored === pet (Kotlin-side: param stored)
    }

    [Fact]
    public void KotlinZookeeper_PairWithKotlinGoatPartner_KotlinSideIdentityPreserved()
    {
        // Same IKeeper.Pair crossing, but the partner is a Kotlin-implemented Goat instead of a
        // real Ferret. ADR-086's identity table promises only KOTLIN-side identity for this
        // origin (the token probe resolving the parameter back to the ORIGINAL Goat) — C#-side
        // ReferenceEquals is explicitly not promised there (a fresh bridge mints per return
        // crossing), so this test asserts only the Kotlin-side `===`.
        bool same = MenagerieSample.kotlinZookeeperRoundTripWithGoatPartner();
        Assert.True(
            same,
            "expected IKeeper.Pair's parameter to resolve back to the original Kotlin Goat via " +
            "the token probe");
    }

    // Phase 13 Wave 2, item 3 (ADR-087 stage 2): catchable propagation from a Kotlin-implemented
    // slot. The slot half: IFeedableBridge.Describe() throws
    // TestLibrary.KotlinInvalidOperationException("no vacancy"), the mapped ADR-029 type. The
    // second half is ADR-104: this path reaches the bridge through Kotlin calling C#
    // (Sanctuary.Introduce), so the thrown exception leaves Introduce_Thunk, an
    // [UnmanagedCallersOnly] method, through the channel's error slot instead of terminating the
    // host on its way back through the Kotlin/Native frame below it.
    //
    // The full round trip is four hops: Kotlin NoVacancy.describe() throws -> ADR-087 slot
    // envelope -> C# IFeedableBridge.Describe() throws KotlinInvalidOperationException, inside
    // Sanctuary.Introduce -> ADR-104 reverse thunk error channel -> Kotlin NugetManagedException
    // -> escapes the forward-exported sample function -> ADR-024 forward channel -> here.
    //
    // RELAXED to ADR-104's actual contract (its Fork C gate decision, taken 2026-08-31): the host
    // survives, a catchable exception reaches the C# caller, and the message is preserved. The
    // stricter assertions this test used to carry are deferred, not abandoned, and each names a
    // separate open Phase 11 item that will restore it:
    //
    //   Assert.ThrowsAny<InvalidOperationException> / Assert.IsType<KotlinInvalidOperationException>
    //     needs .NET-to-Kotlin exception type mapping. Without it the forward map sees the Kotlin
    //     type NugetManagedException and falls through to KotlinException : Exception
    //     (CirErrorRenderer.kt:78,88), so neither assertion can hold on the channel alone. For the
    //     Kotlin* family specifically, restoring them means round-tripping the type home through
    //     text.
    //
    //   Assert.Contains("IFeedableBridge", ex.StackTrace) / Assert.Contains("Describe", ...)
    //     needs .NET stack-trace propagation. The channel discards the inner throw's managed
    //     stack; the outer exception's own StackTrace is just the P/Invoke call site. Even once it
    //     lands it arrives on KotlinStackTrace, not StackTrace.
    [Fact]
    public void KotlinNoVacancy_DescribeThrows_ReachesCSharpAsACatchableException()
    {
        // Sanctuary.Introduce calls IFeedable.Describe() on NoVacancy, whose Describe() always
        // throws kotlin.IllegalStateException.
        var ex = Assert.ThrowsAny<Exception>(
            () => MenagerieSample.kotlinNoVacancyIntroduceThrows());
        // Verbatim across all four hops. This is the whole of what ADR-104's Fork B envelope
        // carries: type name and message, nothing else.
        Assert.Equal("no vacancy", ex.Message);
    }

    [Fact]
    public void KotlinNoVacancy_LegsOnly_NonThrowingSiblingSlotStillWorks()
    {
        // Same interface, same throwing-implementation shape as the test above, but this crossing
        // only ever touches IFeedable.Legs. The ADR-104 envelope the test above needs must not tax
        // the happy path, and does not: nothing throws, and the value comes back unchanged.
        int legs = MenagerieSample.kotlinNoVacancyLegsOnly();
        Assert.Equal(4, legs);
    }

    // ADR-085 lifetime (the reverse mirror of ADR-084's release tests in BidirectionalTests.cs):
    // the factory's GCHandle is a TRANSFER handle the Kotlin call site frees once the native call
    // returns, so a bridge C# did not store has no roots left and the .NET GC can collect it. Its
    // KotlinRefHandle then releases the Kotlin object through nuget_kotlin_release, which is what
    // the counter observes. Release is GC-timed and never prompt, so this polls forced collections
    // rather than asserting once.
    [Fact]
    public void KotlinGoatBridge_IsReleased_AfterTheTransferHandleIsFreed()
    {
        // Settle anything an earlier test in this class left pending, so the delta below can only
        // be this call's bridge.
        SettleKotlinReleases();
        int before = MenagerieSample.kotlinBridgeReleaseCount();

        // Introduce does not store the feedable, so nothing on either side roots the bridge once
        // the call site disposes its transfer handle.
        MenagerieSample.kotlinGoatIntroduce();

        Assert.True(
            KotlinReleaseFiredWithin(before, TimeSpan.FromSeconds(5)),
            $"expected the collected bridge to release its Kotlin object; " +
            $"kotlinBridgeReleaseCount stayed at {before}");
    }

    // The other half of the contract: a bridge C# still holds must survive a collection. Reading
    // Sanctuary.Featured back goes through that live bridge's identity token (and therefore its
    // ctx StableRef), so a prematurely released bridge cannot pass this.
    [Fact]
    public void LiveKotlinGoatBridge_SurvivesACollection_AndStillResolves()
    {
        MenagerieSample.kotlinGoatStoreFeatured();

        for (int round = 0; round < 3; round++)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
        }

        Assert.True(
            MenagerieSample.kotlinGoatStoredFeaturedIsSameInstance(),
            "a bridge C# still references must survive a collection and still resolve its Kotlin object");

        MenagerieSample.kotlinGoatDropHeld();
    }

    // Phase 13 Wave 3, item 1: bridge reuse per Kotlin object. `nugetMintBridge` mints a fresh
    // bridge on every crossing today, so two crossings of the same Kotlin Goat give C# two
    // distinct IFeedable instances even while `Sanctuary.Remember` still holds the first one
    // alive. EXPECTED TO FAIL as of this commit (two bridges, not one) until bridge reuse lands.
    [Fact]
    public void KotlinGoatRememberedTwice_SameInstanceCrossingIsReused()
    {
        bool same = MenagerieSample.kotlinGoatRememberedTwiceAreSame();
        Assert.True(
            same,
            "expected the SECOND crossing of the same live Kotlin Goat to resolve to the SAME " +
            "C#-side bridge instance as the first crossing, not mint a fresh one");
    }

    // Regression pin: two DIFFERENT Kotlin Goat instances must never report ReferenceEquals,
    // whether or not bridge reuse has landed. Passes today; must keep passing after.
    [Fact]
    public void KotlinGoatsRememberedTwice_DifferentInstancesAreNeverSame()
    {
        bool same = MenagerieSample.kotlinGoatsRememberedTwiceAreNotSame();
        Assert.False(same, "expected two different Kotlin Goat instances to never be ReferenceEquals");
    }

    private static void SettleKotlinReleases()
    {
        for (int round = 0; round < 5; round++)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
            Thread.Sleep(25);
        }
    }

    private static bool KotlinReleaseFiredWithin(int before, TimeSpan budget)
    {
        DateTime deadline = DateTime.UtcNow + budget;
        while (DateTime.UtcNow < deadline)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
            if (MenagerieSample.kotlinBridgeReleaseCount() > before) return true;
            Thread.Sleep(25);
        }
        return false;
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
