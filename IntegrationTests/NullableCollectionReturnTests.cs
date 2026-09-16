using TestLibrary.Clinic;

namespace IntegrationTests;

/// <summary>
/// A nullable collection method return (<c>fun x(): List&lt;T&gt;?</c>) has no planner route, so
/// <c>Dispensary</c>'s members are absent from the generated C# and named
/// <c>SKIPPED_UNSUPPORTED_RETURN</c> instead. These prove the ADR-075 getter rule holds in the
/// method-return position too: a null result pointer comes back as C# <c>null</c>, and otherwise
/// the collection materialises with every element projected the way its component kind demands.
/// One pair per component kind (value class, bare primitive, object handle, plain string) so a
/// fix that only works where a projection exists cannot hide. Oreo and Mylo are on file.
/// </summary>
public class NullableCollectionReturnTests
{
    // --- fun ids(): List<ChartId>? - a value-class element, re-wrapped per entry ---

    [Fact]
    public void Dispensary_Ids_WhenStocked_MaterializesTheValueClassElements()
    {
        using var dispensary = new Dispensary(true);

        IReadOnlyList<ChartId>? ids = dispensary.Ids();

        Assert.NotNull(ids);
        Assert.Equal(new[] { "CH-OREO-1", "CH-MYLO-2" }, ids!.Select(id => id.Value));
    }

    [Fact]
    public void Dispensary_Ids_WhenUnstocked_IsNullNotAnEmptyList()
    {
        using var dispensary = new Dispensary(false);

        Assert.Null(dispensary.Ids());
    }

    // --- fun counts(): Set<Int>? - a direct primitive element, no conversion in the slot ---

    [Fact]
    public void Dispensary_Counts_WhenStocked_MaterializesEveryPrimitiveElement()
    {
        using var dispensary = new Dispensary(true);

        IReadOnlySet<int>? counts = dispensary.Counts();

        Assert.NotNull(counts);
        Assert.Equal(3, counts!.Count);
        Assert.Equal(new[] { 2, 7, 11 }, counts.OrderBy(count => count));
    }

    [Fact]
    public void Dispensary_Counts_WhenUnstocked_IsNullNotAnEmptySet()
    {
        using var dispensary = new Dispensary(false);

        Assert.Null(dispensary.Counts());
    }

    // --- fun staff(): Map<String, Nurse>? - an object-handle value, read through FromHandle<T> ---

    [Fact]
    public void Dispensary_Staff_WhenStocked_MaterializesTheObjectHandleValues()
    {
        using var dispensary = new Dispensary(true);

        IReadOnlyDictionary<string, Nurse>? staff = dispensary.Staff();

        Assert.NotNull(staff);
        Assert.Equal(new[] { "mylo", "oreo" }, staff!.Keys.OrderBy(key => key));

        using Nurse oreosNurse = staff["oreo"];
        using Nurse mylosNurse = staff["mylo"];
        Assert.Equal("Nightingale", oreosNurse.Name);
        Assert.Equal("Barnard", mylosNurse.Name);
    }

    [Fact]
    public void Dispensary_Staff_WhenUnstocked_IsNullNotAnEmptyDictionary()
    {
        using var dispensary = new Dispensary(false);

        Assert.Null(dispensary.Staff());
    }

    // --- fun Dispensary.aliases(): List<String>? - the extension-function position, which rides
    // the same result route as a method per ADR-061, with a conversion-free element ---

    [Fact]
    public void Dispensary_Aliases_WhenStocked_MaterializesTheExtensionResult()
    {
        using var dispensary = new Dispensary(true);

        IReadOnlyList<string>? aliases = dispensary.Aliases();

        Assert.NotNull(aliases);
        Assert.Equal(new[] { "biscuit", "milo" }, aliases!);
    }

    [Fact]
    public void Dispensary_Aliases_WhenUnstocked_IsNull()
    {
        using var dispensary = new Dispensary(false);

        Assert.Null(dispensary.Aliases());
    }
}
