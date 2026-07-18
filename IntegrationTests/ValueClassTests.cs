using TestLibrary.Cat;
using TestLibrary.Clinic;

namespace IntegrationTests;

public class ValueClassTests
{
    [Fact]
    public void CatId_Constructor_WrapsString()
    {
        var id = new CatId("oreo-123");
        Assert.Equal("oreo-123", id.Id);
    }
    
    [Fact]
    public void CatId_Constructor_WithMultipleValues_WrapsString()
    {
        var id = new CatId("oreo", 123);
        Assert.Equal("oreo-123", id.Id);
    }

    [Fact]
    public void CatId_Equality_SameValue_AreEqual()
    {
        var oreoId1 = new CatId("oreo-123");
        var oreoId2 = new CatId("oreo-123");
        Assert.Equal(oreoId1, oreoId2);
    }

    [Fact]
    public void CatId_Equality_DifferentValues_AreNotEqual()
    {
        var oreoId = new CatId("oreo-123");
        var myloId = new CatId("mylo-456");
        Assert.NotEqual(oreoId, myloId);
    }

    [Fact]
    public void CatId_Length_ReturnsUnderlyingStringLength()
    {
        var id = new CatId("oreo-123");
        Assert.Equal(8, id.Length);
    }

    [Fact]
    public void CatId_IsValid_NonBlankId_ReturnsTrue()
    {
        var id = new CatId("mylo-456");
        Assert.True(id.IsValid());
    }

    [Fact]
    public void CatId_IsValid_BlankId_ReturnsFalse()
    {
        var id = new CatId("   ");
        Assert.False(id.IsValid());
    }

    [Fact]
    public void CatId_IsValid_EmptyId_ReturnsFalse()
    {
        var id = new CatId("");
        Assert.False(id.IsValid());
    }

    [Fact]
    public void CatId_ToString_ContainsUnderlyingValue()
    {
        var id = new CatId("oreo-123");
        Assert.Contains("oreo-123", id.ToString());
    }
}

// ADR-060 cells 15/16 — value-class methods with parameters (Phase 9).
public class ClinicValueClassMethodTests
{
    [Fact]
    public void ChartId_Matches_SameValue_ReturnsTrue()
    {
        var id = new ChartId("abc");
        Assert.True(id.Matches("abc"));
    }

    [Fact]
    public void ChartId_Matches_DifferentValue_ReturnsFalse()
    {
        var id = new ChartId("abc");
        Assert.False(id.Matches("xyz"));
    }

    [Fact]
    public void ChartRef_Label_AppendsSuffix()
    {
        using var patient = new Patient("Rex");
        var chart = new ChartRef(patient);
        Assert.Equal("Rex-ward", chart.Label("-ward"));
    }
}

