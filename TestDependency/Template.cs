namespace Test.Text;

/// <summary>
/// A trivial string template that substitutes a single <c>{name}</c> placeholder.
/// </summary>
/// <remarks>
/// This class is the ADR-051/052/(Phase 9 line 151) fixture type: a public non-static class
/// with a public instance constructor, two single-overload static methods
/// (<see cref="Parse"/>, <see cref="Render"/>), and a small instance-member surface —
/// a read-only property (<see cref="Source"/>), a settable property (<see cref="Name"/>), an
/// instance method with a string in/out (<see cref="Apply"/>), and an instance method
/// returning a fresh handle to the same bound type (<see cref="Clone"/>). Member names are
/// deliberately distinct from <see cref="Parse"/>/<see cref="Render"/> (a static and an
/// instance member sharing a name would be grouped and skipped as an overload set) and clear
/// of <c>Handle</c>/<c>Close</c>/<c>Cleaner</c>, which collide with the ADR-051 Kotlin
/// wrapper's own members.
/// </remarks>
public class Template
{
    private readonly string _source;

    /// <summary>
    /// The cat name used by callers that want a shared template default. Settable static
    /// property → Kotlin <c>var defaultName: String</c> in <c>Template</c>'s companion object.
    /// </summary>
    public static string DefaultName { get; set; } = "Oreo";

    /// <summary>
    /// The number of calls to <see cref="Render"/>. Read-only static property → Kotlin
    /// <c>val renderCount: Int</c> in <c>Template</c>'s companion object.
    /// </summary>
    public static int RenderCount { get; private set; }

    /// <summary>
    /// Creates a <see cref="Template"/> directly from a source string such as
    /// <c>"Hello, {name}"</c>. Public since ADR-052 so the fixture exercises the mapped
    /// Kotlin secondary constructor (<c>Template(source)</c>) in addition to <see cref="Parse"/>.
    /// </summary>
    public Template(string source) => _source = source;

    /// <summary>
    /// The source string this <see cref="Template"/> was created from. Read-only instance
    /// property → Kotlin <c>val source: String</c>.
    /// </summary>
    public string Source => _source;

    /// <summary>
    /// An arbitrary caller-assigned name carried alongside the template (e.g. copied by
    /// <see cref="Clone"/>). Settable instance property → Kotlin <c>var name: String</c>.
    /// </summary>
    public string Name { get; set; } = "world";

    /// <summary>
    /// Substitutes the <c>{name}</c> placeholder in this template's source with
    /// <paramref name="name"/> and returns the rendered string. Instance method, string
    /// in/out → Kotlin <c>fun apply(name: String): String</c>.
    /// </summary>
    public string Apply(string name) => _source.Replace("{name}", name);

    /// <summary>
    /// Creates an independent <see cref="Template"/> with the same source and
    /// <see cref="Name"/> as this one. Instance method returning a bound handle type →
    /// Kotlin <c>fun clone(): Template?</c> (nullable per ADR-051, <c>NullableAttribute</c>
    /// deferred).
    /// </summary>
    public Template Clone() => new(_source) { Name = Name };

    /// <summary>
    /// Creates a <see cref="Template"/> from a source string such as <c>"Hello, {name}"</c>.
    /// </summary>
    public static Template Parse(string source) => new(source);

    /// <summary>
    /// Substitutes the <c>{name}</c> placeholder in <paramref name="template"/> with
    /// <paramref name="name"/> and returns the rendered string.
    /// </summary>
    public static string Render(Template template, string name)
    {
        RenderCount++;
        return template._source.Replace("{name}", name);
    }
}
