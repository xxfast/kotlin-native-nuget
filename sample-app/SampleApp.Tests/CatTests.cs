using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class CatTests
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
        IntPtr handle = SampleLibraryNative.createBrothers("Oreo", "Mylo");
        using var oreo = new Cat(handle);

        using Cat? buddy = oreo.Brother;
        Assert.NotNull(buddy);
        Assert.Equal("Mylo", buddy!.Name);
    }

    [Fact]
    public void Cat_Brother_EachAccessReturnsNewWrapper()
    {
        IntPtr handle = SampleLibraryNative.createBrothers("Oreo", "Mylo");
        using var oreo = new Cat(handle);

        using Cat? buddy1 = oreo.Brother;
        using Cat? buddy2 = oreo.Brother;

        // Per ADR-005: identity is NOT preserved (new wrapper each access)
        Assert.NotSame(buddy1, buddy2);
        Assert.Equal(buddy1!.Name, buddy2!.Name);
    }

    [Fact]
    public void Cat_Brother_DisposingBuddyDoesNotAffectOriginal()
    {
        IntPtr handle = SampleLibraryNative.createBrothers("Oreo", "Mylo");
        using var oreo = new Cat(handle);

        Cat? buddy = oreo.Brother;
        buddy!.Dispose();

        // Oreo is still alive — disposing the brother wrapper only releases that one StableRef
        Assert.Equal("Oreo", oreo.Name);
    }

    [Fact]
    public void Cat_Brother_CyclicReference_BothCanBeDisposed()
    {
        IntPtr handle = SampleLibraryNative.createBrothers("Oreo", "Mylo");
        using var oreo = new Cat(handle);

        using Cat? mylo = oreo.Brother;
        Assert.NotNull(mylo);

        // Mylo's brother is Oreo (cyclic reference on Kotlin side)
        using Cat? mylosBrother = mylo!.Brother;
        Assert.NotNull(mylosBrother);
        Assert.Equal("Oreo", mylosBrother!.Name);

        // All wrappers can be independently disposed without crashes
    }
}
