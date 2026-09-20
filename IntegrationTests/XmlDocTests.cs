using System.Xml.Linq;
using TestLibrary;
using TestLibrary.Kdoc;

namespace IntegrationTests;

/// <summary>
/// ADR-150: KDoc on an exported Kotlin declaration becomes an XML doc comment on its generated C#
/// declaration.
///
/// The observable is the documentation XML the compiler emits for THIS assembly: the generated
/// bindings are source-included contentFiles, so their <c>///</c> comments land in
/// IntegrationTests.xml next to the test host (the csproj turns on GenerateDocumentationFile for
/// exactly this). Reflection cannot see a doc comment, so there is no other consumer-side way to
/// assert the feature.
///
/// The fixture is <c>test-library/src/nativeMain/.../test/kdoc/</c>, one documented member per
/// declaration family. Each test calls the member it asserts docs for, so the generated shim the
/// doc rides on is exercised rather than only described; <c>IPerchable</c> is the one exception,
/// since nothing implements it and an interface member cannot be invoked.
/// </summary>
public class XmlDocTests
{
    private const string Ns = "TestLibrary.Kdoc";

    private static readonly XDocument Doc = LoadDoc();

    /// <summary>
    /// The documentation file MSBuild copies next to the test host, named after the assembly. Read
    /// from the test assembly's own location rather than a built path, so it follows the host RID
    /// and configuration this run actually used.
    /// </summary>
    private static XDocument LoadDoc()
    {
        string assembly = typeof(XmlDocTests).Assembly.Location;
        string path = Path.ChangeExtension(assembly, ".xml");
        if (!File.Exists(path))
        {
            throw new InvalidOperationException(
                $"no documentation file at {path}; IntegrationTests.csproj must set " +
                "<GenerateDocumentationFile>true</GenerateDocumentationFile>");
        }

        return XDocument.Load(path);
    }

    private static XElement? Member(string name) => Doc
        .Descendants("member")
        .FirstOrDefault(member => (string?)member.Attribute("name") == name);

    private static XElement Required(string name) =>
        Member(name) ?? throw new Xunit.Sdk.XunitException(
            $"no <member name=\"{name}\"> in the documentation file; documented entries are:\n  " +
            string.Join(
                "\n  ",
                Doc.Descendants("member")
                    .Select(member => (string?)member.Attribute("name"))
                    .Where(entry => entry is not null && entry.Contains(Ns))));

    private static string? Tag(XElement member, string tag) =>
        member.Element(tag)?.Value.Trim();

    private static string? Param(XElement member, string name) => member
        .Elements("param")
        .FirstOrDefault(param => (string?)param.Attribute("name") == name)
        ?.Value.Trim();

    private static bool HasParam(XElement member, string name) => member
        .Elements("param")
        .Any(param => (string?)param.Attribute("name") == name);

    [Fact]
    public void BoardingDesk_ClassSummary_ComesFromItsKdoc()
    {
        using var desk = new BoardingDesk("Oreo");
        Assert.Equal("Boarding desk for Oreo and Mylo.", Tag(Required($"T:{Ns}.BoardingDesk"), "summary"));
    }

    [Fact]
    public void Book_DocumentsEveryParameterTheReturnAndTheMappedException()
    {
        using var desk = new BoardingDesk("Oreo");
        Assert.Equal("Oreo/suite/2", desk.Book(2, "suite"));
        Assert.Throws<KotlinArgumentException>(() => desk.Book(0, "suite"));

        XElement member = Required($"M:{Ns}.BoardingDesk.Book(System.Int32,System.String)");
        Assert.Equal("Books a stay for the cat.", Tag(member, "summary"));
        Assert.Equal("how many nights", Param(member, "nights"));
        Assert.Equal("which suite, defaults to the sunny one", Param(member, "suite"));
        Assert.Equal("the booking reference", Tag(member, "returns"));

        XElement exception = Assert.Single(member.Elements("exception"));
        Assert.Equal("T:TestLibrary.KotlinArgumentException", (string?)exception.Attribute("cref"));
        Assert.Equal("when nights is not positive", exception.Value.Trim());
    }

