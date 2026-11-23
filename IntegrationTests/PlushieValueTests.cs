using System.Reflection;
using TestLibrary.Plushie;

namespace IntegrationTests;

/// <summary>
/// ADR-242 (issue #611): an immutable, all-scalar Kotlin <c>data class</c> crosses as a C# value.
/// <c>Plushie</c> is a <c>sealed class : IEquatable&lt;Plushie&gt;</c> with get-only components and
/// no <c>IDisposable</c>; its constructor, computed members, <c>Equals</c>, <c>GetHashCode</c>,
/// <c>ToString</c> and <c>Copy</c> still run Kotlin's code. Nothing here disposes anything, on
/// purpose: a value has nothing to dispose.
/// <para>
/// Every seam the value crosses is driven once: return, parameter, nullable in and out,
/// <c>List</c> out and in, <c>suspend</c>, <c>Flow</c>, a lambda payload and result, and a property
/// on a handle class (<c>Shelf</c>). The wider components ride on <c>PlushieTag</c> and the nested
/// ones on <c>PlushieBox</c>. The last test pins the negatives: every predicate exclusion stays an
/// ADR-008 handle class.
/// </para>
/// Bun is Oreo's favourite plushie. Milo is the brown and creamy one Mylo sleeps on.
/// </summary>
public class PlushieValueTests
{
    private static readonly string[] BunColors = ["pink", "white"];

    private static Plushie Bun() => new("Bun", BunColors, Mood.Happy, 3);

    [Fact]
    public void Plushie_CrossesAsAValue_WithKotlinComputedMembers()
    {
        Plushie p = PlushieSample.FavouritePlushie(); // no using, no Dispose
        Assert.Equal("Bun", p.Name);
        Assert.Equal(BunColors, p.Colors);
        Assert.Equal("Bun (pink/white)", p.Label);                 // Kotlin getter
        Assert.Equal("BUN!", p.Describe(loud: true));               // Kotlin method
        Assert.Equal(p, new Plushie("Bun", BunColors, Mood.Happy, 3)); // Kotlin equals
        Assert.Equal("Plushie(name=Bun, colors=[pink, white], mood=Happy, squeaks=3)", p.ToString());
        Assert.Throws<KotlinArgumentException>(() => p.Copy(name: ""));     // init { require } still runs
        Assert.False(typeof(IDisposable).IsAssignableFrom(typeof(Plushie)));
    }

    [Fact]
    public void Plushie_IsASealedEquatableValue_WithNoHandleSurface()
    {
        Type type = typeof(Plushie);
        Assert.True(type.IsSealed);
        Assert.True(typeof(IEquatable<Plushie>).IsAssignableFrom(type));
        Assert.False(typeof(IDisposable).IsAssignableFrom(type));
        Assert.Null(type.GetMethod("Dispose", BindingFlags.Public | BindingFlags.Instance, Type.EmptyTypes));
        Assert.NotNull(type.GetMethod("op_Equality", BindingFlags.Public | BindingFlags.Static));
        Assert.NotNull(type.GetMethod("op_Inequality", BindingFlags.Public | BindingFlags.Static));
    }

    [Fact]
    public void Plushie_Components_ArePlainGetOnlyProperties()
    {
        Plushie p = Bun();
        Assert.Equal("Bun", p.Name);
        Assert.Equal(BunColors, p.Colors);
        Assert.Equal(Mood.Happy, p.Mood);
        Assert.Equal(3, p.Squeaks);
        foreach (string component in new[] { "Name", "Colors", "Mood", "Squeaks", "Label" })
        {
            PropertyInfo property = typeof(Plushie).GetProperty(component)!;
            Assert.True(property.CanRead, component);
            Assert.False(property.CanWrite, component);
        }
    }

    [Fact]
    public void Plushie_ComputedPropertyAndMethod_RunKotlinOnEveryCall()
    {
        Plushie milo = new("Milo", ["brown", "cream"], Mood.Sleepy, 0);
        Assert.Equal("Milo (brown/cream)", milo.Label);
        Assert.Equal("Milo", milo.Describe(loud: false));
        Assert.Equal("MILO!", milo.Describe(loud: true));
    }

