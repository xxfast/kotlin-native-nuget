using System.Linq;
using System.Reflection;
using TestLibrary.Outcome;

namespace IntegrationTests;

/// <summary>
/// ADR-199: a generic sealed hierarchy binds as <c>Outcome&lt;T&gt;</c> with its arms on the
/// non-generic <c>Outcome</c> holder (ADR-196). An arm that forwards <c>T</c> is generic over it
/// (<c>Outcome.Ok&lt;T&gt;</c>), an arm that fixes a variant argument carries a phantom
/// (<c>Outcome.Err&lt;T&gt;</c>), and an arm under an invariant parameter keeps Kotlin's exact
/// shape (<c>Cell.IntCell : Cell&lt;int&gt;</c>).
///
/// Oreo (black, white in the middle) asks for dinner and mostly gets <c>Ok</c>. Mylo (brown and
/// creamy) asks for a second dinner and mostly gets <c>Err</c>.
/// </summary>
public class GenericSealedTests
{
    // --- Arms, received ---------------------------------------------------------------------

    [Fact]
    public void Returned_ForwardingAndPhantomArms_PatternMatch()
    {
        using Outcome<int> oreo = OutcomeDesk.Fetch(3);
        using Outcome<int> mylo = OutcomeDesk.Fetch(0);

        Assert.Equal(3, Assert.IsType<Outcome.Ok<int>>(oreo).Value);
        Assert.Equal("no 0", Assert.IsType<Outcome.Err<int>>(mylo).Message);
    }

    [Fact]
    public void Returned_SwitchOverArms_ReachesEachArmAndTheBaseMember()
    {
        IReadOnlyList<Outcome<int>> dinners = OutcomeDesk.All();

        string[] said = dinners.Select(dinner => dinner switch
        {
            Outcome.Ok<int> ok => $"ok {ok.Value}",
            Outcome.Err<int> err => err.Message,
            Outcome.Loading<int> => "loading",
            _ => dinner.Label(),
        }).ToArray();

        Assert.Equal(new[] { "ok 1", "two", "loading" }, said);
        foreach (Outcome<int> dinner in dinners) dinner.Dispose();
    }

    [Fact]
    public void DataObjectArm_OverridesTheOpenBaseMember_AndHasNoPublicConstructor()
    {
        IReadOnlyList<Outcome<int>> dinners = OutcomeDesk.All();
        using Outcome<int> loading = dinners[2];

        Assert.Equal("still loading", Assert.IsType<Outcome.Loading<int>>(loading).Label());
        Assert.Equal("still loading", OutcomeDesk.Describe(loading));
        Assert.Empty(typeof(Outcome.Loading<int>).GetConstructors());
        dinners[0].Dispose();
        dinners[1].Dispose();
    }

    [Fact]
    public void DataArm_EqualsAndToString_ComeFromKotlin()
    {
        using Outcome<int> oreo = OutcomeDesk.Fetch(3);
        using var same = new Outcome.Ok<int>(3);
        using Outcome<int> mylo = OutcomeDesk.Fetch(0);

        Assert.Equal(same, oreo);
        Assert.Equal(same.GetHashCode(), oreo.GetHashCode());
        Assert.Equal("Ok(value=3)", oreo.ToString());
        Assert.Equal("Err(message=no 0)", mylo.ToString());
    }

    [Fact]
    public void AbstractArm_NonArmSubclassHandle_ReconstructsAsTheArm()
    {
        using Outcome<int> eventually = OutcomeDesk.Eventually();

        Outcome.Pending<int> pending = Assert.IsAssignableFrom<Outcome.Pending<int>>(eventually);
        Assert.Equal(3, pending.Eta());
        Assert.True(typeof(Outcome.Pending<int>).IsAbstract);
        Assert.Equal("pending 3", OutcomeDesk.Describe(eventually));
    }

