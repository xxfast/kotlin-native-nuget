using System.ComponentModel;
using System.Reflection;
using Kotlin.Native.Interop;

namespace ContractTests;

public class ContractTests
{
    [Fact]
    public void UnmappedFailureHasNoPublicConstructorAndFactoryPreservesMetadata()
    {
        Assert.Empty(typeof(KotlinException).GetConstructors());
        MethodInfo factory = Assert.Single(typeof(KotlinException).GetMethods(), m => m.Name == "Create");
        Assert.Equal(EditorBrowsableState.Never,
            factory.GetCustomAttribute<EditorBrowsableAttribute>()!.State);
        var cause = new InvalidOperationException("empty bowl");
        KotlinException error = KotlinException.Create("cats.DinnerComplaint", "Oreo", "feed:42", cause);
        Assert.Same(cause, error.InnerException);
        Assert.Equal("cats.DinnerComplaint", error.KotlinType);
        Assert.Equal("feed:42", error.KotlinStackTrace);
        Assert.Contains("Kotlin type: cats.DinnerComplaint", error.ToString());
        Assert.Contains("feed:42", error.ToString());
    }

    [Fact]
    public void EveryMappedFailureRetainsItsBclBaseAndPublicSealedContract()
    {
        (Type mapped, Type bcl)[] types =
        [
            (typeof(KotlinArgumentException), typeof(ArgumentException)),
            (typeof(KotlinInvalidOperationException), typeof(InvalidOperationException)),
            (typeof(KotlinNotSupportedException), typeof(NotSupportedException)),
            (typeof(KotlinInvalidCastException), typeof(InvalidCastException)),
            (typeof(KotlinArithmeticException), typeof(ArithmeticException)),
            (typeof(KotlinFormatException), typeof(FormatException)),
            (typeof(KotlinIOException), typeof(IOException)),
            (typeof(KotlinNullReferenceException), typeof(NullReferenceException)),
            (typeof(KotlinOperationCanceledException), typeof(OperationCanceledException)),
        ];
        foreach ((Type mapped, Type bcl) in types)
        {
            Assert.True(mapped.IsSealed);
            Assert.Equal(bcl, mapped.BaseType);
            ConstructorInfo constructor = Assert.Single(mapped.GetConstructors());
            var cause = new Exception("empty bowl");
            var error = (Exception)constructor.Invoke(["cats.Failure", "Mylo", "feed:7", cause]);
            Assert.Same(cause, error.InnerException);
            Assert.Equal("Mylo", error.Message);
            var kotlin = Assert.IsAssignableFrom<IKotlinException>(error);
            Assert.Equal("cats.Failure", kotlin.KotlinType);
            Assert.Equal("feed:7", kotlin.KotlinStackTrace);
            Assert.Contains("Kotlin type: cats.Failure", error.ToString());
        }
    }

    [Fact]
    public void PresenceHasThreeDistinctStatesForReferenceAndNullableValueTypes()
    {
        KotlinOptional<string?> absent = KotlinOptional<string?>.None;
        KotlinOptional<string?> nil = (string?)null;
        KotlinOptional<string?> oreo = "Oreo";
        Assert.False(absent.HasValue);
        Assert.True(nil.HasValue);
        Assert.Null(nil.Value);
        Assert.True(oreo.HasValue);
        Assert.Equal("Oreo", oreo.Value);
        KotlinOptional<int?> missing = default;
        KotlinOptional<int?> noTreats = (int?)null;
        KotlinOptional<int?> treats = 2;
        Assert.False(missing.HasValue);
        Assert.True(noTreats.HasValue);
        Assert.Null(noTreats.Value);
        Assert.True(treats.HasValue);
        Assert.Equal(2, treats.Value);
    }
}
