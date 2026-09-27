using TestLibrary.Cat;

namespace IntegrationTests;

public class EnumTests
{
    [Fact]
    public void Mood_HasCorrectValues()
    {
        Assert.Equal(0, (int)Mood.Happy);
        Assert.Equal(1, (int)Mood.Sleepy);
        Assert.Equal(2, (int)Mood.Grumpy);
    }

    [Fact]
    public void Cat_Mood_ReturnsDefaultValue()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.Equal(Mood.Sleepy, cat.Mood);
    }

    [Fact]
    public void Cat_Mood_CanBeSet()
    {
        using var cat = new Cat("Oreo", 9);
        cat.Mood = Mood.Happy;
        Assert.Equal(Mood.Happy, cat.Mood);
    }

    [Fact]
    public void Mood_Description_ReturnsCorrectString()
    {
        Assert.Equal("The cat is happy and content.", Mood.Happy.Description());
        Assert.Equal("The cat is sleepy and ready for a nap.", Mood.Sleepy.Description());
        Assert.Equal("The cat is grumpy and doesn't want to be disturbed.", Mood.Grumpy.Description());
    }

    // The camelCase enum-property abort: such a property used to fail generation, because the C#
    // entry point lowercased the Kotlin name (`mood_get_displayname`) and the Kotlin export did not
    // (`mood_get_displayName`). Not `is`-specific and not `Boolean`-specific, so the first read is
    // a plain `String` constructor property.
    [Fact]
    public void Mood_DisplayName_CamelCaseConstructorProperty_ReadsEveryEntry()
    {
        Assert.Equal("Purring Oreo", Mood.Happy.DisplayName());
        Assert.Equal("Snoozing Mylo", Mood.Sleepy.DisplayName());
        Assert.Equal("Hissing Oreo", Mood.Grumpy.DisplayName());
    }

    // The `is`-prefixed `Boolean` as a constructor property. Grumpy Oreo is the `false` row, and it
    // is the load-bearing one: without `[return: MarshalAs(UnmanagedType.I1)]` the 1-byte Kotlin
    // `bool` is read as a 4-byte `BOOL`, and garbage in the upper bytes turns `false` into `true`.
    [Fact]
    public void Mood_IsCuddly_BooleanConstructorProperty_ReadsTrueAndFalse()
    {
        Assert.True(Mood.Happy.IsCuddly());
        Assert.True(Mood.Sleepy.IsCuddly());
        Assert.False(Mood.Grumpy.IsCuddly());
    }

    // The `is`-prefixed `Boolean` as a body getter. Only Mylo's nap answers `true`, a different
    // pattern from `IsCuddly`, so the two getters' entry points cannot be crossed unnoticed (Happy
    // is `true` for one and `false` for the other).
    [Fact]
    public void Mood_IsSleepy_BooleanBodyGetter_ReadsTrueAndFalse()
    {
        Assert.False(Mood.Happy.IsSleepy());
        Assert.True(Mood.Sleepy.IsSleepy());
        Assert.False(Mood.Grumpy.IsSleepy());
    }

    // The Mood a live Cat reports goes through the same extension, not just the enum literal:
    // Oreo starts out sleepy (Cat.kt), and once cheered up he is no longer.
    [Fact]
    public void Cat_Mood_IsSleepy_FollowsTheCatsMood()
    {
        using var oreo = new Cat("Oreo", 9);
        Assert.True(oreo.Mood.IsSleepy());

        oreo.Mood = Mood.Happy;
        Assert.False(oreo.Mood.IsSleepy());
        Assert.True(oreo.Mood.IsCuddly());
    }
}