    [Fact]
    public void Plushie_EqualsHashCodeToString_AreKotlins()
    {
        Plushie a = Bun();
        Plushie b = PlushieSample.FavouritePlushie();
        Plushie milo = new("Milo", ["brown", "cream"], Mood.Sleepy, 0);

        Assert.True(a.Equals(b));
        Assert.True(((IEquatable<Plushie>)a).Equals(b));
        Assert.True(a.Equals((object)b));
        Assert.False(a.Equals(milo));
        Assert.False(a.Equals((object?)null));
        Assert.Equal(a.GetHashCode(), b.GetHashCode());
        Assert.Equal(a.GetHashCode(), a.GetHashCode());
        Assert.Equal("Plushie(name=Milo, colors=[brown, cream], mood=Sleepy, squeaks=0)", milo.ToString());
    }

    [Fact]
    public void Plushie_EqualityOperators_DelegateToEquals()
    {
        // Two crossings of the same Kotlin value are two C# objects, so reference `==` would be false.
        Plushie a = PlushieSample.FavouritePlushie();
        Plushie b = PlushieSample.FavouritePlushie();
        Plushie milo = new("Milo", ["brown", "cream"], Mood.Sleepy, 0);
        Plushie? nobody = null;

        Assert.True(a == b);
        Assert.False(a != b);
        Assert.False(a == milo);
        Assert.True(a != milo);
        Assert.False(a == nobody);
        Assert.False(nobody == a);
        Assert.True(a != nobody);
        Assert.True(nobody == null);
    }

    [Fact]
    public void Plushie_Copy_ChangesOnlyTheNamedComponents()
    {
        Plushie bun = Bun();
        Plushie louder = bun.Copy(squeaks: 4);
        Assert.Equal(new Plushie("Bun", BunColors, Mood.Happy, 4), louder);

        Plushie sleepy = bun.Copy(mood: Mood.Sleepy, colors: ["grey"]);
        Assert.Equal("Bun", sleepy.Name);
        Assert.Equal(["grey"], sleepy.Colors);
        Assert.Equal(Mood.Sleepy, sleepy.Mood);
        Assert.Equal(3, sleepy.Squeaks);

        Assert.Equal(bun, bun.Copy());
        Assert.Equal(3, bun.Squeaks);
    }

    [Fact]
    public void Plushie_ConstructorAndCopy_RejectABlankName_WithTheMappedKotlinException()
    {
        var ctor = Assert.Throws<KotlinArgumentException>(() => new Plushie(" ", BunColors, Mood.Happy, 3));
        Assert.Contains("a plushie needs a name", ctor.Message);

        var copy = Assert.Throws<KotlinArgumentException>(() => Bun().Copy(name: ""));
        Assert.Contains("a plushie needs a name", copy.Message);
    }

    [Fact]
    public void Plushie_ThrowingMember_SurfacesTheKotlinException()
    {
        var thrown = Assert.Throws<KotlinInvalidOperationException>(() => Bun().Explode());
        Assert.Contains("stuffing everywhere", thrown.Message);
    }

    [Fact]
    public void Plushie_Constructor_CopiesTheCollectionItWasGiven()
    {
        string[] colors = ["pink", "white"];
        Plushie p = new("Bun", colors, Mood.Happy, 3);
        colors[0] = "black";
        Assert.Equal(BunColors, p.Colors);
        Assert.Equal("Bun (pink/white)", p.Label);
    }

    [Fact]
    public void Plushie_RoundTrip_ThroughKotlin_IsEqual()
    {
        Plushie mine = new("Oreo's Biscuit", ["black", "white", "black"], Mood.Sleepy, 7);
        Plushie echoed = PlushieSample.EchoPlushie(mine);
        Assert.Equal(mine, echoed);
        Assert.Equal("Oreo's Biscuit", echoed.Name);
        Assert.Equal(["black", "white", "black"], echoed.Colors);
        Assert.Equal(Mood.Sleepy, echoed.Mood);
        Assert.Equal(7, echoed.Squeaks);

        Assert.True(mine == echoed);
    }

    [Fact]
    public void Plushie_Nullable_InAndOut()
    {
        Plushie milo = new("Milo", ["brown", "cream"], Mood.Sleepy, 0);
        Plushie? found = PlushieSample.FindPlushie("Milo");
        Assert.Equal(milo, found);
        Assert.Null(PlushieSample.FindPlushie("Mylo"));

        Assert.Equal("Bun", PlushieSample.NameOf(Bun()));
        Assert.Equal("nobody", PlushieSample.NameOf(null));

        Assert.True(found == milo);
    }

