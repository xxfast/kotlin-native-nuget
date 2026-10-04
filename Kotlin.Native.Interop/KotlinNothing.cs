namespace Kotlin.Native.Interop;

/// <summary>
/// Kotlin's <c>Nothing</c> as a type argument, the one a generic sealed arm fixes a variant
/// parameter to: Kotlin's <c>fun fail(): Outcome.Err</c> returns
/// <c>Outcome.Err&lt;KotlinNothing&gt;</c>.
/// Like <c>Nothing</c>, it has no values, so it cannot be instantiated.
/// </summary>
public sealed class KotlinNothing
{
    private KotlinNothing()
    {
    }
}