    [Fact]
    public void Book_LaterParagraphs_BecomeParasOfOneRemarksAndLeaveTheSummaryAlone()
    {
        using var desk = new BoardingDesk("Mylo");
        Assert.Equal("Mylo/sunny/1", desk.Book(1));

        XElement member = Required($"M:{Ns}.BoardingDesk.Book(System.Int32,System.String)");

        // Paragraph one stays the whole summary; <remarks> is what the rest is for.
        Assert.Equal("Books a stay for the cat.", Tag(member, "summary"));
        Assert.DoesNotContain("sleeps through", Tag(member, "summary")!);

        // One <remarks> per member, never two, so ADR-064's generator remark has somewhere to sit.
        XElement remarks = Assert.Single(member.Elements("remarks"));
        XElement[] paras = remarks.Elements("para").ToArray();
        Assert.Equal(2, paras.Length);
        Assert.Contains("the cat sleeps through", paras[0].Value);
        Assert.StartsWith("The reference reads", paras[1].Value.TrimStart());
    }

    [Fact]
    public void Book_TypeLinkResolves_WhileAParameterLinkFallsBackToInlineCode()
    {
        using var desk = new BoardingDesk("Mylo");
        Assert.Equal("Mylo/sunny/2", desk.Book(2));

        XElement member = Required($"M:{Ns}.BoardingDesk.Book(System.Int32,System.String)");
        XElement para = Assert.Single(member.Elements("remarks")).Elements("para").First();

        // [Snooze] names a type this generated file really declares, so it becomes a navigable
        // cref. The compiler rewrites a cref it resolved into a documentation ID, which is the
        // proof that it resolved rather than merely being spelled.
        Assert.Equal(
            $"T:{Ns}.Snooze",
            (string?)Assert.Single(para.Descendants("see")).Attribute("cref"));

        // [nights] names a parameter, which has no cref spelling the bridge can resolve, so it
        // keeps the author's Kotlin spelling inside <c> rather than guessing "Nights".
        Assert.Equal("nights", Assert.Single(para.Descendants("c")).Value);
    }

    [Fact]
    public void Book_BacktickSpan_BecomesInlineCodeWithItsAngleBracketsAndAmpersandEscaped()
    {
        using var desk = new BoardingDesk("Oreo");
        Assert.Equal("Oreo/sunny/1", desk.Book(1));

        XElement member = Required($"M:{Ns}.BoardingDesk.Book(System.Int32,System.String)");
        XElement para = Assert.Single(member.Elements("remarks")).Elements("para").Last();

        // A raw `<` or `&` in a doc comment is CS1570 (fatal under GeneratedBindingsCheck), so this
        // text can only arrive escaped; that the file parses at all is half the assertion and the
        // round-tripped value is the other half.
        Assert.Equal("Pair<Oreo & Mylo>", Assert.Single(para.Descendants("c")).Value);
    }

    [Fact]
    public void Book_FencedBlock_BecomesACodeElementWithoutItsLanguageTag()
    {
        using var desk = new BoardingDesk("Oreo");
        Assert.Equal("Oreo/suite/2", desk.Book(2, "suite"));

        XElement member = Required($"M:{Ns}.BoardingDesk.Book(System.Int32,System.String)");
        XElement remarks = Assert.Single(member.Elements("remarks"));

        XElement code = Assert.Single(remarks.Elements("code"));
        Assert.Equal(
            "val reference = desk.book(nights = 2) // Oreo & Mylo <both>",
            code.Value.Trim());

        // The fence's `kotlin` language tag is a markdown affordance, not documentation text, and
        // the fence markers themselves must not survive as literal prose.
        Assert.DoesNotContain("kotlin", remarks.Value);
        Assert.DoesNotContain("```", remarks.Value);
    }

    [Fact]
    public void Book_OmittingOverload_KeepsTheDocWithoutTheOmittedParam()
    {
        using var desk = new BoardingDesk("Mylo");
        Assert.Equal("Mylo/sunny/3", desk.Book(3));

        XElement member = Required($"M:{Ns}.BoardingDesk.Book(System.Int32)");
        Assert.Equal("Books a stay for the cat.", Tag(member, "summary"));
        Assert.Equal("how many nights", Param(member, "nights"));
        Assert.False(HasParam(member, "suite"), "the omitting overload has no suite parameter");
        Assert.Equal("the booking reference", Tag(member, "returns"));
    }

