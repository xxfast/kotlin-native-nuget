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

    // ADR-052 round trip: the Template is built via the mapped Kotlin secondary constructor
    // (`Template("Hello, {name}")`) instead of the `Template.parse(...)` factory, then still
    // rendered through the static `Template.render(template, name)` consumer.
    //
    //   C# SampleApp.Tests
    //     -> (forward bridge, Interop.cs)        Greetings.greetViaConstructor("Oreo")
    //       -> Kotlin sample-library             fun greetViaConstructor(name)  (Greetings.kt)
    //         -> (reverse bridge, ADR-052 ctor)  sample.text.Template("Hello, {name}")
    //           -> real C# SampleDependency NuGet  new Template("Hello, {name}")
    //         -> (reverse bridge, ADR-051 static)  sample.text.Template.render
    //           -> real C# SampleDependency NuGet  Template.Render(t, "Oreo")
    //           <- "Hello, Oreo"
    //
    // Expected red state: `Template(string source)` has no mapped Kotlin secondary constructor
    // yet, so `sample-library` fails to compile — the ADR-052 generator work (Step 4) has not
    // landed. This test stays red until then.
    [Fact]
    public void GreetViaConstructor_Oreo_ReturnsHelloOreo()
    {
        // Oreo: black with white in the middle, like the biscuit.
        string result = Greetings.greetViaConstructor("Oreo");
        Assert.Equal("Hello, Oreo", result);
    }

    [Fact]
    public void GreetViaConstructor_Mylo_ReturnsHelloMylo()
    {
        // Mylo: brown and creamy, like the drink Milo.
        string result = Greetings.greetViaConstructor("Mylo");
        Assert.Equal("Hello, Mylo", result);
    }
}
