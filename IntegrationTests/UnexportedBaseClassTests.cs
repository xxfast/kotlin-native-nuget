using TestLibrary.Issue42;

namespace IntegrationTests;

/// <summary>
/// Issue #42, base-class half: an exported class whose *base class* is declared outside the
/// export set. ADR-101 closed the interface case; the base-class hole it named stayed open, so
/// <c>class Issue42Derived : UnexportedBase()</c> renders <c>public class Issue42Derived :
/// UnexportedBase</c> for a type nothing ever generates and the whole <c>Interop.cs</c> dies on
/// CS0246.
///
/// The fix is not symmetric with the interface case, and these tests are written to catch the
/// asymmetry: an unexported interface carries nothing C# could call, but an unexported *base
/// class* does. So dropping the base must keep its public members, re-homed onto the derived
/// class itself. C# must see <c>Issue42Derived.Greet()</c> and <c>Issue42Derived.Label</c>
/// directly, with no <c>UnexportedBase</c> type and no inheritance relation anywhere in sight.
///
/// Mylo is the base -- creamy and welcoming; Oreo is the derived one who insists he is his own
/// cat while still greeting you exactly the way Mylo taught him.
/// </summary>
public class UnexportedBaseClassTests
{
    [Fact]
    public void Own_DeclaredMethod_SurvivesTheDroppedBaseClass()
    {
        // The half that "just don't export the class" would also satisfy, and must not.
        using var derived = new Issue42Derived();

        Assert.Equal("own", derived.Own());
    }

    [Fact]
    public void Greet_InheritedMethod_IsBoundOnTheDerivedClass()
    {
        // The half that separates a base class from an interface: the base's concrete method is
        // callable on the derived instance, dispatched through the base's own Kotlin body.
        using var derived = new Issue42Derived();

        Assert.Equal("hello Oreo from base", derived.Greet("Oreo"));
    }

    [Fact]
    public void Label_InheritedProperty_IsBoundOnTheDerivedClass()
    {
        // Properties travel the same road as methods; the getter must be exported under the
        // derived class's own prefix.
        using var derived = new Issue42Derived();

        Assert.Equal("base", derived.Label);
    }

    [Fact]
    public void Issue42Derived_DoesNotExtendAGeneratedStandInForTheUnexportedBase()
    {
        // Name-agnostic and stronger than "not named UnexportedBase": the generated class must
        // derive straight from object, so no stub base of any name can sneak in.
        Assert.Equal(typeof(object), typeof(Issue42Derived).BaseType);
    }

    [Fact]
    public void NoStubClass_IsEmittedForTheUnexportedBase()
    {
        // The other admissible fix -- emitting an empty `public class UnexportedBase` -- is
        // explicitly not what we chose: a stub base would need its own _handle and Dispose, i.e.
        // a real generated class for a type the author excluded. Interop.cs compiles into
        // IntegrationTests.dll, so that assembly is the right haystack.
        var emitted = typeof(Issue42Derived).Assembly
            .GetTypes()
            .Where(t => t.Name == "UnexportedBase")
            .ToList();

        Assert.Empty(emitted);
    }
}
