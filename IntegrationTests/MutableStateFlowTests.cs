using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-071: settable <c>.Value</c> on <c>MutableStateFlow&lt;T&gt;</c>. A <c>MutableStateFlow&lt;T&gt;</c>
/// declared PUBLICLY (not narrowed through <c>.asStateFlow()</c>) surfaces as a generated
/// <c>KotlinMutableStateFlow&lt;T&gt; : KotlinStateFlow&lt;T&gt;</c> with a settable
/// <c>public new T Value { get; set; }</c>. Read-only <c>StateFlow&lt;T&gt;</c> members (ADR-065/067,
/// e.g. <c>Mood</c>/<c>EnergyLevel</c> on the same fixture) are completely untouched and must remain
/// get-only.
///
/// Fixture: <see cref="CatMoodTracker"/> (test-library) crosses every element-type marshalling seam
/// on the write path: <c>MutableStateFlow&lt;int&gt;</c> (no conversion), <c>MutableStateFlow&lt;string&gt;</c>
/// (string marshalling), and <c>MutableStateFlow&lt;Cat&gt;</c> (object handle unwrap). <see cref="Grudge"/>
/// is an element type whose Kotlin <c>equals</c> always throws, forcing the ADR-030 <c>errorOut</c>
/// path on the setter export.
/// </summary>
public class MutableStateFlowTests
{
    [Fact]
    public void SettableValue_IntElement_NoConversion_MyloGetsSevenTreats()
    {
        // Mylo earns 7 treats -- a plain Int write, the cheapest seam on the write path
        using var tracker = new CatMoodTracker("Mylo");
        tracker.TreatCount.Value = 7;
        Assert.Equal(7, tracker.TreatCount.Value);
        Assert.Equal(7, tracker.TreatsGivenSoFar()); // the write really landed in Kotlin, not just C#
    }

    [Fact]
    public void SettableValue_StringElement_NeedsConversion_MyloGetsATartanCollar()
    {
        // Mylo's collar colour is a String element -- needs string marshalling at the write seam
        using var tracker = new CatMoodTracker("Mylo");
        tracker.CollarColour.Value = "tartan";
        Assert.Equal("tartan", tracker.CollarColour.Value);
    }

    [Fact]
    public void SettableValue_ObjectElement_CrossesAsAHandle_MyloGetsANewFavouriteToy()
    {
        // Mylo's favourite toy is a Cat -- an object element, unwrapped via the handle
        using var tracker = new CatMoodTracker("Mylo");
        using var mouse = new Cat("Mouse", 9);
        tracker.FavouriteToy.Value = mouse;
        using var toy = tracker.FavouriteToy.Value; // fresh wrapper per read (ADR-005)
        Assert.Equal("Mouse", toy.Name);
    }

    [Fact]
    public async Task SettableValue_WriteIsObservedByALiveCollector_OreosTreatCountStreamSeesTheUpdate()
    {
        // A write must be observable through the collect side too -- hot, replay-1, conflated
        using var tracker = new CatMoodTracker("Oreo");
        var seen = new List<int>();
        var cts = new CancellationTokenSource();
        tracker.TreatCount.Value = 3;
        await foreach (var n in tracker.TreatCount.WithCancellation(cts.Token))
        {
            seen.Add(n);
            cts.Cancel(); // StateFlow never completes on its own
        }
        Assert.Equal(3, seen[0]);
    }

    [Fact]
    public void SettableValue_PropertyAndFunctionReturn_ShareStorage_OreosTreatJarMatchesTreatCount()
    {
        // TreatJar() is a non-suspend function return sharing storage with the TreatCount property
        using var tracker = new CatMoodTracker("Oreo");
        tracker.TreatJar().Value = 11;
        Assert.Equal(11, tracker.TreatCount.Value);
    }

    [Fact]
    public void KotlinMutableStateFlow_IsAKotlinStateFlow_IsAKotlinFlow_IsAnIAsyncEnumerable_OreoUpcastsLikeKotlinsOwn()
    {
        // KotlinMutableStateFlow<T> IS-A KotlinStateFlow<T> IS-A KotlinFlow<T> IS-A IAsyncEnumerable<T>
        // -- mirrors Kotlin's own MutableStateFlow : StateFlow : Flow upcast
        using var tracker = new CatMoodTracker("Oreo");
        tracker.TreatCount.Value = 5;

        KotlinStateFlow<int> ro = tracker.TreatCount;
        KotlinFlow<int> flow = tracker.TreatCount;
        IAsyncEnumerable<int> seq = tracker.TreatCount;

        Assert.NotNull(flow);
        Assert.NotNull(seq);
        Assert.Equal(tracker.TreatCount.Value, ro.Value); // base-typed read sees the derived write
    }

    // A read-only StateFlow member must STAY get-only. This must NOT compile:
    //
    //   tracker.Mood.Value = "zoomies";
    //
    // Expected compiler error: CS0200 ("Property or indexer 'KotlinStateFlow<string>.Value' cannot
    // be assigned to -- it is read only"). This is a deliberate, permanent compile-time-negative
    // note (not a runtime assert) documenting that the declared-type keying (ADR-071) does not leak
    // a setter onto a genuinely read-only StateFlow<T>-declared member.

