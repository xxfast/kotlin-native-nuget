using SampleLibrary.Cat;

namespace SampleApp.Tests;

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
        CatRegistry.clear();
        CatRegistry.register("Oreo");
        CatRegistry.register("Mylo");
        Assert.Equal(2, CatRegistry.count());
        CatRegistry.clear();
    }

    [Fact]
    public void CatRegistry_Clear()
    {
        CatRegistry.register("Oreo");
        CatRegistry.clear();
        Assert.Equal(0, CatRegistry.count());
    }
}
