using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-067: nullable StateFlow mapping -- <c>StateFlow&lt;T?&gt;</c> (nullable element) and
/// <c>StateFlow&lt;T&gt;?</c> (nullable member), extending ADR-065's <c>KotlinStateFlow&lt;T&gt;</c>.
///
/// Fixture: <see cref="CatMoodTracker"/> (test-library) gains four members that force every seam
/// this ADR touches:
///  - <c>Nickname</c>:    <c>KotlinStateFlow&lt;string?&gt;</c> -- nullable REFERENCE element, reuses
///                        <c>FromHandle&lt;T&gt;</c> unchanged.
///  - <c>Streak</c>:      <c>KotlinStateFlow&lt;int?&gt;</c> -- nullable VALUE element, needs the new
///                        <c>Nullable&lt;T&gt;</c>-aware unwrap (a plain <c>int</c> would need no
///                        conversion and pass trivially -- this is deliberately NOT that).
///  - <c>MaybeMood</c>:   <c>KotlinStateFlow&lt;string&gt;?</c> -- nullable MEMBER, backed by the
///                        <c>_has_value</c> presence-probe.
///  - <c>MaybeStreak</c>: <c>KotlinStateFlow&lt;int?&gt;?</c> -- nullable member AND nullable value
///                        element, composed.
/// </summary>
public class NullableStateFlowTests
{
    [Fact]
    public void NullableReferenceElement_StartsNull_OreoHasNoNicknameYet()
    {
        // Oreo hasn't earned a nickname yet -- the tracker exists, its current value is null
        using var tracker = new CatMoodTracker("Oreo");
        KotlinStateFlow<string?> nick = tracker.Nickname;
        Assert.Null(nick.Value);
    }

    [Fact]
    public void NullableReferenceElement_AfterSet_ValueReflectsNickname_OreoBecomesMrWhiskers()
    {
        // Oreo earns the nickname "Mr. Whiskers"
        using var tracker = new CatMoodTracker("Oreo");
        tracker.SetNickname("Mr. Whiskers");
        Assert.Equal("Mr. Whiskers", tracker.Nickname.Value);
    }

    [Fact]
    public void NullableReferenceElement_SetBackToNull_ValueIsNullAgain_MyloLosesHisNickname()
    {
        // Mylo had a nickname, then lost it -- the null channel must round-trip both ways
        using var tracker = new CatMoodTracker("Mylo");
        tracker.SetNickname("Milo-bean");
        Assert.Equal("Milo-bean", tracker.Nickname.Value);

        tracker.SetNickname(null);
        Assert.Null(tracker.Nickname.Value);
    }

    [Fact]
    public void NullableValueElement_StartsNull_NotDefaultZero_OreosStreakIsUntracked()
    {
        // Oreo's win streak hasn't started -- must be null, NOT default(int) == 0. This is the
        // seam that needs the Nullable<T>-aware unwrap; a plain `int` would hide this entirely.
        using var tracker = new CatMoodTracker("Oreo");
        KotlinStateFlow<int?> streak = tracker.Streak;
        Assert.Null(streak.Value);
    }

    [Fact]
    public void NullableValueElement_AfterSet_ValueIsNonNullInt_OreoIsOnAWinningStreak()
    {
        // Oreo racks up a 7-day zoomies streak
        using var tracker = new CatMoodTracker("Oreo");
        tracker.SetStreak(7);
        Assert.Equal(7, tracker.Streak.Value);
    }

    [Fact]
    public void NullableValueElement_SetBackToNull_ValueIsNullAgain_OreosStreakResets()
    {
        // Oreo's streak resets to untracked -- null must cross correctly a second time
        using var tracker = new CatMoodTracker("Oreo");
        tracker.SetStreak(7);
        Assert.Equal(7, tracker.Streak.Value);

        tracker.SetStreak(null);
        Assert.Null(tracker.Streak.Value);
    }

    [Fact]
    public async Task NullableValueElement_AwaitForeach_NullElementIsARealEmission_MylosStreakEmitsNull()
    {
        // A null current value replayed to a new subscriber is a genuine emission -- distinct
        // from the cancel signal via the isCancelled byte, not a completed/skipped stream.
        using var tracker = new CatMoodTracker("Mylo");
        tracker.SetStreak(null);

        var seen = new List<int?>();
        var cts = new CancellationTokenSource();
        await foreach (var s in tracker.Streak.WithCancellation(cts.Token))
        {
            seen.Add(s);
            cts.Cancel();
        }

        Assert.Single(seen);
        Assert.Null(seen[0]);
    }