    [Fact]
    public void AbstractArm_NonArmSubclass_ConstructedInCSharp_IsAnOutcome()
    {
        using var later = new Later<string>("Mylo's second dinner");
        Outcome<string> outcome = later;
        using var counted = new Later<int>(2);

        Assert.IsAssignableFrom<Outcome.Pending<string>>(outcome);
        Assert.Equal("Mylo's second dinner", later.Item);
        Assert.Equal(3, later.Eta());
        Assert.Equal("pending 3", OutcomeDesk.Describe(counted));
    }

    [Fact]
    public void OpenArm_IsNotSealed_AndItsOpenMemberIsVirtual()
    {
        using Outcome<string> partial = OutcomeDesk.Partial();

        Outcome.Partial<string> half = Assert.IsType<Outcome.Partial<string>>(partial);
        Assert.Equal("half a treat", half.Sofar);
        Assert.Equal("partial half a treat", half.Progress());
        Assert.False(typeof(Outcome.Partial<string>).IsSealed);
        Assert.True(typeof(Outcome.Partial<string>).GetMethod("Progress")!.IsVirtual);
    }

    [Fact]
    public void IntermediateSealedArm_LeavesReconstructAsTheirOwnArms()
    {
        using Outcome<int> timeout = OutcomeDesk.Timeout();
        using Outcome<string> refused = OutcomeDesk.Refused();

        Assert.IsAssignableFrom<Outcome.Fault<int>>(timeout);
        Assert.Equal(30, Assert.IsType<Outcome.Fault.Timeout<int>>(timeout).Seconds);
        Assert.IsAssignableFrom<Outcome.Fault<string>>(refused);
        Assert.Equal("kibble", Assert.IsType<Outcome.Fault.Refused<string>>(refused).Offer);
        Assert.True(typeof(Outcome.Fault<int>).IsAbstract);
    }

    [Fact]
    public void SiblingArm_IsDeclaredAtNamespaceLevel()
    {
        using Outcome<string> wander = OutcomeDesk.Wander();

        Assert.Equal("Mylo", Assert.IsType<Stray<string>>(wander).Found);
        Assert.Null(typeof(Stray<>).DeclaringType);
        Assert.Equal(typeof(Outcome<>), typeof(Stray<>).BaseType!.GetGenericTypeDefinition());
    }

    [Fact]
    public void UnrecoverableArmParameter_ArmBindsWithoutIt_AndItsMembersAreSkipped()
    {
        using Outcome<int> both = OutcomeDesk.Both();

        Assert.Equal(5, Assert.IsType<Outcome.Both<int>>(both).Value);
        Assert.Single(typeof(Outcome.Both<>).GetGenericArguments());
        Assert.Null(typeof(Outcome.Both<int>).GetProperty("Extra"));
        Assert.Empty(typeof(Outcome.Both<int>).GetConstructors());
    }

    [Fact]
    public void NestedNonArmDeclaration_LivesOnTheHolder()
    {
        using var detail = new Outcome.Detail("Oreo knocked the bowl over");

        Assert.Equal("Oreo knocked the bowl over", detail.Note);
        Assert.False(typeof(Outcome<int>).IsAssignableFrom(typeof(Outcome.Detail)));
    }

    [Fact]
    public void IntermediateArmFixingTheArgument_CarriesThePhantomDown()
    {
        using Outcome<int> stall = OutcomeDesk.Stall();
        using Outcome<string> gone = OutcomeDesk.Gone();

        Assert.IsAssignableFrom<Outcome.Lapse<int>>(stall);
        Assert.Equal(15, Assert.IsType<Outcome.Lapse.Stall<int>>(stall).Minutes);
        Assert.True(typeof(Outcome.Lapse<int>).IsAbstract);
        Assert.IsType<Outcome.Lapse.Gone<string>>(gone);
        Assert.Empty(typeof(Outcome.Lapse.Gone<string>).GetConstructors());
    }

