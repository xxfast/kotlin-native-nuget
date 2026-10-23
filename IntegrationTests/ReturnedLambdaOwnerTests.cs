using TestLibrary.Cheersquad;

namespace IntegrationTests;

/// <summary>
/// ADR-239: a Kotlin function or member that RETURNS a lambda binds as a BCL delegate on every
/// owner, in the shapes ADR-236 gave a lambda property: <c>(A) -&gt; R</c> is <c>Func&lt;A, R&gt;</c>,
/// <c>(A) -&gt; Unit</c> is <c>Action&lt;A&gt;</c>, <c>suspend (A) -&gt; R</c> is
/// <c>Func&lt;A, CancellationToken, Task&lt;R&gt;&gt;</c>, <c>suspend (A) -&gt; Unit</c> is
/// <c>Func&lt;A, CancellationToken, Task&gt;</c>, and a nullable lambda result is the same type with
/// <c>?</c>. Never <c>KotlinFunc</c>.
///
/// Owners: the top level, an ordinary class, an <c>object</c>, a default and a named companion, a
/// Kotlin-backed interface and a C#-implemented one (Kotlin calls the delegate C# returned), a sealed
/// base and its arm, a generic class, a value class, an enum body, and a class- and
/// <c>String</c>-receiver extension.
///
/// Every local is typed explicitly, so a member that still returns a wrapper fails to compile
/// (CS0029) rather than passing through <c>var</c>. Oreo shakes the pompoms; Mylo leads the chant.
/// </summary>
public class ReturnedLambdaOwnerTests
{
    // (1) The top level, static on the file facade.

    [Fact]
    public void TopLevel_Cheer_IsAFunc()
    {
        Func<string, string> cheer = CheersquadSample.Cheer();

        Assert.Equal("go Oreo", cheer.Invoke("Oreo"));
        Assert.Equal(typeof(Func<string, string>), typeof(CheersquadSample).GetMethod("Cheer")!.ReturnType);
    }

    [Fact]
    public void TopLevel_Clap_UnitResultIsAnAction()
    {
        int before = CheersquadSample.ClapCount();
        Action<int> clap = CheersquadSample.Clap();

        clap.Invoke(3);

        Assert.Equal(before + 3, CheersquadSample.ClapCount());
    }

    [Fact]
    public async Task TopLevel_Chant_SuspendResultIsAnAsyncFunc()
    {
        Func<string, CancellationToken, Task<string>> chant = CheersquadSample.Chant();

        Assert.Equal("Mylo, Mylo, Mylo", await chant.Invoke("Mylo", CancellationToken.None));
    }

    [Fact]
    public async Task TopLevel_Stomp_SuspendUnitResultIsAnAsyncFuncOfTask()
    {
        int before = CheersquadSample.ClapCount();
        Func<int, CancellationToken, Task> stomp = CheersquadSample.Stomp();

        await stomp.Invoke(2, CancellationToken.None);

        Assert.Equal(before + 2, CheersquadSample.ClapCount());
    }

    [Fact]
    public void TopLevel_MaybeCheer_NullableResultIsNullOrAFunc()
    {
        Func<string, string>? quiet = CheersquadSample.MaybeCheer(false);
        Func<string, string>? loud = CheersquadSample.MaybeCheer(true);

        Assert.Null(quiet);
        Assert.Equal("GO Oreo", loud!.Invoke("Oreo"));
    }

    [Fact]
    public async Task TopLevel_MaybeChant_NullableSuspendResult()
    {
        Func<string, CancellationToken, Task<string>>? quiet = CheersquadSample.MaybeChant(false);
        Func<string, CancellationToken, Task<string>>? loud = CheersquadSample.MaybeChant(true);

        Assert.Null(quiet);
        Assert.Equal("Mylo! Mylo!", await loud!.Invoke("Mylo", CancellationToken.None));
    }

    // (2) An ordinary class: the returned lambda closes over the instance.

