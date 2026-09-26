using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-090's overload numbering on the <em>interface</em> route. Kotlin's <c>Brusher</c> declares
/// three <c>brush</c> overloads (none, <c>Int</c>, <c>Mood</c>) and an ADR-164 defaulted
/// <c>trim</c> pair, and is reachable both ways: returned from <c>houseBrusher()</c> (ADR-040) and
/// accepted by <c>GroomingSalon</c> (ADR-084). The native exports and the bridge slots carry the
/// number; the C# surface stays one natural overload set on <see cref="IBrusher"/>.
///
/// Today the reachable shape does not generate at all: the interface plan is keyed by
/// <c>Brusher.brush</c> alone, so the catalog holds three plans under one name and KSP stops with
/// <c>ERROR_INTERNAL_GENERATOR_FAILURE</c>.
///
/// <c>Brush(2)</c> and <c>Brush(Mood.Grumpy)</c> cross the same <c>int</c> value (Grumpy is ordinal
/// 2), so an export or slot wired to the wrong member of that pair cannot pass by luck: only the
/// returned text tells them apart, and every body embeds its own verb and argument.
///
/// Oreo gets the house brush. Mylo, who is brown and creamy and sheds on everything, gets brushed
/// by C#.
/// </summary>
public class InterfaceOverloadTests
{
    // --- Kotlin-implemented, reached through the returned interface (ADR-040 backing wrapper) ---

    [Fact]
    public void KotlinImplemented_Brush_NoArguments_DispatchesToFirstOverload()
    {
        using IBrusher brusher = BrusherKt.HouseBrusher();

        Assert.Equal("Oreo is brushed", brusher.Brush());
    }

    [Fact]
    public void KotlinImplemented_Brush_WithInt_DispatchesToIntOverload()
    {
        using IBrusher brusher = BrusherKt.HouseBrusher();

        Assert.Equal("Oreo is brushed 2 times", brusher.Brush(2));
    }

    [Fact]
    public void KotlinImplemented_Brush_WithMood_DispatchesToEnumOverload()
    {
        // Same wire value as Brush(2) above: the enum's ordinal conversion is what this overload
        // needs and the Int one does not.
        using IBrusher brusher = BrusherKt.HouseBrusher();

        Assert.Equal("Oreo is brushed while grumpy", brusher.Brush(Mood.Grumpy));
        Assert.Equal("Oreo is brushed while sleepy", brusher.Brush(Mood.Sleepy));
    }

    [Fact]
    public void KotlinImplemented_Brush_AllThreeOverloads_ShareOneInstanceAndStayDistinct()
    {
        using IBrusher brusher = BrusherKt.HouseBrusher();

        Assert.Equal("Oreo is brushed", brusher.Brush());
        Assert.Equal("Oreo is brushed 2 times", brusher.Brush(2));
        Assert.Equal("Oreo is brushed while grumpy", brusher.Brush(Mood.Grumpy));
    }

    [Fact]
    public void KotlinImplemented_Trim_OmittedClaws_UsesTheFirstOverloadsKotlinDefault()
    {
        using IBrusher brusher = BrusherKt.HouseBrusher();

        Assert.Equal("Oreo has 4 claws trimmed", brusher.Trim());
        Assert.Equal("Oreo has 3 claws trimmed", brusher.Trim(3));
    }

    [Fact]
    public void KotlinImplemented_Trim_WithPaw_UsesTheSecondOverloadsOwnDefault()
    {
        // 1, not 4: each numbered export carries its own mask dispatch, so the omitted `claws`
        // must resolve to the default declared on THIS overload.
        using IBrusher brusher = BrusherKt.HouseBrusher();

        Assert.Equal("Oreo has 1 claws trimmed on the front-left paw", brusher.Trim("front-left"));
        Assert.Equal("Oreo has 2 claws trimmed on the back-right paw", brusher.Trim("back-right", 2));
    }

    // --- Kotlin-implemented exported class (ADR-090 class route) ---

    [Fact]
    public void KotlinClass_EveryOverload_DispatchesToItsOwnMember()
    {
        using var slicker = new SlickerBrush("Oreo");

        Assert.Equal("Oreo is slicked", slicker.Brush());
        Assert.Equal("Oreo is slicked 2 times", slicker.Brush(2));
        Assert.Equal("Oreo is slicked while grumpy", slicker.Brush(Mood.Grumpy));
        Assert.Equal("Oreo has 4 claws clipped", slicker.Trim());
        Assert.Equal("Oreo has 1 claws clipped on the front-left paw", slicker.Trim("front-left"));
    }

    // --- Kotlin-implemented instances passed back to Kotlin (handle unwrap, not the bridge) ---

    [Fact]
    public void KotlinImplemented_PassedToSalon_KotlinCallsEveryOverloadOnTheOriginal()
    {
        using var salon = new GroomingSalon();
        using IBrusher house = BrusherKt.HouseBrusher();

        Assert.Equal(
            "Oreo is brushed / Oreo is brushed 2 times / Oreo is brushed while grumpy",
            salon.BrushAll(house));
        Assert.Equal(
            "Oreo has 4 claws trimmed / Oreo has 1 claws trimmed on the front-left paw / "
                + "Oreo has 2 claws trimmed on the back-right paw",
            salon.TrimAll(house));
    }

