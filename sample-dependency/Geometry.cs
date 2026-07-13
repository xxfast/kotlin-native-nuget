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