    [Fact]
    public void Class_Cheer_ClosesOverTheInstance()
    {
        using var tassel = new Tassel("black and white");
        Func<string, string> cheer = tassel.Cheer();

        Assert.Equal("black and white tassel for Oreo", cheer.Invoke("Oreo"));
        Assert.Equal(typeof(Func<string, string>), typeof(Tassel).GetMethod("Cheer")!.ReturnType);
    }

    [Fact]
    public void Class_Clap_ActionWritesBackToTheInstance()
    {
        using var tassel = new Tassel("brown");
        Action<int> clap = tassel.Clap();

        clap.Invoke(4);
        clap.Invoke(1);

        Assert.Equal(5, tassel.Claps);
    }

    [Fact]
    public async Task Class_ChantAndStomp_SuspendResults()
    {
        using var tassel = new Tassel("creamy");
        Func<string, CancellationToken, Task<string>> chant = tassel.Chant();
        Func<int, CancellationToken, Task> stomp = tassel.Stomp();

        Assert.Equal("creamy chant for Mylo", await chant.Invoke("Mylo", CancellationToken.None));
        await stomp.Invoke(6, CancellationToken.None);
        Assert.Equal(6, tassel.Claps);
    }

    [Fact]
    public async Task Class_MaybeCheerAndMaybeChant_NullableResults()
    {
        using var tassel = new Tassel("black");

        Func<string, string>? quiet = tassel.MaybeCheer(false);
        Func<string, string>? loud = tassel.MaybeCheer(true);
        Func<string, CancellationToken, Task<string>>? still = tassel.MaybeChant(false);
        Func<string, CancellationToken, Task<string>>? howl = tassel.MaybeChant(true);

        Assert.Null(quiet);
        Assert.Equal("black roar for Oreo", loud!.Invoke("Oreo"));
        Assert.Null(still);
        Assert.Equal("black howl for Mylo", await howl!.Invoke("Mylo", CancellationToken.None));
    }

    [Fact]
    public void Class_PairAndSwish_ArityTwoAndZero()
    {
        using var tassel = new Tassel("white");
        Func<string, int, string> pair = tassel.Pair();
        Func<string> swish = tassel.Swish();

        Assert.Equal("Oreo x3 in white", pair.Invoke("Oreo", 3));
        Assert.Equal("white swish", swish.Invoke());
    }

    [Fact]
    public void Class_InstanceBesideACompanion_Wave()
    {
        using var mascot = new Mascot("Mylo");
        Func<string, string> wave = mascot.Wave();

        Assert.Equal("Mylo waves at Oreo", wave.Invoke("Oreo"));
    }

    // (3) An object, static on the object's class.

    [Fact]
    public async Task Object_EveryShape()
    {
        Func<string, string> cheer = Bleachers.Cheer();
        Action<int> stomp = Bleachers.Stomp();
        Func<string, CancellationToken, Task<string>> chant = Bleachers.Chant();
        Func<string, string>? quiet = Bleachers.MaybeCheer(false);
        Func<string, string>? loud = Bleachers.MaybeCheer(true);
        int before = Bleachers.Stomps;

        Assert.Equal("the bleachers cheer Oreo", cheer.Invoke("Oreo"));
        stomp.Invoke(2);
        Assert.Equal(before + 2, Bleachers.Stomps);
        Assert.Equal("the bleachers chant Mylo", await chant.Invoke("Mylo", CancellationToken.None));
        Assert.Null(quiet);
        Assert.Equal("the bleachers roar Oreo", loud!.Invoke("Oreo"));
    }

    // (4) A default and a named companion, static on the owning class.

