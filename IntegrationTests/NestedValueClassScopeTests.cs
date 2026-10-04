using TestLibrary.Nestedscope;

namespace IntegrationTests;

public class NestedValueClassScopeTests
{
    [Fact]
    public void OutOfScopeNestedValueClass_AndDependentMembersAreAbsent()
    {
        Assert.Null(typeof(NestedScopeSample).GetMethod("RefusedTag"));
        Assert.Null(typeof(NestedScopeSample).GetProperty("RefusedTagProperty"));
        Assert.DoesNotContain(typeof(NestedScopeSample).Assembly.GetTypes(),
            type => type.Name == "UnexportedLabelOwner" ||
                type.FullName?.Contains("UnexportedLabelOwner+Tag") == true);
    }

    [Fact]
    public void UnaffectedPrimitiveControl_StillRoundTrips()
    {
        Assert.Equal(7, NestedScopeSample.Control());
    }
}