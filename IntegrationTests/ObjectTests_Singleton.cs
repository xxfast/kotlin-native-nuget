using TestLibrary.Cat;

namespace IntegrationTests;

public class SingletonTests
{
    [Fact]
    public void CatRegistry_IsStaticClass()
    {
        Assert.True(typeof(CatRegistry).IsAbstract && typeof(CatRegistry).IsSealed);
    }

    [Fact]
    public void CatRegistry_RegisterAndCount()
    {
        CatRegistry.Clear();
        CatRegistry.Register("Oreo");
        CatRegistry.Register("Mylo");
        Assert.Equal(2, CatRegistry.Count());
        CatRegistry.Clear();
    }

    [Fact]
    public void CatRegistry_Clear()
    {
        CatRegistry.Register("Oreo");
        CatRegistry.Clear();
        Assert.Equal(0, CatRegistry.Count());
    }
}