    [Fact]
    public async Task DefaultCompanion_EveryShape()
    {
        Func<string, string> cheer = Mascot.Cheer();
        Func<string, CancellationToken, Task<string>> chant = Mascot.Chant();
        Func<string, string>? quiet = Mascot.MaybeCheer(false);
        Func<string, string>? loud = Mascot.MaybeCheer(true);

        Assert.Equal("the mascot cheers Oreo", cheer.Invoke("Oreo"));
        Assert.Equal("the mascot chants Mylo", await chant.Invoke("Mylo", CancellationToken.None));
        Assert.Null(quiet);
        Assert.Equal("the mascot roars Oreo", loud!.Invoke("Oreo"));
    }

    [Fact]
    public void NamedCompanion_Raise()
    {
        Func<int, string> raise = Pennant.Raise();

        Assert.Equal("raised 3 paws high", raise.Invoke(3));
    }

    // (5) A Kotlin interface: the Kotlin-backed half through its wrapper, the C# half through the
    // bridge, where Kotlin calls the delegate the C# implementation returned.

    [Fact]
    public async Task KotlinBackedInterface_EveryShape()
    {
        using IChanter megaphone = CheersquadSample.Megaphone();

        Func<string, string> cheer = megaphone.Cheer();
        Func<string, CancellationToken, Task<string>> chant = megaphone.Chant();
        Func<string, string>? quiet = megaphone.MaybeCheer(false);
        Func<string, string>? loud = megaphone.MaybeCheer(true);

        Assert.Equal("Mylo cheers Oreo", cheer.Invoke("Oreo"));
        Assert.Equal("Mylo chants Oreo", await chant.Invoke("Oreo", CancellationToken.None));
        Assert.Null(quiet);
        Assert.Equal("Mylo roars Oreo", loud!.Invoke("Oreo"));
    }

    private sealed class Pompom(string shaker) : IChanter
    {
        public Func<string, string> Cheer() => who => $"{shaker} shakes for {who}";

        public Func<string, CancellationToken, Task<string>> Chant() => async (who, token) =>
        {
            await Task.Delay(1, token);
            return $"{shaker} chants for {who}";
        };

        public Func<string, string>? MaybeCheer(bool loud) => loud ? who => $"{shaker} roars for {who}" : null;

        public void Dispose() { }
    }

    [Fact]
    public void CSharpImplementedInterface_KotlinCallsTheReturnedDelegate()
    {
        using IChanter oreo = new Pompom("Oreo");

        Assert.Equal("Oreo shakes for Mylo", CheersquadSample.Rally(oreo, "Mylo"));
    }

    [Fact]
    public async Task CSharpImplementedInterface_KotlinAwaitsTheReturnedAsyncDelegate()
    {
        using IChanter mylo = new Pompom("Mylo");
        using var cheerleader = new Cheerleader(mylo);

        Assert.Equal("Mylo chants for Oreo", await cheerleader.RallyLaterAsync("Oreo"));
    }

    [Fact]
    public void CSharpImplementedInterface_NullableResultCrossesNullAndADelegate()
    {
        using IChanter oreo = new Pompom("Oreo");

        Assert.Equal("silence for Mylo", CheersquadSample.RallyMaybe(oreo, false, "Mylo"));
        Assert.Equal("Oreo roars for Mylo", CheersquadSample.RallyMaybe(oreo, true, "Mylo"));
    }

    // (6) A sealed base and its arm.

    [Fact]
    public void SealedBase_Cheer_BindsOnTheBaseSeenFromKotlin()
    {
        using Routine routine = CheersquadSample.Tumble(2);
        Func<string, string> cheer = routine.Cheer();

        Assert.Equal("tumble for Oreo", cheer.Invoke("Oreo"));
    }

