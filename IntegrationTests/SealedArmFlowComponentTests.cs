using System.Linq;
using System.Reflection;
using TestLibrary.Issue115;

namespace IntegrationTests;

/// <summary>
/// Issue #230: Kotlin's <c>componentN()</c> destructuring operator leaked into the C# API on the
/// sealed-arm flow route. A <c>data class</c> arm whose constructor parameter is a
/// <c>Flow&lt;T&gt;</c> or <c>StateFlow&lt;T&gt;</c> exported that parameter's component as
/// <c>public KotlinFlow&lt;string&gt; Component1()</c> beside the <c>Purrs</c> property it
/// duplicates, because the arm flow selector was the one member route with no copy of the
/// synthetic-member filter. Non-flow components never leaked, which is why this shape got through.
/// <para>
/// The Kotlin fixture is <c>test-library/.../issue115/JobSample.kt</c>: <c>Job.Purring</c> takes
/// both flows in its constructor, so <c>component1()</c> and <c>component2()</c> are the ones that
/// used to cross. A <c>Flow</c> cannot be passed in, so the arm has no C# constructor of its own and
/// <c>JobFactory.Purring()</c> is the way in.
/// </para>
/// <para>
/// The absence is asserted by reflection, because a member that should not exist is invisible to
/// the compiler: <c>Assert.DoesNotContain</c> over the arm's own public methods names whatever
/// leaked instead of failing on a bare boolean. The two properties are asserted on their
/// <em>values</em> beside it, so a fix that deletes the whole route rather than the operator fails
/// here too.
/// </para>
/// <para>
/// Mylo, folded on the windowsill, rumbling at seven.
/// </para>
/// </summary>
public class SealedArmFlowComponentTests
{
    /// <summary>
    /// The absence: no member of the arm is a destructuring operator, whatever its type.
    /// </summary>
    [Fact]
    public void Purring_DeclaresNoDestructuringOperator()
    {
        string[] components = typeof(Job.Purring)
            .GetMethods(BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly)
            .Select(method => method.Name)
            .Where(name => name.StartsWith("Component", StringComparison.Ordinal))
            .OrderBy(name => name, StringComparer.Ordinal)
            .ToArray();

        Assert.True(
            components.Length == 0,
            $"Job.Purring must declare no componentN: {string.Join(", ", components)}");
    }

    /// <summary>
    /// The presence, on the same arm: the flow-typed constructor parameters bind as properties and
    /// carry real values across, through the same collect and value thunks every other arm flow
    /// uses.
    /// </summary>
    [Fact]
    public async Task Purring_BindsItsFlowParametersAsProperties()
    {
        using var factory = new JobFactory();
        await using Job.Purring mylo = factory.Purring();

        Assert.Equal(7, mylo.Loudness.Value);

        var rumbles = new List<string>();
        await foreach (string rumble in mylo.Purrs)
        {
            rumbles.Add(rumble);
        }

        Assert.Equal(["rumble", "rumble rumble"], rumbles);
    }
}
