using TestLibrary;

namespace IntegrationTests;

// Issue #233: ADR-007 names a file's static holder after the file stem, and a Kotlin stem is not a
// C# identifier. `Compass.native.kt` used to emit `public static partial class Compass.native`,
// which C# reads as a qualified name: one dot, 19 parse errors, cascading to the end of
// Interop.cs. The stem now goes through the sanitiser, so the holder is `CompassNative`.
//
// This file compiling at all is most of the assertion. `Compass` beside `CompassNative` is the
// other half: the two files must stay two holders rather than silently merging.
public class DottedFileClassNameTests
{
    [Fact]
    public void Compass_CompassSize_ComesFromTheUndottedFile()
    {
        Assert.Equal(32, Compass.CompassSize());
    }

    [Fact]
    public void CompassNative_CompassPlatform_ComesFromThePlatformSuffixedFile()
    {
        Assert.Equal("native", CompassNative.CompassPlatform());
    }
}
