using TestLibrary;

namespace IntegrationTests;

// Oreo and Mylo deserve personalised greetings. This fixture proves the ADR-051 opaque-handle
// round trip:
//
//   C# IntegrationTests
//     -> (forward bridge, Interop.cs)        Greetings.Greet("Oreo")
//       -> Kotlin test-library             fun greet(name: String): String  (Greetings.kt)
//         -> (reverse bridge, ADR-051 stubs) test.text.Template.parse / render
//           -> real C# TestDependency NuGet  Template.Parse("Hello, {name}")
//                                              Template.Render(t, "Oreo")
//           <- "Hello, Oreo"
//
// Expected red state: `Greetings` does not exist in the generated Interop.cs yet — the
// Kotlin `Greetings.kt` and the bind{} wiring for TestDependency have not been added.
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
    //   C# IntegrationTests
    //     -> (forward bridge, Interop.cs)        Greetings.greetViaConstructor("Oreo")
    //       -> Kotlin test-library             fun greetViaConstructor(name)  (Greetings.kt)
    //         -> (reverse bridge, ADR-052 ctor)  test.text.Template("Hello, {name}")
    //           -> real C# TestDependency NuGet  new Template("Hello, {name}")
    //         -> (reverse bridge, ADR-051 static)  test.text.Template.render
    //           -> real C# TestDependency NuGet  Template.Render(t, "Oreo")
    //           <- "Hello, Oreo"
    //
    // Expected red state: `Template(string source)` has no mapped Kotlin secondary constructor
    // yet, so `test-library` fails to compile — the ADR-052 generator work (Step 4) has not
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

    // Phase 9 line 151 round trip: instance methods and instance properties. Oreo's template
    // is built, tagged with his name via the settable `Name` property, cloned via the
    // instance `Clone()` method (carrying `Name` and `Source` across the copy), and finally
    // rendered through the instance `Apply(string)` method rather than the static `Render`.
    //
    //   C# IntegrationTests
    //     -> (forward bridge, Interop.cs)        Greetings.greetViaInstanceMembers("Oreo")
    //       -> Kotlin test-library             fun greetViaInstanceMembers(name)  (Greetings.kt)
    //         -> (reverse bridge, ADR-052 ctor)    test.text.Template("Hello, {name}")
    //         -> (reverse bridge, this feature)    template.name = name           (Name setter)
    //         -> (reverse bridge, this feature)    template.clone()               (Clone -> handle)
    //         -> (reverse bridge, this feature)    copy.name / copy.source        (property getters)
    //         -> (reverse bridge, this feature)    copy.apply(name)               (Apply instance method)
    //           -> real C# TestDependency NuGet  new Template("Hello, {name}") { Name = "Oreo" }
    //                                              .Clone(); .Apply("Oreo")
    //           <- "Hello, Oreo"
    //
    // Expected red state: `Template` has no mapped `name`/`source` properties or
    // `apply`/`clone` instance methods yet, so `test-library` fails to compile — the
    // instance-member generator work (Step 4) has not landed. This test stays red until then.
    [Fact]
    public void GreetViaInstanceMembers_Oreo_ReturnsHelloOreo()
    {
        // Oreo: black with white in the middle, like the biscuit.
        string result = Greetings.greetViaInstanceMembers("Oreo");
        Assert.Equal("Hello, Oreo", result);
    }

    [Fact]
    public void GreetViaInstanceMembers_Mylo_ReturnsHelloMylo()
    {
        // Mylo: brown and creamy, like the drink Milo.
        string result = Greetings.greetViaInstanceMembers("Mylo");
        Assert.Equal("Hello, Mylo", result);
    }

    // Phase 9 static-property round trip: the public static C# properties are mapped onto
    // Template's Kotlin companion object. Kotlin writes and reads DefaultName (String var),
    // then reads RenderCount (Int val) after C# has entered Kotlin and Kotlin has called C#.
    [Fact]
    public void StaticProperties_MyloNameAndRenderCount_RoundTripThroughKotlin()
    {
        string name = Greetings.setDefaultTemplateCatName("Mylo");
        int renderCount = Greetings.templateRenderCount();

        Assert.Equal("Mylo", name);
        Assert.True(renderCount >= 0);
    }
}