    [Fact]
    public void Groom_PartialParamSet_GivesTheUndocumentedParameterAnEmptyTag()
    {
        using var desk = new BoardingDesk("Oreo");
        Assert.Equal("Oreo groomed with the slicker brush gently", desk.Groom("slicker", true));

        XElement member = Required($"M:{Ns}.BoardingDesk.Groom(System.String,System.Boolean)");
        Assert.Equal("Grooms the cat.", Tag(member, "summary"));
        Assert.Equal("which brush to use", Param(member, "brush"));
        Assert.True(HasParam(member, "gentle"), "an undocumented parameter still gets a tag (CS1573)");
        Assert.Equal(string.Empty, Param(member, "gentle"));
    }

    [Fact]
    public void Rehome_UnmappedThrows_CrefsKotlinException()
    {
        using var desk = new BoardingDesk("Mylo");
        Assert.Equal("Mylo moved to the sunroom", desk.Rehome("the sunroom"));
        Assert.Throws<KotlinException>(() => desk.Rehome(" "));

        XElement member = Required($"M:{Ns}.BoardingDesk.Rehome(System.String)");

        // The @param section here runs through a blank line (see the tag-section test below), so
        // its text is the whole section, not the first line of it.
        Assert.Equal("where the cat goes once the sunbeam moves", Param(member, "home"));

        XElement exception = Assert.Single(member.Elements("exception"));
        Assert.Equal("T:TestLibrary.KotlinException", (string?)exception.Attribute("cref"));
        // The unmapped Kotlin type survives as a plain-text prefix, not a <c> span, so the consumer
        // still sees which Kotlin exception the author named.
        Assert.Equal("RuntimeException: when the boarding desk is closed", exception.Value.Trim());
    }

    [Fact]
    public void Rehome_TagSectionWithABlankLine_StaysInTheTagAndOutOfTheRemarks()
    {
        using var desk = new BoardingDesk("Oreo");
        Assert.Equal("Oreo moved to the airing cupboard", desk.Rehome("the airing cupboard"));

        XElement member = Required($"M:{Ns}.BoardingDesk.Rehome(System.String)");

        // A blank line inside a tag section continues that section; the section ends at the next
        // tag. Text after the blank line is @param text, never a body paragraph.
        Assert.Equal("where the cat goes once the sunbeam moves", Param(member, "home"));
        Assert.DoesNotContain(
            "sunbeam",
            string.Concat(member.Elements("remarks").Select(remarks => remarks.Value)));

        // And the tag that follows the blank line is still parsed as a tag.
        Assert.Equal(
            "RuntimeException: when the boarding desk is closed",
            Assert.Single(member.Elements("exception")).Value.Trim());
    }

    [Fact]
    public void Rehome_See_IsASeealsoWhenItResolves_AndARemarkWhenItDoesNot()
    {
        using var desk = new BoardingDesk("Mylo");
        Assert.Equal("Mylo moved to the sunroom", desk.Rehome("the sunroom"));

        XElement member = Required($"M:{Ns}.BoardingDesk.Rehome(System.String)");

        // @see SunSpot resolves to a type this file declares, so it is a real <seealso cref>; the
        // compiler rewriting it into a documentation ID is the proof it resolved.
        Assert.Equal(
            $"T:{Ns}.SunSpot",
            (string?)Assert.Single(member.Elements("seealso")).Attribute("cref"));

        // @see book names a member, which has no resolvable cref spelling yet, so it closes the
        // remarks as prose instead of being dropped on the floor.
        XElement seeAlso = Assert.Single(Assert.Single(member.Elements("remarks")).Elements("para"));
        Assert.StartsWith("See also:", seeAlso.Value.Trim());
        Assert.Equal("book", Assert.Single(seeAlso.Descendants("c")).Value);
    }