    [Fact]
    public void IntermediateArmClosingAnInvariantArgument_StaysNonGeneric()
    {
        using Cell<int> spare = OutcomeDesk.SpareCell();
        using var mylo = new Cell.Spare.Last(8);

        Assert.Equal(3, Assert.IsType<Cell.Spare.Last>(spare).Count);
        Assert.False(typeof(Cell.Spare.Last).IsGenericType);
        Assert.Equal(8, OutcomeDesk.Peek(mylo));
    }

    // --- Arms, built in C# and passed back --------------------------------------------------

    [Fact]
    public void ArmsBuiltInCSharp_RoundTripThroughABaseParameter()
    {
        using var seven = new Outcome.Ok<int>(7);
        using var mine = new Outcome.Err<int>("Mylo ate it");

        Assert.Equal("ok 7", OutcomeDesk.Describe(seven));
        Assert.Equal("err Mylo ate it", OutcomeDesk.Describe(mine));
    }

    [Fact]
    public void ArmBuiltInCSharp_RoundTripsThroughAnArmTypedParameter()
    {
        using var nine = new Outcome.Ok<int>(9);

        Assert.Equal(9, OutcomeDesk.Unwrap(nine));
    }

    [Fact]
    public void NullableParameterAndReturn_CarryNullAndAValue()
    {
        using var four = new Outcome.Ok<int>(4);
        using Outcome<string>? oreo = OutcomeDesk.Name(1);

        Assert.Equal("none", OutcomeDesk.DescribeOrNone(null));
        Assert.Equal("ok 4", OutcomeDesk.DescribeOrNone(four));
        Assert.Equal("Oreo", Assert.IsType<Outcome.Ok<string>>(oreo).Value);
        Assert.Null(OutcomeDesk.Name(0));
    }

    [Fact]
    public void ListParameter_ElementsBuiltInCSharp_AreReadAsOutcomes()
    {
        using var oreo = new Outcome.Ok<int>(1);
        using var mylo = new Outcome.Err<int>("no");
        using var also = new Outcome.Ok<int>(2);

        Assert.Equal(2, OutcomeDesk.OkCount(new List<Outcome<int>> { oreo, mylo, also }));
    }

    // --- Positions --------------------------------------------------------------------------

    [Fact]
    public void TopLevelFunctionReturn_ConvertsAString()
    {
        using Outcome<string> meal = OutcomeSample.FirstMeal();

        Assert.Equal("tuna", Assert.IsType<Outcome.Ok<string>>(meal).Value);
    }

    [Fact]
    public void ExportedClassArgument_ReadsBackAsTheWrapper()
    {
        using Outcome<Lodger> adopted = OutcomeDesk.Adopt();

        using Lodger mylo = Assert.IsType<Outcome.Ok<Lodger>>(adopted).Value;
        Assert.Equal("Mylo", mylo.Name);
    }

    [Fact]
    public void ArmTypedNothingReturn_IsSpelledWithTheMarker()
    {
        using Outcome.Err<KotlinNothing> boom = OutcomeDesk.Fail();

        Assert.Equal("boom", boom.Message);
    }

    [Fact]
    public void ConstructorParameterAndMutableProperty_RoundTrip()
    {
        using var tuna = new Outcome.Ok<string>("tuna");
        using var diary = new Diary(tuna);

        Assert.Equal("dear diary: tuna", diary.Read());
        using (Outcome<string> entry = diary.Entry)
        {
            Assert.Equal("tuna", Assert.IsType<Outcome.Ok<string>>(entry).Value);
        }

        using var gone = new Outcome.Err<string>("Mylo found the tuna");
        diary.Entry = gone;
        Assert.Equal("dear diary: Mylo found the tuna", diary.Read());
        using Outcome<string> after = diary.Entry;
        Assert.Equal("Mylo found the tuna", Assert.IsType<Outcome.Err<string>>(after).Message);
    }

    [Fact]
    public void TypeParameterInScope_ReturnsAndAcceptsOutcomeOfT()
    {
        using var hamper = new Hamper<int>(6);
        using Outcome<int> wrapped = hamper.Wrap();
        using var mylo = new Outcome.Err<int>("empty");

        Assert.Equal(6, Assert.IsType<Outcome.Ok<int>>(wrapped).Value);
        Assert.Equal(6, hamper.UnwrapOr(wrapped, 0));
        Assert.Equal(-1, hamper.UnwrapOr(mylo, -1));
    }

