using System.Linq;
using System.Reflection;
using TestLibrary.Cat;
using TestLibrary.Pantry;
using TestLibrary.Parcel;

namespace IntegrationTests;

// ADR-147 amendment: an unbounded Kotlin `T` has upper bound `Any?`, so `Box<String?>(null)` is legal
// Kotlin and a `null` argument at a bare `T` position must reach Kotlin as the null pointer
// (ADR-083) and read back as null. A `T : Any` stays non-null and says so with `where T : notnull`.
public class NullableTypeArgumentTests
{
    // Mylo's empty food bowl, as far as he is concerned: nothing in it.
    [Fact]
    public void Box_NullableString_NullRoundTrips()
    {
        using var bowl = new Box<string?>(null);
        Assert.Null(bowl.Value);
    }

    // Oreo's nap count before breakfast is unknown; after breakfast it is five.
    [Fact]
    public void Box_NullableInt_NullAndValueRoundTrip()
    {
        using var beforeBreakfast = new Box<int?>(null);
        using var afterBreakfast = new Box<int?>(5);
        Assert.Null(beforeBreakfast.Value);
        Assert.Equal(5, afterBreakfast.Value);
    }

    // A Kotlin function returning `Box<Int?>` hands back a `Box<int?>`: Oreo's unknown nap count
    // reads null, not 0, and the counted one reads 5.
    [Fact]
    public void ReturnedBox_NullableInt_NullAndValueRoundTrip()
    {
        using Box<int?> unknown = CatMoodTrackerKt.UnknownNaps();
        using Box<int?> counted = CatMoodTrackerKt.CountedNaps();
        Assert.Null(unknown.Value);
        Assert.Equal(5, counted.Value);
    }

    // The reference-type twin: a stray with no name yet, then Mylo.
    [Fact]
    public void ReturnedBox_NullableString_NullAndValueRoundTrip()
    {
        using Box<string?> unnamed = CatMoodTrackerKt.UnnamedStray();
        using Box<string?> named = CatMoodTrackerKt.NamedStray();
        Assert.Null(unnamed.Value);
        Assert.Equal("Mylo", named.Value);
    }

    // The cat carrier, with no cat in it: both of them hid under the bed.
    [Fact]
    public void Box_NullableCat_NullRoundTrips()
    {
        using var carrier = new Box<Cat?>(null);
        Assert.Null(carrier.Value);
    }

    [Fact]
    public void Slot_NullableString_NullRoundTripsThroughEveryGetter()
    {
        using var perch = new Slot<string?>(null);
        Assert.Null(perch.Value);
        Assert.Null(perch.Current);
        Assert.Null(perch.Previous);
    }

    // Oreo is offered nothing; he picks nothing, and describes it at length anyway.
    [Fact]
    public void Crate_NullableString_NullParameterAndReturnCross()
    {
        using var crate = new Crate<string?>("tuna");
        Assert.Null(crate.Pick(null));
        Assert.Equal("null:tuna", crate.Describe(null));
    }

    [Fact]
    public void Crate_NullableString_NullItemCrossesTheConstructor()
    {
        using var crate = new Crate<string?>(null);
        Assert.Null(crate.Item);
        Assert.Equal("salmon:null", crate.Describe("salmon"));
    }

    // `where T : notnull` has no reflection constraint of its own: the compiler encodes it as a
    // NullableAttribute(1) on the generic parameter, or omits that when the enclosing type's
    // NullableContextAttribute already says 1. Unconstrained `T` reads 2 today.
    [Fact]
    public void Tin_AnyBoundTypeParameter_RendersNotNullConstraint()
    {
        Assert.Equal((byte)1, EffectiveNullability(typeof(Tin<>).GetGenericArguments()[0]));
    }

    // Control for the helper above: an unbounded `T` must stay nullable (2), never `notnull`.
    [Fact]
    public void Box_UnboundedTypeParameter_HasNoNotNullConstraint()
    {
        Assert.Equal((byte)2, EffectiveNullability(typeof(Box<>).GetGenericArguments()[0]));
    }

