using System.Reflection;
using TestLibrary.Cat;

namespace IntegrationTests;

public class VarianceTests
{
    [Fact]
    public void IReadable_TypeParameter_IsCovariant()
    {
        GenericParameterAttributes attributes = typeof(IReadable<>).GetGenericArguments()[0].GenericParameterAttributes;
        Assert.True((attributes & GenericParameterAttributes.Covariant) != 0);
    }

    [Fact]
    public void IWritable_TypeParameter_IsContravariant()
    {
        GenericParameterAttributes attributes = typeof(IWritable<>).GetGenericArguments()[0].GenericParameterAttributes;
        Assert.True((attributes & GenericParameterAttributes.Contravariant) != 0);
    }

    [Fact]
    public void IReadable_Covariance_AllowsNarrowingAssignment()
    {
        // IReadable<Cat> can be assigned to IReadable<IPet> because T is covariant (out T)
        // Oreo is a Cat, and Cat : IPet, so a reader of Cat is also a reader of IPet
        Assert.True(typeof(IReadable<IPet>).IsAssignableFrom(typeof(IReadable<Cat>)));
    }

    [Fact]
    public void IWritable_Contravariance_AllowsWideningAssignment()
    {
        // IWritable<IPet> can be assigned to IWritable<Cat> because T is contravariant (in T)
        // A writer that accepts any IPet can also serve as a writer of Cat specifically
        Assert.True(typeof(IWritable<Cat>).IsAssignableFrom(typeof(IWritable<IPet>)));
    }
}