    [Fact]
    public void ErasedSlot_ConsumerChosenOutcomeOfString_ReadsBack()
    {
        using var oreo = new Outcome.Ok<string>("Oreo");
        using var hamper = new Hamper<Outcome<string>>(oreo);

        using Outcome<string> item = hamper.Item;
        Assert.Equal("Oreo", Assert.IsType<Outcome.Ok<string>>(item).Value);
    }

    [Fact]
    public void ErasedSlot_InstantiationNoKotlinPositionNames_ReadsBackThroughTheFactorySlot()
    {
        // No Kotlin signature mentions Outcome<Long>, so no static Factories entry can exist for
        // it: the read has to come through the NugetFactory<T> slot the C# arm populated.
        using var treats = new Outcome.Ok<long>(9_000_000_000L);
        using var hamper = new Hamper<Outcome<long>>(treats);

        using Outcome<long> item = hamper.Item;
        Assert.Equal(9_000_000_000L, Assert.IsType<Outcome.Ok<long>>(item).Value);
    }

    [Fact]
    public void ErasedSlot_ArmTypedInstantiationNoKotlinPositionNames_FillsTheArmsOwnSlot()
    {
        // Typed as the arm, not the base: Materialize<Outcome.Ok<short>> can only hit the slot
        // the arm's own static constructor filled. Mylo hides exactly four treats in the hamper.
        using var treats = new Outcome.Ok<short>(4);
        using var hamper = new Hamper<Outcome.Ok<short>>(treats);

        using Outcome.Ok<short> item = hamper.Item;
        Assert.Equal((short)4, item.Value);
    }

    [Fact]
    public async Task FlowElement_DiscriminatesEachArm()
    {
        using var tuna = new Outcome.Ok<string>("tuna");
        using var diary = new Diary(tuna);
        var rung = new List<string>();
        await foreach (Outcome<int> bell in diary.DinnerBell())
        {
            using (bell)
            {
                rung.Add(bell switch
                {
                    Outcome.Ok<int> ok => $"ok {ok.Value}",
                    Outcome.Err<int> err => err.Message,
                    Outcome.Loading<int> => "loading",
                    _ => bell.Label(),
                });
            }
        }

        Assert.Equal(["ok 1", "late", "loading"], rung);
    }

    [Fact]
    public void ReturnedLambda_TakesAndReturnsAnOutcome()
    {
        using TestLibrary.KotlinFunc<Outcome<int>, Outcome<int>> retry = OutcomeSample.Retry();
        using var two = new Outcome.Ok<int>(2);
        using var asleep = new Outcome.Err<int>("Oreo is asleep");

        using Outcome<int> three = retry.Invoke(two);
        using Outcome<int> again = retry.Invoke(asleep);

        Assert.Equal(3, Assert.IsType<Outcome.Ok<int>>(three).Value);
        Assert.Equal("retry", Assert.IsType<Outcome.Err<int>>(again).Message);
    }

    // --- Other bases ------------------------------------------------------------------------

    [Fact]
    public void InvariantBase_ClosedFixedArmKeepsKotlinsShape()
    {
        using Cell<int> cell = OutcomeDesk.NumberCell();
        using Cell<string> full = OutcomeDesk.FullCell();

        Assert.Equal(4, Assert.IsType<Cell.IntCell>(cell).Number);
        Assert.Equal("Oreo", Assert.IsType<Cell.Full<string>>(full).Item);
        Assert.False(typeof(Cell.IntCell).IsGenericType);
        Assert.Equal(typeof(Cell<int>), typeof(Cell.IntCell).BaseType);
    }

    [Fact]
    public void InvariantBase_ClosedArmBuiltInCSharp_PassesAsTheBase()
    {
        using var nine = new Cell.IntCell(9);
        using var twelve = new Cell.Full<int>(12);

        Assert.Equal(9, OutcomeDesk.Peek(nine));
        Assert.Equal(12, OutcomeDesk.Peek(twelve));
    }

