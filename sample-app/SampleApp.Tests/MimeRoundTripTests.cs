using SampleLibrary.Mime;

namespace SampleApp.Tests;

// Oreo emailed his vet a data.json file and his glamour shot logo.png.
// Round trip: C# -> Kotlin sample-library -> real MimeMapping NuGet package -> back to C#.
public class MimeRoundTripTests
{
    [Fact]
    public void MimeTypeFor_JsonFile_ReturnsApplicationJson()
    {
        string result = MimeSample.mimeTypeFor("data.json");
        Assert.Equal("application/json", result);
    }

    [Fact]
    public void MimeTypeFor_PngFile_ReturnsImagePng()
    {
        // logo.png: a glamour shot of Oreo, black and white like the biscuit.
        string result = MimeSample.mimeTypeFor("logo.png");
        Assert.Equal("image/png", result);
    }
}