    [Fact]
    public void Plushie_List_OutAndIn()
    {
        IReadOnlyList<Plushie> shelf = PlushieSample.Plushies();
        Assert.Equal(2, shelf.Count);
        Assert.Equal(Bun(), shelf[0]);
        Assert.Equal("Milo (brown/cream)", shelf[1].Label);

        Assert.Equal(3, PlushieSample.TotalSqueaks(shelf));
        Assert.Equal(10, PlushieSample.TotalSqueaks([Bun(), Bun().Copy(squeaks: 7)]));
        Assert.Equal(0, PlushieSample.TotalSqueaks([]));

        Assert.True(shelf[0] == Bun());
    }

    [Fact]
    public async Task Plushie_SuspendResult_IsAValue()
    {
        Plushie fetched = await PlushieSample.FetchPlushieAsync();
        Assert.Equal(Bun(), fetched);
        Assert.Equal("Bun (pink/white)", fetched.Label);

        Assert.True(fetched == Bun());
    }

    [Fact]
    public async Task Plushie_FlowElements_AreValues()
    {
        var parade = new List<Plushie>();
        await foreach (Plushie p in PlushieSample.PlushieParade()) parade.Add(p);

        Assert.Equal(2, parade.Count);
        Assert.Equal(Bun(), parade[0]);
        Assert.Equal("Milo", parade[1].Name);

        Assert.True(parade[0] == Bun());
    }

    [Fact]
    public void Plushie_LambdaPayloadAndResult_AreValues()
    {
        Plushie? seen = null;
        Plushie restyled = PlushieSample.Restyle(p =>
        {
            seen = p;
            return p.Copy(colors: ["gold"]);
        });

        Assert.Equal(Bun(), seen);
        Assert.Equal("Bun (gold)", restyled.Label);

        Assert.True(seen == Bun());
    }

    [Fact]
    public void Plushie_OnAHandleClassProperty_GetsAndSets()
    {
        using var shelf = new Shelf(); // Shelf is an ordinary class: it stays IDisposable
        Assert.Equal(Bun(), shelf.Favourite);

        Plushie milo = new("Milo", ["brown", "cream"], Mood.Sleepy, 0);
        shelf.Favourite = milo;
        Assert.Equal(milo, shelf.Favourite);
        Assert.Equal(1, shelf.Put(milo));
        Assert.Equal(2, shelf.Put(Bun()));

        Assert.True(shelf.Favourite == milo);
    }

    [Fact]
    public void PlushieTag_WiderComponents_CrossAsAValue()
    {
        PlushieTag mylos = PlushieSample.MylosTag();
        Assert.Equal(new Stitches(42), mylos.Stitches);
        Assert.Equal(DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_000), mylos.MadeAt);
        Assert.Equal(TimeSpan.FromHours(72), mylos.WashEvery);
        Assert.Equal(Guid.Parse("550e8400-e29b-41d4-a716-446655440000"), mylos.Serial);
        Assert.Null(mylos.Nickname);
        Assert.Equal(0.25, mylos.Weight);
        Assert.True(mylos.Fabrics.SetEquals(["velvet", "fleece"]));
        Assert.Equal(4, mylos.Patches["paw"]);

        var mine = new PlushieTag(
            new Stitches(7),
            DateTimeOffset.FromUnixTimeMilliseconds(1_600_000_000_000),
            TimeSpan.FromMinutes(90),
            Guid.Parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8"),
            "Milo",
            1.5,
            new HashSet<string> { "plush" },
            new Dictionary<string, int> { ["tail"] = 1 });
        PlushieTag echoed = PlushieSample.EchoTag(mine);
        Assert.Equal(mine, echoed);
        Assert.Equal(mine.GetHashCode(), echoed.GetHashCode());
        Assert.Equal("Milo", echoed.Nickname);
        Assert.Equal(1, echoed.Patches["tail"]);
        Assert.NotEqual(mine, mine.Copy(nickname: null));