    [Fact]
    public void TwoParameterBase_PermutedArmListsItsParametersInBaseOrder()
    {
        using Duel<string, int> rematch = OutcomeDesk.Rematch();

        Duel.Flip<string, int> flip = Assert.IsType<Duel.Flip<string, int>>(rematch);
        Assert.Equal(7, flip.First);
        Assert.Equal("Oreo", flip.Second);
        Assert.Equal(new[] { "Y", "X" }, typeof(Duel.Flip<,>).GetGenericArguments().Select(t => t.Name));
    }

    [Fact]
    public void TwoParameterBase_PermutedArmBuiltInCSharp_RoundTrips()
    {
        using var flip = new Duel.Flip<string, int>(2, "Mylo");

        Assert.Equal("Mylo beat 2", OutcomeDesk.Referee(flip));
        Assert.Equal(2, typeof(Duel.Draw<,>).GetGenericArguments().Length);
    }

    [Fact]
    public void EnumBoundBase_ArmRestatesTheBound_ClosedArmKeepsKotlinsShape()
    {
        using Showcase<Ribbon> gold = OutcomeDesk.ShowOff(true);
        using Showcase<Ribbon> scratched = OutcomeDesk.ShowOff(false);
        using var red = new Showcase.Placed<Ribbon>(Ribbon.Red);

        Showcase.Placed<Ribbon> placed = Assert.IsType<Showcase.Placed<Ribbon>>(gold);
        Assert.Equal(Ribbon.Gold, placed.Prize);
        Assert.True(placed.Beats(Ribbon.Blue));
        Assert.False(red.Beats(Ribbon.Gold));
        Assert.IsType<Showcase.Scratched>(scratched);
        Assert.Equal("RED#1", OutcomeDesk.Judge(red));
        Assert.Equal("scratched", OutcomeDesk.Judge(scratched));
    }

    [Fact]
    public void InterfaceBoundBase_ArmsRestateTheBound()
    {
        using Blanket<Lodger> bedtime = OutcomeDesk.Bedtime();

        using Lodger oreo = Assert.IsType<Blanket.Claimed<Lodger>>(bedtime).Dozer;
        Assert.Equal("Oreo", oreo.Name);
        Type phantom = typeof(Blanket.Folded<>).GetGenericArguments().Single();
        Assert.Contains(typeof(IDozer), phantom.GetGenericParameterConstraints());
    }

    [Fact]
    public void GenericSealedInterface_TakesTheSameRoute()
    {
        using Reply<int> trill = OutcomeDesk.Call(true);
        using Reply<int> hiss = OutcomeDesk.Call(false);
        using var quiet = new Reply.Trill<int>(2);

        Assert.Equal(11, Assert.IsType<Reply.Trill<int>>(trill).Pitch);
        Assert.IsType<Reply.Hiss<int>>(hiss);
        Assert.True(typeof(Reply<int>).IsAbstract);
        Assert.Equal("trill 2", OutcomeDesk.Hear(quiet));
        Assert.Equal("hiss", OutcomeDesk.Hear(hiss));
    }

    // --- Named skips ------------------------------------------------------------------------

    [Theory]
    [InlineData("AnyLabel")] // Outcome<*>: C# has no projection of a generic class
    [InlineData("Batch")] // Outcome<List<Int>>: the erased wire cannot read the argument
    [InlineData("Folded")] // Blanket.Folded<KotlinNothing>: the marker fails where T : IDozer
    public void NamedSkip_LeavesTheRestOfTheOwnerBound(string skipped)
    {
        MethodInfo[] methods = typeof(OutcomeDesk).GetMethods(BindingFlags.Public | BindingFlags.Static);

        Assert.DoesNotContain(methods, method => method.Name == skipped);
        Assert.Contains(methods, method => method.Name == "Fetch");
    }
}