    [Fact]
    public void FoodBowl_PropertyTag_DocumentsTheCsharpPropertyAndTheConstructorParameter()
    {
        using var bowl = new KdocFoodBowl("tuna", 2);
        Assert.Equal("tuna", bowl.Flavour);
        Assert.Equal(2, bowl.Scoops);

        Assert.Equal(
            "The bowl Mylo empties in one sitting.",
            Tag(Required($"T:{Ns}.KdocFoodBowl"), "summary"));

        // A constructor property reports no docString of its own, so this text can only have come
        // from the class-level @property tag.
        Assert.Equal(
            "what Mylo is eating",
            Tag(Required($"P:{Ns}.KdocFoodBowl.Flavour"), "summary"));

        XElement ctor = Required($"M:{Ns}.KdocFoodBowl.#ctor(System.String,System.Int32)");
        Assert.Equal("Fills the bowl for one sitting.", Tag(ctor, "summary"));   // @constructor
        Assert.Equal("what Mylo is eating", Param(ctor, "flavour"));             // @property fallback
        Assert.Equal("how many scoops went in", Param(ctor, "scoops"));          // class-level @param

        // The class-level @param documents the constructor parameter only: it is not a property
        // summary, so Scoops keeps no documentation entry at all.
        Assert.Null(Member($"P:{Ns}.KdocFoodBowl.Scoops"));
    }

    [Fact]
    public void FoodBowl_PropertyWithItsOwnKdoc_OutranksThePropertyTag()
    {
        using var bowl = new KdocFoodBowl("chicken", 3);
        Assert.True(bowl.Rinsed);

        XElement member = Required($"P:{Ns}.KdocFoodBowl.Rinsed");
        Assert.Equal("Whether Oreo licked it clean first.", Tag(member, "summary"));
        Assert.DoesNotContain("hosed down", member.Value);
    }

    [Fact]
    public void SecretTreat_IsSuppressed_AndCarriesNoDocEntry()
    {
        using var desk = new BoardingDesk("Oreo");
        Assert.Equal("Oreo gets a churu", desk.SecretTreat());

        // Paired with the documented sibling on purpose: absence alone is also true of a build
        // that documents nothing at all.
        Assert.NotNull(Member($"M:{Ns}.BoardingDesk.Groom(System.String,System.Boolean)"));
        Assert.Null(Member($"M:{Ns}.BoardingDesk.SecretTreat"));
    }

    [Fact]
    public void Volume_EnumSummary_LandsOnTheEnumAndNowhereElse()
    {
        Assert.Equal(20, Volume.Yowl.Loudness());
        Assert.Equal(0, Volume.Purr.Loudness());

        Assert.Equal("How loud Mylo is right now.", Tag(Required($"T:{Ns}.Volume"), "summary"));

        // `values`/`valueOf`/`entries` are SYNTHETIC and report the enum's own docString through
        // KSP, and the undocumented `loudness` property becomes VolumeExtensions.Loudness, so the
        // enum type entry must be the only Volume entry in the file.
        string[] others = Doc
            .Descendants("member")
            .Select(member => (string)member.Attribute("name")!)
            .Where(name => name.Contains($"{Ns}.Volume") && name != $"T:{Ns}.Volume")
            .ToArray();

        Assert.Empty(others);
    }

    [Fact]
    public void SunSpot_ActualIsBare_SoTheSummaryComesFromTheExpect()
    {
        using var perch = new SunSpot();
        Assert.Contains("warm on ", perch.Sunbeam());

        Assert.Equal("Oreo's sunny window perch.", Tag(Required($"T:{Ns}.SunSpot"), "summary"));
    }

    [Fact]
    public void Basking_OverloadedExpectFuns_EachTakeTheirOwnExpectsDoc()
    {
        Assert.Contains("Oreo basks on ", SunSpotKt.Basking("Oreo"));
        Assert.Contains("basked 5 min on ", SunSpotKt.Basking(5));

        // The pair is the discriminating cell: same parameter count, different parameter types and
        // names, distinct summaries. A lookup keyed by name alone, or by count alone, puts one
        // overload's text on the other; an ambiguous no-match leaves both undocumented.
        XElement byCat = Required($"M:{Ns}.SunSpotKt.Basking(System.String)");
        Assert.Equal("Finds where a cat is basking.", Tag(byCat, "summary"));
        Assert.Equal("which cat", Param(byCat, "cat"));
        Assert.Equal("the sunny spot", Tag(byCat, "returns"));

        XElement byMinutes = Required($"M:{Ns}.SunSpotKt.Basking(System.Int32)");
        Assert.Equal("Finds who has basked this long.", Tag(byMinutes, "summary"));
        Assert.Equal("how long", Param(byMinutes, "minutes"));
        Assert.Equal("who was found", Tag(byMinutes, "returns"));
    }

