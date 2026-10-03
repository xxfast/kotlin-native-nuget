// Compile against the freshly generated collision cell's Interop.cs.
// Select exactly one owner shape with DefineConstants. All expressions are metadata-only.
namespace CollisionConsumer;

public static class Probe
{
#if CLASS_OWNER || OBJECT_OWNER
    public static System.Type OwnerType => typeof(global::Interop.Owner);
    public static System.Type ReaderType => typeof(global::Interop.Reader);
#elif SEALED_OWNER
    public static System.Type OwnerType => typeof(global::Interop.Purr);
    public static System.Type ArmType => typeof(global::Interop.Purr.On);
    public static System.Type ReaderType => typeof(global::Interop.Reader);
#else
#error Select CLASS_OWNER, OBJECT_OWNER, or SEALED_OWNER for this fresh fixture.
#endif
}