using TestLibrary.Cat;

namespace IntegrationTests;

public class PropertyPositionMarshallingTests
{
    [Fact]
    public void Class_NullablePrimitiveGetter_PreservesLegacyTwoCallAbi()
    {
        using var probe = new PropertyProbe();
        probe.Age = 7;
        probe.ResetAgeReadCount();

        Assert.Equal(7, probe.Age);
        Assert.Equal(2, probe.AgeReadCount);
    }

    [Fact]
    public void Class_NullablePrimitiveNullGetter_OnlyChecksForAValue()
    {
        using var probe = new PropertyProbe();
        probe.Age = null;
        probe.ResetAgeReadCount();

        Assert.Null(probe.Age);
        Assert.Equal(1, probe.AgeReadCount);
    }

    [Fact]
    public void TopLevel_ObjectEnumAndCollectionProperties_Marshal()
    {
        using var oreo = new Cat("Oreo", 9);
        PropertyCoverage.TopLevelBuddy = oreo;
        PropertyCoverage.TopLevelMood = Mood.Happy;

        using Cat? buddy = PropertyCoverage.TopLevelBuddy;
        IReadOnlyList<string> tags = PropertyCoverage.TopLevelTags;

        Assert.NotNull(buddy);
        Assert.Equal("Oreo", buddy!.Name);
        Assert.Equal(Mood.Happy, PropertyCoverage.TopLevelMood);
        Assert.Equal(new[] { "top-level", "property" }, tags);

        PropertyCoverage.TopLevelBuddy = null;
        Assert.Null(PropertyCoverage.TopLevelBuddy);
    }

    [Fact]
    public void Extension_NullablePrimitiveStringObjectEnumAndCollectionProperties_Marshal()
    {
        using var probe = new PropertyProbe();
        using var mylo = new Cat("Mylo", 9);

        probe.ExtensionAge = 4;
        probe.ExtensionNickname = "Mighty Mylo";
        probe.ExtensionBuddy = mylo;
        probe.ExtensionMood = Mood.Happy;

        using Cat? buddy = probe.ExtensionBuddy;
        IReadOnlyList<string> tags = probe.ExtensionTags;

        Assert.Equal(4, probe.ExtensionAge);
        Assert.Equal("Mighty Mylo", probe.ExtensionNickname);
        Assert.NotNull(buddy);
        Assert.Equal("Mylo", buddy!.Name);
        Assert.Equal(Mood.Happy, probe.ExtensionMood);
        Assert.Equal(new[] { "extension", "property" }, tags);

        probe.ExtensionAge = null;
        probe.ExtensionNickname = null;
        probe.ExtensionBuddy = null;
        Assert.Null(probe.ExtensionAge);
        Assert.Null(probe.ExtensionNickname);
        Assert.Null(probe.ExtensionBuddy);
    }

    // The no-conversion half of the extension `var` shape: non-null `Int` and `Boolean` cross
    // with no allocation, string or handle, so the C# 14 setter body is the plain scalar path.
    // Oreo starts with nine lives indoors; after a night on the fence he is down to eight and
    // Mylo has let him stay outside.
    [Fact]
    public void Extension_NonNullIntAndBoolVars_RoundTripWithoutConversion()
    {
        using var probe = new PropertyProbe();

        Assert.Equal(9, probe.ExtensionLives);
        Assert.True(probe.ExtensionIsIndoor);

        probe.ExtensionLives = 8;
        probe.ExtensionIsIndoor = false;

        Assert.Equal(8, probe.ExtensionLives);
        Assert.False(probe.ExtensionIsIndoor);

        probe.ExtensionLives -= 1;
        Assert.Equal(7, probe.ExtensionLives);
    }

    [Fact]
    public void Companion_NullablePrimitiveStringObjectEnumAndCollectionProperties_Marshal()
    {
        using var oreo = new Cat("Oreo", 9);
        PropertyProbe.SharedAge = 5;
        PropertyProbe.SharedNickname = "Captain Oreo";
        PropertyProbe.SharedBuddy = oreo;
        PropertyProbe.SharedMood = Mood.Happy;

        using Cat? buddy = PropertyProbe.SharedBuddy;
        IReadOnlyList<string> tags = PropertyProbe.SharedTags;

        Assert.Equal(5, PropertyProbe.SharedAge);
        Assert.Equal("Captain Oreo", PropertyProbe.SharedNickname);
        Assert.NotNull(buddy);
        Assert.Equal("Oreo", buddy!.Name);
        Assert.Equal(Mood.Happy, PropertyProbe.SharedMood);
        Assert.Equal(new[] { "clinic", "priority" }, tags);

        PropertyProbe.SharedAge = null;
        PropertyProbe.SharedNickname = null;
        PropertyProbe.SharedBuddy = null;
        Assert.Null(PropertyProbe.SharedAge);
        Assert.Null(PropertyProbe.SharedNickname);
        Assert.Null(PropertyProbe.SharedBuddy);
    }
}