    [Fact]
    public void KotlinClass_PassedToSalon_KotlinCallsEveryOverloadOnTheOriginal()
    {
        using var salon = new GroomingSalon();
        using var slicker = new SlickerBrush("Oreo");

        Assert.Equal(
            "Oreo is slicked / Oreo is slicked 2 times / Oreo is slicked while grumpy",
            salon.BrushAll(slicker));
    }

    // --- C#-implemented, passed to Kotlin through the ADR-084 bridge factory ---

    /// <summary>
    /// Mylo's brush. Records every call as <c>member(argument types and values)</c>, so the tests
    /// assert which C# overload each Kotlin call landed on, not only what text came back.
    /// </summary>
    private sealed class MyloBrusher : IBrusher
    {
        public List<string> Calls { get; } = new();

        public string Brush()
        {
            Calls.Add("Brush()");
            return "Mylo is brushed";
        }

        public string Brush(int strokes)
        {
            Calls.Add($"Brush(int {strokes})");
            return $"Mylo is brushed {strokes} times";
        }

        public string Brush(Mood mood)
        {
            Calls.Add($"Brush(Mood {mood})");
            return $"Mylo is brushed while {mood.ToString().ToLowerInvariant()}";
        }

        public string Trim(int? claws = null)
        {
            Calls.Add($"Trim(int? {claws})");
            return $"Mylo has {claws} claws trimmed";
        }

        public string Trim(string paw, int? claws = null)
        {
            Calls.Add($"Trim(string {paw}, int? {claws})");
            return $"Mylo has {claws} claws trimmed on the {paw} paw";
        }

        public void Dispose() { }
    }

    [Fact]
    public void CSharpImplemented_Brush_EachKotlinCallReachesItsOwnCSharpOverload()
    {
        using var salon = new GroomingSalon();
        using var mylo = new MyloBrusher();

        string result = salon.BrushAll(mylo);

        // Kotlin calls brush(2) and brush(GRUMPY): the same int on the wire, two different slots.
        Assert.Equal(new[] { "Brush()", "Brush(int 2)", "Brush(Mood Grumpy)" }, mylo.Calls);
        Assert.Equal("Mylo is brushed / Mylo is brushed 2 times / Mylo is brushed while grumpy", result);
    }

    [Fact]
    public void CSharpImplemented_Trim_EachKotlinCallReachesItsOwnCSharpOverload()
    {
        using var salon = new GroomingSalon();
        using var mylo = new MyloBrusher();

        string result = salon.TrimAll(mylo);

        // The Kotlin caller takes both defaults itself, so C# sees them as concrete values: 4 on
        // the first overload, 1 on the second, never null.
        Assert.Equal(
            new[] { "Trim(int? 4)", "Trim(string front-left, int? 1)", "Trim(string back-right, int? 2)" },
            mylo.Calls);
        Assert.Equal(
            "Mylo has 4 claws trimmed / Mylo has 1 claws trimmed on the front-left paw / "
                + "Mylo has 2 claws trimmed on the back-right paw",
            result);
    }

    [Fact]
    public void CSharpImplemented_RepeatedSessions_KeepEverySlotOnItsOwnOverload()
    {
        // The same bridged implementer through both salon methods, then the first again. ADR-084
        // caches the bridge state per implementer, so the later crossings reuse the slot table the
        // first one built; a numbered slot that only lines up on a fresh bridge shows up here.
        using var salon = new GroomingSalon();
        using var mylo = new MyloBrusher();

        salon.BrushAll(mylo);
        salon.TrimAll(mylo);
        salon.BrushAll(mylo);

        Assert.Equal(
            new[]
            {
                "Brush()", "Brush(int 2)", "Brush(Mood Grumpy)",
                "Trim(int? 4)", "Trim(string front-left, int? 1)", "Trim(string back-right, int? 2)",
                "Brush()", "Brush(int 2)", "Brush(Mood Grumpy)",
            },
            mylo.Calls);
    }

    // --- The numbering stays off the public surface ---

    [Theory]
    [InlineData(typeof(IBrusher))]
    [InlineData(typeof(Brusher))]
    [InlineData(typeof(SlickerBrush))]
    public void Overloads_AreOneNaturalSet_WithoutNumberedPublicNames(Type type)
    {
        // The number is a native-export and bridge-slot detail (ADR-090). Nothing named Brush_2,
        // Brush_3 or Trim_2 may leak onto the interface, its backing wrapper, or the implementer.
        var publicMethods = type.GetMethods();

        Assert.Equal(3, publicMethods.Count(m => m.Name == "Brush"));
        Assert.Equal(2, publicMethods.Count(m => m.Name == "Trim"));
        Assert.DoesNotContain(publicMethods, m => m.Name.StartsWith("Brush_") || m.Name.StartsWith("Trim_"));

        Assert.NotNull(type.GetMethod("Brush", Type.EmptyTypes));
        Assert.NotNull(type.GetMethod("Brush", new[] { typeof(int) }));
        Assert.NotNull(type.GetMethod("Brush", new[] { typeof(Mood) }));
    }
}