    [Fact]
    public void SettableValue_SetterExceptionPropagation_OreosGrudgeAgainstTheVetCannotBeReplaced()
    {
        // Grudge.equals always throws -- MutableStateFlow.value's setter conflates by Any.equals
        // on the PREVIOUS value (StateFlow.kt:332), so the throw propagates out of the setter
        // export via the ADR-030 errorOut path.
        using var tracker = new CatMoodTracker("Oreo");
        using var newGrudge = new Grudge("the carrier");
        Assert.Throws<KotlinInvalidOperationException>(() => tracker.Grudge.Value = newGrudge);
    }

    [Fact]
    public async Task SettableValue_WriteFromThreadpoolThread_MyloGetsHisTreatFromABackgroundThread()
    {
        // Pins the one inferred-not-spiked threading claim in ADR-071: a .Value write issued from
        // an arbitrary .NET threadpool thread (not the thread the tracker was constructed on) must
        // not crash or corrupt state.
        using var tracker = new CatMoodTracker("Mylo");
        await Task.Run(() => tracker.TreatCount.Value = 99);
        Assert.Equal(99, tracker.TreatCount.Value);
    }

    [Fact]
    public void SettableValue_AfterDispose_PropertyGetterThrowsObjectDisposedException_OreosTrackerIsPutAway()
    {
        // Parity with ADR-065: disposal must throw for the mutable surface too
        var tracker = new CatMoodTracker("Oreo");
        tracker.Dispose();
        Assert.Throws<ObjectDisposedException>(() => { var _ = tracker.TreatCount; });
    }

    // --- ADR-071 amendment (nullable element write): `.Value = null` on a MutableStateFlow<T?>. ---

    [Fact]
    public void NullableReferenceElementWrite_RoundTripsNull_OreoLosesHerCollarTag()
    {
        using var tracker = new CatMoodTracker("Oreo");
        KotlinMutableStateFlow<string?> tag = tracker.CollarTag;
        tag.Value = "bell";
        Assert.Equal("bell", tag.Value);
        tag.Value = null;
        Assert.Null(tag.Value);
    }

    [Fact]
    public void NullableValueElementWrite_NullIsNotZero_MylosNapIsUntracked()
    {
        // The has-value pair: a null write must not land as 0, and a 0 write must not land as null.
        using var tracker = new CatMoodTracker("Mylo");
        tracker.NapMinutes.Value = 15;
        Assert.Equal(15, tracker.NapMinutes.Value);
        tracker.NapMinutes.Value = null;
        Assert.Null(tracker.NapMinutes.Value);
        tracker.NapMinutes.Value = 0;
        Assert.Equal(0, tracker.NapMinutes.Value);
    }

    [Fact]
    public void NullableObjectElementWrite_NullHandleLandsInKotlin_MyloFallsOutWithHisBestFriend()
    {
        using var tracker = new CatMoodTracker("Mylo");
        using var oreo = new Cat("Oreo", 4);
        tracker.BestFriend.Value = oreo;
        Assert.Equal("Oreo", tracker.BestFriendName());
        tracker.BestFriend.Value = null;
        Assert.Null(tracker.BestFriendName()); // the null really landed in Kotlin
        Assert.Null(tracker.BestFriend.Value);
    }

    [Fact]
    public void NullableElementWrite_HeldFunctionReturn_SharesStorage_OreosNapLogIsCleared()
    {
        // NapLog() is the held function-return twin of NapMinutes: its null reads go through the
        // runtime's null-aware value export, and its writes through the flow-keyed setter.
        using var tracker = new CatMoodTracker("Oreo");
        using KotlinMutableStateFlow<int?> log = tracker.NapLog();
        Assert.Null(log.Value);
        log.Value = 40;
        Assert.Equal(40, tracker.NapMinutes.Value);
        log.Value = null;
        Assert.Null(tracker.NapMinutes.Value);
        Assert.Null(log.Value);
    }

    // --- ADR-071 amendment (nullable member write): a MutableStateFlow<T>? member. ---

    [Fact]
    public void NullableMemberWrite_AbsentIsNull_PresentIsSettable_OreoStartsADiary()
    {
        using var tracker = new CatMoodTracker("Oreo");
        Assert.Null(tracker.Diary);
        tracker.OpenDiary("day one");
        KotlinMutableStateFlow<string> diary = tracker.Diary!;
        diary.Value = "day two";
        Assert.Equal("day two", tracker.Diary!.Value);
    }

