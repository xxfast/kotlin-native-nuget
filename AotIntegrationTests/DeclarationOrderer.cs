using Xunit.Sdk;
using Xunit.v3;

[assembly: TestMethodOrderer(typeof(AotIntegrationTests.DeclarationOrderer))]
[assembly: TestCaseOrderer(typeof(UnorderedTestCaseOrderer))]

namespace AotIntegrationTests;

/// <summary>
/// Runs the test methods of a class in source declaration order, the AOT twin of
/// IntegrationTests/TestOrdering.cs. Stateful classes rely on that order (default-value checks
/// precede mutation tests), and xunit.v3's default method order is stable but not declaration
/// order. The AOT runner has no MethodInfo to read a metadata token from, so this sorts by the
/// source position its generator records. Theory rows keep their data order through
/// <see cref="UnorderedTestCaseOrderer"/>, as the JIT orderer's stable sort keeps them.
/// </summary>
public class DeclarationOrderer : ITestMethodOrderer
{
    public IReadOnlyCollection<TTestMethod?> OrderTestMethods<TTestMethod>(
        IReadOnlyCollection<TTestMethod?> testMethods)
        where TTestMethod : notnull, ITestMethod
    {
        return testMethods
            .OrderBy(
                method => (method as ICodeGenTestMethod)?.SourceFilePath,
                StringComparer.Ordinal)
            .ThenBy(method => (method as ICodeGenTestMethod)?.SourceLineNumber ?? int.MaxValue)
            .ToList();
    }
}
