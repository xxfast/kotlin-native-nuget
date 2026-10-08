using System.Reflection;
using System.Runtime.InteropServices;

namespace IntegrationTests;

/// <summary>
/// The tests that read <c>[DllImport]</c> EntryPoints off the generated bindings rely on the
/// runtime reconstructing <see cref="DllImportAttribute"/>, a pseudo-custom attribute that lives
/// in metadata flags rather than as a custom attribute. CoreCLR reflection rebuilds it; NativeAOT
/// reflection does not, so under AotIntegrationTests those walks come back empty and their
/// canaries fail. What they check (the entry point names the generator wrote) is a compile-time
/// property the JIT run already proves, so under NativeAOT they are skipped, visibly, instead.
/// </summary>
internal static class PseudoAttributes
{
    [DllImport("pseudo-attribute-probe", EntryPoint = "pseudo_attribute_probe")]
    private static extern void Probe();

    /// <summary>
    /// Whether this runtime rebuilds <see cref="DllImportAttribute"/>, read off a probe import
    /// declared here rather than inferred from the runtime flavour.
    /// </summary>
    private static bool AreReconstructed { get; } = typeof(PseudoAttributes)
        .GetMethod(nameof(Probe), BindingFlags.NonPublic | BindingFlags.Static)?
        .GetCustomAttribute<DllImportAttribute>()?.EntryPoint == "pseudo_attribute_probe";

    /// <summary>
    /// Skips the calling test when the runtime does not rebuild <see cref="DllImportAttribute"/>.
    /// Only xunit.v3 (the AOT host) can skip at run time; under xunit 2 (the JIT host) the probe
    /// always succeeds, and if it ever did not, the callers' canary assertions fail loudly.
    /// </summary>
    public static void SkipUnlessReconstructed()
    {
#if XUNIT_V3
        Assert.SkipUnless(
            AreReconstructed,
            "this runtime does not rebuild the DllImportAttribute pseudo-attribute (NativeAOT); " +
            "the EntryPoint names are asserted by the JIT run of IntegrationTests");
#endif
    }
}
