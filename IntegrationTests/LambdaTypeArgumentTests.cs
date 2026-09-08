using System.Reflection;
using TestLibrary;
using TestLibrary.Catcam;
using TestLibrary.Catcam.Lens;

namespace IntegrationTests;

/// <summary>
/// Issue #111. The legacy lambda routes spelled every one of a lambda's type arguments with
/// <c>arg.type?.resolve()?.declaration?.simpleName</c>, which drops the argument's namespace and
/// its own type arguments. <c>packNuget</c> stayed green and the consumer got
/// <c>CS0246: The type or namespace name 'Flow' could not be found</c>.
///
/// Two outcomes, never one:
/// <list type="bullet">
/// <item>a type argument C# can name (an exported class, in any namespace) is <b>qualified</b>, so
/// <c>CatCam.OnPick</c> is a real, callable <c>KotlinFunc&lt;CamId, Snapshot&gt;</c> even though
/// both arguments live in <c>TestLibrary.Catcam.Lens</c> and the property lives in
/// <c>TestLibrary.Catcam</c>;</item>
/// <item>a type argument it cannot name (<c>Flow&lt;T&gt;</c>, an unexported dependency type) makes
/// the member <b>absent</b>, named by a KSP diagnostic rather than emitted broken.</item>
/// </list>
///
/// The fact that this file compiles at all is half the test: pre-fix the generated
/// <c>Interop.cs</c> does not, so <c>GeneratedBindingsCheck</c> fails before xunit ever runs. The
/// diagnostics themselves are asserted at Tier 1, where the KSP log is readable
/// (<c>Tier1LambdaTypeArgumentTest</c>), matching the AnnotationClassTests precedent.
///
/// The cam watches Oreo (black with the white bib) and Mylo (brown and creamy). Neither moves.
/// </summary>
public class LambdaTypeArgumentTests
{
    [Fact]
    public void CatCam_OnPick_QualifiesBothCrossNamespaceTypeArgumentsAndInvokes()
    {
        // The headline cell. `(CamId) -> Snapshot` with BOTH arguments one namespace away: a fix
        // that qualified only the result would still not compile.
        using var cam = new CatCam("Oreo");
        using var id = new CamId("oreo-front");
        using KotlinFunc<CamId, Snapshot> pick = cam.OnPick;

        using Snapshot snapshot = pick.Invoke(id);

        Assert.Equal("oreo-front: Oreo, unmoved", snapshot.Caption);
    }

    [Fact]
    public void CatCam_OnPick_IsTypedWithTheLensTypes_NotSomeBareLookalike()
    {
        // Metadata cannot see `global::`, but it can see WHICH CamId/Snapshot the property landed
        // on. A same-simple-name type in the wrong namespace would pass the compile and fail here.
        PropertyInfo onPick = typeof(CatCam).GetProperty("OnPick")!;

        Assert.Equal(typeof(KotlinFunc<CamId, Snapshot>), onPick.PropertyType);
        Assert.Equal("TestLibrary.Catcam.Lens", typeof(CamId).Namespace);
        Assert.Equal("TestLibrary.Catcam.Lens", typeof(Snapshot).Namespace);
        Assert.Equal("TestLibrary.Catcam", typeof(CatCam).Namespace);
    }

    [Fact]
    public void CatCam_Watching_StillBinds()
    {
        // Control: the skips below must cost the rest of the class nothing.
        using var cam = new CatCam("Mylo");

        Assert.Equal("Mylo", cam.Watching);
    }

    [Theory]
    // The reported repro: `(CamId) -> Flow<Snapshot>`, whose result has no C# spelling here.
    [InlineData("OnStream")]
    // The `suspend` twin, through the second copy of the arm (CirClassTranslator.kt:352).
    [InlineData("OnStreamAsync")]
    // An unexported dependency type argument: `dev.other.core` is never admitted, so
    // `Advertisement` is never declared and no spelling of it resolves.
    [InlineData("OnSponsor")]
    public void CatCam_UnspellableLambdaProperty_IsAbsent(string member)
    {
        // Absent, not present-but-broken. A member typed `KotlinFunc<CamId, Flow>` compiles
        // nowhere, so emitting it takes the whole consumer build down with it.
        Assert.Null(typeof(CatCam).GetProperty(member));
        Assert.Empty(typeof(CatCam).GetMember(member));
    }

    [Fact]
    public void SealedSubclass_OnPick_QualifiesItsTypeArguments()
    {
        // The third copy of the arm (CirClassTranslator.kt:1118). A sealed subclass property is
        // the one position where the residual legacy lambda route still runs after ADR-111, so it
        // has to be crossed on its own. No instance needed: the property's declared type is the
        // assertion.
        PropertyInfo onPick = typeof(CamFeed.Live).GetProperty("OnPick")!;

        Assert.Equal(typeof(KotlinFunc<CamId, Snapshot>), onPick.PropertyType);
    }

    [Fact]
    public void SealedSubclass_UnspellableLambdaProperty_IsAbsent()
    {
        Assert.Null(typeof(CamFeed.Live).GetProperty("OnStream"));
        Assert.Empty(typeof(CamFeed.Live).GetMember("OnStream"));
    }

    [Fact]
    public void SealedSubclass_Label_StillBinds()
    {
        // Control for the sealed arm, same reason as CatCam.Watching.
        Assert.NotNull(typeof(CamFeed.Live).GetProperty("Label"));
    }

    [Fact]
    public void TopLevelFunction_ReturningALambda_QualifiesItsTypeArgumentsAndInvokes()
    {
        // The static route (CirFunctionTranslator.kt:134), which no class-property fix touches.
        using var id = new CamId("mylo-couch");
        using KotlinFunc<CamId, Snapshot> picker = CatCamRoutes.CamPicker();

        using Snapshot snapshot = picker.Invoke(id);

        Assert.Equal("mylo-couch: picked", snapshot.Caption);
    }

    [Fact]
    public void TopLevelFunction_ReturningAnUnspellableLambda_IsAbsent()
    {
        // `camStreamer(): (CamId) -> Flow<Snapshot>` must skip named with
        // SKIPPED_UNSUPPORTED_RETURN, so CatCamRoutes carries no CamStreamer at all.
        Assert.Null(typeof(CatCamRoutes).GetMethod("CamStreamer"));
        Assert.Empty(typeof(CatCamRoutes).GetMember("CamStreamer"));
    }
}
