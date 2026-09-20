using System.Reflection;
using TestLibrary.Husk;

namespace IntegrationTests;

/// <summary>
/// A Kotlin file whose every top-level declaration is skipped still gets its ADR-007 file-named
/// static class emitted, empty: <c>public static partial class HuskOnly { }</c>. That type is not
/// an API, it is the residue of a skip, and it is indistinguishable from a class whose members
/// are merely still to come. The fix elides a static class whose merged member set is empty, and
/// drops a namespace that is left with no declarations at all.
///
/// Three fixtures, three outcomes, all in one pack:
/// <list type="bullet">
///   <item><c>husk/HuskOnly.kt</c> -- all declarations skipped, class must vanish, but its
///   namespace survives because a sibling file lives there.</item>
///   <item><c>husk/HuskMixed.kt</c> -- one skipped, one surviving. The class must stay. This is
///   the control that stops "elide when empty" from degrading into "elide when anything
///   skipped".</item>
///   <item><c>chaff/ChaffOnly.kt</c> -- the only file in its package, all skipped, so the
///   namespace <c>TestLibrary.Chaff</c> must go too. Nothing else can hold it up.</item>
/// </list>
///
/// The generated <c>Interop.cs</c> compiles into <c>IntegrationTests.dll</c>, so that assembly is
/// the haystack for every absence assertion (precedent: <c>Issue42Tests</c>).
///
/// <para>
/// ADR-064's amendment for issue #249 AMENDS the rule these cells pin, and they are flipped with
/// it. "Indistinguishable from a class whose members are merely still to come" is exactly what
/// stops being true once the holder SAYS why it is empty: a file whose every declaration was
/// dropped is the one place a consumer can be told at all, so the holder survives carrying nothing
/// but its <c>&lt;remarks&gt;</c>. The half that still holds -- nothing declared and nothing
/// dropped means still no holder -- has no fixture here (every file in this pack that declares
/// nothing also drops something), so it is pinned in Tier 1 instead, by
/// <c>Tier1EmptyStaticClassElisionTest</c>'s quiet-file cell.
/// </para>
///
/// Oreo scans the empty treat bag. Mylo pings back. Both of them make the generated file now, but
/// only one of them is callable.
/// </summary>
public class EmptyStaticClassTests
{
    // HuskMixed is the one fixture type here that must exist both before and after the fix, so
    // it is the stable anchor for reaching the assembly the generated Interop.cs compiles into.
    private static IReadOnlyList<Type> Emitted =>
        typeof(HuskMixed).Assembly.GetTypes();

    [Fact]
    public void HuskOnly_EveryDeclarationSkipped_KeepsAHolderThatCarriesOnlyTheRemark()
    {
        // Issue #249: the holder is kept, because it is the only place `scan`'s absence can reach
        // a consumer -- but it carries no member of any kind, which is the half of the original
        // rule that never changed.
        var husk = Assert.Single(Emitted.Where(t => t.Name == "HuskOnly"));

        Assert.Equal("TestLibrary.Husk", husk.Namespace);
        Assert.Empty(husk.GetMethods(
            BindingFlags.Public | BindingFlags.Static | BindingFlags.DeclaredOnly));
    }

    [Fact]
    public void HuskMixed_OneSurvivingDeclaration_KeepsItsStaticClass()
    {
        // Distance of exactly one member from the husk, and that is enough to keep the class.
        var mixed = Emitted.SingleOrDefault(t => t.Name == "HuskMixed");

        Assert.NotNull(mixed);
        Assert.Equal("TestLibrary.Husk", mixed!.Namespace);
    }

    [Fact]
    public void HuskMixed_Ping_StillCallable()
    {
        // Top-level functions are PascalCase in the generated C# (ADR-110), so Kotlin's `ping()`
        // is `Ping()` here, called through the real P/Invoke.
        Assert.Equal(1, HuskMixed.Ping());
    }

    [Fact]
    public void HuskMixed_SkippedSibling_HasNoMember()
    {
        // `sift(List<List<String>?>)` is dropped (named SKIPPED_UNSUPPORTED_INPUT at pack time),
        // so keeping the class must not smuggle it back in under any spelling.
        var mixed = Emitted.Single(t => t.Name == "HuskMixed");
        var members = mixed
            .GetMethods(BindingFlags.Public | BindingFlags.Static | BindingFlags.DeclaredOnly)
            .Select(m => m.Name)
            .ToList();

        Assert.Equal(new[] { "Ping" }, members);
    }

    [Fact]
    public void HuskNamespace_HoldsNothingButTheMixedFile()
    {
        // Namespace-level sweep: whatever else the husk rule does, `TestLibrary.Husk` must end up
        // containing exactly these two holders and nothing invented beside them. Name-agnostic, so
        // a renamed stub cannot slip through.
        var inHusk = Emitted
            .Where(t => t.Namespace == "TestLibrary.Husk")
            .Select(t => t.Name)
            .OrderBy(n => n)
            .ToList();

        Assert.Equal(new[] { "HuskMixed", "HuskOnly" }, inHusk);
    }

    [Fact]
    public void ChaffNamespace_HoldsOnlyTheHolderThatExplainsItself()
    {
        // `chaff/ChaffOnly.kt` is the only file in its package and every declaration in it was
        // dropped, so before issue #249 the namespace went with the husk. It is kept now, for the
        // one reason a namespace is ever worth keeping around an empty type: the type says why it
        // is empty. Nothing else lives there and nothing is callable on it.
        var chaff = Assert.Single(Emitted.Where(t => t.Namespace == "TestLibrary.Chaff"));

        Assert.Equal("ChaffOnly", chaff.Name);
        Assert.Empty(chaff.GetMethods(
            BindingFlags.Public | BindingFlags.Static | BindingFlags.DeclaredOnly));
    }
}