    // Matched by name: both attributes are embedded types in the consuming assembly.
    private static byte EffectiveNullability(Type genericParameter)
    {
        CustomAttributeData? own = genericParameter.GetCustomAttributesData()
            .FirstOrDefault(a => a.AttributeType.FullName == "System.Runtime.CompilerServices.NullableAttribute");
        if (own != null) return (byte)own.ConstructorArguments[0].Value!;

        // A method's type parameter inherits the method's context before the declaring type's.
        CustomAttributeData? methodContext = genericParameter.DeclaringMethod?.GetCustomAttributesData()
            .FirstOrDefault(a => a.AttributeType.FullName == "System.Runtime.CompilerServices.NullableContextAttribute");
        if (methodContext != null) return (byte)methodContext.ConstructorArguments[0].Value!;

        for (Type? scope = genericParameter.DeclaringType; scope != null; scope = scope.DeclaringType)
        {
            CustomAttributeData? context = scope.GetCustomAttributesData()
                .FirstOrDefault(a => a.AttributeType.FullName == "System.Runtime.CompilerServices.NullableContextAttribute");
            if (context != null) return (byte)context.ConstructorArguments[0].Value!;
        }

        return 0;
    }

    // Control: the non-null crossing a `T : Any` keeps.
    [Fact]
    public void Tin_AnyBoundTypeParameter_CarriesANonNullValue()
    {
        using var tin = new Tin<string>("Oreo's treats");
        Assert.Equal("Oreo's treats", tin.Value);
    }

    // The legacy generic-function route: `fun <T> identity(value: T): T`. The string width already
    // carries null; the nullable value type did not (NullReferenceException in C#).
    [Fact]
    public void Identity_NullableInt_NullRoundTrips()
    {
        int? mylosNaps = Helpers.Identity<int?>(null);
        Assert.Null(mylosNaps);
    }

    // A null string no longer crosses the string width into a non-null Kotlin `String`; it takes
    // the object variant's null pointer, like every other null argument on this route.
    [Fact]
    public void Identity_NullableString_NullRoundTripsThroughTheObjectVariant()
    {
        Assert.Null(Helpers.Identity<string?>(null));
        using var emptyBowl = Helpers.WrapInBox<string?>(null);
        Assert.Null(emptyBowl.Value);
    }

    [Fact]
    public void Identity_NullableInt_ValueRoundTrips()
    {
        int? oreosNaps = Helpers.Identity<int?>(7);
        Assert.Equal(7, oreosNaps);
    }

    // `fun <T : Any> handBack(treat: T): T` renders `where T : notnull`; the per-width dispatch
    // still carries a non-null `int` and `string`.
    [Fact]
    public void HandBack_AnyBoundFunction_CarriesNonNullValues()
    {
        Assert.Equal(3, Treats.HandBack(3));
        Assert.Equal("tuna", Treats.HandBack("tuna"));
    }

    [Fact]
    public void HandBack_AnyBoundFunction_RendersNotNullConstraint()
    {
        Type typeParameter = typeof(Treats).GetMethod(nameof(Treats.HandBack))!.GetGenericArguments()[0];
        Assert.Equal((byte)1, EffectiveNullability(typeParameter));
    }
}

// A generic class whose property names collide with its type parameter names (`Duo<A, B>` with
// `val a: A`, `val b: B`) must generate compiling C#; the properties keep their PascalCase names.
public class GenericTypeParameterNameCollisionTests
{
    [Fact]
    public void Duo_PropertiesNamedLikeTypeParameters_CarryTheirValues()
    {
        using var windowsill = new Duo<string, int>("Oreo", 9);
        Assert.Equal("Oreo", windowsill.A);
        Assert.Equal(9, windowsill.B);
    }

    [Fact]
    public void Duo_NullableTypeArguments_CarryNull()
    {
        using var windowsill = new Duo<string?, int?>(null, null);
        Assert.Null(windowsill.A);
        Assert.Null(windowsill.B);
    }
}
