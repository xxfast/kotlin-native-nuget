using System.Reflection;
using TestLibrary.Dozing;
using TestLibrary.Membergeneric;
using TestLibrary.Multibound;
using TestLibrary.Rankings;

namespace IntegrationTests;

/// <summary>
/// ADR-217: a <c>T</c> whose Kotlin spelling needs a type variable (an unspellable bound such as
/// <c>Enum&lt;T&gt;</c> or <c>Node&lt;T&gt;</c>, or an intersection no bare parameter witnesses)
/// gets ADR-198's trampoline, and the per-element collection cast (or the generic-class cast)
/// happens inside it. Before ADR-217 every one of these was skipped by name, or (for
/// <see cref="Arena{T}.Team"/>) generated Kotlin that did not compile.
///
/// Every Kotlin body uses the bound's own API (<c>compareTo</c>, <c>name</c>, <c>ordinal</c>,
/// <c>link</c>, <c>tricks</c>), so an element that crossed as anything but the real entry would
/// answer wrongly or throw. Orders are chosen so a list Kotlin did not rebuild reads differently.
///
/// Oreo collects gold; Mylo collects everything else and wears it on a sash.
/// </summary>
public class TrampolinedCollectionTests
{
    // --- A member's own `T : Enum<T>` (on an object) ---

    [Fact]
    public void Judge_SortsAListOfMedals_BestFirst()
    {
        Assert.Equal(
            new[] { Medal.Gold, Medal.Silver, Medal.Bronze },
            Judge.Sorted(new[] { Medal.Silver, Medal.Bronze, Medal.Gold }));
    }

    [Fact]
    public void Judge_PadsWithNull_AndANullElementStaysNull_NotBronze()
    {
        // Under `struct`, `T?` is `Nullable<T>`. Reading a null element through `FromHandle<T>`
        // compiles and answers `default(Medal)`, which is Bronze: the silent wrong answer.
        IReadOnlyList<Medal?> padded = Judge.Pad(new Medal?[] { Medal.Gold, null, Medal.Bronze });

        Assert.Equal(new Medal?[] { Medal.Gold, null, Medal.Bronze, null }, padded);
        Assert.Null(padded[1]);
        Assert.Null(padded[3]);
    }

    [Fact]
    public void Judge_FiltersASetOfMedals_ByOrdinalOnTheKotlinSide()
    {
        IReadOnlySet<Medal> prizes =
            Judge.Unique(new HashSet<Medal> { Medal.Bronze, Medal.Silver, Medal.Gold });

        Assert.Equal(new HashSet<Medal> { Medal.Silver, Medal.Gold }, prizes.ToHashSet());
    }

    [Fact]
    public void Judge_DoublesAScoreboardKeyedByMedal()
    {
        IReadOnlyDictionary<Medal, int> doubled = Judge.Scores(
            new Dictionary<Medal, int> { [Medal.Gold] = 3, [Medal.Bronze] = 1 });

        Assert.Equal(2, doubled.Count);
        Assert.Equal(6, doubled[Medal.Gold]);
        Assert.Equal(2, doubled[Medal.Bronze]);
    }

    [Fact]
    public void Judge_SortsEveryHeat_InAListOfLists()
    {
        IReadOnlyList<IReadOnlyList<Medal>> heats = Judge.Heats(new IReadOnlyList<Medal>[]
        {
            new[] { Medal.Bronze, Medal.Gold },
            new[] { Medal.Silver },
            Array.Empty<Medal>(),
        });

        Assert.Equal(3, heats.Count);
        Assert.Equal(new[] { Medal.Gold, Medal.Bronze }, heats[0]);
        Assert.Equal(new[] { Medal.Silver }, heats[1]);
        Assert.Empty(heats[2]);
    }

    [Fact]
    public void Judge_ForeignEnum_SatisfiesTheConstraint_AndIsRefusedAtTheElement()
    {
        // `DayOfWeek` compiles under `where T : struct, Enum` but has no box; `CreateList`'s
        // per-element `Wrap<T>` refuses it before anything crosses. The route still answers.
        Assert.ThrowsAny<NotSupportedException>(() =>
            Judge.Sorted(new[] { DayOfWeek.Friday, DayOfWeek.Monday }));
        Assert.Equal(new[] { Medal.Gold }, Judge.Sorted(new[] { Medal.Gold }));
    }

    // --- A top-level `T : Enum<T>` (ADR-213's plan route) ---

    [Fact]
    public void Ordered_TopLevel_SortsByNameOnTheKotlinSide()
    {
        // By name: BRONZE < GOLD < SILVER, which is not the ordinal order.
        Assert.Equal(
            new[] { Medal.Bronze, Medal.Gold, Medal.Silver },
            Medals.Ordered(new[] { Medal.Silver, Medal.Gold, Medal.Bronze }));
    }

    // --- A class's own `T : Enum<T>` ---

    [Fact]
    public void Rosette_RanksAListOfT_WithTheWinnerIncluded()
    {
        using var mylo = new Rosette<Medal>(Medal.Silver);

        Assert.Equal(
            new[] { Medal.Gold, Medal.Silver, Medal.Bronze },
            mylo.Rank(new[] { Medal.Bronze, Medal.Gold }));
    }

