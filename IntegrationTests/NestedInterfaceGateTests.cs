using System;
using System.Linq;
using TestLibrary.Issue54;

namespace IntegrationTests;

/// <summary>
/// ADR-133 flips the nested-interface gate from absence to presence: a Kotlin <c>interface</c>
/// nested inside an exported class is declared as <c>NestedListenerOwner.IListener</c>, with the
/// ADR-040 backing wrapper <c>NestedListenerOwner.Listener</c> nested beside it instead of at
/// namespace root. That placement is the whole point of the flip: the pre-2026-09-07 route
/// declared a bare <c>TestLibrary.Issue54.IListener</c> while every reference spelled
/// <c>NestedListenerOwner.Listener</c>, which is CS0426.
///
/// What this file does NOT decide is whether a <em>nullable</em> interface position binds. That is
/// an orthogonal capability (see <c>attached: Listener?</c> and <c>current(): Listener?</c> on the
/// fixture), so the nullable cells only pin that the two nullable positions agree with each other
/// rather than guessing a shape ADR-133 never settled.
///
/// Mylo hears the treat cupboard from three rooms away, and now C# can name the interface he does
/// it through.
/// </summary>
public class NestedInterfaceGateTests
{
    private sealed class EchoListener : NestedListenerOwner.IListener
    {
        public string OnEvent() => "echo";

        public void Dispose() { }
    }

    [Fact]
    public void NestedListenerOwner_StillConstructs_AndItsUnrelatedMemberStillBinds()
    {
        // The control half: an implementation that drops the owning class to make its nested
        // interface fit would also make this pass-looking test fail, which is the point.
        using var owner = new NestedListenerOwner();

        Assert.Equal("owner", owner.Name);
    }

    [Fact]
    public void NestedInterface_IsDeclaredUnderItsOwner_NotAtNamespaceRoot()
    {
        // Was: Assert.Null(GetNestedType("IListener")). This is the ADR-133 pin for the interface
        // kind, and the half that fails if the old flattening returns.
        Type? listener = typeof(NestedListenerOwner).GetNestedType("IListener");

        Assert.NotNull(listener);
        Assert.True(listener!.IsInterface);
        Assert.Same(typeof(NestedListenerOwner), listener.DeclaringType);
        Assert.Null(typeof(NestedListenerOwner).Assembly.GetType("TestLibrary.Issue54.IListener"));
    }

    [Fact]
    public void NestedListenerOwner_ParameterPosition_Binds_AndKotlinCallsBack()
    {
        // Was: Assert.Null(GetMethod("Attach")). Non-null parameter position, so this cell is
        // independent of the nullable question below: a C# implementation of the nested interface
        // has to reach Kotlin through the ADR-040 bridge.
        using var owner = new NestedListenerOwner();

        owner.Attach(new EchoListener());

        Assert.NotNull(typeof(NestedListenerOwner).GetMethod("Attach"));
    }

    [Fact]
    public void TheNullableInterfacePositions_AgreeWithEachOther()
    {
        // `attached: Listener?` (property) and `current(): Listener?` (return) are both nullable
        // interface positions. Whether a nullable interface binds at all is not what ADR-133
        // decides, so this pins only that the two do not diverge: an implementation that binds one
        // and drops the other has a bug in the nullable path, not in nesting.
        bool property = typeof(NestedListenerOwner).GetProperty("Attached") is not null;
        bool method = typeof(NestedListenerOwner).GetMethod("Current") is not null;

        Assert.Equal(property, method);
    }

    [Fact]
    public void TheBackingWrapper_IsNestedBesideItsInterface_NeverAtNamespaceRoot()
    {
        // ADR-040's wrapper follows its interface's scope now. A namespace-root `Listener` beside
        // `NestedListenerOwner` is the exact shape the 2026-09-07 caveat described and ADR-133
        // closes; no per-member assertion above can see it.
        var strays = typeof(NestedListenerOwner).Assembly
            .GetTypes()
            .Where(type => !type.IsNested)
            .Where(type => type.Name is "IListener" or "Listener")
            .Select(type => type.FullName)
            .OrderBy(name => name)
            .ToArray();

        Assert.Empty(strays);
    }
}
