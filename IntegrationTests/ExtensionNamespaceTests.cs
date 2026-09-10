namespace IntegrationTests;

/// <summary>
/// A merged <c>{Receiver}Extensions</c> class for an unexported receiver (<c>String</c>, primitives,
/// anything this library does not itself export) lands in the namespace of the package that
/// <em>declares</em> the extension, not the package of whichever extension KSP visited first.
/// <para>
/// Every cell here calls the extension as a fully-qualified static method
/// (<c>TestLibrary.StringExtensions.Meowify("Oreo")</c> rather than <c>"Oreo".Meowify()</c>).
/// Extension-method syntax resolves through <c>using</c>s and would keep compiling whichever
/// namespace the class drifted into, so it can never pin a namespace. A fully-qualified static call
/// is a compile-time assertion: if the class moves, this file stops compiling with CS0234/CS0117.
/// </para>
/// <para>
/// The fixture crosses both grouping keys that were order-dependent: extension <em>functions</em>
/// (<c>meowify</c>, <c>isPurring</c>, <c>tag</c>) and an extension <em>property</em>
/// (<c>wordCount</c>), which the translator groups separately, plus two declaring packages (root and
/// <c>reserved</c>) so a same-receiver extension in one package cannot relocate the other's class.
/// The exported-receiver route (<c>Cat</c>) is the control: it homes on the receiver's own package
/// and must not move.
/// </para>
/// </summary>
public class ExtensionNamespaceTests
{
    [Fact]
    public void RootPackage_Meowify_RendersInTheRootNamespace()
    {
        // Kotlin package `...nuget.test` (root), so namespace TestLibrary. Today it renders in
        // TestLibrary.Cat because the `cat` package's `tag` was the first String extension visited.
        Assert.Equal("Oreo meow!", TestLibrary.StringExtensions.Meowify("Oreo"));
    }

    [Fact]
    public void RootPackage_IsPurring_RendersInTheRootNamespace()
    {
        Assert.True(TestLibrary.StringExtensions.IsPurring("Mylo is purrfectly asleep"));
        Assert.False(TestLibrary.StringExtensions.IsPurring("Oreo demands breakfast"));
    }

    [Fact]
    public void RootPackage_ExtensionPropertyAndFunctions_ShareOneClass()
    {
        // The property group picks its namespace independently of the function group, so today the
        // root package's String extensions already split across two `StringExtensions` classes in
        // two namespaces. One declaring package must mean exactly one class.
        Assert.Equal(3, TestLibrary.StringExtensions.GetWordCount("Oreo and Mylo"));
        Assert.NotNull(typeof(TestLibrary.StringExtensions).GetMethod("Meowify"));
        Assert.NotNull(typeof(TestLibrary.StringExtensions).GetMethod("GetWordCount"));
    }

    [Fact]
    public void ReservedPackage_Tag_RendersInItsOwnNamespace()
    {
        // `reserved/ReservedExtensions.kt` declares this one. It used to be parked in `cat/` purely
        // to dodge the relocation; back home, it must arrive in TestLibrary.Reserved.
        Assert.Equal("Oreo:Mylo", TestLibrary.Reserved.StringExtensions.Tag("Oreo", "Mylo"));
        Assert.NotNull(typeof(TestLibrary.Reserved.StringExtensions).GetMethod("Tag"));
    }

    [Fact]
    public void SameReceiverInTwoPackages_DoesNotMerge()
    {
        // The partition, stated as an absence: the reserved package's `tag` never joins the root
        // package's class, and the root package's `meowify` never joins the reserved one. Both
        // directions fail today, from either side of whichever package won the visit-order race.
        Assert.Null(typeof(TestLibrary.StringExtensions).GetMethod("Tag"));
        Assert.Null(typeof(TestLibrary.Reserved.StringExtensions).GetMethod("Meowify"));
    }

    [Fact]
    public void ExportedReceiver_StaysInTheReceiversNamespace()
    {
        // Control. `Cat` is exported, so `CatExtensions` homes on the receiver's package and this
        // rule change must not touch it. Both an extension function and an extension property.
        using var oreo = new TestLibrary.Cat.Cat("Oreo", 9);
        Assert.Equal("My name is Oreo", TestLibrary.Cat.CatExtensions.SayName(oreo));
        Assert.True(TestLibrary.Cat.CatExtensions.GetIsKitten(oreo));
    }
}
