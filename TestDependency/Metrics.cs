using Test.Enums;

namespace Test.Structs;

/// <summary>
/// Shape A across the "pass-through" component vocabulary: <c>long</c>/<c>float</c>/<c>double</c>
/// each cross the bridge as their own scalar with no ABI-side conversion, unlike <c>bool</c>,
/// <c>char</c>, <c>string</c>, and a bound enum component (see <see cref="Profile"/>) — which
/// needed generator fixes but now work too. Ctor parameters camelCase vs. properties PascalCase
/// again exercises the case-insensitive component match (Decision 3a rule 3).
/// </summary>
public readonly struct Metrics
{
    public Metrics(long heartRateBpm, float weightKg, double temperatureC)
    {
        HeartRateBpm = heartRateBpm;
        WeightKg = weightKg;
        TemperatureC = temperatureC;
    }

    public long HeartRateBpm { get; }
    public float WeightKg { get; }
    public double TemperatureC { get; }
}

/// <summary>
/// A bound handle class (ADR-051/052) with an INSTANCE method taking and returning a struct — the
/// "instance members of bound classes" half of ADR-056's v1 Scope (<see cref="Geometry"/>'s
/// methods are all static) — and a SETTABLE struct-typed property (<see cref="CurrentProfile"/>),
/// the one piece of ADR-056's v1 Scope nothing exercised end to end before this fixture.
/// </summary>
public class Cattery
{
    public string Name { get; }

    public Profile CurrentProfile { get; set; } = new Profile("unset", false, '?', CatMood.Sleepy);

    public Cattery(string name) => Name = name;

    public Metrics Weigh(Metrics m, float factor) =>
        new Metrics((long)(m.HeartRateBpm * factor), m.WeightKg * factor, m.TemperatureC);
}