    [Fact]
    public void Sash_ConstructorTakesAListOfT_AndReadsItBack()
    {
        using var mylo = new Sash<Medal>(new[] { Medal.Silver, Medal.Bronze, Medal.Gold });

        Assert.Equal(new[] { Medal.Silver, Medal.Bronze, Medal.Gold }, mylo.Ribbons);
        Assert.Equal(Medal.Gold, mylo.Best);
        Assert.Equal("SILVER#1, BRONZE#0, GOLD#2", mylo.Describe());
    }

    [Fact]
    public void EnumBound_IsARealConstraint_OnEveryNewGenericMethod()
    {
        foreach (Type parameter in new[]
                 {
                     typeof(Judge).GetMethod(nameof(Judge.Sorted))!.GetGenericArguments()[0],
                     typeof(Judge).GetMethod(nameof(Judge.Pad))!.GetGenericArguments()[0],
                     typeof(Medals).GetMethod(nameof(Medals.Ordered))!.GetGenericArguments()[0],
                     typeof(Sash<>).GetGenericArguments()[0],
                 })
        {
            Assert.Contains(typeof(Enum), parameter.GetGenericParameterConstraints());
            Assert.True(parameter.GenericParameterAttributes
                .HasFlag(GenericParameterAttributes.NotNullableValueTypeConstraint));
        }
    }

    // --- `T : Node<T>`, an invariant self-bound with a wrapper element ---

    [Fact]
    public void Chain_AttachAll_LinksEveryBeadInTheList()
    {
        using var oreo = new Bead("Oreo");
        using var mylo = new Bead("Mylo");
        using var milo = new Bead("Milo");
        using var chain = new Chain<Bead>(oreo);

        IReadOnlyList<Bead> linked = chain.AttachAll(new[] { mylo, milo });
        try
        {
            Assert.Equal(new[] { "Oreo-Mylo", "Oreo-Milo" }, linked.Select(bead => bead.Name));
        }
        finally
        {
            foreach (Bead bead in linked) bead.Dispose();
        }
    }

    [Fact]
    public void Braid_TopLevel_LinksTheListLeftToRight()
    {
        using var oreo = new Bead("Oreo");
        using var mylo = new Bead("Mylo");
        using var cookie = new Bead("Cookie");

        using Bead braided = Chains.Braid(new[] { oreo, mylo, cookie });
        Assert.Equal("Oreo-Mylo-Cookie", braided.Name);
    }

    // --- A multi-bound owner `T` (`Pet & Trainable`) mentioned only inside a collection ---

    [Fact]
    public void Arena_Team_SumsTricksOfAListOfT_WithNoBareWitness()
    {
        using var oreo = new Performer("Oreo", 3);
        using var mylo = new Performer("Mylo", 5);
        using var cookie = new Performer("Cookie", 1);
        using var arena = new Arena<Performer>(oreo);

        Assert.Equal(9, arena.Team(new[] { mylo, cookie }));
        Assert.Equal(3, arena.Team(Array.Empty<Performer>()));
    }

    [Fact]
    public void Arena_Mix_TakesAListOfT_BesideABareSiblingWitness()
    {
        using var oreo = new Performer("Oreo", 3);
        using var mylo = new Performer("Mylo", 5);
        using var arena = new Arena<Performer>(oreo);

        Assert.Equal("Oreo:3, Mylo:5", arena.Mix(new[] { mylo }, oreo));
    }

    // --- A member's multi-bound `T` witnessed only inside a generic class (ADR-216 refused it) ---

    [Fact]
    public void Groomer_Crown_UnpacksAMultiBoundTFromABasket()
    {
        using var groomer = new Groomer();
        using var oreo = new Tabby("Oreo", 3);
        using var basket = new Basket<Tabby>(oreo);

        using Tabby crowned = groomer.Crown(basket);
        Assert.Equal("Oreo", crowned.Name);
        Assert.Equal(3, crowned.Tricks);
    }

    [Fact]
    public void Groomer_Crown_RunsTheBoundsOnTheKotlinSide()
    {
        using var groomer = new Groomer();
        using var mylo = new Tabby("Mylo", 0);
        using var basket = new Basket<Tabby>(mylo);

        var refused = Assert.ThrowsAny<ArgumentException>(() => groomer.Crown(basket));
        Assert.Contains("Mylo knows no tricks", refused.Message);
    }

    [Fact]
    public void Groomer_Crown_IsConstrainedByBothBounds()
    {
        Type[] constraints = typeof(Groomer)
            .GetMethod(nameof(Groomer.Crown))!
            .GetGenericArguments()[0]
            .GetGenericParameterConstraints();

        Assert.Contains(typeof(IFurry), constraints);
        Assert.Contains(typeof(ITricky), constraints);
    }

    // --- A non-generic interface reachable only as a `T` bound (ADR-213's sync plan route) ---

    // Oreo's windowsill snore. `ISnoozer` is named by no parameter, property or return, only by
    // `tuckIn`'s bound, so this row proves the bound alone gives a C# implementation a bridge.
    private sealed class WindowsillOreo : ISnoozer
    {
        public string Snore => "purr-zzz";
        public void Dispose() { }
    }

    [Fact]
    public void TuckIn_CSharpImplementedSnoozer_CrossesThroughATBound()
    {
        using var oreo = new WindowsillOreo();

        Assert.Equal("tucked in: purr-zzz", Lullaby.TuckIn(oreo));
    }
}
