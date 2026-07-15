using TestLibrary.Cat;

namespace IntegrationTests;

public class ObjectTests
{
    [Fact]
    public void Cat_Constructor_CreatesInstance()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.NotNull(cat);
    }

    [Fact]
    public void Cat_Name_ReturnsCorrectValue()
    {
        using var cat = new Cat("Mylo", 9);
        Assert.Equal("Mylo", cat.Name);
    }

    [Fact]
    public void Cat_Lives_ReturnsCorrectValue()
    {
        using var cat = new Cat("Mylo", 7);
        Assert.Equal(7, cat.Lives);
    }

    [Fact]
    public void Cat_Meow_ReturnsGreeting()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.Equal("Meow! My name is Oreo", cat.Meow());
    }

    [Fact]
    public void Cat_Pet_ReturnsPurr()
    {
        using var cat = new Cat("Mylo", 9);
        Assert.Equal("Mylo purrs contentedly", cat.Pet());
    }

    [Fact]
    public void Cat_Dispose_CanBeCalledMultipleTimes()
    {
        var cat = new Cat("Oreo", 9);
        cat.Dispose();
        cat.Dispose();
    }

    [Fact]
    public void Cat_UsingBlock_DisposesAutomatically()
    {
        Cat cat;
        using (cat = new Cat("Mylo", 9))
        {
            Assert.Equal("Mylo", cat.Name);
        }

        // After dispose, accessing should not crash the process
        // (handle is zeroed, native call with IntPtr.Zero)
    }

    [Fact]
    public void Cat_Brother_WhenNull_ReturnsNull()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.Null(cat.Brother);
    }

    [Fact]
    public void Cat_Brother_WhenSet_ReturnsWrapper()
    {
        using var oreo = new Cat("Oreo", 9);
        using var mylo = new Cat("Mylo", 9);

        oreo.Brother = mylo;

        using Cat? brother = oreo.Brother;
        Assert.NotNull(brother);
        Assert.Equal("Mylo", brother!.Name);
    }

    [Fact]
    public void Cat_Brother_EachAccessReturnsNewWrapper()
    {
        using var oreo = new Cat("Oreo", 9);
        using var mylo = new Cat("Mylo", 9);

        oreo.Brother = mylo;

        using Cat? brother1 = oreo.Brother;
        using Cat? brother2 = oreo.Brother;

        // Per ADR-005: identity is NOT preserved (new wrapper each access)
        Assert.NotSame(brother1, brother2);
        Assert.Equal(brother1!.Name, brother2!.Name);
    }

    [Fact]
    public void Cat_Brother_DisposingBrotherDoesNotAffectOriginal()
    {
        using var oreo = new Cat("Oreo", 9);
        using var mylo = new Cat("Mylo", 9);

        oreo.Brother = mylo;

        Cat? brother = oreo.Brother;
        brother!.Dispose();

        // Oreo is still alive — disposing the brother wrapper only releases that one StableRef
        Assert.Equal("Oreo", oreo.Name);
    }

    [Fact]
    public void Cat_Brother_CyclicReference_BothCanBeDisposed()
    {
        using var oreo = new Cat("Oreo", 9);
        using var mylo = new Cat("Mylo", 9);

        oreo.Brother = mylo;
        mylo.Brother = oreo;

        using Cat? oreosBrother = oreo.Brother;
        Assert.NotNull(oreosBrother);
        Assert.Equal("Mylo", oreosBrother!.Name);

        // Mylo's brother is Oreo (cyclic reference)
        using Cat? mylosBrother = mylo.Brother;
        Assert.NotNull(mylosBrother);
        Assert.Equal("Oreo", mylosBrother!.Name);

        // All wrappers can be independently disposed without crashes
    }

    [Fact]
    public void Cat_Brother_CanBeSet()
    {
        using var oreo = new Cat("Oreo", 9);
        using var mylo = new Cat("Mylo", 9);

        oreo.Brother = mylo;

        using Cat? brother = oreo.Brother;
        Assert.NotNull(brother);
        Assert.Equal("Mylo", brother!.Name);
    }

    [Fact]
    public void Cat_Brother_CanBeSetToNull()
    {
        using var oreo = new Cat("Oreo", 9);
        using var mylo = new Cat("Mylo", 9);

        oreo.Brother = mylo;
        oreo.Brother = null;

        Assert.Null(oreo.Brother);
    }
}