        // The value-shape assertions last, so today's handle class still proves every crossing above.
        Assert.True(mylos == PlushieSample.MylosTag());
        Assert.True(mine == echoed);
        Assert.False(typeof(IDisposable).IsAssignableFrom(typeof(PlushieTag)));
    }

    [Fact]
    public void PlushieBox_NestedComponents_CrossAsAValue()
    {
        PlushieBox box = PlushieSample.OreosBox();
        Assert.Equal(Bun(), box.Star);
        Assert.Null(box.Spare);
        Assert.Equal(2, box.Stack.Count);
        Assert.Equal("Milo", box.Stack[1].Name);

        Plushie milo = new("Milo", ["brown", "cream"], Mood.Sleepy, 0);
        PlushieBox mine = new(milo, Bun(), [milo]);
        PlushieBox echoed = PlushieSample.EchoBox(mine);
        Assert.Equal(mine, echoed);
        Assert.Equal(Bun(), echoed.Spare);
        Assert.Equal(milo, box.Copy(spare: milo).Spare);

        // The value-shape assertions last, so today's handle class still proves every crossing above.
        Assert.True(box.Star == Bun());
        Assert.True(mine == echoed);
        Assert.False(typeof(IDisposable).IsAssignableFrom(typeof(PlushieBox)));
    }

    [Fact]
    public void PlushieTagAndBox_AtAnErasedPosition_AndTheirObjectMembers()
    {
        PlushieTag tag = PlushieSample.MylosTag();
        PlushieTag heavier = tag.Copy(weight: 1.0);
        PlushieBox box = PlushieSample.OreosBox();
        PlushieBox spared = box.Copy(spare: Bun());

        // An erased `T`: each value is minted into an owned box on the way in and read back out.
        Assert.StartsWith("PlushieTag(stitches=", TestLibrary.Cat.Descriptions.Describe(tag));
        Assert.EndsWith(": PlushieTag", TestLibrary.Cat.Descriptions.Describe(tag));
        Assert.Equal(tag, TestLibrary.Cat.Descriptions.Pick(heavier, tag));
        Assert.EndsWith(": PlushieBox", TestLibrary.Cat.Descriptions.Describe(box));
        Assert.Equal(spared, TestLibrary.Cat.Descriptions.Pick(box, spared));

        Assert.StartsWith("PlushieTag(stitches=", tag.ToString());
        Assert.Contains("nickname=null", tag.ToString());
        Assert.StartsWith("PlushieBox(star=Plushie(name=Bun", box.ToString());
        Assert.True(tag.Equals((object)PlushieSample.MylosTag()));
        Assert.False(tag.Equals((object)box));
        Assert.True(box.Equals((object)PlushieSample.OreosBox()));
        Assert.False(box.Equals((object)tag));
        Assert.True(tag != heavier);
        Assert.False(tag != PlushieSample.MylosTag());
        Assert.True(box != spared);
        Assert.False(box != PlushieSample.OreosBox());
    }

    [Fact]
    public void PredicateExclusions_StayDisposableHandleClasses()
    {
        // One data class per ADR-242 exclusion, each obtained through Kotlin so the factory binds.
        // ADR-008 is unchanged for all of them. No generic data class: it does not compile today.
        // `object`, not `IDisposable`: a wrongly admitted negative must fail this test at run time,
        // not break the whole project's compile.
        object[] handles =
        [
            PlushieSample.SquishyPlushie(), // var component
            PlushieSample.StampedPlushie(), // body val with an initialiser
            PlushieSample.LazyPlushie(), // by lazy body property
            PlushieSample.LatePlushie(), // lateinit var body property
            PlushieSample.StuffedPlushie(), // handle-typed component
            PlushieSample.StuffingPile(), // List of handle-typed elements
            PlushieSample.GrowingPlushie(), // MutableList component
            PlushieSample.ChippedPlushie(), // ByteArray component
            PlushieSample.BearPlushie(), // sealed arm
            PlushieSample.BoxedPlushie(), // open superclass
            PlushieSample.CuddlyPlushie(), // exported interface
            PlushieSample.PlushieChain(), // self-referencing type graph
        ];
        try
        {
            foreach (object handle in handles) Assert.IsAssignableFrom<IDisposable>(handle);
        }
        finally
        {
            foreach (object handle in handles) (handle as IDisposable)?.Dispose();
        }

        Assert.True(typeof(IDisposable).IsAssignableFrom(typeof(Shelf)));
    }
}