    [Fact]
    public void NullableMemberWrite_AbsentAtWriteTime_Throws_MylosDiaryWasThrownAway()
    {
        // A holder obtained while the member was present, written after it went absent, throws
        // through the setter's errorOut (IllegalStateException) instead of dropping the write.
        using var tracker = new CatMoodTracker("Mylo");
        tracker.OpenDiary("day one");
        KotlinMutableStateFlow<string> diary = tracker.Diary!;
        tracker.CloseDiary();
        Assert.Throws<KotlinInvalidOperationException>(() => diary.Value = "day two");
        Assert.Null(tracker.Diary);
    }

    // ADR-071 Alternative 4: CompareAndSet over a per-member `_compare_and_set` export, and
    // Update / UpdateAndGet / GetAndUpdate as C# retry loops over it.

    [Fact]
    public void CompareAndSet_IntElement_OnlyMovesFromTheExpectedValue_MylosTreatCount()
    {
        using var tracker = new CatMoodTracker("Mylo");
        tracker.TreatCount.Value = 3;
        Assert.False(tracker.TreatCount.CompareAndSet(expect: 9, update: 4)); // stale expect
        Assert.Equal(3, tracker.TreatCount.Value);
        Assert.True(tracker.TreatCount.CompareAndSet(expect: 3, update: 4));
        Assert.Equal(4, tracker.TreatsGivenSoFar()); // the swap landed in Kotlin
    }

    [Fact]
    public void CompareAndSet_StringElement_NeedsConversion_MylosCollarSwapsOnlyFromTartan()
    {
        using var tracker = new CatMoodTracker("Mylo");
        tracker.CollarColour.Value = "tartan";
        Assert.False(tracker.CollarColour.CompareAndSet("blue", "green"));
        Assert.True(tracker.CollarColour.CompareAndSet("tartan", "red"));
        Assert.Equal("red", tracker.CollarColour.Value);
    }

    [Fact]
    public void CompareAndSet_ObjectElement_ComparesByKotlinEquals_MylosToyIsReplaced()
    {
        // Cat is a plain class, so Kotlin equals is identity: a wrapper read back from .Value is
        // the same Kotlin instance, another Cat with the same name is not.
        using var tracker = new CatMoodTracker("Mylo");
        using var mouse = new Cat("Mouse", 9);
        tracker.FavouriteToy.Value = mouse;
        using var lookalike = new Cat("Mouse", 9);
        using var ball = new Cat("Ball", 1);
        Assert.False(tracker.FavouriteToy.CompareAndSet(lookalike, ball));
        using var current = tracker.FavouriteToy.Value; // fresh wrapper, same Kotlin instance
        Assert.True(tracker.FavouriteToy.CompareAndSet(current, ball));
        using var toy = tracker.FavouriteToy.Value;
        Assert.Equal("Ball", toy.Name);
    }

    [Fact]
    public void CompareAndSet_ThrowingEquals_Throws_OreosGrudgeIsNotAMissedSwap()
    {
        // A throwing Kotlin equals must surface as an exception, never read as `false`.
        using var tracker = new CatMoodTracker("Oreo");
        using var expect = new Grudge("the vet");
        using var update = new Grudge("the carrier");
        Assert.Throws<KotlinInvalidOperationException>(
            () => tracker.Grudge.CompareAndSet(expect, update));
    }

    [Fact]
    public void Update_ScalarElement_ConcurrentIncrementsLoseNothing_OreoCountsEveryTreat()
    {
        using var tracker = new CatMoodTracker("Oreo");
        tracker.TreatCount.Value = 0;
        Parallel.For(0, 200, _ => tracker.TreatCount.Update(n => n + 1));
        Assert.Equal(200, tracker.TreatCount.Value);
        Assert.Equal(200, tracker.TreatsGivenSoFar());
    }

    [Fact]
    public void Update_ObjectElement_IdentityEquals_ConvergesFirstTime_MylosToyIsSwappedForABall()
    {
        using var tracker = new CatMoodTracker("Mylo");
        using var ball = new Cat("Ball", 1);
        int calls = 0;
        tracker.FavouriteToy.Update(_ => { calls++; return ball; });
        Assert.Equal(1, calls);
        using var toy = tracker.FavouriteToy.Value;
        Assert.Equal("Ball", toy.Name);
    }

    [Fact]
    public void UpdateAndGet_GetAndUpdate_ReturnTheNewAndThePreviousValue_MylosTreatCount()
    {
        using var tracker = new CatMoodTracker("Mylo");
        tracker.TreatCount.Value = 5;
        Assert.Equal(6, tracker.TreatCount.UpdateAndGet(n => n + 1));
        Assert.Equal(6, tracker.TreatCount.GetAndUpdate(n => n * 2));
        Assert.Equal(12, tracker.TreatsGivenSoFar());
    }

    [Fact]
    public void Update_TransformThrows_PropagatesUnchanged_MylosTreatCountIsLeftAlone()
    {
        using var tracker = new CatMoodTracker("Mylo");
        tracker.TreatCount.Value = 2;
        Assert.Throws<InvalidOperationException>(
            () => tracker.TreatCount.Update(_ => throw new InvalidOperationException("no treats")));
        Assert.Equal(2, tracker.TreatCount.Value);
    }
}
