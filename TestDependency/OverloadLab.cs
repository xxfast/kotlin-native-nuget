namespace Test.Overloads;

/// <summary>
/// ADR-057 fixture for same-name method overloads and multiple public class constructors.
/// Every route returns a distinct marker so a registration-slot or pointer-order mix-up cannot
/// accidentally satisfy the consumer assertions.
/// </summary>
public sealed class OverloadLab
{
    private readonly string _origin;

    public OverloadLab(int seed) => _origin = $"seed:{seed}";

    public OverloadLab(bool enabled) => _origin = enabled ? "enabled:on" : "enabled:off";

    public static string Describe(int value) => $"static:int:{value}";

    public static string Describe(bool value) => value ? "static:bool:on" : "static:bool:off";

    public string Apply(string value) => $"{_origin}:text:{value}";

    public string Apply(int value) => $"{_origin}:int:{value}";
}
