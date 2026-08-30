using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// Issue #39: a <c>List&lt;T&gt;</c> property on a sealed subclass must render as a
/// <c>get { ... }</c> block in the generated Interop.cs, so the package's contentFiles parse and the
/// consumer reads the property as <c>IReadOnlyList&lt;T&gt;</c>.
/// </summary>
public class Issue39Tests
{
    [Fact]
    public void LoadedCats_ReturnsLoaded()
    {
        using Issue39State state = Issue39Sample.loadedCats();
        Assert.IsType<Issue39State.Loaded>(state);
    }

    [Fact]
    public void LoadedCats_Items_IsReadOnlyListOfTwo()
    {
        using Issue39State state = Issue39Sample.loadedCats();
        var loaded = Assert.IsType<Issue39State.Loaded>(state);

        IReadOnlyList<Issue39Item> items = loaded.Items;
        Assert.IsAssignableFrom<IReadOnlyList<Issue39Item>>(items);
        Assert.Equal(2, items.Count);
    }

    [Fact]
    public void LoadedCats_Items_ElementValues()
    {
        using Issue39State state = Issue39Sample.loadedCats();
        var loaded = Assert.IsType<Issue39State.Loaded>(state);

        IReadOnlyList<Issue39Item> items = loaded.Items;

        using Issue39Item oreo = items[0];
        Assert.Equal("Oreo", oreo.Name);
        Assert.Equal(1, oreo.Count);

        using Issue39Item mylo = items[1];
        Assert.Equal("Mylo", mylo.Name);
        Assert.Equal(2, mylo.Count);
    }

    [Fact]
    public void LoadedCats_Items_Enumeration()
    {
        using Issue39State state = Issue39Sample.loadedCats();
        var loaded = Assert.IsType<Issue39State.Loaded>(state);

        var names = new List<string>();
        foreach (Issue39Item item in loaded.Items)
        {
            using var owned = item;
            names.Add(owned.Name);
        }

        Assert.Equal(new List<string> { "Oreo", "Mylo" }, names);
    }

    [Fact]
    public void LoadedCats_Refreshing_IsTrue()
    {
        using Issue39State state = Issue39Sample.loadedCats();
        var loaded = Assert.IsType<Issue39State.Loaded>(state);

        Assert.True(loaded.Refreshing);
    }

    [Fact]
    public void LoadedCats_PatternMatching_ReadsBothProperties()
    {
        using Issue39State state = Issue39Sample.loadedCats();

        string message = state switch
        {
            Issue39State.Loaded l => $"{l.Items.Count} cats, refreshing={l.Refreshing}",
            Issue39State.Loading => "loading",
            _ => throw new InvalidOperationException(),
        };

        Assert.Equal("2 cats, refreshing=True", message);
    }

    [Fact]
    public void LoadingCats_ReturnsLoading()
    {
        using Issue39State state = Issue39Sample.loadingCats();
        Assert.IsType<Issue39State.Loading>(state);
    }
}