    [Fact]
    public async Task NullableValueElement_AwaitForeach_NonNullElementEmitsCorrectly_OreosStreakEmitsSeven()
    {
        // Sanity check alongside the null-emission test -- a non-null current value must also
        // replay correctly as the first element.
        using var tracker = new CatMoodTracker("Oreo");
        tracker.SetStreak(7);

        var seen = new List<int?>();
        var cts = new CancellationTokenSource();
        await foreach (var s in tracker.Streak.WithCancellation(cts.Token))
        {
            seen.Add(s);
            cts.Cancel();
        }

        Assert.Single(seen);
        Assert.Equal(7, seen[0]);
    }

    [Fact]
    public void NullableMember_AbsentUntilTrackingStarts_MyloHasNoMoodTrackingYet()
    {
        // The whole StateFlow is null until StartTracking() is called -- not merely a null value
        using var tracker = new CatMoodTracker("Mylo");
        Assert.Null(tracker.MaybeMood);
    }

    [Fact]
    public void NullableMember_AfterStartTracking_BecomesPresentNonNullStateFlow_MyloStartsMoodTracking()
    {
        // Once tracking starts, the member is a real, present KotlinStateFlow<string>
        using var tracker = new CatMoodTracker("Mylo");
        tracker.StartTracking("curious");

        KotlinStateFlow<string>? present = tracker.MaybeMood;
        Assert.NotNull(present);
        Assert.Equal("curious", present!.Value);
    }

    [Fact]
    public void NullableMember_AfterStartTracking_MutationIsObservedThroughValue_MyloGetsGrumpy()
    {
        // Mutating after tracking has started must be visible through the present member's .Value
        using var tracker = new CatMoodTracker("Mylo");
        tracker.StartTracking("curious");
        tracker.SetMaybeMood("grumpy");

        Assert.Equal("grumpy", tracker.MaybeMood!.Value);
    }

    [Fact]
    public void NullableMemberAndValueElement_BothAbsent_OreosStreakTrackingHasntStarted()
    {
        // Neither the member nor (once present) its value has been established yet
        using var tracker = new CatMoodTracker("Oreo");
        Assert.Null(tracker.MaybeStreak);
    }

    [Fact]
    public void NullableMemberAndValueElement_PresentButValueNull_OreoTracksAnUntrackedStreak()
    {
        // The member becomes present, but its current value is still null -- both nulls at once,
        // and they must be distinguishable: MaybeStreak itself is non-null, MaybeStreak.Value is null.
        using var tracker = new CatMoodTracker("Oreo");
        tracker.StartStreakTracking(null);

        KotlinStateFlow<int?>? maybeStreak = tracker.MaybeStreak;
        Assert.NotNull(maybeStreak);
        Assert.Null(maybeStreak!.Value);
    }

    [Fact]
    public void NullableMemberAndValueElement_PresentWithNonNullValue_OreoTracksASevenDayStreak()
    {
        // Both the member and its value are present and non-null
        using var tracker = new CatMoodTracker("Oreo");
        tracker.StartStreakTracking(3);
        tracker.SetMaybeStreak(7);

        Assert.Equal(7, tracker.MaybeStreak!.Value);
    }

    [Fact]
    public void AfterDispose_NullableElementGetterThrows_OreosTrackerIsPutAway()
    {
        // Parity with ADR-065: disposal must throw for the nullable-element surfaces too
        var tracker = new CatMoodTracker("Oreo");
        tracker.Dispose();
        Assert.Throws<ObjectDisposedException>(() => { var _ = tracker.Nickname; });
    }

    [Fact]
    public void AfterDispose_NullableMemberGetterThrows_MylosTrackerIsPutAway()
    {
        // Parity with ADR-065: disposal must throw for the nullable-member surface too, even
        // though the member's own value may be null/absent -- the handle guard fires first.
        var tracker = new CatMoodTracker("Mylo");
        tracker.StartTracking("curious");
        tracker.Dispose();
        Assert.Throws<ObjectDisposedException>(() => { var _ = tracker.MaybeMood; });
    }
}
