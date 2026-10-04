using System;
using System.Reflection;
using TestLibrary.Klibabstract;

namespace IntegrationTests;

public class KlibAbstractPropertyTests
{
    [Fact]
    public void HiddenKlibBase_PropertiesAreRehomedAsAbstract()
    {
        Assert.Same(typeof(object), typeof(KlibNester).BaseType);
        Assert.DoesNotContain(typeof(KlibNester).Assembly.GetTypes(),
            type => type.Name == "UnexportedAbstractRoost");
        PropertyInfo material = typeof(KlibNester).GetProperty("Material")!;
        Assert.NotNull(material);
        Assert.True(material.GetMethod!.IsAbstract);
        Assert.Null(material.SetMethod);
        PropertyInfo height = typeof(KlibNester).GetProperty("Height")!;
        Assert.NotNull(height);
        Assert.True(height.GetMethod!.IsAbstract);
        Assert.True(height.SetMethod!.IsAbstract);
        Assert.True(typeof(KlibNester).GetMethod("Chirp")!.IsAbstract);
    }

    [Fact]
    public void ConcreteKotlinSubclass_DispatchesInheritedPropertyReadsAndWrites()
    {
        using var oreo = new KlibWren();
        KlibNester nest = oreo;
        Assert.Equal("twig", nest.Material);
        Assert.Equal(3, nest.Height);
        nest.Height = 7;
        Assert.Equal(7, oreo.Height);
        Assert.Equal("twig@7", nest.Describe());
        Assert.Equal("Mylo's twig nest at 7", nest.Chirp());
        Assert.Same(typeof(KlibNester), typeof(KlibWren).GetProperty("Height")!
            .SetMethod!.GetBaseDefinition().DeclaringType);
    }

    private sealed class PaperNest : KlibNester
    {
        public PaperNest() : base(IntPtr.Zero, out _) { }
        public override string Material => "Oreo's cardboard";
        public override int Height { get; set; } = 2;
        public override string Chirp() => "Mylo approves";
        public override void Dispose() { }
    }

    [Fact]
    public void PureCSharpSubclass_ImplementsTheRehomedContract()
    {
        using var paper = new PaperNest();
        KlibNester nest = paper;
        Assert.Equal("Oreo's cardboard", nest.Material);
        nest.Height = 5;
        Assert.Equal(5, paper.Height);
        Assert.Equal("Mylo approves", nest.Chirp());
    }
}