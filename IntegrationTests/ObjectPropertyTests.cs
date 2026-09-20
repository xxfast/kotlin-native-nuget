using System.Reflection;
using TestLibrary;
using TestLibrary.Cat;
using TestLibrary.Objectprops;

namespace IntegrationTests;

/// <summary>
/// ROADMAP Phase 4: a Kotlin <c>object</c>'s own <c>val</c>/<c>var</c> properties reach C# as
/// STATIC properties on the generated static class (<c>TreatPantry.Count</c>), with a setter for a
/// <c>var</c>, at the same type coverage a class or companion property already has. These tests
/// pin that surface member by member: a <c>const</c>, a declared <c>val</c> with no setter, a
/// primitive and a nullable-primitive <c>var</c>, an enum, a collection, a handle, an inherited
/// member, the state-flow skip, and the <c>lateinit</c> throw.
///
/// The pantry is process-global state — there is exactly one of it, the same way there is exactly
/// one treat cupboard Oreo has learned to open. Every test that writes restores the original value
/// in a <c>finally</c>, and no test depends on running before or after another.
/// </summary>
public class ObjectPropertyTests
{
    // --- const val ---

    /// <summary>
    /// Two objects in ONE fixture file each declare <c>const val CAPACITY</c> with DIFFERENT
    /// values, and both values are asserted. This is the regression guard on the const-literal
    /// lookup: a lookup that matches the first <c>const val CAPACITY</c> in the source file rather
    /// than the one belonging to this object hands SparePantry the pantry's 12 instead of 3.
    /// </summary>
    [Fact]
    public void ObjectConst_EachObjectKeepsItsOwnValue()
    {
        Assert.Equal(12, TreatPantry.Capacity);
        Assert.Equal(3, SparePantry.Capacity);
    }

    // --- val ---

    /// <summary>An <c>override val</c> declared in the object body: the plain declared route.</summary>
    [Fact]
    public void ObjectOverrideVal_ReadsAsStaticProperty()
    {
        Assert.Equal("treats", TreatPantry.Label);
    }

    /// <summary>A <c>val</c> is get-only: no setter is projected for it.</summary>
    [Fact]
    public void ObjectVal_HasNoSetter()
    {
        PropertyInfo? label = typeof(TreatPantry)
            .GetProperty("Label", BindingFlags.Public | BindingFlags.Static);

        Assert.NotNull(label);
        Assert.Null(label!.SetMethod);
    }

    // --- var ---

    /// <summary>An <c>Int</c> var: read, write, read back, then put the pantry back as it was.</summary>
    [Fact]
    public void ObjectIntVar_RoundTripsThroughTheSingleton()
    {
        int original = TreatPantry.Count;
        Assert.Equal(4, original);
        try
        {
            TreatPantry.Count = 11;
            Assert.Equal(11, TreatPantry.Count);
        }
        finally
        {
            TreatPantry.Count = original;
        }
    }

    /// <summary>A nullable primitive var: the value branch and the null branch.</summary>
    [Fact]
    public void ObjectNullablePrimitiveVar_RoundTripsValueAndNull()
    {
        int? original = TreatPantry.Portion;
        Assert.Null(original);
        try
        {
            TreatPantry.Portion = 7;
            Assert.Equal(7, TreatPantry.Portion);

            TreatPantry.Portion = null;
            Assert.Null(TreatPantry.Portion);
        }
        finally
        {
            TreatPantry.Portion = original;
        }
    }

    /// <summary>An enum-typed var crossing as its ordinal in both directions.</summary>
    [Fact]
    public void ObjectEnumVar_RoundTrips()
    {
        Mood original = TreatPantry.Mood;
        Assert.Equal(Mood.Sleepy, original);
        try
        {
            TreatPantry.Mood = Mood.Happy;
            Assert.Equal(Mood.Happy, TreatPantry.Mood);
        }
        finally
        {
            TreatPantry.Mood = original;
        }
    }

    // --- types that need conversion ---

    /// <summary>A <c>List&lt;String&gt;</c> val: the collection seam on a static owner.</summary>
    [Fact]
    public void ObjectCollectionVal_MarshalsEveryElement()
    {
        Assert.Equal(new[] { "tuna", "salmon" }, TreatPantry.Flavours);
    }