    [Fact]
    public async Task SealedArm_EveryShape()
    {
        using var tumble = new Routine.Tumble(3);

        Func<int, string> flip = tumble.Flip();
        Func<int, CancellationToken, Task<int>> spin = tumble.Spin();
        Func<int, string>? wobbly = tumble.MaybeFlip(false);
        Func<int, string>? clean = tumble.MaybeFlip(true);
        Func<string, string> cheer = tumble.Cheer();

        Assert.Equal("5 flips", flip.Invoke(2));
        Assert.Equal(12, await spin.Invoke(4, CancellationToken.None));
        Assert.Null(wobbly);
        Assert.Equal("4 clean flips", clean!.Invoke(1));
        Assert.Equal("tumble for Mylo", cheer.Invoke("Mylo"));
    }

    // (7) A generic class: the delegate is typed by the closed T.

    [Fact]
    public async Task GenericClass_DelegatesAreTypedByTheClosedT()
    {
        using var bell = new Megaphone<string>("the bell");

        Func<string, string> echo = bell.Echo();
        Func<string> hold = bell.Hold();
        Func<string, string> announce = bell.Announce();
        Func<string>? quiet = bell.MaybeHold(false);
        Func<string>? loud = bell.MaybeHold(true);
        Func<CancellationToken, Task<string>> later = bell.HoldLater();

        Assert.Equal("Oreo", echo.Invoke("Oreo"));
        Assert.Equal("the bell", hold.Invoke());
        Assert.Equal("Mylo holds the megaphone", announce.Invoke("Mylo"));
        Assert.Null(quiet);
        Assert.Equal("the bell", loud!.Invoke());
        Assert.Equal("the bell", await later.Invoke(CancellationToken.None));
    }

    [Fact]
    public void GenericClass_ClassHandleT()
    {
        using var red = new Tassel("red");
        using var megaphone = new Megaphone<Tassel>(red);
        Func<Tassel> hold = megaphone.Hold();

        using Tassel held = hold.Invoke();
        Assert.Equal("red", held.Colour);
    }

    // (8) A value class: instance members of its record struct.

    [Fact]
    public async Task ValueClass_CountAndCountLater()
    {
        var squad = new Squad(4);
        Func<int, int> count = squad.Count();
        Func<int, CancellationToken, Task<int>> later = squad.CountLater();

        Assert.Equal(12, count.Invoke(3));
        Assert.Equal(8, await later.Invoke(2, CancellationToken.None));
    }

    // (9) An enum body member, a `FormationExtensions` extension.

    [Fact]
    public void EnumBody_Call()
    {
        Func<string, string> pyramid = Formation.Pyramid.Call();
        Func<string, string> line = Formation.Line.Call();

        Assert.Equal("Oreo into the pyramid", pyramid.Invoke("Oreo"));
        Assert.Equal("Mylo into the line", line.Invoke("Mylo"));
    }

    // (10) Extensions: a class receiver and a String receiver.

    [Fact]
    public async Task ClassReceiverExtension_EncoreAndEncoreLater()
    {
        using var tassel = new Tassel("black");
        Func<string, string> encore = tassel.Encore();
        Func<string, CancellationToken, Task<string>> later = tassel.EncoreLater();

        Assert.Equal("black encore for Mylo", encore.Invoke("Mylo"));
        Assert.Equal("black encore later for Oreo", await later.Invoke("Oreo", CancellationToken.None));
    }

    [Fact]
    public void StringReceiverExtension_Refrain()
    {
        Func<int, string> refrain = "Oreo".Refrain();

        Assert.Equal("Oreo Oreo Oreo", refrain.Invoke(3));
    }

    // The delegate owns its Kotlin lambda, not the C# wrapper it came from.
    [Fact]
    public void ReturnedDelegate_IsCallableManyTimesAndAfterItsOwnerIsDisposed()
    {
        Func<string, string> cheer;
        using (var tassel = new Tassel("brown"))
        {
            cheer = tassel.Cheer();
        }

        // The lambda captured the Kotlin tassel, not the C# wrapper, so it outlives the wrapper.
        Assert.Equal("brown tassel for Oreo", cheer.Invoke("Oreo"));
        Assert.Equal("brown tassel for Mylo", cheer.Invoke("Mylo"));
    }
}
