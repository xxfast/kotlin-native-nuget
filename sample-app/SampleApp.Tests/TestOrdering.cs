using System.Reflection;
using Xunit.Abstractions;
using Xunit.Sdk;

[assembly: TestCaseOrderer("SampleApp.Tests.DeclarationOrderer", "SampleApp.Tests")]

namespace SampleApp.Tests;

/// <summary>
/// Orders test cases within a class by their metadata token, which reflects source
/// declaration order. This ensures that stateful tests run in the order they were
/// written, so that default-value checks precede mutation tests.
/// </summary>
public class DeclarationOrderer : ITestCaseOrderer
{
    public IEnumerable<TTestCase> OrderTestCases<TTestCase>(IEnumerable<TTestCase> testCases)
        where TTestCase : ITestCase
    {
        return testCases.OrderBy(tc =>
        {
            var methodInfo = tc.TestMethod.Method.ToRuntimeMethod();
            return methodInfo?.MetadataToken ?? int.MaxValue;
        });
    }
}
