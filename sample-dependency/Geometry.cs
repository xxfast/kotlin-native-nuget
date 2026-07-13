namespace Sample.Structs;

/// <summary>
/// Shape A struct used as the struct-typed argument to Point's alternate constructor.
/// </summary>
public readonly struct Size
{
    public Size(int width, int height)
    {
        Width = width;
        Height = height;
    }

    public int Width { get; }
    public int Height { get; }
}

/// <summary>
/// ADR-056 walking skeleton, thinnest slice: Shape A, all-primitive components. Ctor parameter
/// names are deliberately lower camelCase (<c>x</c>, <c>y</c>) while the properties they back are
/// PascalCase (<c>X</c>, <c>Y</c>) — this is the case-insensitive component-match rule (Decision
/// 3a, rule 3) in a single, easy-to-verify shape.
/// </summary>
public readonly struct Point
{
    public Point(int x, int y)
    {
        X = x;
        Y = y;
    }

    public Point(int value) : this(value, value) { }

    public Point(bool unit) : this(unit ? 1 : 0, unit ? 1 : 0) { }

    public Point(Size size) : this(size.Width, size.Height) { }

    public int X { get; }
    public int Y { get; }

    /// <summary>
    /// Get-only computed property that is NOT a component (components are <see cref="X"/> /
    /// <see cref="Y"/>). Manhattan distance from the origin, named <c>Magnitude</c> so it is not
    /// confused with the static <see cref="Geometry.Manhattan"/> two-point form.
    /// </summary>
    public int Magnitude => Math.Abs(X) + Math.Abs(Y);

    /// <summary>
    /// Instance method returning a struct: reconstruct-on-call (ADR-014 mirror / ADR-056 deferred
    /// scope). Receiver components lead the wire args; return goes through out-pointers.
    /// </summary>
    public Point Offset(int dx, int dy) => new Point(X + dx, Y + dy);

    /// <summary>
    /// Instance method with a non-struct return: components as receiver only, string conversion
    /// on the way back. Deliberately denser than <see cref="Geometry.Describe"/> (no spaces).
    /// </summary>
    public string Format() => $"({X},{Y})";

    /// <summary>
    /// Static method on the struct itself → Kotlin <c>companion object</c> (not a free function
    /// on <see cref="Geometry"/>).
    /// </summary>
    public static Point Origin() => new Point(0, 0);
}

/// <summary>
/// Static methods on Point: struct parameter + struct return (<see cref="Translate"/>), struct
/// parameter + non-struct return (<see cref="Describe"/>), and TWO struct parameters in one
/// signature (<see cref="Manhattan"/>) — the three method shapes ADR-056 Step 4 asks the walking
/// skeleton to widen to.
/// </summary>
public static class Geometry
{
    public static Point Translate(Point p, int dx, int dy) => new Point(p.X + dx, p.Y + dy);

    public static string Describe(Point p) => $"({p.X}, {p.Y})";

    public static int Manhattan(Point a, Point b) => Math.Abs(a.X - b.X) + Math.Abs(a.Y - b.Y);
}
