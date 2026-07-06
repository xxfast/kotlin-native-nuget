namespace Sample.Text;

/// <summary>
/// A trivial string template that substitutes a single <c>{name}</c> placeholder.
/// </summary>
/// <remarks>
/// This class is the ADR-051 fixture type: a public non-static class with exactly two
/// single-overload static methods that survive the v1 bridgeable-subset filter
/// (ADR-043) — one factory returning an instance (<see cref="Parse"/>) and one static
/// consumer of that instance (<see cref="Render"/>). No overloads, no public instance
/// members beyond the implicit constructor.
/// </remarks>
public class Template
{
    private readonly string _source;

    // Internal constructor — callers use Parse(string).
    private Template(string source) => _source = source;

    /// <summary>
    /// Creates a <see cref="Template"/> from a source string such as <c>"Hello, {name}"</c>.
    /// </summary>
    public static Template Parse(string source) => new(source);

    /// <summary>
    /// Substitutes the <c>{name}</c> placeholder in <paramref name="template"/> with
    /// <paramref name="name"/> and returns the rendered string.
    /// </summary>
    public static string Render(Template template, string name) =>
        template._source.Replace("{name}", name);
}