    [Fact]
    public void SunSpotWarmth_ExpectClassProperty_CarriesTheExpectsSummary()
    {
        using var perch = new SunSpot();
        Assert.Contains("toasty on ", perch.Warmth);

        Assert.Equal(
            "How warm the perch is right now.",
            Tag(Required($"P:{Ns}.SunSpot.Warmth"), "summary"));
    }

    [Fact]
    public void BaskingTag_TopLevelExpectVal_CarriesTheExpectsSummary()
    {
        Assert.StartsWith("perch-", SunSpotKt.BaskingTag);

        Assert.Equal(
            "The label stitched onto Oreo's favourite perch.",
            Tag(Required($"P:{Ns}.SunSpotKt.BaskingTag"), "summary"));
    }

    [Fact]
    public void SunnyNapMinutes_NullablePrimitiveReturn_IsStillDocumented()
    {
        // Deliberately NOT an `expect`: this isolates the nullable-primitive top-level route from
        // the expect index, so a red here is about the route carrying no doc at all.
        Assert.Equal((int?)14, SunSpotKt.SunnyNapMinutes());

        XElement member = Required($"M:{Ns}.SunSpotKt.SunnyNapMinutes");
        Assert.Equal(
            "How many minutes Mylo napped in the sun, when anyone was counting.",
            Tag(member, "summary"));
        Assert.Equal("the minutes, or null when nobody kept count", Tag(member, "returns"));
    }

    [Fact]
    public void Stretch_ExpectExtensionFunction_CarriesTheExpectsSummary()
    {
        using var perch = new SunSpot();
        Assert.Contains("stretched out on ", perch.Stretch());

        XElement member = Required($"M:{Ns}.SunSpotExtensions.Stretch({Ns}.SunSpot)");
        Assert.Equal("Stretches out across the whole spot.", Tag(member, "summary"));
        Assert.Equal("how the stretch went", Tag(member, "returns"));
    }

    [Fact]
    public void SunLounge_ExpectObject_CarriesTheExpectsSummary()
    {
        Assert.Equal(2, SunLounge.Loungers());

        Assert.Equal(
            "Where the whole household suns itself.",
            Tag(Required($"T:{Ns}.SunLounge"), "summary"));
    }

    [Fact]
    public void Guest_Property_CarriesItsOwnSummary()
    {
        using var desk = new BoardingDesk("Oreo");
        Assert.Equal("Oreo", desk.Guest);

        Assert.Equal(
            "The cat this desk is keeping.",
            Tag(Required($"P:{Ns}.BoardingDesk.Guest"), "summary"));
    }

    [Fact]
    public void SecondaryConstructor_CarriesItsSummaryAndParam()
    {
        using var desk = new BoardingDesk(7);
        Assert.Equal("stray #7", desk.Guest);

        XElement member = Required($"M:{Ns}.BoardingDesk.#ctor(System.Int32)");
        Assert.Equal("A desk for an unnamed stray.", Tag(member, "summary"));
        Assert.Equal("the stray's number", Param(member, "stray"));
    }

    [Fact]
    public async Task SuspendMethod_DocumentsItsKotlinParametersAndTheMintedCancellationToken()
    {
        using var desk = new BoardingDesk("Oreo");
        Assert.Equal("Oreo settled in 3 minutes", await desk.SettleAsync(3));

        XElement member =
            Required($"M:{Ns}.BoardingDesk.SettleAsync(System.Int32,System.Threading.CancellationToken)");
        Assert.Equal("Waits for the cat to settle.", Tag(member, "summary"));
        Assert.Equal("how long to wait", Param(member, "minutes"));
        Assert.Equal("what the cat did", Tag(member, "returns"));

        // The generator mints `cancellationToken`, so Kotlin can never document it. The empty tag
        // is what keeps CS1573 (a parameter with no tag beside tagged ones) off a consumer's build.
        Assert.True(HasParam(member, "cancellationToken"), "the minted parameter still gets a tag");
        Assert.Equal(string.Empty, Param(member, "cancellationToken"));
    }

