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

        probe.SetExtensionAge(4);
        probe.SetExtensionNickname("Mighty Mylo");
        probe.SetExtensionBuddy(mylo);
        probe.SetExtensionMood(Mood.Happy);

        using Cat? buddy = probe.GetExtensionBuddy();
        IReadOnlyList<string> tags = probe.GetExtensionTags();

        Assert.Equal(4, probe.GetExtensionAge());
        Assert.Equal("Mighty Mylo", probe.GetExtensionNickname());
        Assert.NotNull(buddy);
        Assert.Equal("Mylo", buddy!.Name);
        Assert.Equal(Mood.Happy, probe.GetExtensionMood());
        Assert.Equal(new[] { "extension", "property" }, tags);

        probe.SetExtensionAge(null);
        probe.SetExtensionNickname(null);
        probe.SetExtensionBuddy(null);
        Assert.Null(probe.GetExtensionAge());
        Assert.Null(probe.GetExtensionNickname());
        Assert.Null(probe.GetExtensionBuddy());
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