    /// <summary>
    /// A handle-typed val returning an exported class. Each read mints a StableRef the returned
    /// wrapper owns, so the consumer disposes it; the pantry keeps its Oreo either way.
    /// </summary>
    [Fact]
    public void ObjectHandleVal_ReturnsAnOwnedWrapper()
    {
        using Cat favourite = TreatPantry.Favourite;
        Assert.Equal("Oreo", favourite.Name);
    }

    /// <summary>Two reads hand back two wrappers; disposing one leaves the other usable (ADR-005).</summary>
    [Fact]
    public void ObjectHandleVal_EachReadIsANewWrapper()
    {
        using Cat first = TreatPantry.Favourite;
        Cat second = TreatPantry.Favourite;
        second.Dispose();

        Assert.Equal("Oreo", first.Name);
    }

    // --- named skip ---

    /// <summary>
    /// A <c>StateFlow</c>-typed object property has no adapter on the static route, so it is a
    /// NAMED skip: a diagnostic and NO C# member. Asserted by reflection so that a member
    /// appearing one day is an assertion failure here rather than a compile break. The diagnostic
    /// sentence itself is Tier 1's business, not this file's.
    /// </summary>
    [Fact]
    public void ObjectStateFlowProperty_ProjectsNoStaticMember()
    {
        MemberInfo[] level = typeof(TreatPantry)
            .GetMember("Level", BindingFlags.Public | BindingFlags.Static);

        Assert.Empty(level);
    }

    // --- inherited ---

    /// <summary>
    /// DECIDED: BIND. <c>object TreatPantry : Stockroom(...)</c> inherits <c>origin</c> from its
    /// open base rather than declaring it, and a C# static class cannot extend anything, so an
    /// object FLATTENS its inherited members — properties and methods alike — onto its own static
    /// class, via the class route's <c>isForwardPlannableMemberOf(obj, superClass = null)</c>
    /// predicate. Asserted by reflection rather than as <c>TreatPantry.Origin</c> so that a
    /// regression back to declared-only reads as an assertion failure here, not a compile break.
    /// The method half of the same decision is the test below.
    /// </summary>
    [Fact]
    public void ObjectInheritedVal_IsReachableAsAStaticProperty()
    {
        PropertyInfo? origin = typeof(TreatPantry)
            .GetProperty("Origin", BindingFlags.Public | BindingFlags.Static);

        Assert.NotNull(origin);
        Assert.Equal("kitchen", origin!.GetValue(null));
    }

    /// <summary>
    /// The method-route twin of the inherited property. <c>Stockroom.restock()</c> is inherited
    /// rather than declared on the object, and a C# static class cannot extend anything, so it is
    /// bound as a static method on <c>TreatPantry</c> itself. "kitchen" is seven characters long.
    /// </summary>
    [Fact]
    public void ObjectInheritedFun_IsReachableAsAStaticMethod()
    {
        Assert.Equal(7, TreatPantry.Restock());
    }

    // --- lateinit ---

    /// <summary>
    /// A <c>lateinit var</c> read before assignment must surface as a C# exception, not a crash.
    /// <c>UninitializedPropertyAccessException</c> is not in the ADR-029 mapping table (grepped:
    /// no hit in the runtime or the processor), so it falls back to the base
    /// <c>KotlinException</c> the way the custom-exception getter tests do. The assertion is
    /// written against <c>IKotlinException</c> rather than that concrete class: that is the
    /// contract every bridged exception carries, so adding this type to the mapping table later
    /// changes the .NET base class without turning this test red for the wrong reason.
    ///
    /// ONE test touches <c>Keeper</c>, and it asserts the throw FIRST: the pantry is global, so a
    /// second test that assigned the keeper would silently decide this one's outcome.
    /// </summary>
    [Fact]
    public void ObjectLateinitVar_ReadBeforeAssignment_ThrowsThenAssigns()
    {
        Exception ex = Assert.ThrowsAny<Exception>(() => TreatPantry.Keeper);
        Assert.IsAssignableFrom<IKotlinException>(ex);

        TreatPantry.Keeper = "Mylo";
        Assert.Equal("Mylo", TreatPantry.Keeper);
    }
}