    [Fact]
    public void ExtensionFunction_DocumentsTheReturnAndTagsNoParameter()
    {
        using var desk = new BoardingDesk("Mylo");
        Assert.Equal("Mylo/sunny/4", desk.DoubleStay(2));

        XElement member =
            Required($"M:{Ns}.BoardingDeskExtensions.DoubleStay({Ns}.BoardingDesk,System.Int32)");
        Assert.Equal("Doubles a stay.", Tag(member, "summary"));
        Assert.Equal("the doubled night count", Tag(member, "returns"));

        // No @param matches, so no <param> at all: a lone `receiver` or `nights` tag would be
        // CS1573 territory the other way round.
        Assert.Empty(member.Elements("param"));
    }

    [Fact]
    public void TopLevelFunction_CarriesItsSummaryParamAndReturns()
    {
        Assert.Equal(400, BoardingDeskKt.WeighBoardingCat("Oreo"));

        XElement member = Required($"M:{Ns}.BoardingDeskKt.WeighBoardingCat(System.String)");
        Assert.Equal("Weighs a boarding cat.", Tag(member, "summary"));
        Assert.Equal("whose weight", Param(member, "name"));
        Assert.Equal("the weight in grams", Tag(member, "returns"));
    }

    [Fact]
    public void Object_AndItsMember_AreBothDocumented()
    {
        Assert.Equal("a tuna treat", KdocTreatJar.Take("tuna"));

        Assert.Equal("The jar the treats live in.", Tag(Required($"T:{Ns}.KdocTreatJar"), "summary"));

        XElement member = Required($"M:{Ns}.KdocTreatJar.Take(System.String)");
        Assert.Equal("Takes one out.", Tag(member, "summary"));
        Assert.Equal("which treat", Param(member, "flavour"));
        Assert.Equal("what came out", Tag(member, "returns"));
    }

    [Fact]
    public void Interface_AndItsMember_AreBothDocumented()
    {
        // Nothing in the fixture implements IPerchable, so the member cannot be invoked; the shape
        // is what is observable here.
        Assert.NotNull(typeof(IPerchable).GetMethod(nameof(IPerchable.Perch)));

        Assert.Equal("Anything a cat will sit on.", Tag(Required($"T:{Ns}.IPerchable"), "summary"));

        XElement member = Required($"M:{Ns}.IPerchable.Perch");
        Assert.Equal("Sits on it.", Tag(member, "summary"));
        Assert.Equal("where the cat ended up", Tag(member, "returns"));
    }

    [Fact]
    public void ValueClass_AndItsMember_AreBothDocumented()
    {
        Assert.Equal(2, new Grams(2500).Kilograms());

        Assert.Equal("A weight in grams.", Tag(Required($"T:{Ns}.Grams"), "summary"));

        XElement member = Required($"M:{Ns}.Grams.Kilograms");
        Assert.Equal("The same weight in kilograms.", Tag(member, "summary"));
        Assert.Equal("the kilogram value", Tag(member, "returns"));
    }

    [Fact]
    public void SealedBase_Arm_AndArmMember_AreAllDocumented()
    {
        using var catnap = new Snooze.Catnap(10);
        Assert.Equal("short", catnap.Feels());

        Assert.Equal("A kind of nap.", Tag(Required($"T:{Ns}.Snooze"), "summary"));
        Assert.Equal("The short kind.", Tag(Required($"T:{Ns}.Snooze.Catnap"), "summary"));

        XElement member = Required($"M:{Ns}.Snooze.Catnap.Feels");
        Assert.Equal("How it felt.", Tag(member, "summary"));
        Assert.Equal("the feeling", Tag(member, "returns"));
    }

    [Fact]
    public void Whiskers_DocumentedEntry_CarriesItsOwnSummaryBesideTheEnums()
    {
        Assert.Equal(0, (int)Whiskers.Forward);
        Assert.Equal(1, (int)Whiskers.Back);

        Assert.Equal("How Oreo's whiskers are sitting.", Tag(Required($"T:{Ns}.Whiskers"), "summary"));
        Assert.Equal(
            "Pointing forward, interested.",
            Tag(Required($"F:{Ns}.Whiskers.Forward"), "summary"));

        // BACK has no KDoc, so it gets no entry: an entry summary is never inherited from the enum.
        Assert.Null(Member($"F:{Ns}.Whiskers.Back"));
    }
}
