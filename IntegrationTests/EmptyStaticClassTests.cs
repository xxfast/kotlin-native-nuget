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
/// Oreo scans the empty treat bag. Mylo pings back. Only one of them makes it to C#.
/// </summary>
public class EmptyStaticClassTests
{
    // HuskMixed is the one fixture type here that must exist both before and after the fix, so
    // it is the stable anchor for reaching the assembly the generated Interop.cs compiles into.
    private static IReadOnlyList<Type> Emitted =>
        typeof(HuskMixed).Assembly.GetTypes();

    [Fact]
    public void HuskOnly_EveryDeclarationSkipped_EmitsNoStaticClass()
    {
        // The red assertion. Today the husk is generated as an empty static class.
        var husks = Emitted.Where(t => t.Name == "HuskOnly").ToList();

        Assert.Empty(husks);
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
        // Namespace-level sweep: whatever else the husk elision does, `TestLibrary.Husk` must end
        // up containing exactly one type. Name-agnostic, so a renamed stub cannot slip through.
        var inHusk = Emitted
            .Where(t => t.Namespace == "TestLibrary.Husk")
            .Select(t => t.Name)
            .OrderBy(n => n)
            .ToList();

        Assert.Equal(new[] { "HuskMixed" }, inHusk);
    }

    [Fact]
    public void ChaffNamespace_LeftWithNoDeclarations_IsDroppedEntirely()
    {
        // The other half of the fix: an elided static class that was the namespace's only
        // occupant takes the namespace with it. Nothing may live in `TestLibrary.Chaff`, and no
        // type may be named `ChaffOnly`.
        var inChaff = Emitted
            .Where(t => t.Namespace == "TestLibrary.Chaff" || t.Name == "ChaffOnly")
            .Select(t => t.FullName)
            .ToList();

        Assert.Empty(inChaff);
    }
}
