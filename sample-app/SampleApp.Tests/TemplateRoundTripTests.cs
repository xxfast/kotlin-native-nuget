using SampleLibrary;

namespace SampleApp.Tests;

// Oreo and Mylo deserve personalised greetings. This fixture proves the ADR-051 opaque-handle
// round trip:
//
//   C# SampleApp.Tests
//     -> (forward bridge, Interop.cs)        Greetings.Greet("Oreo")
//       -> Kotlin sample-library             fun greet(name: String): String  (Greetings.kt)
//         -> (reverse bridge, ADR-051 stubs) sample.text.Template.parse / render
//           -> real C# SampleDependency NuGet  Template.Parse("Hello, {name}")
//                                              Template.Render(t, "Oreo")
//           <- "Hello, Oreo"
//
// Expected red state: `Greetings` does not exist in the generated Interop.cs yet — the
// Kotlin `Greetings.kt` and the bind{} wiring for SampleDependency have not been added.
// This file will fail to compile until the kotlin-dev implements that work.
public class TemplateRoundTripTests
{
    [Fact]
    public void Greet_Oreo_ReturnsHelloOreo()
    {
        // Oreo: black with white in the middle, like the biscuit.
        string result = Greetings.greet("Oreo");
        Assert.Equal("Hello, Oreo", result);
    }

    [Fact]
    public void Greet_Mylo_ReturnsHelloMylo()
    {
        // Mylo: brown and creamy, like the drink Milo.
        string result = Greetings.greet("Mylo");
        Assert.Equal("Hello, Mylo", result);
    }
}
